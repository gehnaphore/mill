
import java.math.BigInteger
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.{Base64, Date}

import ammonite.ops.Path
import mill.api.Logger
import mill.scalalib.publish.Artifact
import os.Shellable

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.xml.{Elem, XML}

case class PublishSpec(meta: Artifact, payload: Seq[(Path, String)], hashUpdate: Option[(Path,String)])
object PublishSpec {
  implicit def rw: upickle.default.ReadWriter[PublishSpec] = upickle.default.macroRW
}


class FBHttpApi(
  uri: String,
  credentials: String,
  readTimeout: Int,
  connectTimeout: Int
) {
  val http = requests.Session(readTimeout = readTimeout, connectTimeout = connectTimeout, maxRedirects = 0, check = false)

  private val base64Creds = base64(credentials)

  private val commonHeaders = Seq(
    "Authorization" -> s"Basic $base64Creds",
    "Accept" -> "application/json",
    "Content-Type" -> "application/json"
  )

  // https://oss.sonatype.org/nexus-staging-plugin/default/docs/path__staging_profiles.html
  def getStagingProfileUri(groupId: String): String = {
    val response = withRetry(
      http.get(
        s"$uri/staging/profiles",
        headers = commonHeaders
      )
    )

    if (!response.is2xx) {
      throw new Exception(s"$uri/staging/profiles returned ${response.statusCode}")
    }

    val resourceUri =
      ujson
        .read(response.text())("data")
        .arr
        .find(profile =>
          groupId.split('.').startsWith(profile("name").str.split('.')))
        .map(_("resourceURI").str.toString)

    resourceUri.getOrElse(
      throw new RuntimeException(
        s"Could not find staging profile for groupId: ${groupId}")
    )
  }

  def getStagingRepoState(stagingRepoId: String): String = {
    val response = http.get(
      s"${uri}/staging/repository/${stagingRepoId}",
      headers = commonHeaders
    )
    ujson.read(response.text())("type").str.toString
  }

  // https://oss.sonatype.org/nexus-staging-plugin/default/docs/path__staging_profiles_-profileIdKey-_start.html
  def createStagingRepo(profileUri: String, groupId: String): String = {
    val response = withRetry(http.post(
      s"${profileUri}/start",
      headers = commonHeaders,
      data = s"""{"data": {"description": "fresh staging profile for ${groupId}"}}"""
    ))

    if (!response.is2xx) {
      throw new Exception(s"$uri/staging/profiles returned ${response.statusCode}\n${response.text()}")
    }

    ujson.read(response.text())("data")("stagedRepositoryId").str.toString
  }

  // https://oss.sonatype.org/nexus-staging-plugin/default/docs/path__staging_profiles_-profileIdKey-_finish.html
  def closeStagingRepo(profileUri: String, repositoryId: String): Boolean = {
    val response = withRetry(
      http.post(
        s"${profileUri}/finish",
        headers = commonHeaders,
        data = s"""{"data": {"stagedRepositoryId": "${repositoryId}", "description": "closing staging repository"}}"""
      )
    )

    response.statusCode == 201
  }

  // https://oss.sonatype.org/nexus-staging-plugin/default/docs/path__staging_profiles_-profileIdKey-_promote.html
  def promoteStagingRepo(profileUri: String, repositoryId: String): Boolean = {
    val response = withRetry(
      http.post(
        s"${profileUri}/promote",
        headers = commonHeaders,
        data = s"""{"data": {"stagedRepositoryId": "${repositoryId}", "description": "promote staging repository"}}"""
      )
    )

    response.statusCode == 201
  }

  // https://oss.sonatype.org/nexus-staging-plugin/default/docs/path__staging_profiles_-profileIdKey-_drop.html
  def dropStagingRepo(profileUri: String, repositoryId: String): Boolean = {
    val response = withRetry(
      http.post(
        s"${profileUri}/drop",
        headers = commonHeaders,
        data = s"""{"data": {"stagedRepositoryId": "${repositoryId}", "description": "drop staging repository"}}"""
      )
    )
    response.statusCode == 201
  }

  def download(uri: String): requests.Response = {
    http.get(
      uri,
      headers = Seq(
        "Authorization" -> s"Basic ${base64Creds}"
      )
    )
  }

  def downloadAsync(uri: String): Future[Either[Int,Array[Byte]]] = {
    Future {
      val r = download(uri)
      if (r.is2xx) Right(r.bytes)
      else Left(r.statusCode)
    }
  }

  private val uploadTimeout = 1000 * 60 * 5

  def upload(uri: String, data: Array[Byte], log: Logger): requests.Response = {
/*
    println(s"Trying to delete $uri")
    val delr = http.delete(
      uri,
      headers = Seq(
        "Authorization" -> s"Basic ${base64Creds}"
      )
    )
    println(delr)
*/


    //println(s"Staging $uri")
    //requests.Response(uri, 0, "", new geny.Bytes(Array()), Map.empty, None)

    val r = try {
      http.put(
        uri,
        readTimeout = uploadTimeout,
        check = false,
        headers = Seq(
          "Content-Type" -> "application/binary",
          "Authorization" -> s"Basic ${base64Creds}"
        ),
        data = data
      )
    } catch {
      case e: Throwable =>
        e.printStackTrace()
        requests.Response(uri, 0, e.getMessage, new geny.Bytes(Array()), Map.empty, None)
    }
    if (r.statusCode >= 200 && r.statusCode < 300) {
      log.info(s"Uploaded $uri (${r.statusCode})")
    } else {
      log.error(s"Skipped $uri with error code = ${r.statusCode}")
    }
    r
  }

  def uploadAsync(uri: String, data: Array[Byte], log: Logger): Future[requests.Response] = Future {
    upload(uri,data,log)
  }

  private def withRetry(request: => requests.Response,
    retries: Int = 10): requests.Response = {
    val resp = request
    if (resp.is5xx && retries > 0) {
      Thread.sleep(500)
      withRetry(request, retries - 1)
    } else {
      resp
    }
  }

  private def base64(s: String) =
    new String(Base64.getEncoder.encode(s.getBytes))

}

class FBPublisher(uri: String,
  snapshotUri: String,
  credentials: String,
  gpgPassphrase: Option[String],
  gpgKeyName: Option[String],
  signed: Boolean,
  readTimeout: Int,
  connectTimeout: Int,
  log: Logger,
  awaitTimeout: Int,
  stagingRelease: Boolean = true,
  forceUpdate: Boolean = false
) {

  private val api = new FBHttpApi(uri, credentials, readTimeout = readTimeout, connectTimeout = connectTimeout)

  def publish(fileMapping: Seq[(os.Path, String)], artifact: Artifact, release: Boolean): Unit = {
    publishAll(release, PublishSpec(artifact,fileMapping,None))
  }

  def publishPathsFor(artifact: Artifact) = {
    Seq(
      artifact.group.replace(".", "/"),
      artifact.id,
      artifact.version
    )
  }

  def publishAll(release: Boolean, artifacts: PublishSpec*): Unit = {

    val (snapshots, releases0) = artifacts.partition(_.meta.isSnapshot)
    if (snapshots.nonEmpty) publishSnapshots(snapshots)

    if (releases0.nonEmpty) {
      val releases = for (PublishSpec(artifact,fileMapping0,_) <- releases0) yield {
        val publishPath = publishPathsFor(artifact).mkString("/")
        val fileMapping = fileMapping0.map { case (file, name) => (file, publishPath + "/" + name) }

        val signedArtifacts = if (signed) fileMapping.map {
          case (file, name) => poorMansSign(file, gpgPassphrase, gpgKeyName) -> s"$name.asc"
        } else Seq()

        artifact -> (fileMapping ++ signedArtifacts).flatMap {
          case (file, name) =>
            val content = os.read.bytes(file)

            Seq(
              name -> content /*,
              (name + ".md5") -> md5hex(content),
              (name + ".sha1") -> sha1hex(content)*/
            )
        }
      }

      val releaseGroups = releases.groupBy(_._1.group)
      for ((group, groupReleases) <- releaseGroups) {
        if (stagingRelease) {
          publishRelease(
            release,
            groupReleases.flatMap(_._2),
            group,
            releases.map(_._1),
            awaitTimeout
          )
        } else publishReleaseNonstaging(groupReleases.flatMap(_._2), releases.map(_._1))
      }
    }
  }

  def snapshotSnippet(timestamp: String, buildNumber: Int) = {
    <snapshot>
      <timestamp>{timestamp}</timestamp>
      <buildNumber>{buildNumber}</buildNumber>
    </snapshot>
  }

  def snapshotVersionSnippet(extension: String, value: String, updated: String) = {
    <snapshotVersion>
      <extension>{extension}</extension>
      <value>{value}</value>
      <updated>{updated}</updated>
    </snapshotVersion>
  }

  def snapshotVersionsSnippet(value: String, updated: String) = {
    <snapshotVersions>
      {snapshotVersionSnippet("jar", value, updated)}
      {snapshotVersionSnippet("zip", value, updated)}
      {snapshotVersionSnippet("pom", value, updated)}
    </snapshotVersions>
  }

  def versioningSnippet(version: String, timestamp: String, buildNumber: Int) = {
    val tsNoDot = timestamp.replace(".","")
    val fullVersion = version.replace("SNAPSHOT",s"$timestamp-$buildNumber")

    <versioning>
      {snapshotSnippet(timestamp, buildNumber)}
      <lastUpdated>{tsNoDot}</lastUpdated>
      {snapshotVersionsSnippet(fullVersion, tsNoDot)}
    </versioning>
  }

  def mavenManifestFor(artifact: Artifact, timestamp: String, buildNumber: Int): Elem = {
    <metadata modelVersion="1.1.0">
      <groupId>com.facebook.ta</groupId>
      <artifactId>domains-tnd_2.11</artifactId>
      <version>0.0.1-fake-a3f9c6b2-5b50df75-SNAPSHOT</version>
      {versioningSnippet(artifact.version, timestamp, buildNumber)}
    </metadata>
  }

  def printXML(xml: Elem): String = {
    val pp = new scala.xml.PrettyPrinter(999, 2)
    pp.format(xml)
  }

  def parseLastSnapshotBuildQualifier(xml: Elem): (Int,String) = {
    val versioning = xml \ "versioning"
    val snapshotElem = versioning \ "snapshot"
    val tsElem = snapshotElem \ "timestamp"
    val bnElem = snapshotElem \ "buildNumber"
    val ts = tsElem.text
    val bn = bnElem.text.toInt
    (bn,ts)
  }

  def metadataUriFor(artifact: Artifact): String = {
    val publishPath = publishPathsFor(artifact).mkString("/")
    s"$snapshotUri/$publishPath/maven-metadata.xml"
  }

  def latestSnapshotBuildQualifier(artifact: Artifact): Future[Option[(Int, String)]] = {
    val url = metadataUriFor(artifact)
    //log.info(s"Hitting $url")
    api.downloadAsync(url).map { r =>
      r.fold(
        { errCode => log.error(s"Got $errCode from $url") ; None },
        { payload =>
          //log.info(s"Loading $url")
          val xml = XML.loadString(new String(payload))
          Some(parseLastSnapshotBuildQualifier(xml))
        }
      )
    }
  }

  val tsFormat = new SimpleDateFormat("yyyyMMdd.HHmmss")

  def updateHash(update: Option[(Path,String)]) = {
    update.foreach { case (file, hash) =>
      os.makeDir.all(file / os.up)
      os.write.over(file, hash)
    }
  }

  def uploadSnapshotVersion(artifact: Artifact, payloads: Seq[(os.Path,String)], ts: String, buildNumber: Int, hashUpdate: Option[(Path,String)]) = {
    val artifactName = s"${artifact.group}:${artifact.id}:${artifact.version}"
    val snapshotRepl = s"$ts-$buildNumber"
    val versionedPayloads = payloads.map(x => (x._1, x._2.replace("SNAPSHOT",snapshotRepl)))
    val publishPath = publishPathsFor(artifact).mkString("/")
    val fileStr = versionedPayloads.map(x => s"  ${x._1} -> ${x._2}").mkString("\n")
    log.info(s"Publishing ${artifactName} #$buildNumber ($ts)\n$fileStr")
    val artifactUploads = versionedPayloads.map(vp => api.uploadAsync(s"$snapshotUri/$publishPath/${vp._2}", os.read.bytes(vp._1), log))
    val allArtifactResponses = Future.sequence(artifactUploads)
    allArtifactResponses.flatMap { responses =>
      responses.find(!_.is2xx).fold {
        val url = metadataUriFor(artifact)
        val xmlStr = printXML(mavenManifestFor(artifact, ts, buildNumber))
        //println(xmlStr)
        val xmlBytes = xmlStr.getBytes
        api.uploadAsync(url, xmlBytes, log).map { metaResponse =>
          if (metaResponse.is2xx) {
            updateHash(hashUpdate)
          }
          metaResponse
        }
      }(Future.successful)
    }
  }

  private def publishSnapshots(
    snapshots: Seq[PublishSpec]
  ): Unit = {
    val publishResultsF = snapshots.map { case PublishSpec(artifact, payloads, hashUpdate) =>
      val latestBuilds = latestSnapshotBuildQualifier(artifact)
      val artifactName = s"${artifact.group}:${artifact.id}:${artifact.version}"
      latestBuilds.flatMap { qualifiers =>
        qualifiers.fold {
          val now = new Date()
          val bn = 1
          log.info(s"For artifact $artifactName, no build found; deploying new snapshot")
          uploadSnapshotVersion(artifact, payloads, tsFormat.format(now), bn, hashUpdate)
        }{ case (bn0, ts) =>
          if (forceUpdate) {
            log.info(s"For artifact $artifactName, found build #$bn0 ($ts), but forcing an update")
            val bn = bn0 + 1
            val now = new Date()
            val newTS = tsFormat.format(now)
            uploadSnapshotVersion(artifact, payloads, newTS, bn, hashUpdate)
          } else {
            log.info(s"For artifact $artifactName, found build #$bn0 ($ts), not updating")
            updateHash(hashUpdate)
            Future.successful {
              requests.Response(uri, 204, "Auto-snapshot is already up-to-date", new geny.Bytes(Array()), Map.empty, None)
            }
          }
        }
      }
    }

    val publishResults = Await.result(Future.sequence(publishResultsF), Duration.Inf)
    reportPublishResults(publishResults, snapshots.map(_.meta))
  }

  private def publishToUri(
    payloads: Seq[(String, Array[Byte])],
    artifacts: Seq[Artifact],
    uri: String
  ): Unit = {
    val publishResultsF = payloads.map {
      case (fileName, data) =>
        log.info(s"Uploading $fileName")
        api.uploadAsync(s"$uri/$fileName", data, log)
    }
    val publishResults = Await.result(Future.sequence(publishResultsF), Duration.Inf)
    reportPublishResults(publishResults, artifacts)
  }

  private def publishReleaseNonstaging(payloads: Seq[(String, Array[Byte])],
    artifacts: Seq[Artifact]): Unit = {
    publishToUri(payloads, artifacts, uri)
  }

  private def publishRelease(release: Boolean,
    payloads: Seq[(String, Array[Byte])],
    stagingProfile: String,
    artifacts: Seq[Artifact],
    awaitTimeout: Int): Unit = {
    val profileUri = api.getStagingProfileUri(stagingProfile)
    val stagingRepoId =
      api.createStagingRepo(profileUri, stagingProfile)
    val baseUri = s"$uri/staging/deployByRepositoryId/$stagingRepoId/"

    publishToUri(payloads, artifacts, baseUri)

    if (release) {
      log.info("Closing staging repository")
      api.closeStagingRepo(profileUri, stagingRepoId)

      log.info("Waiting for staging repository to close")
      awaitRepoStatus("closed", stagingRepoId, awaitTimeout)

      log.info("Promoting staging repository")
      api.promoteStagingRepo(profileUri, stagingRepoId)

      log.info("Waiting for staging repository to release")
      awaitRepoStatus("released", stagingRepoId, awaitTimeout)

      log.info("Dropping staging repository")
      api.dropStagingRepo(profileUri, stagingRepoId)

      log.info(s"Published ${artifacts.map(_.id).mkString(", ")} successfully")
    }
  }

  private def reportPublishResults(publishResults: Seq[requests.Response],
    artifacts: Seq[Artifact]) = {
    if (publishResults.forall(_.is2xx)) {
      log.info(s"Published ${artifacts.map(_.id).mkString(", ")} to Sonatype")
    } else {
      val errors = publishResults.filterNot(_.is2xx).map { response =>
        s"Code: ${response.statusCode}, message: ${response.text()}"
      }
      throw new RuntimeException(
        s"Failed to publish ${artifacts.map(_.id).mkString(", ")} to Sonatype. Errors: \n${errors.mkString("\n")}"
      )
    }
  }

  private def awaitRepoStatus(status: String,
    stagingRepoId: String,
    awaitTimeout: Int): Unit = {
    def isRightStatus =
      api.getStagingRepoState(stagingRepoId).equalsIgnoreCase(status)

    var attemptsLeft = awaitTimeout / 3000

    while (attemptsLeft > 0 && !isRightStatus) {
      Thread.sleep(3000)
      attemptsLeft -= 1
      if (attemptsLeft == 0) {
        throw new RuntimeException(
          s"Couldn't wait for staging repository to be ${status}. Failing")
      }
    }
  }

  // http://central.sonatype.org/pages/working-with-pgp-signatures.html#signing-a-file
  private def poorMansSign(file: os.Path, maybePassphrase: Option[String], maybeKeyName: Option[String]): os.Path = {
    val fileName = file.toString
    val optionFlag = (flag: String, ov: Option[String]) => ov.map(flag :: _ :: Nil).getOrElse(Nil)
    val command = "gpg" ::
      optionFlag("--passphrase", maybePassphrase) ++ optionFlag("-u", maybeKeyName) ++
        Seq("--batch", "--yes", "-a", "-b", fileName)

    os.proc(command.map(v => v: Shellable))
      .call(stdin = os.Inherit, stdout = os.Inherit, stderr = os.Inherit)
    os.Path(fileName + ".asc")
  }

  private def md5hex(bytes: Array[Byte]): Array[Byte] =
    hexArray(md5.digest(bytes)).getBytes

  private def sha1hex(bytes: Array[Byte]): Array[Byte] =
    hexArray(sha1.digest(bytes)).getBytes

  private def md5 = MessageDigest.getInstance("md5")

  private def sha1 = MessageDigest.getInstance("sha1")

  private def hexArray(arr: Array[Byte]) =
    String.format("%0" + (arr.length << 1) + "x", new BigInteger(1, arr))

}


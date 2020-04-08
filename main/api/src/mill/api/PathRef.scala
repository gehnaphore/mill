package mill.api

import java.nio.{file => jnio}
import java.security.{DigestOutputStream, MessageDigest}

import upickle.default.{ReadWriter => RW}

/**
 * A wrapper around `os.Path` that calculates it's hashcode based
 * on the contents of the filesystem underneath it. Used to ensure filesystem
 * changes can bust caches which are keyed off hashcodes.
 */
case class PathRef(base: Option[os.Path], relpath: os.RelPath, quick: Boolean, sig: Int) {
  override def hashCode(): Int = sig
  def path = base.getOrElse(os.root) / relpath
  def isStale: Boolean = PathRef.forPath(base, relpath, quick).sig != sig
}

object PathRef {
  /**
   * Create a [[PathRef]] by recursively digesting the content of a given `path`.
   * @param path The digested path.
   * @param quick If `true` the digest is only based to some file attributes (like mtime and size).
   *              If `false` the digest is created of the files content.
   * @return
   */
  def apply(path: os.Path, quick: Boolean = false): PathRef = forPath(None,path.relativeTo(os.root),quick)

  def forPath(base: Option[os.Path], relPath: os.RelPath, quick: Boolean): PathRef = {
    val sig = {
      val isPosix = path.wrapped.getFileSystem.supportedFileAttributeViews().contains("posix")
      val digest = MessageDigest.getInstance("MD5")
      val digestOut = new DigestOutputStream(DummyOutputStream, digest)
      def updateWithInt(value: Int): Unit = {
        digest.update((value >>> 24).toByte)
        digest.update((value >>> 16).toByte)
        digest.update((value >>> 8).toByte)
        digest.update(value.toByte)
      }
      val path = base.getOrElse(os.root) / relPath
      if (os.exists(path)) {
        for ((path, attrs) <- os.walk.attrs(path, includeTarget = true, followLinks = true)) {
          digest.update(relPath.toString.getBytes)
          if (!attrs.isDir) {
            if (isPosix) {
              updateWithInt(os.perms(path).value)
            }
            if (quick) {
              val value = (attrs.mtime, attrs.size).hashCode()
              updateWithInt(value)
            } else if (jnio.Files.isReadable(path.toNIO)) {
              val is = os.read.inputStream(path)
              StreamSupport.stream(is, digestOut)
              is.close()
            }
          }
        }
      }

      java.util.Arrays.hashCode(digest.digest())

    }
    new PathRef(base, relPath, quick, sig)
  }

  def forPath(path: os.Path): PathRef = forPath(None, path.relativeTo(os.root), false)
  def forPath(path: os.Path, quick: Boolean): PathRef = forPath(None, path.relativeTo(os.root), quick)

  /**
    * Default JSON formatter for [[PathRef]].
    */
  implicit def jsonFormatter: RW[PathRef] = upickle.default.readwriter[String].bimap[PathRef](
    p => {
      (if (p.quick) "qref" else "ref") + ":" +
        String.format("%08x", p.sig: Integer) + ":" +
        p.base.fold((os.root / p.relpath).toString())(base => base.toString() + ":" + p.relpath.toString())
    },
    s => {
      val parts = s.split(":", 4)
      val prefix = parts(0)
      val hex = parts(1)
      val (base,relPath) = if (parts.size == 3) {
        (None, os.Path(parts(2)).relativeTo(os.root))
      } else {
        (Some(os.Path(parts(2))), os.RelPath(parts(3)))
      }
      PathRef(
        base,
        relPath,
        prefix match { case "qref" => true case "ref" => false },
        // Parsing to a long and casting to an int is the only way to make
        // round-trip handling of negative numbers work =(
        java.lang.Long.parseLong(hex, 16).toInt
      )
    }
  )
}

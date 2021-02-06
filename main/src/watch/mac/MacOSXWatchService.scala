package mill.watch.mac

import com.sun.jna.{Callback, Library, Native, NativeLong, Pointer}
import mill.watch.mac.jna._
import os.Path

import scala.collection.mutable

class DirTree(
    val children: mutable.Map[String, DirTree] =
      mutable.TreeMap.empty[String, DirTree],
    var watch: Option[(ammonite.interp.Watchable.Path, Long)] = None
) {

  def watchHere(w: (ammonite.interp.Watchable.Path, Long)): Unit = {
    children.clear()
    watch = Some(w)
  }

  def include(segment: String): DirTree = {
    if (watch.isDefined) null
    else children.getOrElseUpdate(segment, new DirTree())
  }
}

object MacOSXWatchService {
  private[MacOSXWatchService] val kFSEventStreamEventIdSinceNow
    : Long = -1 // this is 0xFFFFFFFFFFFFFFFF
  private[MacOSXWatchService] val kFSEventStreamCreateFlagNoDefer: Int =
    0x00000002
  private[MacOSXWatchService] val kFSEventStreamCreateFlagFileEvents: Int =
    0x00000010

}

class MacOSXWatchService(
    watchedPaths: Seq[(ammonite.interp.Watchable.Path, Long)],
    watchedValues: Seq[(ammonite.interp.Watchable, Long)],
    latency: Double = 0.1
) {
  import MacOSXWatchService._

  private val monitor = new Object
  private var filesChanged = false

  def valuesUnchanged() = watchedValues.forall {
    case (file, lastMTime) =>
      file.poll() == lastMTime
  }

  val thread = {
    val root = new DirTree()
    val watchedPathMap = watchedPaths.map(t => t._1.p -> t).toMap
    watchedPathMap.foreach {
      case (p, watchAndTime) =>
        val path = p.segments
        var node = root
        var hasNext = path.hasNext
        while (hasNext && node != null) {
          val segment = path.next()
          hasNext = path.hasNext
          node = node.include(segment)
          if (node != null && !hasNext) node.watchHere(watchAndTime)
        }
    }

    val watchedDirSet = watchedPathMap.map {
      case (p, _) => if (!os.isDir(p)) p / os.up else p
    }.toSet

    val watchFiles = watchedPathMap.keys.toSeq.sorted
    val watchDirs = watchedDirSet.toSeq.sorted

    //println(s"Watching files:\n  ${watchFiles.mkString("\n  ")}")
    //println(s"Watching dirs:\n  ${watchDirs.mkString("\n  ")}")

    val values = watchedDirSet.toArray.map(p =>
      CFStringRef.toCFString(p.toString()).getPointer)

    val pathsToWatch =
      FSEvent.INSTANCE.CFArrayCreate(
        null,
        values,
        CFIndex.valueOf(values.length),
        null)

    val callback = new FSEventStreamCallback {
      override def invoke(streamRef: FSEventStreamRef,
                          clientCallBackInfo: Pointer,
                          numEvents: NativeLong,
                          eventPaths: Pointer,
                          eventFlags: Pointer,
                          eventIds: Pointer): Unit = {
        val length = numEvents.intValue
        val paths = eventPaths.getStringArray(0, length).map(os.Path(_))
        val pathIter = paths.iterator
        var notifyChange = false
        while (!notifyChange && pathIter.hasNext) {
          var node = root
          val path = pathIter.next()

          //println(s"Detected change at ${path}")

          var segmentCount = 0
          val segmentIter = path.segments
          while (node != null && segmentIter.hasNext) {
            node.watch.fold {
              val segment = segmentIter.next()
              segmentCount += 1
              node = node.children.getOrElse(segment, null)
              //if (node == null) println(s"  ...not watched")
            } {
              case (watch, mtime) =>
                //println(s"  ...watched at /${path.segments.take(segmentCount).mkString("/")}")
                if (watch.poll() != mtime) {
                  //println(s"  ...and indeed there is one")
                  node = null
                  notifyChange = true
                } else {
                  //println(s"  ...but there is no actual change")
                }
            }
          }

          if (notifyChange) {
            monitor.synchronized {
              filesChanged = true
              monitor.notifyAll()
            }
          }
        }
      }
    }

    var flags = kFSEventStreamCreateFlagNoDefer | kFSEventStreamCreateFlagFileEvents
    val streamRef = FSEvent.INSTANCE.FSEventStreamCreate(
      Pointer.NULL,
      callback,
      Pointer.NULL,
      pathsToWatch,
      kFSEventStreamEventIdSinceNow,
      latency,
      flags)

    val thread = new FSEvent.CFRunLoopThread(streamRef)
    thread.setDaemon(true)
    thread
  }

  def waitForChange(): Unit = {
    thread.start()

    val interval = Math.min((latency * 1000).toLong, 100)
    monitor.synchronized {
      if (watchedValues.isEmpty) {
        while (!filesChanged) monitor.wait()
      } else {
        while (!filesChanged && valuesUnchanged()) monitor.wait(interval)
      }
    }

    thread.close()
  }
}

package mill.watch.mac.jna

import com.sun.jna.{Callback, Library, Native, NativeLong, Pointer}
import com.sun.jna.ptr.PointerByReference

class CFAllocatorRef extends PointerByReference
class FSEventStreamRef extends PointerByReference
class CFArrayRef extends PointerByReference
class CFRunLoopRef extends PointerByReference

class CFIndex extends NativeLong
object CFIndex {
  def valueOf(i: Int) = {
    val idx = new CFIndex
    idx.setValue(i)
    idx
  }
}

class CFStringRef extends PointerByReference
object CFStringRef {
  def toCFString(s: String) = {
    val chars = s.toCharArray
    val length = chars.length
    FSEvent.INSTANCE.CFStringCreateWithCharacters(
      null,
      chars,
      CFIndex.valueOf(length))
  }
}

trait FSEventStreamCallback extends Callback {
  def invoke(
      streamRef: FSEventStreamRef,
      clientCallBackInfo: Pointer,
      numEvents: NativeLong,
      eventPaths: Pointer,
      eventFlags: Pointer,
      eventIds: Pointer
  ): Unit
}

object FSEvent {
  val INSTANCE = Native.loadLibrary("Carbon", classOf[API])

  class CFRunLoopThread(val streamRef: FSEventStreamRef)
      extends Thread("Mac OS filewatcher") {
    var runLoopRef: CFRunLoopRef = _
    var isClosed = false

    override def run(): Unit = {
      streamRef.synchronized {
        if (isClosed) return
        runLoopRef = INSTANCE.CFRunLoopGetCurrent()
        val runLoopMode = CFStringRef.toCFString("kCFRunLoopDefaultMode")
        INSTANCE.FSEventStreamScheduleWithRunLoop(
          streamRef,
          runLoopRef,
          runLoopMode)
        INSTANCE.FSEventStreamStart(streamRef)
      }
      INSTANCE.CFRunLoopRun()
    }

    def close(): Unit = {
      streamRef.synchronized {
        if (isClosed) return
        if (runLoopRef != null) {
          INSTANCE.CFRunLoopStop(runLoopRef)
          INSTANCE.FSEventStreamStop(streamRef)
          INSTANCE.FSEventStreamInvalidate(streamRef)
        }
        INSTANCE.FSEventStreamRelease(streamRef)
        isClosed = true
      }
    }
  }

  trait API extends Library {
    def CFArrayCreate(
        allocator: CFAllocatorRef, // always set to Pointer.NULL
        values: Array[Pointer],
        numValues: CFIndex,
        callBacks: Void // always set to Pointer.NULL
    ): CFArrayRef

    def CFStringCreateWithCharacters(
        alloc: Void, //  always pass NULL
        chars: Array[Char],
        numChars: CFIndex
    ): CFStringRef

    def FSEventStreamCreate(
        v: Pointer, // always use Pointer.NULL
        callback: FSEventStreamCallback,
        context: Pointer,
        pathsToWatch: CFArrayRef,
        sinceWhen: Long, // use -1 for events since now
        latency: Double, // in seconds
        flags: Int // 0 is good for now
    ): FSEventStreamRef

    def FSEventStreamStart(streamRef: FSEventStreamRef)
    def FSEventStreamStop(streamRef: FSEventStreamRef): Unit
    def FSEventStreamScheduleWithRunLoop(
        streamRef: FSEventStreamRef,
        runLoop: CFRunLoopRef,
        runLoopMode: CFStringRef
    ): Unit
    def FSEventStreamUnscheduleFromRunLoop(
        streamRef: FSEventStreamRef,
        runLoop: CFRunLoopRef,
        runLoopMode: CFStringRef
    ): Unit
    def FSEventStreamInvalidate(streamRef: FSEventStreamRef): Unit
    def FSEventStreamRelease(streamRef: FSEventStreamRef): Unit

    def CFRunLoopGetCurrent(): CFRunLoopRef

    def CFRunLoopRun(): Unit
    def CFRunLoopStop(runLoopRef: CFRunLoopRef): Unit
  }

}

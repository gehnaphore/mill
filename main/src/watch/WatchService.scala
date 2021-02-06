package mill.watch

import ammonite.util._
import mill.watch.mac.MacOSXWatchService

object WatchService {
  val isMac = System.getProperty("os.name").toLowerCase.startsWith("mac")
  var gotMacException = false

  def watcherForPathsAndValues(
      watchedPaths: Seq[(ammonite.interp.Watchable.Path, Long)],
      watchedOther: Seq[(ammonite.interp.Watchable, Long)]
  ): () => Unit = {

    def genericWait() = {
      val allWatches = watchedPaths ++ watchedOther
      def allUnchanged() = allWatches.forall{ case (w, t) => w.poll() == t }
      () => {
        while(allUnchanged()) Thread.sleep(100)
      }
    }

    if (isMac && !gotMacException) {
      try {
        val watcher = new MacOSXWatchService(watchedPaths, watchedOther)
        () => watcher.waitForChange()
      } catch {
        case e: InterruptedException => throw e
        case e: Throwable =>
          gotMacException = true
          genericWait()
      }
    } else genericWait()
  }
}

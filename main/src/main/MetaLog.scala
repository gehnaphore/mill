package mill.main

import java.io.{OutputStream, PrintStream}

import mill.api.{BuildProblemReporter, Severity}

object MetaLog {
  case class ProblemPosition(
    line: Option[Int] = None,
    lineContent: String = "",
    offset: Option[Int] = None,
    pointer: Option[Int] = None,
    pointerSpace: Option[String] = None,
    sourcePath: Option[String] = None
  )

  object ProblemPosition {
    implicit val rw: upickle.default.ReadWriter[ProblemPosition] = upickle.default.macroRW
    def fromProblemPosition(ps: mill.api.ProblemPosition) = ProblemPosition(
      line = ps.line,
      lineContent = ps.lineContent,
      offset = ps.offset,
      pointer = ps.pointer,
      pointerSpace = ps.pointerSpace,
      sourcePath = ps.sourcePath
    )
  }

  case class Problem(
    mod: String,
    cat: String,
    sev: String,
    msg: String,
    pos: ProblemPosition
  )
  object Problem {
    implicit val rw: upickle.default.ReadWriter[Problem] = upickle.default.macroRW
    def fromProblem(module: String, p: mill.api.Problem) = Problem(
      mod = module,
      cat = p.category,
      sev = p.severity.toString,
      msg = p.message,
      pos = ProblemPosition.fromProblemPosition(p.position)
    )
  }
}

class MetaLog(dest: os.Path, debugEnabled: Boolean = false) {

  var _outStream: OutputStream = _
  var _outPrinter: PrintStream = _

  def proxyTo(log: mill.api.Logger): mill.api.Logger = new mill.api.Logger {
    override def colored = false

    override lazy val errorStream = log.errorStream
    override lazy val outputStream = log.outputStream
    override lazy val inStream = log.inStream

    override def info(s: String): Unit = {
      log.info(s)
      messageOut("info",s)
    }

    override def error(s: String): Unit = {
      log.error(s)
      messageOut("error",s)
    }

    override def ticker(s: String): Unit = {
      log.ticker(s)
      messageOut("ticker",s)
    }

    override def debug(s: String): Unit = {
      log.debug(s)
      if (debugEnabled) messageOut("debug",s)
    }
  }

  def close(): Unit = {
    if (_outPrinter != null) {
      try { _outPrinter.close() } catch { case e: Throwable => }
      try { _outStream.close() } catch { case e: Throwable => }
      _outPrinter = null
      _outStream = null
    }
  }

  override def finalize(): Unit = {
    super.finalize()
  }

  def printOut(s: String): Unit = synchronized {
    if (_outPrinter == null) {
      _outStream = os.write.over.outputStream(dest)
      _outPrinter = new PrintStream(_outStream)
      printOut(ujson.Obj("what" -> "startMetaLog", "time" -> System.currentTimeMillis()))
    }
    _outPrinter.println(s)
    _outPrinter.flush()
  }

  def printOut(o: ujson.Obj): Unit = {
    val s = upickle.default.write(o)
    printOut(s)
  }

  def printOut(pp: MetaLog.Problem): Unit = {
    val json = upickle.default.writeJs(pp)
    printOut(ujson.Obj("what" -> "diag", "time" -> System.currentTimeMillis(), "diag" -> json))
  }

  def messageOut(tpe: String, msg: String): Unit = {
    printOut(ujson.Obj("what" -> s"${tpe}Log", "time" -> System.currentTimeMillis(), "msg" -> msg))
  }

  def startBuild(): Unit = {
    printOut(ujson.Obj("what" -> "startBuild", "time" -> System.currentTimeMillis()))
  }

  def startModule(name: String): Unit = {
    printOut(ujson.Obj("what" -> "startModule", "name" -> name, "time" -> System.currentTimeMillis()))
  }

  def endModule(name: String, success: Boolean, counts: Map[Severity,Int]): Unit = {
    printOut(
      ujson.Obj(
        "what" -> "endModule",
        "name" -> name,
        "success" -> success,
        "counts" -> counts.map { case (k,v) => k.toString -> v},
        "time" -> System.currentTimeMillis()
      )
    )
  }

  def finishBuild(): Unit = {
    printOut(ujson.Obj("what" -> "endBuild", "time" -> System.currentTimeMillis()))
  }

  def reporterFor(idx: Int): Option[BuildProblemReporter] = {
    Some(
      new BuildProblemReporter {
        var errorCount = 0
        var warningCount = 0
        var infoCount = 0
        var moduleName = "<unknown>"

        def outputProblem(p: mill.api.Problem): Unit = {
          val pp = MetaLog.Problem.fromProblem(moduleName, p)
          printOut(pp)
        }

        override def logStart(name: String): Unit = {
          moduleName = name
          startModule(name)
        }

        override def logError(problem: mill.api.Problem): Unit = {
          outputProblem(problem)
          errorCount += 1
        }
        override def logWarning(problem: mill.api.Problem): Unit = {
          outputProblem(problem)
          warningCount += 1
        }
        override def logInfo(problem: mill.api.Problem): Unit = {
          outputProblem(problem)
          infoCount += 1
        }

        override def logEnd(success: Boolean, counts: Map[Severity, Int]): Unit = {
          endModule(moduleName, success, counts)
        }

        override def printSummary(): Unit = {}
      }
    )
  }
}

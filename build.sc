#!/usr/bin/env amm
import $cp.scalaplugin.target.`scala-2.12`.`mill-scalaplugin-assembly-0.1-SNAPSHOT.jar`
import ammonite.ops.pwd
import mill._
import mill.scalaplugin.{Module, Dep, TestRunner}

@main def run(args: String*) = mill.Main(args, Build, interp.watch)

@main def idea() = mill.scalaplugin.GenIdea(Build)
object Build{
  object Core extends Module {
    def scalaVersion = "2.12.4"
    override def compileIvyDeps = Seq(
      Dep.Java("org.scala-lang", "scala-reflect", scalaVersion())
    )

    override def ivyDeps = Seq(
      Dep("com.lihaoyi", "sourcecode", "0.1.4"),
      Dep("com.lihaoyi", "pprint", "0.5.3"),
      Dep.Point("com.lihaoyi", "ammonite", "1.0.3"),
      Dep("com.typesafe.play", "play-json", "2.6.6"),
      Dep("org.scala-sbt", "zinc", "1.0.3"),
      Dep.Java("org.scala-sbt", "test-interface", "1.0")
    )

    def basePath = pwd / 'core
    override def sources = pwd/'core/'src/'main/'scala
  }
  object CoreTests extends Module {
    def scalaVersion = "2.12.4"
    override def projectDeps = Seq(Core)
    def basePath = pwd / 'scalaplugin
    override def sources = pwd/'core/'src/'test/'scala
    override def ivyDeps = Seq(
      Dep("com.lihaoyi", "utest", "0.6.0")
    )

    def test() = T.command{
      TestRunner.apply(
        "mill.UTestFramework",
        runDepClasspath().map(_.path) :+ compile().path,
        Seq(compile().path)
      )
    }
  }

  object ScalaPlugin extends Module {
    def scalaVersion = "2.12.4"
    override def projectDeps = Seq(Core)
    def basePath = pwd / 'scalaplugin
    override def sources = pwd/'scalaplugin/'src/'main/'scala
  }
}


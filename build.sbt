ThisBuild / version := "0.1"
ThisBuild / scalaVersion := "2.12.18"
ThisBuild / organization := "brenay"
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision

//---------------- general dependencies -----------
val scalatest = "org.scalatest" %% "scalatest" % "3.2.20"

//--------------- SpinalHDL submodule -------------

val spinalRoot = file("ext/SpinalHDL")

lazy val spinalIdslPlugin = ProjectRef(spinalRoot, "idslplugin")
lazy val spinalSim = ProjectRef(spinalRoot, "sim")
lazy val spinalCore = ProjectRef(spinalRoot, "core")
lazy val spinalLib = ProjectRef(spinalRoot, "lib")

scalacOptions += (spinalIdslPlugin / Compile / packageBin / artifactPath).map { file =>
  s"-Xplugin:${file.getAbsolutePath}"
}.value

scalacOptions ++= Seq(
  "-deprecation",
  "-Ywarn-unused",
  "-feature",
  "-unchecked",
  "-Xlint:adapted-args",
  "-Xlint:infer-any"
)

// TODO fix the fact that vexii doesn't work without full
// `git submodule update --init --recursive  ext/VexiiRiscv/`
val vexiiRoot = file("ext/VexiiRiscv")
lazy val vexiiRiscv = RootProject(vexiiRoot)

//--------------- brenay flows dependencies ------------

val ujson = "com.lihaoyi" %% "ujson" % "4.4.3"
val flowsDeps = Seq(ujson)

//---------------  project definition ------------------

lazy val brenay = (project in file("."))
  .settings(
    libraryDependencies ++= Seq(
      scalatest
    ) ++ flowsDeps
  )
  .dependsOn(spinalIdslPlugin, spinalSim, spinalCore, spinalLib, vexiiRiscv)

// clean generic generated files
cleanFiles += baseDirectory.value / "gen"
cleanFiles += baseDirectory.value / "simWorkspace"

// This is needed to see progression without line return in Flow
run / connectInput := true
outputStrategy := Some(StdoutOutput)

Global / concurrentRestrictions := Seq(
  Tags.limitAll(16)
)

fork := true // this is necessary so spinal Verilator compilation works properly

Test / testForkedParallel := true

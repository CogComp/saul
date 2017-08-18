import de.heikoseeberger.sbtheader.HeaderPattern
import sbtrelease.ReleaseStateTransformations._

scalaVersion in ThisBuild := "2.11.7"

val cogcompNLPVersion = "3.0.71"
val cogcompPipelineVersion = "0.1.25"
val ccgGroupId = "edu.illinois.cs.cogcomp"
val headerMsg =  """/** This software is released under the University of Illinois/Research and Academic Use License. See
                        |  * the LICENSE file in the root folder for details. Copyright (c) 2016
                        |  *
                        |  * Developed by: The Cognitive Computations Group, University of Illinois at Urbana-Champaign
                        |  * http://cogcomp.cs.illinois.edu/
                        |  */
                        |""".stripMargin


lazy val saulUser = System.getenv("SAUL_USER")
lazy val user = if (saulUser == null) System.getProperty("user.name") else saulUser
lazy val keyFile = new java.io.File(Path.userHome.absolutePath + "/.ssh/id_rsa")

lazy val scalaDoc = taskKey[Unit]("Execute the shell script for releasing our Scala doc")
scalaDoc := { "bash scaladoc.sh" ! }

lazy val docSettings = Seq(
  autoAPIMappings := true,
  apiURL := Some(url("http://cogcomp.cs.illinois.edu/software/doc/saul/")),
  scalacOptions in Test ++= Seq("-Yrangepos")
)

lazy val releaseSettings = Seq(
  releaseIgnoreUntrackedFiles := true,
  releaseProcess := Seq[ReleaseStep](
    checkSnapshotDependencies,
    inquireVersions,
    setReleaseVersion,
    commitReleaseVersion,                   // performs the initial git checks
    //tagRelease,
    publishArtifacts,                       // checks whether `publishTo` is properly set up
    releaseStepTask(scalaDoc),              //release the scalaDocs
    setNextVersion,
    commitNextVersion,
    pushChanges                             // checks that an upstream branch is properly configured
  )
)

lazy val publishSettings = Seq(
  publishTo := Some(
    Resolver.ssh(
      "CogcompSoftwareRepo", "bilbo.cs.illinois.edu",
      "/mounts/bilbo/disks/0/www/cogcomp/html/m2repo/") as (user, keyFile)
  )
)

lazy val commonSettings = Seq(
  organization := ccgGroupId,
  name := "saul-project",
  resolvers ++= Seq(
    Resolver.mavenLocal,
    "CogcompSoftware" at "http://cogcomp.cs.illinois.edu/m2repo/"
  ),
  javaOptions ++= List("-Xmx11g"),
  libraryDependencies ++= Seq(
    ccgGroupId % "LBJava" % "1.2.25" withSources,
    ccgGroupId % "illinois-core-utilities" % cogcompNLPVersion withSources,
    "com.gurobi" % "gurobi" % "6.0",
    "org.apache.commons" % "commons-math3" % "3.0",
    "org.scalatest" % "scalatest_2.11" % "2.2.4",
    "ch.qos.logback" % "logback-classic" % "1.1.7",
    "me.tongfei" % "progressbar" % "0.5.5"
  ),
  fork := true,
  connectInput in run := true,
  headers := Map(
    "scala" -> (HeaderPattern.cStyleBlockComment, headerMsg),
    "java" -> (HeaderPattern.cStyleBlockComment, headerMsg)
  ),
  excludeDependencies += "org.slf4j" % "slf4j-log4j12"
) ++ publishSettings

lazy val root = (project in file("."))
  .settings(commonSettings: _*)
  .settings(releaseSettings: _*)
  .aggregate(saulCore, saulExamples)
  .enablePlugins(AutomateHeaderPlugin)

lazy val saulCore = (project in file("saul-core")).
  settings(commonSettings: _*).
  settings(docSettings: _*).
  settings(
    name := "saul"
  ).enablePlugins(AutomateHeaderPlugin)

lazy val saulExamples = (project in file("saul-examples")).
  settings(commonSettings: _*).
  settings(
    name := "saul-examples",
    libraryDependencies ++= Seq(
      ccgGroupId % "illinois-nlp-pipeline" % cogcompPipelineVersion withSources,
      ccgGroupId % "illinois-curator" % cogcompNLPVersion,
      ccgGroupId % "illinois-edison" % cogcompNLPVersion,
      ccgGroupId % "illinois-corpusreaders" % cogcompNLPVersion,
      ccgGroupId % "illinois-pos" % cogcompNLPVersion,
      ccgGroupId % "saul-pos-tagger-models" % "1.4",
      ccgGroupId % "saul-er-models" % "1.8",
      ccgGroupId % "saul-srl-models" % "1.3",
      "org.json" % "json" % "20140107",
      "com.twitter" % "hbc-core" % "2.2.0",
      "org.rogach" %% "scallop" % "2.0.5"
    )
  ).dependsOn(saulCore)
  .aggregate(saulCore)
  .enablePlugins(AutomateHeaderPlugin)

lazy val saulWebapp = (project in file("saul-webapp")).
  enablePlugins(PlayScala).
  settings(commonSettings: _*).
  settings(
    name := "saul-webapp",
    libraryDependencies ++= Seq(
      "com.typesafe.play" % "play_2.11" % "2.4.3",
      "org.webjars" %% "webjars-play" % "2.4.0-1",
      "org.webjars" % "bootstrap" % "3.3.6",
      "org.webjars.bower" % "tether-shepherd" % "1.1.3",
      "org.webjars" % "ace" % "1.2.2",
      "org.webjars" % "sigma.js" % "1.0.3",
      "org.webjars" % "d3js" % "3.5.16",
      "org.scala-lang" % "scala-compiler" % scalaVersion.value,
      jdbc,
      cache,
      ws,
      specs2 % Test
    ),
    resolvers ++= Seq("scalaz-bintray" at "http://dl.bintray.com/scalaz/releases"),
    routesGenerator := InjectedRoutesGenerator
  ).dependsOn(saulExamples).aggregate(saulExamples)
  .enablePlugins(AutomateHeaderPlugin)

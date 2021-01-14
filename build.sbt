name := "pmsm-sbt"
version := "0.0.0"
scalaVersion := "2.13.4"

lazy val testLibs = Seq(
  "org.scalactic" %% "scalactic" % "3.1.0" % Test,
  "org.scalatest" %% "scalatest" % "3.1.0" % Test
)

libraryDependencies ++= testLibs

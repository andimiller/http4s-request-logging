name := "http4s-request-logging"

val Http4sVersion  = "0.18.0"

lazy val root = (project in file("."))
  .settings(
    organization := "net.andimiller",
    name := "http4s-request-logging",
    version := "0.0.1-SNAPSHOT",
    scalaVersion := "2.12.4",
    libraryDependencies ++= Seq(
      "org.http4s"           %% "http4s-dsl"          % Http4sVersion,
      "org.http4s"           %% "http4s-blaze-server" % Http4sVersion
    )
  )
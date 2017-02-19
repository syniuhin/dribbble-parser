name := "DribbbleParser"

version := "1.0"

lazy val `dribbbleparser` = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.11.7"

libraryDependencies ++= Seq(
  ws,
  "com.typesafe.akka" %% "akka-contrib" % "2.4.17"
)

unmanagedResourceDirectories in Test <+= baseDirectory(_ / "target/web/public/test")

resolvers += "scalaz-bintray" at "https://dl.bintray.com/scalaz/releases"  
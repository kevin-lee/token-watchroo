logLevel := sbt.Level.Warn

addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.13.1")

val sbtDevOopsVersion = "3.8.0"
addSbtPlugin("io.kevinlee" % "sbt-devoops-scala"     % sbtDevOopsVersion)
addSbtPlugin("io.kevinlee" % "sbt-devoops-sbt-extra" % sbtDevOopsVersion)
addSbtPlugin("io.kevinlee" % "sbt-devoops-github"    % sbtDevOopsVersion)

addSbtPlugin("io.kevinlee" % "sbt-devoops-starter" % sbtDevOopsVersion)

addSbtPlugin("com.github.sbt" % "sbt-dynver" % "5.1.1")

/* modules/token-watchroo-core/native/src/main/scala/java/lang/impl/PosixThread.scala is a patched copy of this
 * version's javalib file (#43). On an upgrade, diff it against the new javalib source before changing the version. */
addSbtPlugin("org.scala-native"   % "sbt-scala-native"              % "0.5.12")
addSbtPlugin("org.portable-scala" % "sbt-scala-native-crossproject" % "1.4.0")

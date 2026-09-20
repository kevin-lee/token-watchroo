import ProjectInfo.*
import scala.scalanative.build.*
import scala.sys.process.Process

ThisBuild / scalaVersion := props.ScalaVersion
ThisBuild / organization := props.Org
ThisBuild / organizationName := props.OrgName
ThisBuild / developers := List(
  Developer(
    props.GitHubUsername,
    props.AuthorName,
    props.AuthorEmail,
    uri(s"https://github.com/${props.GitHubUsername}"),
  )
)
ThisBuild / homepage := uri(
  s"https://github.com/${props.GitHubUsername}/${props.RepoName}"
).some
ThisBuild / scmInfo :=
  ScmInfo(
    uri(s"https://github.com/${props.GitHubUsername}/${props.RepoName}"),
    s"https://github.com/${props.GitHubUsername}/${props.RepoName}.git",
  ).some

/* The Scala Native 0.5.12 GC loses a root of a thread stopped by a trap-based yieldpoint, the release default, and
 * frees live objects (#44, upstream scala-native/scala-native#5046: on CI 9 of 15 trap jobs failed against 0 of 15
 * linked with conditional yieldpoints). The mode is chosen at link time from SCALANATIVE_GC_TRAP_BASED_YIELDPOINTS in
 * the environment of the process that started the sbt server, nothing inside the build can set it, and the incremental
 * check ignores it, so a server started without the variable would link and test every binary in the wrong mode for as
 * long as it lives. The check runs at load so that such a server never gets to work. TW_ALLOW_TRAP_YIELDPOINTS=1 opts
 * out for experiments that need a trap build and also skips the archive gate in stageNativeLib. Remove this check, that
 * gate, checkYieldpoints, scripts/check-yieldpoints.sh and the CI env together when a Scala Native release fixes #5046. */
Global / onLoad := (Global / onLoad).value.andThen { state =>
  val yieldpoints = sys.env.get("SCALANATIVE_GC_TRAP_BASED_YIELDPOINTS")
  val allowTraps  = sys.env.get("TW_ALLOW_TRAP_YIELDPOINTS").contains("1")
  if (yieldpoints.contains("0") || allowTraps) state
  else
    sys.error(
      s"""SCALANATIVE_GC_TRAP_BASED_YIELDPOINTS is ${yieldpoints
          .fold("not set")(v => s"'$v'")}, so Scala Native would link with trap-based yieldpoints, which the 0.5.12 GC corrupts (#44).
         |Fix: export SCALANATIVE_GC_TRAP_BASED_YIELDPOINTS=0 in the shell profile, run `sbt --client shutdown`, then start a fresh `sbt`.
         |A target linked in the other mode never relinks for the variable: remove the native and native-test folders under target/out/native0.5/scala-3.8.4/*/ first.
         |To build with trap-based yieldpoints on purpose, set TW_ALLOW_TRAP_YIELDPOINTS=1.""".stripMargin
    )
}

lazy val tokenWatchroo = (project in file("."))
  .settings(name := props.ProjectName)
  .settings(noPublish)
  .settings(appAssemblySettings)
  .settings(
    cleanFiles ++= List(
      baseDirectory.value / "dist",
      baseDirectory.value / "swift" / ".build",
      baseDirectory.value / "swift" / "lib",
    )
  )
  .aggregate(core, providers, app)

lazy val core = module("core")
  .enablePlugins(ScalaNativePlugin, BuildInfoPlugin)
  .settings(
    buildInfoKeys := List[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoObject := "TokenWatchrooInfo",
    buildInfoPackage := "tokenwatchroo.info",
    libraryDependencies ++= List(
      libs.catsCore.value,
      libs.kittens.value,
      libs.refined4sCore.value,
      libs.refined4sCats.value,
      libs.refined4sExtrasRender.value,
      libs.extrasRender.value,
      libs.justSemVerCore.value,
      libs.jsoniterCore.value,
      libs.jsoniterMacros.value,
    ) ++
      libs.tests.hedgehog.value ++
      libs.tests.hedgehogExtra.value,
    nativeConfig ~= commonNativeConfig,
  )
  .settings(nativeSettings)
  .settings(
    /* src/main/scala/java/lang/impl/PosixThread.scala is upstream Scala Native code, compiled without `-Werror`
     * there. Its warnings (discarded `CInt` results, a non-exhaustive `@switch`) are silenced instead of fixed, so that
     * the diff against upstream stays small. */
    Compile / scalacOptions += "-Wconf:src=java/lang/impl/.*:silent"
  )

lazy val providers = module("providers")
  .enablePlugins(ScalaNativePlugin)
  .settings(
    libraryDependencies ++= List(
      libs.catsEffect.value,
      libs.extrasCats.value,
      libs.osLib.value,
    ) ++ libs.tests.munit.value,
    nativeConfig ~= commonNativeConfig,
  )
  .settings(nativeSettings)
  .settings(noPublish)
  .dependsOn(core % "compile->compile;test->test")

lazy val app = module("app")
  .enablePlugins(ScalaNativePlugin)
  .settings(
    libraryDependencies ++= libs.tests.munit.value,
    nativeConfig ~= { c =>
      commonNativeConfig(c)
        .withBuildTarget(BuildTarget.libraryStatic)
        .withBaseName(props.StaticLibBaseName)
    },
    /* The test binary must be a runnable application, not a static library. */
    Test / nativeConfig ~= { c => c.withBuildTarget(BuildTarget.application) },
  )
  .settings(nativeSettings)
  .settings(noPublish)
  .dependsOn(providers)

/* App assembly tasks, scoped to the root project: sbt 2 applies bare settings to every subproject. */

lazy val stageNativeLib   = taskKey[String]("Copy the Scala Native static library into swift/lib/")
lazy val swiftBuild       = taskKey[String]("Build the Swift shell against the staged static library")
lazy val swiftTest        = taskKey[Unit]("Run the Swift shell tests against the staged static library")
lazy val bundleApp        = taskKey[String]("Assemble dist/Token Watchroo.app")
lazy val runApp           = taskKey[Unit]("Assemble and open the app bundle")
lazy val checkYieldpoints =
  taskKey[Unit]("Check that the three test binaries were linked with conditional GC yieldpoints (#44)")

lazy val appAssemblySettings: SettingsDefinition = List(
  stageNativeLib := Def.uncached {
    val log       = streams.value.log
    val converter = fileConverter.value
    val archive   = converter.toPath((app / Compile / nativeLink).value).toFile
    val target    = baseDirectory.value / "swift" / "lib" / s"lib${props.StaticLibBaseName}.a"
    /* Gated before it is staged, so bundleApp, runApp and swiftTest fail before Swift builds when a stale target was
     * linked with trap-based yieldpoints (#44, see the onLoad check). */
    if (sys.env.get("TW_ALLOW_TRAP_YIELDPOINTS").contains("1"))
      log.warn(s"TW_ALLOW_TRAP_YIELDPOINTS=1: not checking the yieldpoint mode of ${archive.getPath}")
    else checkYieldpointMode(baseDirectory.value, List(archive))
    IO.copyFile(archive, target)
    log.info(s"Staged ${archive.getPath} -> ${target.getPath}")
    target.getAbsolutePath
  },

  swiftBuild := Def.uncached {
    val log        = streams.value.log
    val swiftDir   = baseDirectory.value / "swift"
    val archive    = file(stageNativeLib.value)
    val hashFile   = swiftDir / "lib" / ".last-hash"
    val executable = swiftDir / ".build" / "release" / props.ExecutableName
    val newHash    = Hash.toHex(Hash(archive))
    val oldHash    = if (hashFile.exists()) IO.read(hashFile).trim else ""
    if (newHash != oldHash || !executable.exists()) {
      /* SwiftPM does not track -Xlinker inputs, so it would not relink after the archive changed. */
      if (executable.exists()) {
        log.info("Static library changed: forcing a relink of the Swift executable")
        IO.delete(executable)
      } else ()
    } else ()
    val exit       = Process(Seq("swift", "build", "-c", "release", "--product", props.ExecutableName), swiftDir).!
    if (exit != 0) sys.error(s"swift build failed with exit code $exit") else ()
    IO.write(hashFile, newHash)
    executable.getAbsolutePath
  },

  swiftTest := Def.uncached {
    val log      = streams.value.log
    val swiftDir = baseDirectory.value / "swift"
    val archive  = stageNativeLib.value
    /* The tests never start the library, so SwiftPM not relinking after an archive change leaves them valid. */
    log.info(s"Swift tests against $archive")
    val exit     = Process(Seq("swift", "test"), swiftDir).!
    if (exit != 0) sys.error(s"swift test failed with exit code $exit") else ()
  },

  bundleApp := Def.uncached {
    val log        = streams.value.log
    val executable = swiftBuild.value
    val script     = baseDirectory.value / "scripts" / "bundle-app.sh"
    val outDir     = baseDirectory.value / "dist"
    val exit       = Process(
      Seq(script.getPath, executable, outDir.getPath, version.value, props.BundleId),
      baseDirectory.value,
    ).!
    if (exit != 0) sys.error(s"bundle-app.sh failed with exit code $exit") else ()
    val appBundle  = outDir / s"${props.ProductName}.app"
    log.info(s"Bundle: ${appBundle.getPath}")
    appBundle.getAbsolutePath
  },

  runApp := Def.uncached {
    val appBundle = bundleApp.value
    val exit      = Process(Seq("open", appBundle)).!
    if (exit != 0) sys.error(s"open failed with exit code $exit") else ()
  },

  checkYieldpoints := Def.uncached {
    val converter = fileConverter.value
    val binaries  = List(
      (core / Test / nativeLink).value,
      (providers / Test / nativeLink).value,
      (app / Test / nativeLink).value,
    ).map(ref => converter.toPath(ref).toFile)
    checkYieldpointMode(baseDirectory.value, binaries)
    streams.value.log.info(s"Conditional GC yieldpoints confirmed for ${binaries.map(_.getName).mkString(", ")}")
  }
)

addCommandAlias("buildApp", "bundleApp")

/* Helpers */

// format: off
def prefixedProjectName(name: String) = s"${props.ProjectName}${if (name.isEmpty) "" else s"-$name"}"
// format: on

def module(projectName: String): Project = {
  val prefixedName = prefixedProjectName(projectName)
  val modulePath   = file(s"modules/$prefixedName")
  Project(projectName, modulePath)
    .settings(
      name := prefixedName,
      fork := true,
      scalacOptions ++= List("-no-indent", "-explain"),
      licenses := props.licenses,
    )
}

lazy val nativeSettings: SettingsDefinition = List(Test / fork := false)

/* Runs scripts/check-yieldpoints.sh conditional on the files and fails when any of them was linked with trap-based
 * yieldpoints (#44). The script prints the five symbol counts per file. */
def checkYieldpointMode(base: File, files: List[File]): Unit = {
  val script = base / "scripts" / "check-yieldpoints.sh"
  val exit   = Process(script.getPath +: "conditional" +: files.map(_.getPath), base).!
  if (exit != 0)
    sys.error(
      s"check-yieldpoints.sh exited with $exit: a binary was not linked with conditional GC yieldpoints (#44). " +
        "Run `sbt --client shutdown`, remove the native and native-test folders under target/out/native0.5/scala-3.8.4/*/, " +
        "and start sbt with SCALANATIVE_GC_TRAP_BASED_YIELDPOINTS=0."
    )
  else ()
}

def commonNativeConfig(c: NativeConfig): NativeConfig = {
  val deploymentTarget = s"-mmacosx-version-min=${props.MinimumMacOsVersion}"
  /* The interflow optimiser in release-fast mode miscompiles the poller's first tick since issue #3: the app never
   * emitted a snapshot, deterministically, while the same code works with the optimiser off, in debug mode, and in
   * release-full mode (verified 2026-09-12). The optimiser stays off until the trigger is bisected (#20). The optimiser
   * does not remove the allocation behind #43 either, which is fixed by the patched `PosixThread` copy in
   * `modules/token-watchroo-core/src/main/scala/java/lang/impl/`. */
  c.withLTO(LTO.none)
    .withMode(Mode.releaseFast)
    .withOptimize(false)
    .withGC(GC.commix)
    .withCompileOptions(c.compileOptions :+ deploymentTarget)
    .withLinkingOptions(c.linkingOptions :+ deploymentTarget)
}

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

/* Scala Native 0.5.12's RegistersCapture.h takes its buffer by value on x86 and x86_64, so the GC never scans the
 * callee-saved registers of a thread that goes Unmanaged, and on x86_64 it frees objects that only a register refers to
 * (#63, upstream scala-native/scala-native#5048). native-overrides/scala-native-0.5.12/ holds a patched copy that
 * commonNativeConfig puts on the C include path ahead of nativelib's own directories. The copy belongs to the nativelib
 * it was taken from, so another Scala Native version is refused until the copy is compared with the new header. Remove
 * this check, the directory, the -I option, checkRegistersCapture, scripts/check-registers-capture.sh and the CI steps
 * together when a Scala Native release includes scala-native#5048. */
lazy val registersCaptureOverrideVersion = "0.5.12"

Global / onLoad := (Global / onLoad).value.andThen { state =>
  if (nativeVersion == registersCaptureOverrideVersion) state
  else
    sys.error(
      s"""Scala Native is $nativeVersion, but native-overrides/scala-native-$registersCaptureOverrideVersion/immix_commix/RegistersCapture.h patches the $registersCaptureOverrideVersion header (#63).
         |If this Scala Native release includes scala-native/scala-native#5048, remove the override as build.sbt describes. Otherwise diff the copy against the new nativelib header, move it to native-overrides/scala-native-$nativeVersion/ and update registersCaptureOverrideVersion.""".stripMargin
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
    nativeConfig := Def.uncached(commonNativeConfig((LocalRootProject / baseDirectory).value)(nativeConfig.value)),
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
    nativeConfig := Def.uncached(commonNativeConfig((LocalRootProject / baseDirectory).value)(nativeConfig.value)),
  )
  .settings(nativeSettings)
  .settings(stormGcSettings)
  .settings(noPublish)
  .dependsOn(core % "compile->compile;test->test")

lazy val app = module("app")
  .enablePlugins(ScalaNativePlugin)
  .settings(
    libraryDependencies ++= libs.tests.munit.value,
    nativeConfig := Def.uncached(
      commonNativeConfig((LocalRootProject / baseDirectory).value)(nativeConfig.value)
        .withBuildTarget(BuildTarget.libraryStatic)
        .withBaseName(props.StaticLibBaseName)
    ),
    /* The test binary must be a runnable application, not a static library. */
    Test / nativeConfig ~= { c => c.withBuildTarget(BuildTarget.application) },
  )
  .settings(nativeSettings)
  .settings(stormGcSettings)
  .settings(noPublish)
  .dependsOn(providers)

/* App assembly tasks, scoped to the root project: sbt 2 applies bare settings to every subproject. */

lazy val stageNativeLib        = taskKey[String]("Copy the Scala Native static library into swift/lib/")
lazy val swiftBuild            = taskKey[String]("Build the Swift shell against the staged static library")
lazy val swiftTest             = taskKey[Unit]("Run the Swift shell tests against the staged static library")
lazy val bundleApp             = taskKey[String]("Assemble dist/Token Watchroo.app")
lazy val runApp                = taskKey[Unit]("Assemble and open the app bundle")
lazy val checkYieldpoints      =
  taskKey[Unit]("Check that the three test binaries were linked with conditional GC yieldpoints (#44)")
lazy val checkRegistersCapture =
  taskKey[Unit]("Check that the three test binaries were compiled with the patched RegistersCapture.h (#63)")

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
    /* Not skipped by TW_ALLOW_TRAP_YIELDPOINTS, which is about the other workaround: an archive compiled with
     * nativelib's own RegistersCapture.h frees live objects on x86_64 (#63). */
    checkRegistersCaptureOverride(baseDirectory.value, List(archive))
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
  },

  checkRegistersCapture := Def.uncached {
    val converter = fileConverter.value
    val binaries  = List(
      (core / Test / nativeLink).value,
      (providers / Test / nativeLink).value,
      (app / Test / nativeLink).value,
    ).map(ref => converter.toPath(ref).toFile)
    checkRegistersCaptureOverride(baseDirectory.value, binaries)
    streams.value.log.info(s"Patched RegistersCapture.h confirmed for ${binaries.map(_.getName).mkString(", ")}")
  },
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

/* BridgeSpec (app) and CurlBufferSpec (providers) call System.gc() back to back while other fibres allocate, and
 * commix's growth rules then grew every test process's heap to the machine's whole memory (#65). At that ceiling an
 * allocation that misses its collect and lazy-sweep retries exits the process with "Out of heap space grow heap", and
 * on 3-CPU CI runners such misses happen hundreds of times per process (#58). The test processes of these two modules
 * therefore run with a bounded heap and growth rules that the storm does not keep firing:
 *   - GC_TIME_RATIO=1.0: a mark never takes the whole time since the previous mark ended, so the mark-time rule never
 *     fires. 0.9 still let arm64 CI processes reach 7 GiB (run 35614739149).
 *   - GC_FREE_RATIO=0.1: growth when fewer than a tenth of the blocks are free after a sweep, instead of half. At 0
 *     nothing grew the heap before a thread ran out of free blocks, and it exited with "Out of heap space
 *     growIfNeeded:re-init cursors" (run 35631519713).
 *   - GC_MAXIMUM_HEAP_SIZE=2G: a test process never takes more than 2 GiB, whatever the machine.
 * The heap also grows when an allocation cannot be met, and when more than a quarter of the blocks are unavailable
 * (compile-time in commix). The settings reach every suite of the two test binaries. TW_STORM_GC_DEFAULTS=1 in the
 * environment that starts the sbt server adds none of them, for experiments and for the control arm of a CI
 * comparison. Def.uncached, because the value depends on that environment, which sbt 2's cache does not see. */
lazy val stormGcSettings: SettingsDefinition = List(
  Test / envVars := Def.uncached(
    (Test / envVars).value ++
      (if (sys.env.get("TW_STORM_GC_DEFAULTS").contains("1")) Map.empty[String, String]
       else Map("GC_TIME_RATIO" -> "1.0", "GC_FREE_RATIO" -> "0.1", "GC_MAXIMUM_HEAP_SIZE" -> "2G"))
  )
)

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

/* Runs scripts/check-registers-capture.sh on the files and fails when any of them was compiled with nativelib's own
 * RegistersCapture.h instead of the patched copy in native-overrides/ (#63). */
def checkRegistersCaptureOverride(base: File, files: List[File]): Unit = {
  val script = base / "scripts" / "check-registers-capture.sh"
  val exit   = Process(script.getPath +: files.map(_.getPath), base).!
  if (exit != 0)
    sys.error(
      s"check-registers-capture.sh exited with $exit: a binary was not compiled with native-overrides/scala-native-$registersCaptureOverrideVersion/immix_commix/RegistersCapture.h (#63). " +
        "Check that commonNativeConfig still adds registersCaptureInclude to the C options, then relink."
    )
  else ()
}

def commonNativeConfig(base: File)(c: NativeConfig): NativeConfig = {
  val deploymentTarget = s"-mmacosx-version-min=${props.MinimumMacOsVersion}"
  /* The interflow optimiser is on. It was off from 2026-09-12 (#20), because the app's first poller tick never
   * completed in release-fast with it on, while it did with the optimiser off, in debug mode and in release-full mode.
   * That build linked with trap-based GC yieldpoints and without the patched `PosixThread`, and both have been worked
   * around since (#44, #43). #20 re-tested the optimiser on this tree with the app and the storm suites on both
   * architectures. The optimiser does not remove the allocation behind #43, so the patched `PosixThread` copy in
   * `modules/token-watchroo-core/src/main/scala/java/lang/impl/` stays either way. */
  c.withLTO(LTO.none)
    .withMode(Mode.releaseFast)
    .withOptimize(true)
    .withGC(GC.commix)
    .withCompileOptions(c.compileOptions :+ deploymentTarget)
    .withCOptions(c.cOptions :+ registersCaptureInclude(base))
    .withLinkingOptions(c.linkingOptions :+ deploymentTarget)
}

/* The -I option that puts native-overrides/scala-native-<version>/ ahead of nativelib's own include directories, so the
 * GC compiles the patched immix_commix/RegistersCapture.h (#63). A C option, not a compile option: C options reach only
 * C and assembly files, never C++ or LLVM IR, and clang sees them before every -I among the compile options, which hold
 * Scala Native's system include directories and, for each native library, that library's own directories. */
def registersCaptureInclude(base: File): String =
  s"-I${(base / "native-overrides" / s"scala-native-$registersCaptureOverrideVersion").getAbsolutePath}"

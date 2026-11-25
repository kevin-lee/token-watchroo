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
  .settings(noPublish)
  .dependsOn(core % "compile->compile;test->test")

lazy val app = module("app")
  .enablePlugins(ScalaNativePlugin)
  .settings(
    libraryDependencies ++= libs.tests.munit.value ++ libs.tests.hedgehog.value,
    nativeConfig := Def.uncached(
      commonNativeConfig((LocalRootProject / baseDirectory).value)(nativeConfig.value)
        .withBuildTarget(BuildTarget.libraryStatic)
        .withBaseName(props.StaticLibBaseName)
    ),
    /* `ar` adds to an existing archive on macOS without llvm-ar, so a relink keeps old members, and archives with stale
     * members crashed or hung at launch (#87). A missing archive always links, so the archive goes only when its inputs
     * changed: removing it every time would relink, and sign the Swift executable again, on every stageNativeLib. The
     * stamp is written before the link, so a failed link leaves no archive and the next run links. File contents, not
     * fullClasspath's text, which prints a project jar in two forms. A class path entry that is not a file never
     * matches, so it removes the archive every time. */
    removeStaleArchive := Def.uncached {
      val converter = fileConverter.value
      val archive   = crossTarget.value / s"lib${props.StaticLibBaseName}.a"
      val stamp     = crossTarget.value / "archive-link-inputs"
      val entries   = (Compile / fullClasspath).value.map(entry => converter.toPath(entry.data).toFile)
      val inputs    = (
        entries
          .map(f => s"${f.getName} ${if (f.isFile) Hash.toHex(Hash(f)) else s"not-a-file-${System.nanoTime}"}")
          .sorted :+
          (Compile / nativeLink / nativeConfig).value.toString
      ).mkString("\n")
      if (archive.exists() && stamp.exists() && IO.read(stamp) == inputs) ()
      else {
        IO.delete(archive)
        IO.write(stamp, inputs)
        streams.value.log.info(s"Removed ${archive.getPath} before the link (#87)")
      }
    },
    Compile / nativeLink := (Compile / nativeLink).dependsOn(removeStaleArchive).value,
    /* The test binary must be a runnable application, not a static library. */
    Test / nativeConfig ~= { c => c.withBuildTarget(BuildTarget.application) },
  )
  .settings(nativeSettings)
  .settings(noPublish)
  .dependsOn(providers)

/* App assembly tasks, scoped to the root project: sbt 2 applies bare settings to every subproject. */

lazy val removeStaleArchive    = taskKey[Unit]("Remove the static library when its link inputs changed (#87)")
lazy val stageNativeLib        = taskKey[String]("Copy the Scala Native static library into swift/lib/")
lazy val swiftBuild            = taskKey[String]("Build the Swift shell against the staged static library")
lazy val swiftTest             = taskKey[Unit]("Run the Swift shell tests against the staged static library")
lazy val bundleApp             = taskKey[String]("Assemble dist/Token Watchroo.app")
lazy val runApp                = taskKey[Unit]("Assemble and open the app bundle")
lazy val checkYieldpoints      =
  taskKey[Unit]("Check that the three test binaries were linked with conditional GC yieldpoints (#44)")

lazy val appAssemblySettings: SettingsDefinition = List(
  stageNativeLib := Def.uncached {
    val log       = streams.value.log
    val converter = fileConverter.value
    val archive   = converter.toPath((app / Compile / nativeLink).value).toFile
    val target    = baseDirectory.value / "swift" / "lib" / s"lib${props.StaticLibBaseName}.a"
    /* Gated before it is staged, so bundleApp, runApp and swiftTest fail on an archive that was not linked with
     * conditional GC yieldpoints (#44). */
    checkYieldpointMode(baseDirectory.value, List(archive))
    /* removeStaleArchive prevents this. The gate catches an archive that it kept, and reports signs of stale members,
     * not a prediction of a crash (#87). */
    checkArchiveMembersGate(baseDirectory.value, archive)
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
        "Check that commonNativeConfig still sets withTrapBasedYieldPoints(false), then relink."
    )
  else ()
}

/* Runs scripts/check-archive-members.sh on the static library and fails when it has signs of stale members (#87). */
def checkArchiveMembersGate(base: File, archive: File): Unit = {
  val script = base / "scripts" / "check-archive-members.sh"
  val exit   = Process(Seq(script.getPath, archive.getPath), base).!
  if (exit != 0)
    sys.error(
      s"check-archive-members.sh exited with $exit: the archive has signs of stale members (#87). " +
        s"Remove ${archive.getPath} and link again."
    )
  else ()
}

def commonNativeConfig(base: File)(c: NativeConfig): NativeConfig = {
  val deploymentTarget = s"-mmacosx-version-min=${props.MinimumMacOsVersion}"
  /* The interflow optimiser is on. It was off from 2026-09-12 (#20), because the app's first poller tick never
   * completed in release-fast with it on, while it did with the optimiser off, in debug mode and in release-full mode.
   * That build linked with trap-based GC yieldpoints and without the patched `PosixThread`. #20 turned the optimiser
   * back on with both workarounds in place, and #117 removed the workarounds after Scala Native 0.5.13 fixed their
   * causes (#43, #44). */
  c.withLTO(LTO.none)
    .withMode(Mode.releaseFast)
    .withOptimize(true)
    .withGC(GC.commix)
    /* Conditional GC yieldpoints (#44, #117): the Scala Native 0.5.13 proof run found faults with trap-based yieldpoints
     * that conditional ones did not show. A NativeConfig setting relinks on a change, unlike the environment variable
     * used before (#72). */
    .withGarbageCollectorConfig(_.withTrapBasedYieldPoints(false))
    .withCompileOptions(c.compileOptions :+ deploymentTarget)
    /* CoreFoundation for CFStringNormalize in providers' mac_text.c (#91). CommonCrypto is in libSystem. The static
     * library for the app does not use these options, so swift/Package.swift links CoreFoundation too. */
    .withLinkingOptions(c.linkingOptions :+ deploymentTarget :+ "-framework" :+ "CoreFoundation")
    /* The message bundles (#10) are read with getResourceAsStream, so they are embedded in every binary. Only the
     * bundles: the default pattern (every path) would embed every classpath resource, also the resources of dependency
     * jars. Files under the `scala-native` resource folder are never embedded. The spike of 2026-11-09 proved that this works in the static
     * library and in the test binaries. */
    .withEmbedResources(true)
    .withResourceIncludePatterns(Seq("/tokenwatchroo/i18n/*.properties"))
}

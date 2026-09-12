import ProjectInfo.*
import scala.scalanative.build.*
import sbtcrossproject.CrossProject
import scala.sys.process.Process

ThisBuild / scalaVersion := props.ScalaVersion
ThisBuild / organization := props.Org
ThisBuild / organizationName := props.OrgName
ThisBuild / developers := List(
  Developer(
    props.GitHubUsername,
    props.AuthorName,
    props.AuthorEmail,
    url(s"https://github.com/${props.GitHubUsername}"),
  )
)
ThisBuild / homepage := url(
  s"https://github.com/${props.GitHubUsername}/${props.RepoName}"
).some
ThisBuild / scmInfo :=
  ScmInfo(
    url(s"https://github.com/${props.GitHubUsername}/${props.RepoName}"),
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
  .aggregate(coreJvm, coreNative, providers, app)

lazy val core = crossModule("core", crossProject(JVMPlatform, NativePlatform).crossType(CrossType.Full))
  .enablePlugins(BuildInfoPlugin)
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
    ),
  )
  .jvmSettings(
    libraryDependencies ++= libs.tests.hedgehog.value ++ libs.tests.hedgehogExtra.value
  )
  .nativeSettings(nativeSettings)
  .nativeSettings(nativeConfig ~= commonNativeConfig)

lazy val coreJvm    = core.jvm
lazy val coreNative = core.native

lazy val providers = module("providers")
  .enablePlugins(ScalaNativePlugin)
  .settings(
    libraryDependencies ++= List(
      libs.catsEffect.value,
      libs.extrasCats.value,
    ) ++ libs.tests.munit.value,
    nativeConfig ~= commonNativeConfig,
  )
  .settings(nativeSettings)
  .settings(noPublish)
  .dependsOn(coreNative)

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

lazy val stageNativeLib = taskKey[String]("Copy the Scala Native static library into swift/lib/")
lazy val swiftBuild     = taskKey[String]("Build the Swift shell against the staged static library")
lazy val bundleApp      = taskKey[String]("Assemble dist/Token Watchroo.app")
lazy val runApp         = taskKey[Unit]("Assemble and open the app bundle")

lazy val appAssemblySettings: SettingsDefinition = List(
  stageNativeLib := {
    val converter = fileConverter.value
    val archive   = converter.toPath((app / Compile / nativeLink).value).toFile
    val target    = baseDirectory.value / "swift" / "lib" / s"lib${props.StaticLibBaseName}.a"
    IO.copyFile(archive, target)
    streams.value.log.info(s"Staged ${archive.getPath} -> ${target.getPath}")
    target.getAbsolutePath
  },

  swiftBuild := {
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

  bundleApp := {
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

  runApp := {
    val appBundle = bundleApp.value
    val exit      = Process(Seq("open", appBundle)).!
    if (exit != 0) sys.error(s"open failed with exit code $exit") else ()
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

def crossModule(projectName: String, crossProject: CrossProject.Builder): CrossProject = {
  val prefixedName = prefixedProjectName(projectName)
  val modulePath   = file(s"modules/$prefixedName")
  List(
    modulePath / "shared" / "src" / "main" / "scala",
    modulePath / "shared" / "src" / "test" / "scala",
  ).foreach(IO.createDirectory)
  crossProject
    .in(modulePath)
    .jvmConfigure(_.withId(s"${projectName}Jvm"))
    .nativeConfigure(_.withId(s"${projectName}Native"))
    .settings(
      name := prefixedName,
      fork := true,
      scalacOptions ++= List("-no-indent", "-explain"),
      licenses := props.licenses,
    )
}

lazy val nativeSettings: SettingsDefinition = List(Test / fork := false)

def commonNativeConfig(c: NativeConfig): NativeConfig = {
  val deploymentTarget = s"-mmacosx-version-min=${props.MinimumMacOsVersion}"
  c.withLTO(LTO.none)
    .withMode(Mode.releaseFast)
    .withGC(GC.commix)
    .withCompileOptions(c.compileOptions :+ deploymentTarget)
    .withLinkingOptions(c.linkingOptions :+ deploymentTarget)
}

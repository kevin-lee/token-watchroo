import sbt.*
import sbt.Keys.*
import devoops.DevOopsGitHubPlugin.autoImport.*

object ProjectInfo {

  object props {

    private val gitHubRepo = findRepoOrgAndName

    val GitHubUsername = gitHubRepo.fold("kevin-lee")(_.orgToString)
    val RepoName       = gitHubRepo.fold("token-watchroo")(_.nameToString)
    val ProjectName    = RepoName

    val ScalaVersion = "3.8.4"

    val Org     = "io.kevinlee"
    val OrgName = "Kevin's Code"

    val ProductName = "Token Watchroo"

    val ProductDescription = "TokenWatchroo - Track your tokens. Get warned before they run out."

    val AuthorName = "Kevin Lee"

    val AuthorEmail = "kevin.code@kevinlee.io"

    lazy val licenses = List(License.MIT)

    val BundleId            = "io.kevinlee.tokenwatchroo"
    val ExecutableName      = "TokenWatchroo"
    val StaticLibBaseName   = "tokenwatchroo"
    val MinimumMacOsVersion = "14.0"

    val CatsVersion          = "2.13.0"
    val CatsEffectVersion    = "3.7.1"
    val KittensVersion       = "3.5.0"
    val Refined4sVersion     = "1.21.0"
    val ExtrasVersion        = "0.56.0"
    val JustSemVerVersion    = "1.3.0"
    val JsoniterScalaVersion = "2.40.1"
    val OsLibVersion         = "0.11.8"

    val HedgehogVersion      = "0.15.0"
    val HedgehogExtraVersion = "0.25.0"
    val MunitVersion         = "1.3.6"
  }

  object libs {

    lazy val catsCore   = Def.setting("org.typelevel" %% "cats-core" % props.CatsVersion)
    lazy val catsEffect = Def.setting("org.typelevel" %% "cats-effect" % props.CatsEffectVersion)
    lazy val kittens    = Def.setting("org.typelevel" %% "kittens" % props.KittensVersion)

    lazy val refined4sCore         = Def.setting("io.kevinlee" %% "refined4s-core" % props.Refined4sVersion)
    lazy val refined4sCats         = Def.setting("io.kevinlee" %% "refined4s-cats" % props.Refined4sVersion)
    lazy val refined4sExtrasRender =
      Def.setting("io.kevinlee" %% "refined4s-extras-render" % props.Refined4sVersion)

    lazy val extrasRender = Def.setting("io.kevinlee" %% "extras-render" % props.ExtrasVersion)
    lazy val extrasCats   = Def.setting("io.kevinlee" %% "extras-cats" % props.ExtrasVersion)

    lazy val justSemVerCore = Def.setting("io.kevinlee" %% "just-semver-core" % props.JustSemVerVersion)

    lazy val jsoniterCore   =
      Def.setting("com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % props.JsoniterScalaVersion)
    lazy val jsoniterMacros =
      Def.setting(
        "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % props.JsoniterScalaVersion % Provided
      )

    lazy val osLib = Def.setting("com.lihaoyi" %% "os-lib" % props.OsLibVersion)

    object tests {

      lazy val hedgehog = Def.setting(
        List(
          "qa.hedgehog" %% "hedgehog-core"   % props.HedgehogVersion % Test,
          "qa.hedgehog" %% "hedgehog-runner" % props.HedgehogVersion % Test,
          "qa.hedgehog" %% "hedgehog-sbt"    % props.HedgehogVersion % Test,
        )
      )

      lazy val hedgehogExtra = Def.setting(
        List(
          "io.kevinlee" %% "hedgehog-extra-core"      % props.HedgehogExtraVersion % Test,
          "io.kevinlee" %% "hedgehog-extra-refined4s" % props.HedgehogExtraVersion % Test,
        )
      )

      lazy val munit = Def.setting(List("org.scalameta" %% "munit" % props.MunitVersion % Test))
    }
  }
}

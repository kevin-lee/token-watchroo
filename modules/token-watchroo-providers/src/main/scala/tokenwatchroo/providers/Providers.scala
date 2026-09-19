package tokenwatchroo.providers

import cats.effect.IO
import tokenwatchroo.info.TokenWatchrooInfo

/** Everything a provider may touch outside the process, so tests can substitute all of it. */
final case class ProviderDeps(
  http: HttpClient,
  keychain: KeychainReader,
  codexAuth: CodexAuthReader,
  rollouts: RolloutFiles,
  env: Env,
  nowMillis: IO[Long],
  appVersion: String,
)

object ProviderDeps {
  val live: ProviderDeps =
    ProviderDeps(
      CurlHttp,
      SecurityCli,
      CodexAuth,
      CodexRolloutFiles,
      Env.system,
      IO.realTime.map(_.toMillis),
      TokenWatchrooInfo.version,
    )
}

/** The provider registry, in display order. */
object Providers {

  def all(deps: ProviderDeps): IO[List[UsageProvider]] =
    for {
      claude <- ClaudeCodeProvider.make(
                  deps.http,
                  SimulatedClaudeSignOut.wrap(new ClaudeCredentials(deps.keychain), deps.env),
                  ClaudeCli.detectVersion(deps.env),
                  deps.nowMillis,
                )
      codex  <- CodexProvider.make(deps.http, deps.codexAuth, deps.rollouts, deps.env, deps.appVersion)
    } yield List(claude, codex)

  val live: IO[List[UsageProvider]] = all(ProviderDeps.live)
}

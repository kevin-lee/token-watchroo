package tokenwatchroo.providers

import refined4s.types.all.*
import tokenwatchroo.core.*

/** `Config.codexHome`, else `$CODEX_HOME`, else `~/.codex`. */
object CodexHomeResolver {

  def resolve(config: Config, env: Env): Either[ProviderError, CodexHome] =
    config
      .codexHome
      .orElse(env.get("CODEX_HOME").flatMap(v => NonEmptyString.from(v).toOption).map(CodexHome(_)))
      .orElse(env.home.flatMap(home => NonEmptyString.from(s"$home/.codex").toOption).map(CodexHome(_)))
      .toRight(ProviderError.credentialsMalformed("Cannot locate the Codex home directory: HOME is not set."))
}

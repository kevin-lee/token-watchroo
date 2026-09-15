package tokenwatchroo.core

import cats.{Eq, Show}
import cats.derived.*

/** Whether a Claude Code token carries the `user:profile` scope needed to read usage. */
enum ProfileScope derives CanEqual, Eq, Show {
  case Granted
  case Missing
}

object ProfileScope {
  def granted: ProfileScope = ProfileScope.Granted
  def missing: ProfileScope = ProfileScope.Missing

  val RequiredScope: String = "user:profile"

  def fromScopes(scopes: List[String]): ProfileScope =
    if (scopes.contains(RequiredScope)) ProfileScope.Granted else ProfileScope.Missing
}

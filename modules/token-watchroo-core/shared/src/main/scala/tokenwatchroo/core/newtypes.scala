package tokenwatchroo.core

import cats.{Eq, Show}
import extras.render.Render
import just.semver.{ParseError, SemVer}
import refined4s.*
import refined4s.modules.cats.derivation.*
import refined4s.modules.extras.derivation.ExtrasRender
import refined4s.types.all.*

/** Seconds since the Unix epoch. All times in the core are `EpochSeconds`, and `now` is always passed in. */
type EpochSeconds = EpochSeconds.Type
object EpochSeconds extends Newtype[Long], CatsEqShow[Long], CatsOrder[Long], ExtrasRender[Long] {
  extension (epoch: EpochSeconds) {
    def plus(seconds: Seconds): EpochSeconds = EpochSeconds(epoch.value + seconds.value)

    /** Positive when `other` is later than this instant. */
    def secondsUntil(other: EpochSeconds): Long = other.value - epoch.value
  }
}

/** A duration. */
type Seconds = Seconds.Type
object Seconds extends Newtype[Long], CatsEqShow[Long], CatsOrder[Long] {
  def clampNonNegative(n: Long): Seconds = Seconds(math.max(0L, n))

  given render: Render[Seconds] = Render.render(Format.longDuration)
}

/** Percentage of a usage window that has been consumed, always within 0.0 to 100.0. */
type UsedPercent = UsedPercent.Type
object UsedPercent extends Refined[Double], CatsEqShow[Double], CatsOrder[Double] {
  override inline def invalidReason(a: Double): String = expectedMessage("a value between 0.0 and 100.0")
  override inline def predicate(a: Double): Boolean    = a >= 0.0d && a <= 100.0d

  /** Total: NaN becomes 0, out-of-range values are clamped, so `unsafeFrom` can never fail here. */
  def clamp(d: Double): UsedPercent =
    if (d.isNaN) unsafeFrom(0.0d) else unsafeFrom(math.min(100.0d, math.max(0.0d, d)))

  given render: Render[UsedPercent] = Render.render(Format.percent)
}

/** The plan badge on a card, such as "Max" or "Plus". */
type PlanLabel = PlanLabel.Type
object PlanLabel extends Newtype[NonEmptyString], CatsEqShow[NonEmptyString] {
  given render: Render[PlanLabel] = Render.render(_.value.value)

  /** Trims and capitalises the first letter of a provider's plan type. Empty input gives none. */
  def fromPlanType(planType: String): Option[PlanLabel] = {
    val trimmed = planType.trim
    NonEmptyString.from(trimmed.take(1).toUpperCase + trimmed.drop(1)).toOption.map(PlanLabel(_))
  }
}

/** An OAuth access token. Never rendered or shown: both instances print a placeholder. */
type AccessToken = AccessToken.Type
object AccessToken extends Newtype[NonEmptyString], CatsEq[NonEmptyString] {
  given show: Show[AccessToken]     = Show.show(_ => "<redacted>")
  given render: Render[AccessToken] = Render.render(_ => "<redacted>")
}

type AccountId = AccountId.Type
object AccountId extends Newtype[NonEmptyString], CatsEqShow[NonEmptyString] {
  given render: Render[AccountId] = Render.render(_.value.value)
}

/** Text shown on an unavailable card. */
type ErrorMessage = ErrorMessage.Type
object ErrorMessage extends Newtype[NonEmptyString], CatsEqShow[NonEmptyString] {
  given render: Render[ErrorMessage] = Render.render(_.value.value)

  def fromString(s: String): Option[ErrorMessage] = NonEmptyString.from(s.trim).toOption.map(ErrorMessage(_))
}

/** Monotonic counter carried by every envelope. */
type SequenceNumber = SequenceNumber.Type
object SequenceNumber extends Newtype[Long], CatsEqShow[Long], CatsOrder[Long], ExtrasRender[Long] {
  extension (seq: SequenceNumber) {
    def next: SequenceNumber = SequenceNumber(seq.value + 1L)
  }
}

/** Poll interval, always within 15 to 3600 seconds. */
type RefreshIntervalSeconds = RefreshIntervalSeconds.Type
object RefreshIntervalSeconds extends Refined[Int], CatsEqShow[Int], CatsOrder[Int] {
  override inline def invalidReason(a: Int): String = expectedMessage("a value between 15 and 3600")
  override inline def predicate(a: Int): Boolean    = a >= 15 && a <= 3600

  val default: RefreshIntervalSeconds = RefreshIntervalSeconds(60)

  /** Total: out-of-range values are clamped, so `unsafeFrom` can never fail here. */
  def clamp(n: Int): RefreshIntervalSeconds = unsafeFrom(math.min(3600, math.max(15, n)))
}

/** Absolute path of the directory holding `state.json`. */
type StateDir = StateDir.Type
object StateDir extends Newtype[NonEmptyString], CatsEqShow[NonEmptyString] {
  given render: Render[StateDir] = Render.render(_.value.value)
}

/** Absolute path of the Codex home directory. */
type CodexHome = CodexHome.Type
object CodexHome extends Newtype[NonEmptyString], CatsEqShow[NonEmptyString] {
  given render: Render[CodexHome] = Render.render(_.value.value)
}

type UserAgent = UserAgent.Type
object UserAgent extends Newtype[NonEmptyString], CatsEqShow[NonEmptyString] {
  given render: Render[UserAgent] = Render.render(_.value.value)
}

/** Version of the Claude Code CLI, used only in the User-Agent header. */
type ClaudeCodeVersion = ClaudeCodeVersion.Type
object ClaudeCodeVersion extends Newtype[SemVer] {
  def parse(version: String): Either[ParseError, ClaudeCodeVersion] =
    SemVer.parse(version.trim).map(ClaudeCodeVersion(_))

  val fallback: ClaudeCodeVersion = ClaudeCodeVersion(SemVer.unsafeParse("2.1.0"))

  given eq: Eq[ClaudeCodeVersion]         = Eq.fromUniversalEquals
  given show: Show[ClaudeCodeVersion]     = Show.show(v => v.value.render)
  given render: Render[ClaudeCodeVersion] = Render.render(v => v.value.render)
}

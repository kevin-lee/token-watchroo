package tokenwatchroo.core

import cats.{Eq, Show}
import cats.syntax.all.*
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

/** A three-letter upper-case ISO 4217 currency code, such as "USD". */
type CurrencyCode = CurrencyCode.Type
object CurrencyCode extends Refined[String], CatsEqShow[String] {
  override inline def invalidReason(a: String): String = expectedMessage("a three-letter upper-case ISO 4217 code")
  override inline def predicate(a: String): Boolean    = a.length === 3 && a.forall(c => c >= 'A' && c <= 'Z')

  given render: Render[CurrencyCode] = Render.render(_.value)
}

/** A non-negative amount of money or credits. Never a `Double`, so a spend of 0.05 stays exact. */
type Amount = Amount.Type
object Amount extends Refined[BigDecimal], CatsEqShow[BigDecimal], CatsOrder[BigDecimal] {
  override inline def invalidReason(a: BigDecimal): String = expectedMessage("a non-negative decimal amount")
  override inline def predicate(a: BigDecimal): Boolean    = a.signum >= 0

  /** Zero is non-negative, so `unsafeFrom` can never fail here. */
  val zero: Amount = unsafeFrom(BigDecimal(0))

  /** `minor` units at `exponent` decimal places, as the Claude usage response sends money: 5 at 2 is 0.05. */
  def fromMinorUnits(minor: Long, exponent: Int): Either[String, Amount] =
    Either
      .cond(exponent >= 0 && exponent <= 18, (), s"Exponent out of range 0 to 18: $exponent")
      .flatMap(_ => from(BigDecimal(BigInt(minor), exponent)))

  /** A decimal text such as "17000.50". Surrounding whitespace is ignored. */
  def fromDecimalString(s: String): Either[String, Amount] =
    Either.catchNonFatal(BigDecimal(s.trim)).leftMap(_ => s"Not a decimal amount: $s").flatMap(from)

  extension (amount: Amount) {

    /** The wire text: no exponent notation, trailing zeros kept, so 200.00 stays "200.00". */
    def plainString: String = amount.value.bigDecimal.toPlainString
  }
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

/** The display name of a Claude model behind a per-model weekly window, such as "Fable" or "Sonnet 4.5", taken from
  * `scope.model.display_name` of the usage response. `fromDisplayName` trims because it reads API text, while
  * `WindowId.parse` never trims because the wire is the contract.
  */
type ModelName = ModelName.Type
object ModelName extends Newtype[NonEmptyString], CatsEqShow[NonEmptyString] {
  given render: Render[ModelName] = Render.render(_.value.value)

  /** Trims the API's display name. Empty input gives none. */
  def fromDisplayName(displayName: String): Option[ModelName] =
    NonEmptyString.from(displayName.trim).toOption.map(ModelName(_))
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

package tokenwatchroo.core

import cats.syntax.all.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import just.semver.SemVer
import refined4s.types.all.*
import tokenwatchroo.core.providers.*

/** Every JSON codec of the core. Newtypes derive from the underlying codec, refined types validate on read, and enums
  * are their `wire` strings.
  */
object codecs {

  /* The macro needs its configuration inline, so each call spells it out: unknown fields are skipped, and provider
   * payloads use snake_case on the wire. */

  def readEither[A](json: String)(using codec: JsonValueCodec[A]): Either[DecodeError, A] =
    try readFromString[A](json).asRight[DecodeError]
    catch {
      case e: JsonReaderException => DecodeError.invalid(Option(e.getMessage).getOrElse("invalid JSON")).asLeft[A]
    }

  def write[A](a: A)(using codec: JsonValueCodec[A]): String = writeToString[A](a)

  /* Base codecs for the underlying types of newtypes. */

  given longCodec: JsonValueCodec[Long]     = JsonCodecMaker.make
  given intCodec: JsonValueCodec[Int]       = JsonCodecMaker.make
  given doubleCodec: JsonValueCodec[Double] = JsonCodecMaker.make
  given stringCodec: JsonValueCodec[String] = JsonCodecMaker.make

  /** Decodes the underlying value, then validates or converts it. A failure is reported as a decode error. */
  private def validating[A, T](decode: A => Either[String, T], encode: T => A, empty: T)(
    using base: JsonValueCodec[A]
  ): JsonValueCodec[T] =
    new JsonValueCodec[T] {
      override def decodeValue(in: JsonReader, default: T): T =
        decode(base.decodeValue(in, base.nullValue)).fold(reason => in.decodeError(reason), identity)
      override def encodeValue(x: T, out: JsonWriter): Unit   = base.encodeValue(encode(x), out)
      override def nullValue: T                               = empty
    }

  private def wireString[T](parse: String => Either[String, T], wire: T => String, empty: T): JsonValueCodec[T] =
    validating[String, T](parse, wire, empty)

  /** Encodes `T` through a wire representation `W`. */
  private def mapped[W, T](to: W => T, from: T => W, empty: T)(using base: JsonValueCodec[W]): JsonValueCodec[T] =
    new JsonValueCodec[T] {
      override def decodeValue(in: JsonReader, default: T): T = to(base.decodeValue(in, base.nullValue))
      override def encodeValue(x: T, out: JsonWriter): Unit   = base.encodeValue(from(x), out)
      override def nullValue: T                               = empty
    }

  private def nonEmpty[T](make: NonEmptyString => T, unwrap: T => NonEmptyString): JsonValueCodec[T] =
    validating[String, T](
      s => NonEmptyString.from(s).map(make),
      t => unwrap(t).value,
      make(NonEmptyString.unsafeFrom("-")),
    )

  /* Newtypes and refined types. */

  given epochSecondsCodec: JsonValueCodec[EpochSeconds]     = EpochSeconds.deriving[JsonValueCodec]
  given secondsCodec: JsonValueCodec[Seconds]               = Seconds.deriving[JsonValueCodec]
  given sequenceNumberCodec: JsonValueCodec[SequenceNumber] = SequenceNumber.deriving[JsonValueCodec]

  given usedPercentCodec: JsonValueCodec[UsedPercent] =
    validating[Double, UsedPercent](UsedPercent.from, _.value, UsedPercent.clamp(0.0d))

  given refreshIntervalCodec: JsonValueCodec[RefreshIntervalSeconds] =
    validating[Int, RefreshIntervalSeconds](
      n => RefreshIntervalSeconds.clamp(n).asRight[String],
      _.value,
      RefreshIntervalSeconds.default,
    )

  /* Amounts are decimal strings on the wire, so no digit passes through a binary floating-point number. */
  given amountCodec: JsonValueCodec[Amount] =
    validating[String, Amount](Amount.fromDecimalString, _.plainString, Amount.zero)

  /* A provider amount may arrive as a string or a number: both are kept as decimal text. */
  given decimalTextCodec: JsonValueCodec[DecimalText] =
    new JsonValueCodec[DecimalText] {
      override def decodeValue(in: JsonReader, default: DecimalText): DecimalText =
        if (in.isNextToken('"')) {
          in.rollbackToken()
          DecimalText(in.readString(null))
        } else {
          in.rollbackToken()
          DecimalText(in.readBigDecimal(null).bigDecimal.toPlainString)
        }
      override def encodeValue(x: DecimalText, out: JsonWriter): Unit             = out.writeVal(x.value)
      override def nullValue: DecimalText                                         = DecimalText("")
    }

  given planLabelCodec: JsonValueCodec[PlanLabel]       = nonEmpty(PlanLabel(_), _.value)
  given errorMessageCodec: JsonValueCodec[ErrorMessage] = nonEmpty(ErrorMessage(_), _.value)
  given stateDirCodec: JsonValueCodec[StateDir]         = nonEmpty(StateDir(_), _.value)
  given codexHomeCodec: JsonValueCodec[CodexHome]       = nonEmpty(CodexHome(_), _.value)

  given claudeCodeVersionCodec: JsonValueCodec[ClaudeCodeVersion] =
    validating[String, ClaudeCodeVersion](
      s => ClaudeCodeVersion.parse(s).leftMap(_.render),
      v => v.value.render,
      ClaudeCodeVersion.fallback,
    )

  /* Enums. */

  given agentIdCodec: JsonValueCodec[AgentId]         = wireString(AgentId.parse, _.wire, AgentId.ClaudeCode)
  given windowIdCodec: JsonValueCodec[WindowId]       = wireString(WindowId.parse, _.wire, WindowId.Session)
  given agentStatusCodec: JsonValueCodec[AgentStatus] = wireString(AgentStatus.parse, _.wire, AgentStatus.Unavailable)
  given sourceCodec: JsonValueCodec[Source]           = wireString(Source.parse, _.wire, Source.Api)
  given menubarKindCodec: JsonValueCodec[MenubarKind] = wireString(MenubarKind.parse, _.wire, MenubarKind.Unavailable)
  given alertKindCodec: JsonValueCodec[AlertKind]     = wireString(AlertKind.parse, _.wire, AlertKind.Reset)
  given currencyCodec: JsonValueCodec[Currency]       = wireString(Currency.parse, _.wire, Currency.Credits)

  /* Records. */

  given usageWindowCodec: JsonValueCodec[UsageWindow] =
    JsonCodecMaker.make(CodecMakerConfig.withSkipUnexpectedFields(true))

  /* Spend: `usedPercent` is written for the shell and recomputed from the amounts on read. */

  final private case class SpendWire(
    currency: Currency,
    spent: Amount,
    limit: Amount,
    usedPercent: UsedPercent,
    resetsAt: Option[EpochSeconds],
  )

  private given spendWireCodec: JsonValueCodec[SpendWire] =
    JsonCodecMaker.make(CodecMakerConfig.withSkipUnexpectedFields(true))

  given spendCodec: JsonValueCodec[Spend] =
    mapped[SpendWire, Spend](
      w => Spend(w.currency, w.spent, w.limit, w.resetsAt),
      s => SpendWire(s.currency, s.spent, s.limit, s.usedPercent, s.resetsAt),
      Spend(Currency.Credits, Amount.zero, Amount.zero, none[EpochSeconds]),
    )

  /* Empty lists are written, not left out, because the shell requires `agents` and `windows` (issue #32). An absent
   * `spend` is left out, which the shell decodes as no spend (issue #33). */
  given agentSnapshotCodec: JsonValueCodec[AgentSnapshot] =
    JsonCodecMaker.make(CodecMakerConfig.withSkipUnexpectedFields(true).withTransientEmpty(false))
  given menubarStateCodec: JsonValueCodec[MenubarState]   =
    JsonCodecMaker.make(CodecMakerConfig.withSkipUnexpectedFields(true))
  given snapshotCodec: JsonValueCodec[Snapshot]           =
    JsonCodecMaker.make(CodecMakerConfig.withSkipUnexpectedFields(true).withTransientEmpty(false))

  /* Alert: the identifier is added so the shell never has to compute it. `usedPercent` is additive for the shell, which
   * treats it as optional. A spend alert (#105) has the `window` value `spend` and a `spend-*` kind, and no
   * `windowResetsAt` when the spend has no reset time. A window alert always has `windowResetsAt`. */

  final private case class AlertPayloadWire(
    agent: AgentId,
    window: String,
    kind: String,
    windowResetsAt: Option[EpochSeconds],
    usedPercent: UsedPercent,
    title: String,
    body: String,
    identifier: String,
  )

  private given alertPayloadWireCodec: JsonValueCodec[AlertPayloadWire] =
    JsonCodecMaker.make(CodecMakerConfig.withSkipUnexpectedFields(true))

  private def alertSubjectFromWire(w: AlertPayloadWire): Either[String, AlertSubject] =
    if (w.window === AlertSubject.SpendWire)
      SpendAlertKind.parse(w.kind).map(kind => AlertSubject.spend(kind, w.windowResetsAt))
    else
      for {
        window   <- WindowId.parse(w.window)
        kind     <- AlertKind.parse(w.kind)
        resetsAt <- w.windowResetsAt.toRight(s"A window alert needs windowResetsAt: ${w.window}")
      } yield AlertSubject.window(window, kind, resetsAt)

  given alertCodec: JsonValueCodec[Alert] =
    validating[AlertPayloadWire, Alert](
      w => alertSubjectFromWire(w).map(subject => Alert(w.agent, subject, w.usedPercent, w.title, w.body)),
      a =>
        AlertPayloadWire(
          a.agent,
          a.subject.windowWire,
          a.subject.kindWire,
          a.subject.resetsAtWire,
          a.usedPercent,
          a.title,
          a.body,
          a.identifier,
        ),
      Alert(
        AgentId.ClaudeCode,
        AlertSubject.window(WindowId.Session, AlertKind.Reset, EpochSeconds(0L)),
        UsedPercent.clamp(0.0d),
        "",
        "",
      ),
    )

  /* Config: the JSON key is `refreshIntervalSeconds`, the field is `refreshInterval`. A missing `agents` is an empty
   * list. In an agent entry only `id` is required: a missing `billing`, `plan` or `enabled` gives its default. An entry
   * with an unknown `id`, `billing` or `plan` is skipped, so a config written by a later version still decodes (#8).
   * A missing `alertLevels`, or levels that break the rules, give the default levels, and the default levels are left
   * out on write (#9). A missing `spendAlertLevels`, or one with an unknown unit or amounts that break the rules,
   * gives the default spend levels, and the defaults are left out on write (#105). A missing or invalid
   * `claudeConfigDir` is not set, and it is left out on write (#91). */

  final private case class SpendAlertLevelsWire(unit: String, amounts: List[Long])

  /* `amounts` is written also when empty, because an empty list means no spend alerts. */
  private given spendAlertLevelsWireCodec: JsonValueCodec[SpendAlertLevelsWire] =
    JsonCodecMaker.make(CodecMakerConfig.withSkipUnexpectedFields(true).withTransientEmpty(false))

  final private case class AgentConfigWire(
    id: String,
    billing: Option[String],
    plan: Option[String],
    enabled: Option[Boolean],
    alertLevels: Option[List[Int]],
    spendAlertLevels: Option[SpendAlertLevelsWire],
    claudeConfigDir: Option[String],
  )

  private given agentConfigWireCodec: JsonValueCodec[AgentConfigWire] =
    JsonCodecMaker.make(CodecMakerConfig.withSkipUnexpectedFields(true))

  private def agentConfigFromWire(w: AgentConfigWire): Option[AgentConfig] =
    (for {
      id      <- AgentId.parse(w.id)
      billing <- w.billing.fold(Billing.default.asRight[String])(Billing.parse)
      plan    <- w.plan.fold(PlanKind.default.asRight[String])(PlanKind.parse)
    } yield AgentConfig(
      id,
      billing,
      plan,
      w.enabled.fold(Enabled.default)(Enabled.fromBoolean),
      w.alertLevels.flatMap(levels => AlertLevels.from(levels).toOption).getOrElse(AlertLevels.defaults),
      w.spendAlertLevels
        .flatMap(levels => Currency.parse(levels.unit).flatMap(SpendAlertLevels.from(_, levels.amounts)).toOption)
        .getOrElse(SpendAlertLevels.defaults),
      w.claudeConfigDir.flatMap(dir => ClaudeConfigDir.from(dir).toOption),
    )).toOption

  private def agentConfigToWire(a: AgentConfig): AgentConfigWire =
    AgentConfigWire(
      a.id.wire,
      a.billing.wire.some,
      a.plan.wire.some,
      a.enabled.isOn.some,
      Option.when(a.alertLevels =!= AlertLevels.defaults)(a.alertLevels.levels.map(_.value)),
      a.spendAlertLevels match {
        case SpendAlertLevels.Defaults => none[SpendAlertLevelsWire]
        case SpendAlertLevels.Amounts(unit, amounts) => SpendAlertLevelsWire(unit.wire, amounts.map(_.value)).some
      },
      a.claudeConfigDir.map(_.value),
    )

  final private case class ConfigWire(
    stateDir: StateDir,
    refreshIntervalSeconds: RefreshIntervalSeconds,
    codexHome: Option[CodexHome],
    claudeCodeVersionOverride: Option[ClaudeCodeVersion],
    agents: List[AgentConfigWire],
  )

  private given configWireCodec: JsonValueCodec[ConfigWire] =
    JsonCodecMaker.make(CodecMakerConfig.withSkipUnexpectedFields(true))

  given configCodec: JsonValueCodec[Config] =
    mapped[ConfigWire, Config](
      w =>
        Config(
          w.stateDir,
          w.refreshIntervalSeconds,
          w.codexHome,
          w.claudeCodeVersionOverride,
          w.agents.flatMap(agentConfigFromWire),
        ),
      c =>
        ConfigWire(
          c.stateDir,
          c.refreshInterval,
          c.codexHome,
          c.claudeCodeVersionOverride,
          c.agents.map(agentConfigToWire),
        ),
      Config.default(StateDir(NonEmptyString.unsafeFrom("-"))),
    )

  /* Alert state: a map keyed by (agent, window) stored as a list of records with the key fields inlined. A `fired` kind
   * that this version cannot parse is skipped, so the rest of the file is kept when a later version added kinds (#9).
   * Spend records (#105) are a separate `spends` list keyed by agent, so a reader before #105 skips the list and keeps
   * the window records. A missing `spends` is an empty list. */

  final private case class WindowRecordWire(
    agent: AgentId,
    window: WindowId,
    resetsAt: EpochSeconds,
    lastPercent: UsedPercent,
    fired: List[String],
  )

  final private case class SpendRecordWire(
    agent: AgentId,
    currency: Currency,
    limit: Amount,
    resetsAt: Option[EpochSeconds],
    lastSpent: Amount,
    fired: List[String],
    seenAt: EpochSeconds,
  )

  final private case class AlertStateWire(records: List[WindowRecordWire], spends: List[SpendRecordWire])

  private given alertStateWireCodec: JsonValueCodec[AlertStateWire] =
    JsonCodecMaker.make(CodecMakerConfig.withSkipUnexpectedFields(true))

  given alertStateCodec: JsonValueCodec[AlertState] =
    mapped[AlertStateWire, AlertState](
      w =>
        AlertState(
          w.records
            .map { r =>
              WindowKey(r.agent, r.window) -> WindowRecord(
                r.resetsAt,
                r.lastPercent,
                r.fired.flatMap(kind => AlertKind.parse(kind).toOption).toSet,
              )
            }
            .toMap,
          w.spends
            .map { r =>
              r.agent -> SpendRecord(
                r.currency,
                r.limit,
                r.resetsAt,
                r.lastSpent,
                r.fired.flatMap(kind => SpendAlertKind.parse(kind).toOption).toSet,
                r.seenAt,
              )
            }
            .toMap,
        ),
      s =>
        AlertStateWire(
          s.records.toList.sortBy { case (key, _) => (key.agent.wire, key.window.wire) }.map {
            case (key, record) =>
              WindowRecordWire(
                key.agent,
                key.window,
                record.resetsAt,
                record.lastPercent,
                record.fired.toList.map(_.wire).sorted
              )
          },
          s.spends.toList.sortBy { case (agent, _) => agent.wire }.map {
            case (agent, record) =>
              SpendRecordWire(
                agent,
                record.currency,
                record.limit,
                record.resetsAt,
                record.lastSpent,
                record.fired.toList.map(_.wire).sorted,
                record.seenAt,
              )
          },
        ),
      AlertState.empty,
    )

  /* Envelope: {"version":1,"type":"snapshot|alert|error","seq":N,"data":{...}} or {"version":1,"type":"error","seq":N,"message":"..."} */

  final private case class EnvelopeHead(version: Int, `type`: String, seq: SequenceNumber)
  final private case class SnapshotBody(data: Snapshot)
  final private case class AlertBody(data: Alert)
  final private case class ErrorBody(message: String)

  private given envelopeHeadCodec: JsonValueCodec[EnvelopeHead] =
    JsonCodecMaker.make(CodecMakerConfig.withSkipUnexpectedFields(true))
  private given snapshotBodyCodec: JsonValueCodec[SnapshotBody] =
    JsonCodecMaker.make(CodecMakerConfig.withSkipUnexpectedFields(true))
  private given alertBodyCodec: JsonValueCodec[AlertBody]       =
    JsonCodecMaker.make(CodecMakerConfig.withSkipUnexpectedFields(true))
  private given errorBodyCodec: JsonValueCodec[ErrorBody]       =
    JsonCodecMaker.make(CodecMakerConfig.withSkipUnexpectedFields(true))

  given envelopeCodec: JsonValueCodec[Envelope] =
    new JsonValueCodec[Envelope] {
      override def decodeValue(in: JsonReader, default: Envelope): Envelope = {
        val raw  = in.readRawValAsBytes()
        val head = readFromArray[EnvelopeHead](raw)
        head.`type` match {
          case "snapshot" => Envelope.snapshot(head.seq, readFromArray[SnapshotBody](raw).data)
          case "alert" => Envelope.alert(head.seq, readFromArray[AlertBody](raw).data)
          case "error" => Envelope.error(head.seq, readFromArray[ErrorBody](raw).message)
          case other => in.decodeError(s"Unknown envelope type: $other")
        }
      }

      override def encodeValue(x: Envelope, out: JsonWriter): Unit = {
        out.writeObjectStart()
        out.writeNonEscapedAsciiKey("version")
        out.writeVal(Envelope.Version)
        out.writeNonEscapedAsciiKey("type")
        out.writeVal(x.wire)
        out.writeNonEscapedAsciiKey("seq")
        out.writeVal(x.seq.value)
        x match {
          case Envelope.Snapshot(_, data) =>
            out.writeNonEscapedAsciiKey("data")
            snapshotCodec.encodeValue(data, out)
          case Envelope.Alert(_, data) =>
            out.writeNonEscapedAsciiKey("data")
            alertCodec.encodeValue(data, out)
          case Envelope.Error(_, message) =>
            out.writeNonEscapedAsciiKey("message")
            out.writeVal(message)
        }
        out.writeObjectEnd()
      }

      override def nullValue: Envelope = Envelope.error(SequenceNumber(0L), "")
    }

  /* Provider payloads: snake_case on the wire. */

  given claudeUsageResponseCodec: JsonValueCodec[ClaudeUsageResponse]     = JsonCodecMaker.make(
    CodecMakerConfig.withSkipUnexpectedFields(true).withFieldNameMapper(JsonCodecMaker.enforce_snake_case)
  )
  given claudeProfileResponseCodec: JsonValueCodec[ClaudeProfileResponse] = JsonCodecMaker.make(
    CodecMakerConfig.withSkipUnexpectedFields(true).withFieldNameMapper(JsonCodecMaker.enforce_snake_case)
  )
  /* Only empty or present is kept, never the refresh token. The blob is only read, so the encoder is never used. */
  given refreshTokenStateCodec: JsonValueCodec[RefreshTokenState]         =
    mapped[String, RefreshTokenState](
      RefreshTokenState.fromRaw,
      {
        case RefreshTokenState.Empty => ""
        case RefreshTokenState.Present => "<redacted>"
      },
      RefreshTokenState.Present,
    )
  given claudeCredentialsBlobCodec: JsonValueCodec[ClaudeCredentialsBlob] =
    JsonCodecMaker.make(CodecMakerConfig.withSkipUnexpectedFields(true))
  given codexUsageResponseCodec: JsonValueCodec[CodexUsageResponse]       = JsonCodecMaker.make(
    CodecMakerConfig.withSkipUnexpectedFields(true).withFieldNameMapper(JsonCodecMaker.enforce_snake_case)
  )
  given codexAuthFileCodec: JsonValueCodec[CodexAuthFile]                 = JsonCodecMaker.make(
    CodecMakerConfig.withSkipUnexpectedFields(true).withFieldNameMapper(JsonCodecMaker.enforce_snake_case)
  )
  given codexRolloutLineCodec: JsonValueCodec[CodexRolloutLine]           = JsonCodecMaker.make(
    CodecMakerConfig.withSkipUnexpectedFields(true).withFieldNameMapper(JsonCodecMaker.enforce_snake_case)
  )
}

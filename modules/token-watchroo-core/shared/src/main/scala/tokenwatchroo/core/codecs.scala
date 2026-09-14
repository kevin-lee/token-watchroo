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
  given alertKindCodec: JsonValueCodec[AlertKind]     = wireString(AlertKind.parse, _.wire, AlertKind.Warning80)

  /* Records. */

  given usageWindowCodec: JsonValueCodec[UsageWindow]     =
    JsonCodecMaker.make(CodecMakerConfig.withSkipUnexpectedFields(true))
  /* Empty lists are written, not left out, because the shell requires `agents` and `windows` (issue #32). */
  given agentSnapshotCodec: JsonValueCodec[AgentSnapshot] =
    JsonCodecMaker.make(CodecMakerConfig.withSkipUnexpectedFields(true).withTransientEmpty(false))
  given menubarStateCodec: JsonValueCodec[MenubarState]   =
    JsonCodecMaker.make(CodecMakerConfig.withSkipUnexpectedFields(true))
  given snapshotCodec: JsonValueCodec[Snapshot]           =
    JsonCodecMaker.make(CodecMakerConfig.withSkipUnexpectedFields(true).withTransientEmpty(false))
  given alertPayloadCodec: JsonValueCodec[AlertPayload]   =
    JsonCodecMaker.make(CodecMakerConfig.withSkipUnexpectedFields(true))

  given alertCodec: JsonValueCodec[Alert] =
    mapped[AlertPayload, Alert](
      _.toAlert,
      AlertPayload.fromAlert,
      Alert(
        AgentId.ClaudeCode,
        WindowId.Session,
        AlertKind.Warning80,
        EpochSeconds(0L),
        UsedPercent.clamp(0.0d),
        "",
        ""
      ),
    )

  /* Config: the JSON key is `refreshIntervalSeconds`, the field is `refreshInterval`. */

  final private case class ConfigWire(
    stateDir: StateDir,
    refreshIntervalSeconds: RefreshIntervalSeconds,
    codexHome: Option[CodexHome],
    claudeCodeVersionOverride: Option[ClaudeCodeVersion],
  )

  private given configWireCodec: JsonValueCodec[ConfigWire] =
    JsonCodecMaker.make(CodecMakerConfig.withSkipUnexpectedFields(true))

  given configCodec: JsonValueCodec[Config] =
    mapped[ConfigWire, Config](
      w => Config(w.stateDir, w.refreshIntervalSeconds, w.codexHome, w.claudeCodeVersionOverride),
      c => ConfigWire(c.stateDir, c.refreshInterval, c.codexHome, c.claudeCodeVersionOverride),
      Config.default(StateDir(NonEmptyString.unsafeFrom("-"))),
    )

  /* Alert state: a map keyed by (agent, window) stored as a list of records with the key fields inlined. */

  final private case class WindowRecordWire(
    agent: AgentId,
    window: WindowId,
    resetsAt: EpochSeconds,
    lastPercent: UsedPercent,
    fired: List[AlertKind],
  )

  final private case class AlertStateWire(records: List[WindowRecordWire])

  private given alertStateWireCodec: JsonValueCodec[AlertStateWire] =
    JsonCodecMaker.make(CodecMakerConfig.withSkipUnexpectedFields(true))

  given alertStateCodec: JsonValueCodec[AlertState] =
    mapped[AlertStateWire, AlertState](
      w =>
        AlertState(
          w.records
            .map { r =>
              WindowKey(r.agent, r.window) -> WindowRecord(r.resetsAt, r.lastPercent, r.fired.toSet)
            }
            .toMap
        ),
      s =>
        AlertStateWire(s.records.toList.sortBy { case (key, _) => (key.agent.wire, key.window.wire) }.map {
          case (key, record) =>
            WindowRecordWire(
              key.agent,
              key.window,
              record.resetsAt,
              record.lastPercent,
              record.fired.toList.sortBy(_.wire)
            )
        }),
      AlertState.empty,
    )

  /* Envelope: {"version":1,"type":"snapshot|alert|error","seq":N,"data":{...}} or {"version":1,"type":"error","seq":N,"message":"..."} */

  final private case class EnvelopeHead(version: Int, `type`: String, seq: SequenceNumber)
  final private case class SnapshotBody(data: Snapshot)
  final private case class AlertBody(data: AlertPayload)
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
          case "alert" => Envelope.alert(head.seq, readFromArray[AlertBody](raw).data.toAlert)
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
            alertPayloadCodec.encodeValue(AlertPayload.fromAlert(data), out)
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

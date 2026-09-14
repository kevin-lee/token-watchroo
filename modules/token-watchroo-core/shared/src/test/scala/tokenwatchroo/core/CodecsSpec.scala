package tokenwatchroo.core

import cats.syntax.all.*
import hedgehog.*
import hedgehog.extra.refined4s.gens.StringGens
import hedgehog.runner.*
import refined4s.types.all.*
import tokenwatchroo.core.codecs.given

object CodecsSpec extends Properties {

  override def tests: List[Test] = List(
    property("epoch seconds round-trip", testEpochRoundTrip),
    property("used percent round-trips and rejects out-of-range values", testUsedPercent),
    example("refresh interval clamps on read", testRefreshInterval),
    property("plan labels round-trip and reject empty strings", testPlanLabel),
    example("enums encode as wire strings", testEnums),
    property("snapshots round-trip", testSnapshotRoundTrip),
    example("alert state round-trips", testAlertState),
    example("config decodes the documented JSON", testConfig),
    example("envelopes carry version, type, seq and round-trip", testEnvelope),
    example("an empty snapshot and an unavailable agent write agents and windows as empty arrays", testEmptyLists),
    property("spend round-trips with string amounts and a used percent", testSpendRoundTrip),
    example("amounts write plain decimals and reject negatives and non-numbers", testAmounts),
    example("currency encodes as the code or credits and rejects lower-case codes", testCurrency),
    example("an agent without spend leaves spend out of the JSON", testSpendOmitted),
  )

  def testSpendRoundTrip: Property =
    for {
      spend <- Fixtures.genSpend.log("spend")
    } yield {
      val json = codecs.write(spend)
      Result.all(
        List(
          codecs.readEither[Spend](json) ==== Right(spend),
          json.contains(s""""spent":"${spend.spent.plainString}"""") ==== true,
          json.contains(s""""limit":"${spend.limit.plainString}"""") ==== true,
          json.contains(""""usedPercent":""") ==== true,
        )
      )
    }

  def testAmounts: Result =
    Result.all(
      List(
        codecs.readEither[Amount](""""0.05"""") ==== Right(Fixtures.amount("0.05")),
        codecs.readEither[Amount](""""-1"""").isLeft ==== true,
        codecs.readEither[Amount](""""abc"""").isLeft ==== true,
        Amount.fromMinorUnits(20000L, 2).map(codecs.write(_)) ==== Right(""""200.00""""),
        Amount.fromMinorUnits(5L, 2).map(codecs.write(_)) ==== Right(""""0.05""""),
        Amount.fromMinorUnits(5L, 19).isLeft ==== true,
      )
    )

  def testCurrency: Result =
    Result.all(
      List(
        codecs.write(Currency.iso(CurrencyCode.unsafeFrom("USD"))) ==== "\"USD\"",
        codecs.write(Currency.credits) ==== "\"credits\"",
        codecs.readEither[Currency]("\"EUR\"") ==== Right(Currency.iso(CurrencyCode.unsafeFrom("EUR"))),
        codecs.readEither[Currency]("\"credits\"") ==== Right(Currency.credits),
        codecs.readEither[Currency]("\"usd\"").isLeft ==== true,
        codecs.readEither[Currency]("\"USDT\"").isLeft ==== true,
      )
    )

  def testSpendOmitted: Result = {
    val at = EpochSeconds(1789185600L)
    Result.all(
      List(
        codecs
          .write(Fixtures.available(AgentId.Codex, at, Fixtures.window(WindowId.Session, 10.0d, at.some)))
          .contains(""""spend"""") ==== false,
        codecs
          .write(
            Fixtures.withSpend(
              AgentId.ClaudeCode,
              at,
              Spend(Currency.credits, Fixtures.amount("1"), Fixtures.amount("2"), none[EpochSeconds]),
            )
          )
          .contains(""""spend":{"currency":"credits","spent":"1","limit":"2","usedPercent":50.0}""") ==== true,
      )
    )
  }

  def testEpochRoundTrip: Property =
    for {
      epoch <- Fixtures.genEpoch.log("epoch")
    } yield {
      val json = codecs.write(epoch)
      Result.all(List(json ==== epoch.value.toString, codecs.readEither[EpochSeconds](json) ==== Right(epoch)))
    }

  def testUsedPercent: Property =
    for {
      p <- Fixtures.genPercent.log("p")
    } yield Result.all(
      List(
        codecs.readEither[UsedPercent](codecs.write(p)) ==== Right(p),
        codecs.readEither[UsedPercent]("150.0").isLeft ==== true,
        codecs.readEither[UsedPercent]("-1").isLeft ==== true,
      )
    )

  def testRefreshInterval: Result =
    Result.all(
      List(
        codecs.readEither[RefreshIntervalSeconds]("5") ==== Right(RefreshIntervalSeconds.clamp(15)),
        codecs.readEither[RefreshIntervalSeconds]("9999") ==== Right(RefreshIntervalSeconds.clamp(3600)),
        codecs.readEither[RefreshIntervalSeconds]("60") ==== Right(RefreshIntervalSeconds.default),
      )
    )

  def testPlanLabel: Property =
    for {
      s <- StringGens.genNonEmptyString(Gen.alphaNum, PosInt(16)).log("s")
    } yield {
      val label = PlanLabel(s)
      Result.all(
        List(
          codecs.readEither[PlanLabel](codecs.write(label)) ==== Right(label),
          codecs.readEither[PlanLabel](""""""""").isLeft ==== true,
        )
      )
    }

  def testEnums: Result =
    Result.all(
      List(
        codecs.write(AgentId.ClaudeCode) ==== "\"claude-code\"",
        codecs.write(WindowId.Weekly) ==== "\"weekly\"",
        codecs.write(WindowId.model(ModelName(NonEmptyString("Sonnet 4.5")))) ==== "\"weekly-model:Sonnet 4.5\"",
        codecs.readEither[WindowId]("\"weekly-model:Fable\"") ==== Right(
          WindowId.model(ModelName(NonEmptyString("Fable")))
        ),
        codecs.readEither[WindowId]("\"weekly-model:\"").isLeft ==== true,
        codecs.write(AgentStatus.Exhausted) ==== "\"exhausted\"",
        codecs.write(Source.LocalLog) ==== "\"local-log\"",
        codecs.write(MenubarKind.Critical) ==== "\"critical\"",
        codecs.write(AlertKind.Critical95) ==== "\"threshold95\"",
        codecs.readEither[AlertKind]("\"threshold80\"") ==== Right(AlertKind.Warning80),
        codecs.readEither[AgentId]("\"nope\"").isLeft ==== true,
      )
    )

  def testSnapshotRoundTrip: Property =
    for {
      now     <- Fixtures.genEpoch.log("now")
      windows <- Fixtures.genAgentWindows.log("windows")
      spend   <- Fixtures.genSpend.option.log("spend")
    } yield {
      val snapshot = Fixtures.snapshot(
        now,
        AgentSnapshot.available(
          AgentId.Codex,
          none[PlanLabel],
          UsageMeters(windows, spend),
          Source.Api,
          now,
          none[ErrorMessage],
        ),
        Fixtures.unavailable(AgentId.ClaudeCode, now)
      )
      codecs.readEither[Snapshot](codecs.write(snapshot)) ==== Right(snapshot)
    }

  def testAlertState: Result = {
    val state = AlertState(
      Map(
        WindowKey(AgentId.Codex, WindowId.Session)                                        ->
          WindowRecord(EpochSeconds(1789187040L), UsedPercent.clamp(82.0d), Set(AlertKind.Warning80)),
        WindowKey(AgentId.ClaudeCode, WindowId.Weekly)                                    ->
          WindowRecord(EpochSeconds(1789617600L), UsedPercent.clamp(3.0d), Set.empty[AlertKind]),
        WindowKey(AgentId.ClaudeCode, WindowId.model(ModelName(NonEmptyString("Fable")))) ->
          WindowRecord(EpochSeconds(1789617600L), UsedPercent.clamp(68.0d), Set(AlertKind.Warning80)),
      )
    )
    Result.all(
      List(
        codecs.readEither[AlertState](codecs.write(state)) ==== Right(state),
        codecs.write(state).contains(""""window":"weekly-model:Fable"""") ==== true,
        codecs.readEither[AlertState]("""{"records":[]}""") ==== Right(AlertState.empty),
      )
    )
  }

  def testConfig: Result = {
    val json    =
      """{"stateDir":"/tmp/tw","refreshIntervalSeconds":30,"codexHome":null,"claudeCodeVersionOverride":"2.1.269","future":true}"""
    val decoded = codecs.readEither[Config](json)
    Result.all(
      List(
        decoded.map(_.stateDir.value.value) ==== Right("/tmp/tw"),
        decoded.map(_.refreshInterval) ==== Right(RefreshIntervalSeconds.clamp(30)),
        decoded.map(_.codexHome) ==== Right(None),
        decoded.map(_.claudeCodeVersionOverride.map(_.value.render)) ==== Right(Some("2.1.269")),
        codecs.readEither[Config]("""{"stateDir":""}""").isLeft ==== true,
      )
    )
  }

  def testEnvelope: Result = {
    val now       = EpochSeconds(1789185600L)
    val snapshot  = Fixtures.snapshot(
      now,
      Fixtures.available(AgentId.Codex, now, Fixtures.window(WindowId.Session, 82.0d, EpochSeconds(1789187040L).some))
    )
    val alert     =
      AlertText.warning80(AgentId.Codex, Fixtures.window(WindowId.Session, 82.0d, EpochSeconds(1789187040L).some), now)
    val envelopes = List(
      Envelope.snapshot(SequenceNumber(42L), snapshot),
      Envelope.alert(SequenceNumber(43L), alert),
      Envelope.error(SequenceNumber(44L), "boom"),
    )
    val jsons     = envelopes.map(codecs.write(_))
    Result.all(
      List(
        jsons.map(_.startsWith("""{"version":1,"type":""")) ==== List(true, true, true),
        jsons.lift(1).exists(_.contains(""""identifier":"codex.session.1789187040.threshold80"""")) ==== true,
        jsons.lift(1).exists(_.contains(""""usedPercent":82.0""")) ==== true,
        jsons.lift(2) ==== Some("""{"version":1,"type":"error","seq":44,"message":"boom"}"""),
        jsons.map(codecs.readEither[Envelope](_)) ==== envelopes.map(Right(_)),
      )
    )
  }

  def testEmptyLists: Result = {
    val at          = EpochSeconds(1789185600L)
    val empty       = codecs.write(Envelope.snapshot(SequenceNumber(1L), Snapshot.of(at, Nil)))
    val unavailable = codecs.write(
      Snapshot.of(
        at,
        List(
          AgentSnapshot.unavailable(AgentId.Codex, at, ErrorMessage(NonEmptyString("Not signed in. Run codex once.")))
        )
      )
    )
    Result.all(
      List(
        empty.contains(""""agents":[]""") ==== true,
        unavailable.contains(""""windows":[]""") ==== true,
      )
    )
  }
}

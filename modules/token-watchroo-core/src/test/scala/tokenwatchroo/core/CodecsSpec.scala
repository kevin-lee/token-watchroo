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
    example("observedAt is written when present and left out when absent", testObservedAt),
    property("config round-trips with and without agents", testConfigRoundTrip),
    example("a v1 config without agents decodes to no agents", testConfigWithoutAgents),
    example("an agent entry with only an id decodes to the defaults", testAgentEntryDefaults),
    example("agent entries with an unknown id, billing or plan are skipped and the others kept", testAgentEntrySkipped),
    example("an agent entry writes its wire values", testAgentEntryWire),
    example("alert kinds use the kept names for 70 and 85 and level<n> for the others", testAlertKindWire),
    example("a fired kind that cannot be parsed is skipped and the rest of the state is kept", testUnknownFiredKind),
    example("alertLevels: absent or broken gives the defaults, the defaults are left out on write", testAlertLevels),
    example(
      "spendAlertLevels: absent or broken gives the defaults, the defaults are left out on write",
      testSpendAlertLevels
    ),
    example("alert state with spends round-trips, and a state without spends reads", testSpendState),
    example("a spend kind that cannot be parsed is skipped and the spend record is kept", testUnknownSpendKind),
    example("a window record with a spend kind keeps the record without it", testSpendKindInWindowRecord),
    example("spend alerts round-trip with and without windowResetsAt", testSpendAlertEnvelope),
    example("alerts with a broken window, kind or reset time fail to decode", testBrokenAlert),
    example(
      "claudeConfigDir: absent or not absolute is not set and the other fields are kept, not set is left out",
      testClaudeConfigDir,
    ),
    example("claudeConfigDir keeps the exact text, a trailing slash and NFD included", testClaudeConfigDirExact),
  )

  def testConfigRoundTrip: Property =
    Fixtures.genConfig.log("config").map { config =>
      val withoutAgents = config.copy(agents = List.empty[AgentConfig])
      Result.all(
        List(
          codecs.readEither[Config](codecs.write(config)) ==== config.asRight[DecodeError],
          codecs.readEither[Config](codecs.write(withoutAgents)) ==== withoutAgents.asRight[DecodeError],
        )
      )
    }

  private val V1ConfigJson =
    """{"stateDir":"/tmp/tw","refreshIntervalSeconds":30,"codexHome":null,"claudeCodeVersionOverride":"2.1.269","future":true}"""

  def testConfigWithoutAgents: Result =
    codecs.readEither[Config](V1ConfigJson).map(_.agents) ==== Right(List.empty[AgentConfig])

  def testAgentEntryDefaults: Result =
    codecs
      .readEither[Config]("""{"stateDir":"/tmp/tw","refreshIntervalSeconds":60,"agents":[{"id":"codex"}]}""")
      .map(_.agents) ==== Right(List(AgentConfig.defaults(AgentId.Codex)))

  private def claudeEntry(field: String): String =
    s"""{"stateDir":"/tmp/tw","refreshIntervalSeconds":60,"agents":[{"id":"claude-code","plan":"team"$field}]}"""

  /** A file from #105 has no field. A value that is not absolute is read as not set (#91). */
  def testClaudeConfigDir: Result = {
    val team = AgentConfig.defaults(AgentId.ClaudeCode).copy(plan = PlanKind.Team)
    Result.all(
      List(
        codecs.readEither[Config](claudeEntry("")).map(_.agents) ==== Right(List(team)),
        codecs.readEither[Config](claudeEntry(""","claudeConfigDir":"relative/x"""")).map(_.agents) ====
          Right(List(team)),
        codecs.readEither[Config](claudeEntry(""","claudeConfigDir":"~/x"""")).map(_.agents) ==== Right(List(team)),
        codecs.readEither[Config](claudeEntry(""","claudeConfigDir":""""")).map(_.agents) ==== Right(List(team)),
        Result.assert(!codecs.write(configWithAgent(team)).contains("claudeConfigDir")),
      )
    )
  }

  def testClaudeConfigDirExact: Result = {
    val texts = List("/Users/alex/.claude-work", "/Users/alex/.claude-work/", "/Users/alex/Cafe\u0301")
    Result.all(
      texts.map { text =>
        val expected = ClaudeConfigDir.unsafeFrom(text)
        codecs.readEither[Config](claudeEntry(s""","claudeConfigDir":"$text"""")).map(_.claudeConfigDir) ====
          Right(expected.some)
      }
    )
  }

  private def configWithAgent(agent: AgentConfig): Config =
    Config.default(StateDir(NonEmptyString("/tmp/tw"))).copy(agents = List(agent))

  def testAgentEntrySkipped: Result = {
    val json =
      """{"stateDir":"/tmp/tw","refreshIntervalSeconds":60,"agents":[""" +
        """{"id":"cursor"},""" +
        """{"id":"codex","billing":"budget"},""" +
        """{"id":"codex","plan":"family"},""" +
        """{"id":"claude-code","billing":"api","plan":"enterprise","enabled":false}""" +
        """]}"""
    codecs.readEither[Config](json).map(_.agents) ==== Right(
      List(
        AgentConfig(
          AgentId.ClaudeCode,
          Billing.Api,
          PlanKind.Enterprise,
          Enabled.Off,
          AlertLevels.defaults,
          SpendAlertLevels.defaults,
          none[ClaudeConfigDir],
        )
      )
    )
  }

  /** The same text is in the Swift `ConfigFileTests`, so both sides agree on the wire. */
  def testAgentEntryWire: Result = {
    val config = Config
      .default(StateDir(NonEmptyString("/tmp/tw")))
      .copy(agents =
        List(
          AgentConfig(
            AgentId.Codex,
            Billing.Subscription,
            PlanKind.Team,
            Enabled.On,
            AlertLevels.defaults,
            SpendAlertLevels.defaults,
            none[ClaudeConfigDir],
          )
        )
      )
    codecs
      .write(config)
      .contains(
        """"agents":[{"id":"codex","billing":"subscription","plan":"team","enabled":true}]"""
      ) ==== true
  }

  def testSpendRoundTrip: Property =
    Fixtures.genSpend.log("spend").map { spend =>
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

  def testObservedAt: Result = {
    val at       = EpochSeconds(1789185600L)
    val without  = Fixtures.available(AgentId.Codex, at, Fixtures.window(WindowId.Session, 10.0d, at.some))
    val withTime = without.copy(observedAt = EpochSeconds(1790565092L).some)
    Result.all(
      List(
        codecs.write(withTime).contains(""""observedAt":1790565092""") ==== true,
        codecs.write(without).contains(""""observedAt"""") ==== false,
        codecs.readEither[AgentSnapshot](codecs.write(withTime)) ==== withTime.asRight[DecodeError],
        codecs.readEither[AgentSnapshot](codecs.write(without)) ==== without.asRight[DecodeError],
      )
    )
  }

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
    Fixtures.genEpoch.log("epoch").map { epoch =>
      val json = codecs.write(epoch)
      Result.all(List(json ==== epoch.value.toString, codecs.readEither[EpochSeconds](json) ==== Right(epoch)))
    }

  def testUsedPercent: Property =
    Fixtures.genPercent.log("p").map { p =>
      Result.all(
        List(
          codecs.readEither[UsedPercent](codecs.write(p)) ==== Right(p),
          codecs.readEither[UsedPercent]("150.0").isLeft ==== true,
          codecs.readEither[UsedPercent]("-1").isLeft ==== true,
        )
      )
    }

  def testRefreshInterval: Result =
    Result.all(
      List(
        codecs.readEither[RefreshIntervalSeconds]("5") ==== Right(RefreshIntervalSeconds.clamp(15)),
        codecs.readEither[RefreshIntervalSeconds]("9999") ==== Right(RefreshIntervalSeconds.clamp(3600)),
        codecs.readEither[RefreshIntervalSeconds]("60") ==== Right(RefreshIntervalSeconds.default),
      )
    )

  def testPlanLabel: Property =
    StringGens.genNonEmptyString(Gen.alphaNum, PosInt(16)).log("s").map { s =>
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
        codecs.write(AlertKind.level(AlertLevel.unsafeFrom(85))) ==== "\"threshold95\"",
        codecs.readEither[AlertKind]("\"threshold80\"") ==== Right(AlertKind.level(AlertLevel.unsafeFrom(70))),
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
          WindowRecord(
            EpochSeconds(1789187040L),
            UsedPercent.clamp(82.0d),
            Set(AlertKind.level(AlertLevel.unsafeFrom(70)))
          ),
        WindowKey(AgentId.ClaudeCode, WindowId.Weekly)                                    ->
          WindowRecord(EpochSeconds(1789617600L), UsedPercent.clamp(3.0d), Set.empty[AlertKind]),
        WindowKey(AgentId.ClaudeCode, WindowId.model(ModelName(NonEmptyString("Fable")))) ->
          WindowRecord(
            EpochSeconds(1789617600L),
            UsedPercent.clamp(68.0d),
            Set(AlertKind.level(AlertLevel.unsafeFrom(70)))
          ),
      ),
      Map.empty[AgentId, SpendRecord],
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
      AlertText.level(
        AgentId.Codex,
        Fixtures.window(WindowId.Session, 82.0d, EpochSeconds(1789187040L).some),
        now,
        AlertLevel.unsafeFrom(70),
      )
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

  def testAlertKindWire: Result = {
    def kind(n: Int): AlertKind = AlertKind.level(AlertLevel.unsafeFrom(n))
    Result.all(
      List(
        kind(70).wire ==== "threshold80",
        kind(85).wire ==== "threshold95",
        kind(60).wire ==== "level60",
        kind(80).wire ==== "level80",
        AlertKind.Reset.wire ==== "reset",
        AlertKind.parse("threshold80") ==== Right(kind(70)),
        AlertKind.parse("threshold95") ==== Right(kind(85)),
        AlertKind.parse("level60") ==== Right(kind(60)),
        AlertKind.parse("level80") ==== Right(kind(80)),
        AlertKind.parse("level0").isLeft ==== true,
        AlertKind.parse("level101").isLeft ==== true,
        AlertKind.parse("levelx").isLeft ==== true,
        AlertKind.parse("spend70").isLeft ==== true,
      )
    )
  }

  def testUnknownFiredKind: Result = {
    val json =
      """{"records":[{"agent":"codex","window":"session","resetsAt":1789187040,"lastPercent":82.0,""" +
        """"fired":["threshold80","spend-level70","level60"]}]}"""
    codecs.readEither[AlertState](json) ==== Right(
      AlertState(
        Map(
          WindowKey(AgentId.Codex, WindowId.Session) ->
            WindowRecord(
              EpochSeconds(1789187040L),
              UsedPercent.clamp(82.0d),
              Set(AlertKind.level(AlertLevel.unsafeFrom(70)), AlertKind.level(AlertLevel.unsafeFrom(60))),
            )
        ),
        Map.empty[AgentId, SpendRecord],
      )
    )
  }

  def testAlertLevels: Result = {
    def entry(levels: String): String                                  =
      s"""{"stateDir":"/tmp/tw","refreshIntervalSeconds":60,"agents":[{"id":"codex","plan":"team"$levels}]}"""
    def levelsOf(json: String): Either[DecodeError, List[AlertLevels]] =
      codecs.readEither[Config](json).map(_.agents.map(_.alertLevels))
    val sixtyNinety                          = AlertLevels.from(List(60, 90)).getOrElse(AlertLevels.defaults)
    def written(levels: AlertLevels): String =
      codecs.write(
        Config
          .default(StateDir(NonEmptyString("/tmp/tw")))
          .copy(agents = List(AgentConfig.defaults(AgentId.Codex).copy(alertLevels = levels)))
      )
    Result.all(
      List(
        levelsOf(entry("")) ==== Right(List(AlertLevels.defaults)),
        levelsOf(entry(""","alertLevels":[60,90]""")) ==== Right(List(sixtyNinety)),
        levelsOf(entry(""","alertLevels":[70,72]""")) ==== Right(List(AlertLevels.defaults)),
        codecs.readEither[Config](entry(""","alertLevels":[70,72]""")).map(_.agents.map(_.plan)) ====
          Right(List(PlanKind.Team)),
        levelsOf(entry(""","alertLevels":[]""")) ==== Right(List(AlertLevels.none)),
        written(AlertLevels.defaults).contains("alertLevels") ==== false,
        written(sixtyNinety).contains(""""alertLevels":[60,90]""") ==== true,
        written(AlertLevels.none).contains(""""alertLevels":[]""") ==== true,
      )
    )
  }

  def testSpendAlertLevels: Result = {
    def entry(levels: String): String                                       =
      s"""{"stateDir":"/tmp/tw","refreshIntervalSeconds":60,"agents":[{"id":"codex","plan":"team"$levels}]}"""
    def levelsOf(json: String): Either[DecodeError, List[SpendAlertLevels]] =
      codecs.readEither[Config](json).map(_.agents.map(_.spendAlertLevels))
    def from(unit: Currency, values: Long*): SpendAlertLevels               =
      SpendAlertLevels.from(unit, values.toList).fold(reason => sys.error(reason), identity)
    def written(levels: SpendAlertLevels): String                           =
      codecs.write(
        Config
          .default(StateDir(NonEmptyString("/tmp/tw")))
          .copy(agents = List(AgentConfig.defaults(AgentId.Codex).copy(spendAlertLevels = levels)))
      )
    Result.all(
      List(
        levelsOf(entry("")) ==== Right(List(SpendAlertLevels.defaults)),
        levelsOf(entry(""","spendAlertLevels":{"unit":"USD","amounts":[150,180]}""")) ====
          Right(List(from(Fixtures.usd, 150L, 180L))),
        levelsOf(entry(""","spendAlertLevels":{"unit":"credits","amounts":[]}""")) ====
          Right(List(from(Currency.credits))),
        levelsOf(entry(""","spendAlertLevels":{"unit":"usd","amounts":[150]}""")) ====
          Right(List(SpendAlertLevels.defaults)),
        levelsOf(entry(""","spendAlertLevels":{"unit":"USD","amounts":[180,150]}""")) ====
          Right(List(SpendAlertLevels.defaults)),
        codecs
          .readEither[Config](entry(""","spendAlertLevels":{"unit":"USD","amounts":[180,150]}"""))
          .map(
            _.agents.map(_.plan)
          ) ==== Right(List(PlanKind.Team)),
        written(SpendAlertLevels.defaults).contains("spendAlertLevels") ==== false,
        written(from(Fixtures.usd, 150L, 180L)).contains(
          """"spendAlertLevels":{"unit":"USD","amounts":[150,180]}"""
        ) ==== true,
        written(from(Currency.credits)).contains(""""spendAlertLevels":{"unit":"credits","amounts":[]}""") ==== true,
      )
    )
  }

  private val spendRecord: SpendRecord =
    SpendRecord(
      Currency.credits,
      Fixtures.amount("10000"),
      none[EpochSeconds],
      Fixtures.amount("8600"),
      Set(
        SpendAlertKind.percent(AlertLevel.unsafeFrom(70)),
        SpendAlertKind.percent(AlertLevel.unsafeFrom(85)),
      ),
      EpochSeconds(1789185600L),
    )

  def testSpendState: Result = {
    val state = AlertState(
      Map.empty[WindowKey, WindowRecord],
      Map(
        AgentId.Codex      -> spendRecord,
        AgentId.ClaudeCode ->
          SpendRecord(
            Fixtures.usd,
            Fixtures.amount("200.00"),
            EpochSeconds(1790812800L).some,
            Fixtures.amount("0.05"),
            Set.empty[SpendAlertKind],
            EpochSeconds(1789185600L),
          ),
      ),
    )
    Result.all(
      List(
        codecs.readEither[AlertState](codecs.write(state)) ==== Right(state),
        codecs.write(state).contains(""""spends":[{"agent":"claude-code",""") ==== true,
        codecs.readEither[AlertState]("""{"records":[]}""") ==== Right(AlertState.empty),
      )
    )
  }

  def testUnknownSpendKind: Result = {
    val json =
      """{"records":[],"spends":[{"agent":"codex","currency":"credits","limit":"10000","lastSpent":"8600",""" +
        """"fired":["spend-pct70","spend-later","spend-pct85"],"seenAt":1789185600}]}"""
    codecs.readEither[AlertState](json) ==== Right(
      AlertState(Map.empty[WindowKey, WindowRecord], Map(AgentId.Codex -> spendRecord))
    )
  }

  /** A reader that knows only window kinds (#9) skips the spend kinds, as this version skips any unknown kind. */
  def testSpendKindInWindowRecord: Result = {
    val json =
      """{"records":[{"agent":"codex","window":"session","resetsAt":1789187040,"lastPercent":82.0,""" +
        """"fired":["threshold80","spend-pct70"]}]}"""
    codecs.readEither[AlertState](json) ==== Right(
      AlertState(
        Map(
          WindowKey(AgentId.Codex, WindowId.Session) ->
            WindowRecord(
              EpochSeconds(1789187040L),
              UsedPercent.clamp(82.0d),
              Set(AlertKind.level(AlertLevel.unsafeFrom(70))),
            )
        ),
        Map.empty[AgentId, SpendRecord],
      )
    )
  }

  def testSpendAlertEnvelope: Result = {
    val now      = EpochSeconds(1789185600L)
    val monthly  = AlertText.spendLevel(
      AgentId.ClaudeCode,
      Fixtures.spend(Fixtures.usd, "145", "200", EpochSeconds(1790812800L).some),
      now,
      SpendAlertKind.percent(AlertLevel.unsafeFrom(70)),
    )
    val noReset  = AlertText.spendLevel(
      AgentId.Codex,
      Fixtures.spend(Currency.credits, "8600", "10000", none[EpochSeconds]),
      now,
      SpendAlertKind.amount(SpendLevel.unsafeFrom(8500L)),
    )
    val envelope = (alert: Alert) => Envelope.alert(SequenceNumber(7L), alert)
    val json     = codecs.write(envelope(noReset))
    Result.all(
      List(
        codecs.readEither[Envelope](codecs.write(envelope(monthly))) ==== Right(envelope(monthly)),
        codecs.readEither[Envelope](json) ==== Right(envelope(noReset)),
        json.contains(""""window":"spend","kind":"spend-amt8500"""") ==== true,
        json.contains("windowResetsAt") ==== false,
        json.contains(""""identifier":"codex.spend.none.spend-amt8500"""") ==== true,
      )
    )
  }

  def testBrokenAlert: Result = {
    def alert(window: String, kind: String, resetsAt: String): String =
      s"""{"version":1,"type":"alert","seq":1,"data":{"agent":"codex","window":"$window","kind":"$kind",""" +
        s"""$resetsAt"usedPercent":82.0,"title":"t","body":"b","identifier":"i"}}"""
    Result.all(
      List(
        codecs.readEither[Envelope](alert("session", "threshold80", "")).isLeft ==== true,
        codecs.readEither[Envelope](alert("spend", "threshold80", "")).isLeft ==== true,
        codecs.readEither[Envelope](alert("session", "spend-pct70", """"windowResetsAt":1,""")).isLeft ==== true,
        codecs.readEither[Envelope](alert("budget", "spend-pct70", "")).isLeft ==== true,
        codecs.readEither[Envelope](alert("session", "threshold80", """"windowResetsAt":1,""")).isRight ==== true,
      )
    )
  }
}

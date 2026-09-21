package tokenwatchroo.core

import cats.syntax.all.*
import hedgehog.*
import hedgehog.runner.*
import tokenwatchroo.core.providers.*

object CodexRolloutSpec extends Properties {

  override def tests: List[Test] = List(
    example("the last token_count line with rate_limits wins", testLatest),
    example("lines without rate_limits and malformed lines are skipped", testSkipped),
    example("window minutes become seconds and plan_type becomes the label", testWindows),
    example("an empty log gives none", testEmpty),
    example("withLine keeps the latest rate limits for a line without any", testWithLine),
  )

  private val tokenFirst =
    """{"timestamp":"2026-03-29T15:04:10.090Z","type":"event_msg","payload":{"type":"token_count","info":{"last_token_usage":{"input_tokens":18193,"cached_input_tokens":10624,"output_tokens":371,"reasoning_output_tokens":38,"total_tokens":18564},"total_token_usage":{"input_tokens":18193,"cached_input_tokens":10624,"output_tokens":371,"reasoning_output_tokens":38,"total_tokens":18564}}}}"""

  private val limitNull =
    """{"timestamp":"2026-04-29T07:59:08.887Z","type":"event_msg","payload":{"type":"token_count","info":null,"rate_limits":{"limit_id":"codex","limit_name":null,"primary":{"used_percent":17.0,"window_minutes":300,"resets_at":1777477636},"secondary":{"used_percent":6.0,"window_minutes":10080,"resets_at":1777960801},"credits":null,"plan_type":"prolite","rate_limit_reached_type":null}}}"""

  private val limitModel =
    """{"timestamp":"2026-04-29T07:59:28.815Z","type":"event_msg","payload":{"type":"token_count","info":{"last_token_usage":{"input_tokens":18193,"cached_input_tokens":10624,"output_tokens":371,"reasoning_output_tokens":38,"total_tokens":18564},"total_token_usage":{"input_tokens":18193,"cached_input_tokens":10624,"output_tokens":371,"reasoning_output_tokens":38,"total_tokens":18564}},"rate_limits":{"limit_id":"codex_bengalfox","limit_name":"GPT-5.3-Codex-Spark","primary":{"used_percent":0.0,"window_minutes":300,"resets_at":1777487853},"secondary":{"used_percent":0.0,"window_minutes":10080,"resets_at":1778074653},"credits":{"has_credits":false,"unlimited":false,"balance":null},"plan_type":null,"rate_limit_reached_type":null}}}"""

  private val limitStringBalance =
    """{"timestamp":"2026-07-16T16:05:42.263Z","type":"event_msg","payload":{"type":"token_count","info":{"last_token_usage":{"input_tokens":19737,"cached_input_tokens":3840,"output_tokens":179,"reasoning_output_tokens":57,"total_tokens":19916},"total_token_usage":{"input_tokens":19737,"cached_input_tokens":3840,"output_tokens":179,"reasoning_output_tokens":57,"total_tokens":19916}},"rate_limits":{"limit_id":"codex","limit_name":null,"primary":{"used_percent":10.0,"window_minutes":10080,"resets_at":1784793140},"secondary":null,"credits":{"has_credits":false,"unlimited":false,"balance":"0"},"individual_limit":null,"plan_type":"prolite","rate_limit_reached_type":null}}}"""

  def testLatest: Result = {
    val latest = CodexRollout.latestRateLimits(List(tokenFirst, limitNull, tokenFirst, limitModel).iterator)
    Result.all(
      List(
        latest.flatMap(_.primary).flatMap(_.resetsAt) ==== Some(1777487853L),
        latest.flatMap(_.planType) ==== None,
      )
    )
  }

  def testSkipped: Result = {
    val latest = CodexRollout.latestRateLimits(List(limitNull, "not json", tokenFirst, """{"type":"other"}""").iterator)
    latest.flatMap(_.primary).flatMap(_.usedPercent) ==== Some(17.0d)
  }

  def testWindows: Result = {
    val latest  = CodexRollout.latestRateLimits(List(limitStringBalance).iterator)
    val windows = latest.map(_.toWindows).getOrElse(Nil)
    Result.all(
      List(
        windows.map(_.id) ==== List(WindowId.Session, WindowId.Weekly),
        windows.headOption.map(_.usedPercent) ==== Some(UsedPercent.clamp(10.0d)),
        windows.headOption.flatMap(_.windowLength) ==== Some(Seconds(604800L)),
        windows.headOption.flatMap(_.resetsAt) ==== Some(EpochSeconds(1784793140L)),
        windows.lastOption.map(_.isIdle) ==== Some(true),
        latest.flatMap(_.planLabel).map(_.value.value) ==== Some("Prolite"),
      )
    )
  }

  def testWithLine: Result = {
    val latest = CodexRollout.latestRateLimits(List(limitModel).iterator)
    Result.all(
      List(
        CodexRollout.withLine(latest, "not json") ==== latest,
        CodexRollout.withLine(latest, tokenFirst) ==== latest,
        CodexRollout.withLine(latest, limitNull) ==== CodexRollout.latestRateLimits(List(limitNull).iterator),
      )
    )
  }

  def testEmpty: Result = CodexRollout.latestRateLimits(Iterator.empty) ==== none[CodexRolloutRateLimits]
}

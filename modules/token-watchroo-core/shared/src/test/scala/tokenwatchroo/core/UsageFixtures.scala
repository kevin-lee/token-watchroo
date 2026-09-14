package tokenwatchroo.core

/** Usage responses for issue #33. The providers tests keep copies in `Fakes`, because the core tests compile only on
  * the JVM.
  */
object UsageFixtures {

  /** Verified 2026-09-14 on a usage-based Claude Enterprise account, nothing to redact. The Claude usage page showed
    * "$0.05 of $200.00 spent" at the same time.
    */
  val claudeEnterpriseUsage: String =
    """{"five_hour":null,"seven_day":null,"seven_day_oauth_apps":null,"seven_day_opus":null,"seven_day_sonnet":null,"seven_day_cowork":null,"seven_day_omelette":null,"tangelo":null,"iguana_necktie":null,"omelette_promotional":null,"nimbus_quill":{"utilization":0.0,"resets_at":null,"limit_dollars":null,"used_dollars":null,"remaining_dollars":null,"locked_reason":null},"cinder_cove":null,"copper_kite":null,"harbor_lantern":null,"amber_ladder":null,"juniper_tide":null,"extra_usage":{"is_enabled":true,"monthly_limit":20000,"used_credits":5.0,"utilization":0.025,"currency":"USD","decimal_places":2,"disabled_reason":null,"user_disabled":false,"spend_limit_reached":false,"credits_ever_enabled":true,"daily":null,"weekly":null},"limits":[],"spend":{"used":{"amount_minor":5,"currency":"USD","exponent":2},"limit":{"amount_minor":20000,"currency":"USD","exponent":2},"percent":0,"severity":"normal","enabled":true,"disabled_reason":null,"cap":{"money":null,"credits":{"amount_minor":20000,"exponent":2}},"balance":null,"auto_reload":null,"disclaimer":"Usage credits cover you when you hit your plan limits. [Learn more](https://support.claude.com/articles/12429409)","can_purchase_credits":false,"can_toggle":false},"member_dashboard_available":true,"seven_day_breakdown":null}"""

  /** Verified 2026-09-14 on a Claude Max 5x account, nothing to redact. */
  val claudeMaxUsage: String =
    """{"five_hour":{"utilization":10.0,"resets_at":"2026-09-14T09:00:00.125337+00:00","limit_dollars":null,"used_dollars":null,"remaining_dollars":null,"locked_reason":null},"seven_day":{"utilization":7.0,"resets_at":"2026-09-19T18:00:00.125361+00:00","limit_dollars":null,"used_dollars":null,"remaining_dollars":null,"locked_reason":null},"seven_day_oauth_apps":null,"seven_day_opus":null,"seven_day_sonnet":null,"seven_day_cowork":null,"seven_day_omelette":null,"tangelo":null,"iguana_necktie":null,"omelette_promotional":null,"nimbus_quill":{"utilization":0.0,"resets_at":null,"limit_dollars":null,"used_dollars":null,"remaining_dollars":null,"locked_reason":null},"cinder_cove":null,"copper_kite":null,"harbor_lantern":null,"amber_ladder":null,"juniper_tide":null,"extra_usage":{"is_enabled":false,"monthly_limit":null,"used_credits":null,"utilization":null,"currency":null,"decimal_places":null,"disabled_reason":null,"user_disabled":true,"spend_limit_reached":false,"credits_ever_enabled":true,"daily":null,"weekly":null},"limits":[{"kind":"session","group":"session","percent":10,"severity":"normal","resets_at":"2026-09-14T09:00:00.125337+00:00","scope":null,"is_active":true},{"kind":"weekly_all","group":"weekly","percent":7,"severity":"normal","resets_at":"2026-09-19T18:00:00.125361+00:00","scope":null,"is_active":false},{"kind":"weekly_scoped","group":"weekly","percent":9,"severity":"normal","resets_at":"2026-09-19T18:00:00.125566+00:00","scope":{"model":{"id":null,"display_name":"Fable"},"surface":null},"is_active":false}],"spend":{"used":{"amount_minor":0,"currency":"USD","exponent":2},"limit":null,"percent":0,"severity":"normal","enabled":false,"disabled_reason":null,"cap":null,"balance":null,"auto_reload":null,"disclaimer":"Usage credits cover you when you hit your plan limits. [Learn more](https://support.claude.com/articles/12429409)","can_purchase_credits":false,"can_toggle":false},"member_dashboard_available":false,"seven_day_breakdown":{"as_of":"2026-09-14T06:29:07.139231+00:00","window_started_at":"2026-09-12T18:00:00.125361+00:00","rows":[{"key":"claude_code","display_name":"Claude Code","percent":100},{"key":"chat","display_name":"Chats","percent":0},{"key":"cowork","display_name":"Cowork","percent":0},{"key":"other","display_name":"Other","percent":0}]}}"""

  /** No window and no usable spend. */
  val claudeWindowsNullNoSpend: String =
    """{"five_hour":null,"seven_day":null,"limits":[],"spend":{"used":{"amount_minor":0,"currency":"USD","exponent":2},"limit":null,"enabled":false}}"""

  /** UNVERIFIED, from alondero/buildmesh#1684, no Codex Enterprise or Edu account available. */
  val codexEnterpriseUsage: String =
    """{"plan_type":"enterprise","rate_limit":null,"credits":{"has_credits":true,"unlimited":false,"balance":"17000.50"},"spend_control":{"reached":false,"individual_limit":{"limit":"25000","used":"8000","remaining":"17000","used_percent":32,"reset_at":1778137680}},"additional_rate_limits":[{"limit_name":"codex_other","metered_feature":"codex_other","rate_limit":{"primary_window":{"used_percent":30.0,"limit_window_seconds":3600,"reset_at":1755288000}}}]}"""

  /** UNVERIFIED: the same with the amounts as JSON numbers, which the report says can happen. */
  val codexEnterpriseNumericAmounts: String =
    """{"plan_type":"enterprise","rate_limit":null,"credits":{"has_credits":true,"unlimited":false,"balance":17000.50},"spend_control":{"reached":false,"individual_limit":{"limit":25000,"used":8000,"remaining":17000,"used_percent":32,"reset_at":1778137680}}}"""

  val codexRateLimitNullNoSpend: String =
    """{"plan_type":"enterprise","rate_limit":null,"credits":null,"spend_control":null}"""
}

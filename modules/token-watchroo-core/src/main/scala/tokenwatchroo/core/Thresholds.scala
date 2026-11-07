package tokenwatchroo.core

/** The fixed colour bands (#101) on the shown whole percent (`Format.wholePercent`): at `elevated` blue, at `warning`
  * amber, at `critical` red (#9: at the number, before #9 over it). The default alert levels are `AlertLevels.defaults`.
  */
object Thresholds {
  val elevated: Long = 50L
  val warning: Long  = 70L
  val critical: Long = 85L
}

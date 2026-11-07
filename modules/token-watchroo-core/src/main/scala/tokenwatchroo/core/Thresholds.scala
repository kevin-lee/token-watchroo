package tokenwatchroo.core

/** The fixed colour bands and alert levels (#101) on the shown whole percent (`Format.wholePercent`): over `elevated`
  * blue, over `warning` amber and the first alert, over `critical` red and the second alert.
  */
object Thresholds {
  val elevated: Long = 50L
  val warning: Long  = 70L
  val critical: Long = 85L
}

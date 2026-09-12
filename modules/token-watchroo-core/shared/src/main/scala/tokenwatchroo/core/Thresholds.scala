package tokenwatchroo.core

/** Alert thresholds from the design canvas: amber and a first notification at 80%, red and a second one at 95%. */
object Thresholds {
  val warning: UsedPercent  = UsedPercent(80.0d)
  val critical: UsedPercent = UsedPercent(95.0d)
}

package tokenwatchroo.core

import cats.{Eq, Show}
import cats.derived.*
import cats.syntax.all.*

enum Iso8601Error derives CanEqual, Eq, Show {
  case Malformed(input: String)
}

object Iso8601Error {
  def malformed(input: String): Iso8601Error = Iso8601Error.Malformed(input)

  extension (error: Iso8601Error) {
    def message: String = error match {
      case Iso8601Error.Malformed(input) => s"Not an ISO-8601 timestamp: $input"
    }
  }
}

/** Minimal ISO-8601 support without `java.time`, which Scala Native's javalib does not ship. */
object Iso8601 {

  private val Pattern =
    """^(\d{4})-(\d{2})-(\d{2})[Tt ](\d{2}):(\d{2}):(\d{2})(?:\.\d+)?(Z|z|[+-]\d{2}:?\d{2})$""".r

  /** Accepts `YYYY-MM-DDTHH:MM:SS`, optional fractional seconds, and `Z` or `+HH:MM` / `-HH:MM`. */
  def parseToEpochSeconds(s: String): Either[Iso8601Error, EpochSeconds] = s.trim match {
    case Pattern(y, mo, d, h, mi, sec, zone) =>
      val year   = y.toLong
      val month  = mo.toLong
      val day    = d.toLong
      val hour   = h.toLong
      val minute = mi.toLong
      val second = sec.toLong
      val valid  =
        month >= 1L && month <= 12L && day >= 1L && day <= daysInMonth(year, month) &&
          hour <= 23L && minute <= 59L && second <= 60L
      if (valid) {
        val local = daysFromCivil(year, month, day) * 86400L + hour * 3600L + minute * 60L + second
        EpochSeconds(local - offsetSeconds(zone)).asRight[Iso8601Error]
      } else Iso8601Error.malformed(s).asLeft[EpochSeconds]
    case _ => Iso8601Error.malformed(s).asLeft[EpochSeconds]
  }

  /** `YYYY-MM-DDTHH:MM:SSZ` */
  def format(epoch: EpochSeconds): String = {
    val total         = epoch.value
    val days          = Math.floorDiv(total, 86400L)
    val secondsOfDay  = Math.floorMod(total, 86400L)
    val (y, mo, d)    = civilFromDays(days)
    val hour          = secondsOfDay / 3600L
    val minute        = (secondsOfDay % 3600L) / 60L
    val second        = secondsOfDay  % 60L
    def two(n: Long)  = if (n < 10L) s"0$n" else n.toString
    def four(n: Long) = if (n < 10L) s"000$n" else if (n < 100L) s"00$n" else if (n < 1000L) s"0$n" else n.toString
    s"${four(y)}-${two(mo)}-${two(d)}T${two(hour)}:${two(minute)}:${two(second)}Z"
  }

  private def offsetSeconds(zone: String): Long = zone match {
    case "Z" | "z" => 0L
    case other =>
      val sign    = if (other.startsWith("-")) -1L else 1L
      val digits  = other.drop(1).replace(":", "")
      val hours   = digits.take(2).toLong
      val minutes = digits.drop(2).toLong
      sign * (hours * 3600L + minutes * 60L)
  }

  private def isLeap(year: Long): Boolean = (year % 4L === 0L && year % 100L =!= 0L) || year % 400L === 0L

  private def daysInMonth(year: Long, month: Long): Long = month match {
    case 2L => if (isLeap(year)) 29L else 28L
    case 4L | 6L | 9L | 11L => 30L
    case _ => 31L
  }

  /** Howard Hinnant's days-from-civil algorithm. */
  private def daysFromCivil(year: Long, month: Long, day: Long): Long = {
    val y   = if (month <= 2L) year - 1L else year
    val era = Math.floorDiv(y, 400L)
    val yoe = y - era * 400L
    val mp  = (month + 9L) % 12L
    val doy = (153L * mp + 2L) / 5L + day - 1L
    val doe = yoe * 365L + yoe / 4L - yoe / 100L + doy
    era * 146097L + doe - 719468L
  }

  private def civilFromDays(days: Long): (Long, Long, Long) = {
    val z   = days + 719468L
    val era = Math.floorDiv(z, 146097L)
    val doe = z - era * 146097L
    val yoe = (doe - doe / 1460L + doe / 36524L - doe / 146096L) / 365L
    val y   = yoe + era * 400L
    val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L)
    val mp  = (5L * doy + 2L) / 153L
    val d   = doy - (153L * mp + 2L) / 5L + 1L
    val m   = if (mp < 10L) mp + 3L else mp - 9L
    (if (m <= 2L) y + 1L else y, m, d)
  }
}

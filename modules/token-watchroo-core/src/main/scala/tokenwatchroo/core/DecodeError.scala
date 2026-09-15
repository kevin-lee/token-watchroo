package tokenwatchroo.core

import cats.{Eq, Show}
import cats.derived.*

/** A JSON document that could not be decoded. */
enum DecodeError derives CanEqual, Eq, Show {
  case Invalid(message: String)
}

object DecodeError {
  def invalid(message: String): DecodeError = DecodeError.Invalid(message)

  extension (error: DecodeError) {
    def message: String = error match {
      case DecodeError.Invalid(message) => message
    }
  }
}

package tokenwatchroo.core

import cats.{Eq, Show}
import cats.derived.*
import cats.syntax.all.*

/** Where an agent snapshot came from. */
enum Source derives CanEqual, Eq, Show {
  case Api
  case LocalLog
}

object Source {
  def api: Source      = Source.Api
  def localLog: Source = Source.LocalLog

  extension (source: Source) {
    def wire: String = source match {
      case Source.Api => "api"
      case Source.LocalLog => "local-log"
    }
  }

  def parse(s: String): Either[String, Source] = s match {
    case "api" => Source.Api.asRight[String]
    case "local-log" => Source.LocalLog.asRight[String]
    case unknown => s"Unknown Source: $unknown".asLeft[Source]
  }
}

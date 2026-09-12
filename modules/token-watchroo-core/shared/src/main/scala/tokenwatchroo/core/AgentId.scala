package tokenwatchroo.core

import cats.{Eq, Show}
import cats.derived.*
import cats.syntax.all.*
import extras.render.Render

enum AgentId derives CanEqual, Eq, Show {
  case ClaudeCode
  case Codex
}

object AgentId {
  def claudeCode: AgentId = AgentId.ClaudeCode
  def codex: AgentId      = AgentId.Codex

  val all: List[AgentId] = List(AgentId.ClaudeCode, AgentId.Codex)

  extension (agentId: AgentId) {
    def wire: String = agentId match {
      case AgentId.ClaudeCode => "claude-code"
      case AgentId.Codex => "codex"
    }

    def displayName: String = agentId match {
      case AgentId.ClaudeCode => "Claude Code"
      case AgentId.Codex => "Codex"
    }
  }

  def parse(s: String): Either[String, AgentId] = s match {
    case "claude-code" => AgentId.ClaudeCode.asRight[String]
    case "codex" => AgentId.Codex.asRight[String]
    case unknown => s"Unknown AgentId: $unknown".asLeft[AgentId]
  }

  given render: Render[AgentId] = Render.render(_.displayName)
}

package tokenwatchroo.core

import cats.syntax.all.*
import hedgehog.*
import hedgehog.runner.*

object KeychainAccountSpec extends Properties {

  override def tests: List[Test] = List(
    property("a valid USER is the account", testValidUser),
    property("with no USER the login name is the account", testLoginName),
    property("a name with another character gives claude-code-user", testInvalidName),
    example("an invalid USER does not use the login name", testInvalidUserSkipsLoginName),
    example("no USER and no login name gives claude-code-user", testNoName),
    example("the login name is not evaluated when USER is valid", testLoginNameNotEvaluated),
  )

  private val genValidName: Gen[String] =
    Gen.string(Gen.choice1(Gen.alphaNum, Gen.element1('.', '_', '-')), Range.linear(1, 32))

  private def nameOf(account: KeychainAccount): String = account.value.value

  def testValidUser: Property =
    for {
      user      <- genValidName.log("user")
      loginName <- genValidName.option.log("loginName")
    } yield nameOf(KeychainAccount.claudeCode(user.some, loginName)) ==== user

  def testLoginName: Property =
    for {
      loginName <- genValidName.log("loginName")
    } yield nameOf(KeychainAccount.claudeCode(none[String], loginName.some)) ==== loginName

  def testInvalidName: Property =
    for {
      name    <- genValidName.log("name")
      invalid <- Gen.element1(' ', '@', '/', '+', 'ü').log("invalid")
      at      <- Gen.int(Range.linear(0, name.length)).log("at")
    } yield {
      val withInvalid = s"${name.take(at)}$invalid${name.drop(at)}"
      Result.all(
        List(
          KeychainAccount.claudeCode(withInvalid.some, none[String]) ==== KeychainAccount.ClaudeCodeFallback,
          KeychainAccount.claudeCode(none[String], withInvalid.some) ==== KeychainAccount.ClaudeCodeFallback,
        )
      )
    }

  def testInvalidUserSkipsLoginName: Result =
    KeychainAccount.claudeCode("a b".some, "alex".some) ==== KeychainAccount.ClaudeCodeFallback

  def testNoName: Result =
    KeychainAccount.claudeCode(none[String], none[String]) ==== KeychainAccount.ClaudeCodeFallback

  def testLoginNameNotEvaluated: Result =
    nameOf(KeychainAccount.claudeCode("alex".some, throw new AssertionError("loginName was evaluated"))) ==== "alex"
}

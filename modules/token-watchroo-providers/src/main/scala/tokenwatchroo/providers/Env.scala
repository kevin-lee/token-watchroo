package tokenwatchroo.providers

/** Environment lookups, injectable so tests never depend on the machine. */
trait Env {
  def get(name: String): Option[String]
  def home: Option[String] = get("HOME")

  /** The user name of the process, used only when `USER` is not set. */
  def loginName: Option[String] = None
}

object Env {
  val system: Env = new Env {
    override def get(name: String): Option[String] = Option(System.getenv(name)).filter(_.nonEmpty)

    override def loginName: Option[String] = Option(System.getProperty("user.name")).filter(_.nonEmpty)
  }

  def fromMap(values: Map[String, String]): Env = new Env {
    override def get(name: String): Option[String] = values.get(name).filter(_.nonEmpty)
  }
}

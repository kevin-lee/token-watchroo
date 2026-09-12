package tokenwatchroo.providers

/** Environment lookups, injectable so tests never depend on the machine. */
trait Env {
  def get(name: String): Option[String]
  def home: Option[String] = get("HOME")
}

object Env {
  val system: Env = new Env {
    override def get(name: String): Option[String] = Option(System.getenv(name)).filter(_.nonEmpty)
  }

  def fromMap(values: Map[String, String]): Env = new Env {
    override def get(name: String): Option[String] = values.get(name).filter(_.nonEmpty)
  }
}

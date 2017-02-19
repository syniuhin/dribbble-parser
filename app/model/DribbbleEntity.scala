package model

/**
  * Created by syniuhin with love <3.
  */
abstract sealed class Entity[T](val url: String) {
  def copy(url: String): Entity[T]
}

case class User(username: String, override val url: String = null) extends Entity[User](url) {
  override def copy(url: String): User = User(username, url)
}

case class Follower(id: Long, override val url: String = null) extends Entity[Follower](url) {
  override def copy(url: String): Follower = Follower(id, url)
}

case class Shot(id: Long, override val url: String = null) extends Entity[Shot](url) {
  override def copy(url: String): Shot = Shot(id, url)
}


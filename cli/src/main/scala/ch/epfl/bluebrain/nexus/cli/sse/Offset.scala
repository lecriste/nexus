package ch.epfl.bluebrain.nexus.cli.sse

import java.util.UUID

import scala.util.Try

/**
  * An offset for events.
  */
final case class Offset(value: UUID) {
  lazy val asString: String = value.toString
}

object Offset {

  /**
    * Attempts to create an [[Offset]] from the passed string value.
    */
  final def apply(string: String): Option[Offset] =
    Try(UUID.fromString(string)).toOption.map(Offset(_))
}

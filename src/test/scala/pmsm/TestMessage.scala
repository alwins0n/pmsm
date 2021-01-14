package pmsm

sealed trait TestMessage
object TestMessage {
  case object MessageA extends TestMessage
  case object MessageB extends TestMessage
}

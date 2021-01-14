package pmsm

case class SimpleState(value: Int) {
  def increment(): SimpleState = copy(value = value + 1)
  def setTo(int: Int): SimpleState = SimpleState(int)
}

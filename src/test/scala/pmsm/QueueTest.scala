package pmsm

import org.scalatest.funsuite.AnyFunSuite

class QueueTest extends AnyFunSuite {

  test("queuing messages test") {
    // setup dummy outputs
    var value = 1

    val store = Store(Nil)

    store.listen {
      case TestMessage.MessageA =>
        store.dispatch(TestMessage.MessageB)
        value = 2
      case TestMessage.MessageB =>
        if (value == 1) throw new IllegalStateException("message b was not queued")
        value = 3
    }

    store.dispatch(TestMessage.MessageA)

    assert(value === 3)
  }



}

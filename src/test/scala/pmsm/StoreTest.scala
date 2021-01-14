package pmsm
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable.ListBuffer

class StoreTest extends AnyFunSuite {

  test("store integration test") {
    // setup dummy outputs
    val globalSideEffect = ListBuffer.empty[String]
    val componentInput = ListBuffer.empty[Int]

    val store = Store(TestState(componentState = SimpleState(0), global = "init"))

    // setup one component with input and one global variable with side effect
    store.reduceMessage[TestMessage](s => {
      case TestMessage.MessageA => s.copy(componentState = s.componentState.increment())
      case TestMessage.MessageB => s.copy(componentState = s.componentState.setTo(7))
    })

    store.installListener[TestMessage.MessageB.type]((s, _) => {
      if (s.componentState.value == 7)
        store.dispatch(GlobalMessage)
    })

    store.select(_.componentState)(s => componentInput prepend s.value)
    store.installReducer[GlobalMessage.type] { (s, _) => s.copy(global = "changedOLD") }
    store.installReducer[GlobalMessage.type] { (s, _) => s.copy(global = "changed") }
    store.installListener[GlobalMessage.type] { (s, _) => globalSideEffect prepend s.global }

    assert(componentInput.isEmpty)

    // test initialize
    store.push()
    assert(componentInput.head == 0) // subscription
    assert(globalSideEffect.isEmpty) // only listener

    // test basic message
    store.dispatch(TestMessage.MessageA)
    assert(componentInput.head == 1) // change detected
    assert(globalSideEffect.isEmpty) // no adapter

    // test adapter
    store.dispatch(TestMessage.MessageB)
    assert(componentInput.head == 7) // change detected
    assert(globalSideEffect.head == "changed") // adapter causes GlobalMessage

    // test idempotence of subscriptions
    assert(componentInput.size == 3)
    assert(globalSideEffect.size == 1)
    store.dispatch(TestMessage.MessageB) // same message
    assert(componentInput.size == 3) // no input change
    assert(globalSideEffect.size == 2) // but side effect

    // test final change of state
    store.dispatch(TestMessage.MessageA)
    assert(componentInput.head == 8) // input change
    assert(componentInput.size == 4)
    assert(globalSideEffect.size == 2) // no side effect
  }

  case class TestState(componentState: SimpleState, global: String)

  case object GlobalMessage


}

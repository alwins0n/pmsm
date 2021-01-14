package pmsm

import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable.ListBuffer

class TypedStoreTest extends AnyFunSuite {

  test("typed store integration test") {
    // setup dummy outputs
    val globalSideEffect = ListBuffer.empty[String]
    val componentInput = ListBuffer.empty[Int]

    val store =
      Store.accepting[TestMessage].init(TestState(componentState = SimpleState(0), global = "init"))
    // setup one component with input and one global variable with side effect
    store.reduceMessage[TestMessage](s => {
      case TestMessage.MessageA => s.copy(componentState = s.componentState.increment())
      case TestMessage.MessageB => s.copy(componentState = s.componentState.setTo(7))
    })
    store.select(_.componentState)(s => componentInput prepend s.value)

    assert(componentInput.isEmpty)
    store.dispatch(TestMessage.MessageA)
    assert(componentInput.head == 1) // change detected
  }

  case class TestState(componentState: SimpleState, global: String)

}

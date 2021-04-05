package pmsm
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable.ListBuffer

class StoreTest extends AnyFunSuite {

  test("store integration test") {
    // setup dummy outputs
    val globalSideEffect = ListBuffer.empty[String]
    val componentInput = ListBuffer.empty[Int]

    val store =
      Store(TestState(componentState = SimpleState(0), global = "init"))

    // setup one component with input and one global variable with side effect
    store
      .select(_.componentState)
      .subscribe(cs => componentInput prepend cs.value)
      .modifying((s, cs) => s.copy(componentState = cs))
      .reduceMessage[TestMessage](cs => {
        case TestMessage.MessageA => cs.increment()
        case TestMessage.MessageB => cs.setTo(7)
      })
      .listenTo[TestMessage] {
        case TestMessage.MessageA => ()
        case TestMessage.MessageB =>
          if (store.state.componentState.value == 7)
            store.dispatch(GlobalMessage)
      }
      .delegate
      .lens(_.global)((s, g) => s.copy(global = g))
      .addMessageReducer[GlobalMessage.type]((_, _) => "changedOLD")
      .addMessageReducer[GlobalMessage.type]((_, _) => "changed")
      .listenTo[GlobalMessage.type] { _ =>
        globalSideEffect prepend store.state.global
      }

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

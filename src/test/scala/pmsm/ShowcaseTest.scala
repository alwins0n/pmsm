package pmsm

import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable.ListBuffer

class ShowcaseTest extends AnyFunSuite {

  test("showcase fluent api") {
    // dummy "components" to test output
    val counterComponentBuffer = ListBuffer.empty[Int]
    val alertBuffer = ListBuffer.empty[String]

    // "complex" message
    sealed trait CounterMessage
    object CounterMessage {
      case object Increment extends CounterMessage
      case object SetToDefaultValue extends CounterMessage
    }

    case object CheckCounter

    // event not dispatched by components but by external sources
    case object SevenWasConfirmedEvent

    // the test state
    case class AppState(component: CounterState, alertMessage: String)
    case class CounterState(value: Int) {
      def increment(): CounterState = copy(value = value + 1)
      def setTo(int: Int): CounterState = CounterState(int)
    }

    // kick off
    val init = AppState(component = CounterState(0), alertMessage = "")
    val store = Store(init)
    store
      .addListener(msg => println(s"DEBUG: msg received: $msg"))
      .addErrorHandler { (ex, state, msg) =>
        println(s"ERROR: error ${ex.getMessage} when handling $msg")
        state.copy(alertMessage = "An error occured")
      }
      .select(_.component) // focus into counter component
      .subscribe(updatedCounterState =>
        counterComponentBuffer prepend updatedCounterState.value
      )
      .modifying((state, counterState) => state.copy(component = counterState))
      .reduceMessage[CounterMessage](counterState => {
        case CounterMessage.Increment         => counterState.increment()
        case CounterMessage.SetToDefaultValue => counterState.setTo(7)
      })
      .listen {
        case CheckCounter =>
          if (store.state.component.value == 7) {
            // async call after which on success:
            store.dispatch(SevenWasConfirmedEvent)
          }
      }
      .delegate // back to root store
      .lens(_.alertMessage)((state, alert) => state.copy(alertMessage = alert))
      .subscribe(newMsg => alertBuffer prepend newMsg)
      .addMessageReducer[SevenWasConfirmedEvent.type]((_, _) =>
        "The 7 was confirmed"
      )

    // init all components by pushing the state manually
    store.push()

    // test
    assert(counterComponentBuffer.head == 0) // subscription
    assert(alertBuffer.head.isEmpty)
    assert(alertBuffer.size == 1)

    // test basic message
    store.dispatch(CounterMessage.Increment)
    assert(counterComponentBuffer.head == 1) // change detected
    assert(alertBuffer.head.isEmpty)

    store.dispatch(CounterMessage.SetToDefaultValue)
    assert(counterComponentBuffer.head == 7) // change detected
    assert(alertBuffer.head.isEmpty)

    store.dispatch(CheckCounter)
    assert(counterComponentBuffer.head == 7) // unchanged
    assert(alertBuffer.size == 2)
    assert(alertBuffer.head == "The 7 was confirmed")

    // test idempotence of subscriptions
    assert(counterComponentBuffer.size == 3)
    assert(alertBuffer.size == 2)
    store.dispatch(CounterMessage.SetToDefaultValue) // same message
    assert(counterComponentBuffer.size == 3) // no input change
    assert(alertBuffer.size == 2) // no input change

    // test final change of state
    store.dispatch(CounterMessage.Increment)
    assert(counterComponentBuffer.head == 8) // input change
    assert(counterComponentBuffer.size == 4)
    assert(alertBuffer.size == 2) // no side effect
  }

}

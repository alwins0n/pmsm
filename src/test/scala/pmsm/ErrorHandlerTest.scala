package pmsm

import org.scalatest.funsuite.AnyFunSuite

class ErrorHandlerTest extends AnyFunSuite {

  test("handling errors in reducer should not invoke listeners") {
    // setup dummy outputs
    var value = 1
    var hasError = false
    var error: Any = null
    var errorCause: Any = null
    var messageDisplay: String = null

    val store = Store(List.empty[String])

    val messageComponent: List[String] => Unit = msgs =>
      messageDisplay = msgs.mkString(", ")

    store.select(identity).subscribe(messageComponent)
    store.reduce(_ => throw new IllegalArgumentException)
    store.listen {
      case TestMessage.MessageA =>
        store.dispatch(TestMessage.MessageB)
        value = 2
    }
    store.addErrorListener((e, m) => {
      hasError = true
      errorCause = m
      error = e
    })

    store.addErrorHandler { (e, s, m ) =>
      ("error occured") :: s
    }

    store.dispatch(TestMessage.MessageA)

    assert(value === 1)
    assert(hasError)
    assertResult(TestMessage.MessageA)(errorCause)
    assert(error.isInstanceOf[IllegalArgumentException])
    assert(store.state.size == 1)
    assertResult("error occured")(messageDisplay)
  }



}

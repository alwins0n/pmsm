package pmsm

import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable.ListBuffer

class SlicedStoreTest extends AnyFunSuite {

  test("typed store integration test") {
    // setup dummy outputs
    val globalSideEffect = ListBuffer.empty[String]
    val componentInput = ListBuffer.empty[Int]

    val store = Store(TestState(SimpleState(1), "foo"))

    val selected = store.select(_.global)
    selected.subscribe(globalSideEffect.addOne)
    val modifying = selected.modifying((s, a) => s.copy(global = a))
    modifying.reduce(g => {
      case _ => g + "changed"
    })

    val lensed = store.lens(_.componentState)((s, a) => s.copy(componentState = a))

    lensed.reduce(compState => {
      case TestMessage.MessageA => compState.increment()
      case _                    => compState.setTo(0)
    })
    lensed.subscribe(s => componentInput.append(s.value))

    assert(componentInput.isEmpty)
    store.dispatch(TestMessage.MessageA)
    assert(componentInput.head == 2) // change detected
    assert(globalSideEffect.size == 1)
  }


  case class TestState(componentState: SimpleState, global: String)

}

package pmsm
import pmsm.Store._

import scala.annotation.tailrec
import scala.collection.mutable
import scala.reflect.ClassTag

/**
  * Entry point of "poor man's state management".
  * Use the constructor, apply or the factory methods `accepting` to instantiate.
  * Can be used as message accepting function (M => Unit)
  *
  * @param init initial state
  * @param historySize how many states are persisted in a fifo queue
  * @tparam S type of the State that should is managed
  * @tparam M type of the messages that should be manageable
  */
class Store[S, M](init: S, historySize: Int = minimumHistorySize)
    extends Dispatch[M] {

  require(
    historySize >= minimumHistorySize,
    s"history size must be at least $minimumHistorySize"
  )

  type Selector[A] = S => A
  private[Store] trait Subscription extends (S => Unit) {
    type Selection
    def select(state: S): Selection
    def use(s: Selection): Unit

    def apply(state: S): Unit = use(select(state))
  }
  type Listener = M => Unit
  type Reducer = (S, M) => S

  private var processing: Boolean = false
  private val messageQueue: mutable.Queue[M] = mutable.Queue.empty

  private var states: List[S] = List(init)
  private def currentState: S = states.head
  private def previousState: Option[S] = states.tail.headOption

  /**
    * @return the current state
    */
  def state: S = currentState

  /**
    * @return all previous states up to historySize - 1 (excluding the current state)
    */
  def history: List[S] = states.tail

  private var reducers: List[Reducer] = Nil
  private var subscriptions: List[Subscription] = Nil
  private var listeners: List[Listener] = Nil

  /**
    * subscribe to changes by selecting a slice of the state
    *
    * @param selector function to select a slice of the state
    * @param usage function to use a slice of the state
    * @tparam A type of the slice of the state
    */
  def select[A](selector: Selector[A])(usage: Downstream[A]): Unit = {
    subscriptions = new Subscription {
      override type Selection = A
      override def select(s: S): A = selector(s)
      override def use(a: A): Unit = usage(a)
    } :: subscriptions
  }

  /**
    * listens to a specified set of messages.
    *
    * usage:
    * {{{
    *   store.listen {
    *     case HandledMessage(...) => // perform side effect
    *     ...
    *   })
    * }}}
    *
    * a default case is not necessary, unspecified messages are simply not handled
    *
    * @param listen partial function of M => Unit
    */
  def listen(listen: PartialFunction[M, Unit]): Unit = {
    val lst: Listener = m => listen.applyOrElse(m, (_: M) => ())
    this.listeners = lst :: this.listeners
  }

  /**
    * listens to messages of a certain type.
    *
    * usage:
    * {{{
    *   store.listenTo[HandledMessage] {
    *     case SubTypeOfHandledMessage(...) => // perform side effect
    *     ...
    *   }
    * }}}
    *
    * note as opposed to [[Store.listen]] the case handling is checked for exhaustiveness
    *
    * @param listener side effecting function M1 => Unit
    * @tparam M1 the listened to message type
    */
  def listenTo[M1 <: M: ClassTag](listener: M1 => Unit): Unit = {
    val lst: Listener = {
      case a: M1 => listener(a)
      case _ => ()
    }
    this.listeners = lst :: this.listeners
  }

  /**
    * reduces the state by specifying handled messages.
    *
    * usage:
    * {{{
    *   store.reduce(s => {
    *     case HandledMessage(...) => s.copy(...)
    *      ...
    *   })
    * }}}
    *
    * a default case is not necessary, unspecified messages do not modify the state
    *
    * @param reducer a "partial" version of S => M => S
    */
  def reduce(reducer: S => PartialFunction[M, S]): Unit = {
    val red: Reducer = (s, m) => reducer(s).lift(m).getOrElse(s)
    this.reducers = red :: reducers
  }

  /**
    * reduces the state by specifying the message handled.
    *
    * usage:
    * {{{
    *   store.reduceMessage[HandledMessage](s => {
    *     case SubTypeOfHandledMessage(...) => s.copy(...)
    *      ...
    *   })
    * }}}
    *
    * note as opposed to [[Store.reduce]] the case handling is checked for exhaustiveness
    *
    * @param reducer reducing function (S, M) => S in the curried form of S => M1 => S
    * @tparam M1 the reduced message type
    */
  def reduceMessage[M1 <: M: ClassTag](reducer: S => M1 => S): Unit = {
    val red: Reducer = (s, m) =>
      m match {
        case a: M1 => reducer(s)(a)
        case _     => s
      }
    this.reducers = red :: reducers
  }

  /**
    * adds a reducer in the simplest form (s, m) => s
    *
    * eg:
    * {{{
    *   store.installReducer[HandledMessage] { (s, m) =>
    *      ... // deal with m, guaranteed to be of type M1
    *   }
    * }}}
    *
    * @param reducer the typed reducing function
    * @tparam M1 the reduced message type
    */
  def installReducer[M1 <: M: ClassTag](reducer: (S, M1) => S): Unit =
    reduceMessage(reducer.curried)

  /**
    * @see [[dispatch]]
    */
  override def apply(m: M): Unit = dispatch(m)

  /**
    * dispatches a message to the store. causes state to be reduced
    * and listeners as well as subscriptions (select) to be invoked with the reduced state.
    *
    * @param message any message of acceptable type
    */
  def dispatch(message: M): Unit = {
    if (processing) {
      messageQueue.enqueue(message)
    } else {
      processing = true
      try {
        process(message)
      } finally {
        processing = false
      }
    }
  }

  @tailrec
  private def process(message: M): Unit = {
    val reduced = reduceMessage(currentState, message)

    states = (reduced :: states).take(historySize)

    pushChanges()
    for (listener <- listeners.reverse)
      listener.apply(message)

    if (messageQueue.nonEmpty)
      process(messageQueue.dequeue())
  }

  private def reduceMessage(state: S, m: M): S =
    reducers.foldRight(state)((r, s) => r(s, m))

  private def pushChanges(): Unit = {
    subscriptions.foreach { subscription =>
      val slice = subscription.select(currentState)
      val previous = previousState.map(subscription.select)
      val hasChanged = !previous.contains(slice)
      if (hasChanged) {
        subscription.use(slice)
      }
    }
  }

  /**
    * invokes all subscriptions with the current state
    */
  def push(): Unit = subscriptions.foreach { _.apply(currentState) }
}

object Store {
  private val minimumHistorySize = 2

  def apply[S](init: S, historySize: Int = minimumHistorySize): Store[S, Any] =
    new Store(init, historySize)

  /**
    * builder factory function. usage e.g.
    *
    * {{{
    *   Store.accepting[Event].reducing(State()) { (s, m) =>
    *    ... // handle all cases of type Event
    *   }
    * }}}
    *
    * @tparam M the message type to be handled
    * @return builder for a {{Store}} capable of handling messages of type M
    */
  def accepting[M : ClassTag]: StoreBuilder[M] = new StoreBuilder[M]

  class StoreBuilder[M : ClassTag] {
    def init[S](s: S): Store[S, M] = this(s)
    def apply[S](s: S): Store[S, M] = new Store(s)
    def reducing[S](s: S)(reducer: (S, M) => S): Store[S, M] = {
      val store = apply(s)
      store.installReducer[M] { (st, m) => reducer(st, m) }
      store
    }
  }

  type Dispatch[M] = M => Unit
  type Downstream[T] = T => Unit
}

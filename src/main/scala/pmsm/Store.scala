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
    extends Reducing[S, M]
    with Consuming[S]
    with Listening[M] {

  require(
    historySize >= minimumHistorySize,
    s"history size must be at least $minimumHistorySize"
  )

  type StateSelector[A] = Selector[S, A]
  type StateModifier[A] = Modifier[S, A]
  type StateSubscription = Subscription[S]

  type ErrorListener = (Throwable, M) => Unit
  type ErrorHandler = (Throwable, S, M) => S

  private var processing: Boolean = false
  private val messageQueue: mutable.Queue[M] = mutable.Queue.empty

  private var states: List[S] = List(init)
  private def currentState: S = states.head
  private def previousState: Option[S] = states.tail.headOption

  override def state: S = currentState

  /**
    * @return all previous states up to historySize - 1 (excluding the current state)
    */
  def history: List[S] = states.tail

  private var reducers: List[Reducer] = Nil
  private var subscriptions: List[StateSubscription] = Nil
  private var listeners: List[Listener] = Nil
  private var errorHandler: List[ErrorHandler] = Nil

  /**
    * subscribe to changes by selecting a slice of the state
    *
    * @param selector function to select a slice of the state
    * @tparam A type of the slice of the state
    */
  def select[A](selector: StateSelector[A]): Store.Selected[S, M, A] =
    new Store.Selected[S, M, A](selector, this)

  def lens[A](
      get: StateSelector[A]
  )(mod: StateModifier[A]): Store.Lensed[S, M, A] =
    new Store.Lensed(get, mod, this)

  def addSubscription(subscription: StateSubscription): this.type = {
    this.subscriptions = subscription :: subscriptions
    this
  }

  override def subscribe(sub: Downstream[S]): this.type = {
    addSubscription(Subscription(identity, sub))
    this
  }

  override def addListener(listener: Listener): this.type = {
    this.listeners = listener :: this.listeners
    this
  }

  override def addReducer(reducer: (S, M) => S): this.type = {
    this.reducers = reducer :: reducers
    this
  }

  /**
    * @see [[dispatch]]
    */
  def apply(m: M): this.type = dispatch(m)

  /**
    * dispatches a message to the store. causes state to be reduced
    * and listeners as well as subscriptions (select) to be invoked with the reduced state.
    *
    * if multiple messages are received by the store within the same digest cycle
    * they are queued to ensure correct ordering/behaviour
    *
    * @param message any message of acceptable type
    */
  def dispatch(message: M): this.type = {
    if (processing) {
      messageQueue.enqueue(message)
    } else {
      processing = true
      try {
        process(message)
      } catch {
        case e: Throwable => handleError(e, message)
      } finally {
        processing = false
      }
    }
    this
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

  private def handleError(error: Throwable, causingMessage: M): Unit = {
    if (errorHandler.isEmpty)
      throw error
    else {
      val stateAfterError = errorHandler.foldRight(state) { (handler, s) =>
        handler(error, s, causingMessage)
      }
      states = stateAfterError :: states
      pushChanges()
    }
  }

  /**
    * invokes all subscriptions with the current state
    */
  def push(): this.type = {
    subscriptions.foreach { s =>
      (s.select _).andThen(s.use).apply(currentState)
    }
    this
  }

  /**
    * installs a side effecting error listener that is invoked when
    * an error in the message processing occurs.
    *
    * errors within handler functions are not further processed and are rethrown
    *
    * @param listener the callback
    */
  def addErrorListener(listener: ErrorListener): this.type = {
    val asHandler: ErrorHandler = (e, s, m) => {
      listener(e, m)
      s
    }
    this.errorHandler = asHandler :: errorHandler
    this
  }

  /**
    *  installs a state returning error handler that is invoked when
    * an error in the message processing occurs.
    *
    * errors within handler functions are not further processed and are rethrown
    *
    * @param handler the callback
    */
  def addErrorHandler(handler: ErrorHandler): this.type = {
    this.errorHandler = handler :: errorHandler
    this
  }
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
  def accepting[M: ClassTag]: StoreBuilder[M] = new StoreBuilder[M]

  class StoreBuilder[M: ClassTag] {
    def init[S](s: S): Store[S, M] = this(s)
    def apply[S](s: S): Store[S, M] = new Store(s)
    def reducing[S](s: S)(reducer: (S, M) => S): Store[S, M] = {
      val store = apply(s)
      store.addMessageReducer[M] { (st, m) => reducer(st, m) }
      store
    }
  }

  type Downstream[T] = T => Unit

  type Selector[S, S1] = S => S1
  type Modifier[S, S1] = (S, S1) => S

  trait Subscription[S] {
    type Selection
    def select(state: S): Selection
    def use(s: Selection): Unit
  }
  object Subscription {
    def apply[S, S1](
        selector: Selector[S, S1],
        consumer: Downstream[S1]
    ): Subscription[S] =
      new Subscription[S] {
        override type Selection = S1
        override def select(state: S): S1 = selector(state)
        override def use(selection: S1): Unit = consumer(selection)
      }
  }

  trait Consuming[S] {

    /**
      * @return the current state
      */
    def state: S

    /**
      * subscribe to changes of S
      * @param sub a downstream consumer function
      */
    def subscribe(sub: Downstream[S]): this.type
  }

  trait Listening[M] {

    type Listener = M => Unit

    /**
      * adds a listener for M in the simplest form of M => Unit
      *
      * @param listener the (side effecting) listener
      */
    def addListener(listener: Listener): this.type

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
    def listen(listen: PartialFunction[M, Unit]): this.type = {
      val lst: Listener = m => listen.applyOrElse(m, (_: M) => ())
      addListener(lst)
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
    def listenTo[M1 <: M: ClassTag](listener: M1 => Unit): this.type = {
      val lst: Listener = {
        case a: M1 => listener(a)
        case _     => ()
      }
      addListener(lst)
    }
  }

  trait Reducing[S, M] {

    type Reducer = (S, M) => S

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
    def reduce(reducer: S => PartialFunction[M, S]): this.type =
      addReducer((s, m) => reducer(s).lift(m).getOrElse(s))

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
    def reduceMessage[M1 <: M: ClassTag](reducer: S => M1 => S): this.type =
      addReducer((s, m) =>
        m match {
          case a: M1 => reducer(s)(a)
          case _     => s
        }
      )

    /**
      * adds a typed reducer in the simplest form (s, m) => s
      *
      * eg:
      * {{{
      *   store.addMessageReducer[HandledMessage] { (s, m) =>
      *      ... // deal with m, guaranteed to be of type HandledMessage
      *   }
      * }}}
      *
      * @param reducer the typed reducing function
      * @tparam M1 the reduced message type
      */
    def addMessageReducer[M1 <: M: ClassTag](reducer: (S, M1) => S): this.type =
      reduceMessage(reducer.curried)

    /**
      * adds a reducer in the simplest form (s, m) => s
      *
      * eg:
      * {{{
      *   store.installReducer[HandledMessage] { (s, m) =>
      *      ...
      *   }
      * }}}
      *
      * @param reducer the typed reducing function
      */
    def addReducer(reducer: Reducer): this.type
  }

  class Selected[S, M, S1](
      selector: Selector[S, S1],
      val delegate: Store[S, M]
  ) extends Consuming[S1]
      with Listening[M] {
    override def state: S1 = selector(delegate.state)

    override def subscribe(sub: Downstream[S1]): this.type = {
      delegate.addSubscription(Subscription(selector, sub))
      this
    }

    override def addListener(listener: Listener): this.type = {
      delegate.addListener(listener)
      this
    }

    def modifying(mod: Modifier[S, S1]): Lensed[S, M, S1] =
      new Lensed(selector, mod, delegate)
  }

  class Lensed[S, M, S1](
      selector: Selector[S, S1],
      modifier: Modifier[S, S1],
      override val delegate: Store[S, M]
  ) extends Selected[S, M, S1](selector, delegate)
      with Reducing[S1, M] {
    override def addReducer(reducer: (S1, M) => S1): this.type = {
      delegate.addReducer((s, m) => modifier(s, reducer(selector(s), m)))
      this
    }

  }
}

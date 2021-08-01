# PMSM - Poor Man's State Management

A super lightweight library for "redux-like" development in scala.js

---

# Motivation
While there are existing state management solutions for scala.js (Diode, redux)
my requirements for a project were too simple to throw a complex framework at it

# Design Goals
- simplicity, ease of use and ease of understanding (no magic imports, etc.)
- scala.js idiomatic
- leverage FP but be pragmatic (don't obsess with purity, effect tracking, ...)
- minimal/clean API (make common things easy and specialized things possible)

# Basic Usage

*TODO artifact coordinates*

---

Get started by instantiating a `Store[S, M]`.
Store is a (stateful) central processing unit typed to hold a state `S` 
and accept messages of type `M`.

```scala
val store = Store(MyState()) // : Store[MyState, Any]
```

creates a new "generic" store. It is capable of handling any type of message.
It can be useful to narrow acceptable messsage down to a type.

Use

```scala
val store = new Store[MyState, MyMessage](MyState())
```

or

```scala
val store = Store.accepting[MyMessage].init(MyState())
```

to create a `Store[MyState, MyMessage]`.

While the state can be any class, it is recommended to be a case class with sensible defaults.
e.g.


```scala
case class MyState(alerts: List[String] = Nil, ...)
```

*Note that in order for changes to be detected
`equals` is called on the state or parts thereof. 
Which is why it is recommended to use pure values/data*

## Input

We now can send messages to the store of the acceptable type via `dispatch` 
or just using the apply method.
Once instantiated, a Store is a function `M => Unit` from usage side.

```scala
store.dispatch(SomeMessage("param"))
// or
store(SomeMessage("param"))
```

## Behaviour

Behaviour is installed in the store by adding reducers.
Have a look at the internal definition of a store's reducer:

```scala
type Reducer = (S, M) => S 
```

Add behaviour to the store by defining a new reducer:

```scala
store.addReducer { (s, m) => 
  ... // use m and return updated s
}
```

The `Reducer` will match on the message to decide on how to update the state 
as well as provide a default (the untouched state) for all unhandled cases.

Since this is a common pattern it is syntactially improved by using a curried reducer.

```scala
type ReducerCurried = S => M => S 
```

This is the curried form of a
pure function which takes a state and a message
and returns the resulting or "next" state.

It can be installed as
`S => PartialFunction[M, S]` which gives us the possibility
to non-exhaustively list all messages that should be handled.

```scala
store.reduce(state => {
   case ChangeName(newName) => state.copy(nameState = ...)
   case OtherMessage => ...
   // no default case necessary since there is no exhaustiveness check
})
```

It can be read as: "given a `state` use it to handle all listed messages"

### Behaviour Details

After a message is received by the store all `Reducers` 
are invoked in the order of registration. That implies that later 
registered state transitions of some message M
can react to previously installed behaviour (state changes). 
The reduction step is performed "transactionally" 
i.e. if an error occurs the state is left
unchanged (or rolled back if you will). 

The state after each invocation 
is fed into the next `Reducer` resulting in a `foldLeft` semantic.

## Output

The current state which is the last sucessfully reduced state is
available via `store.state`.

Furthermore the downstream of the store can take two forms:
`Subscription` (targeted state downstream) or 
`Listener` (targeted message downstream).

### Subscription

If the state changes due to reduction 
the changed state can be consumed by subscribers. 
They take the form `S => Unit` and can be created as follows:

```scala
store.subscribe { state => 
  ... // use changed state
}
```

Change detection works via deep equals on the state, as mentioned before.

### Listeners

The `Listener` is a side-effecting function which gives the store
the capability to react to messages.

```scala
type Listener = M => Unit
```

Similar to `addReducer` installing a listener is done by calling:

```scala
store.addListener { m => 
  ... // use m
}
```

And similar to `reduce` the `listen` API handles messages non-exhaustive.

```scala
store.listen { 
  case Handled(param) => ...
}
```

Dispatching a message from within a listener is possible and encouraged 
(e.g. for messages resulting in ajax calls). 

*Note that if the current "digest" is not finished
(not all listeners are processed) the dispatched message 
is queued to ensure a clean ordering of messages.*

# Advanced Usage

The previous sections introduced concepts and APIs which are useful only
in the simplest of use cases.

If state and messages grow in complexity adding behaviour becomes cumbersome, 
amount of messages that are not handled properly increases 
and state change detection is always global.

There are APIs to zoom in on state and message types to make the store
behave more robust and sensible while keeping the boilerplate to a minimum

## Message Selection

Assume there is a component capable of issuing a `Message` 
having two concrete types (message is a "sum-type"):

```scala
// i.e. Message = ChangeName | ConfirmName
sealed trait ComponentMessage
object ComponentMessage {
  final case class ChangeName(newName: String) extends ComponentMessage
  case object ConfirmName extends ComponentMessage
}

```

to install a reducer for a particular message type use `addMessageReducer`

```scala
store.addMessageReducer[ChangeName] { (state, message) => // message is guaranteed to be of type "ChangeNamed"
    state.copy(nameState = message.newName)
    ...
})
```


To use the curried function syntax introduced earlier, but
benefit from type safety (i.e. exhaustiveness matching) use `reduceMessage`.

```scala
store.reduceMessage[ComponentMessage](state => {
    case ChangeName(newName) => state.copy(nameState = ...)
    // warning/error since ConfirmName is not handled
})
```

---

Listeners can also installed pre-selecting messages to be handled with `listenTo`.

```scala
store.listenTo[ChangeName] { message => // mesage is guaranteed ot be of type ChangeName
  console.log(s"name ${message.name} has been entered and processed to be ${store.state.sanitizedName}")
}
```

Note that the reduced state after receiving the message `ChangeName`
is accessed via `store.state` in the listener.

## State Optics

Subscriptions without specific change detection have limits in their usefulnes.
In general we are only interested in certain changes of the state.

This is why a subscription is actually function `S => Unit` composed of two functions
- `S => A` (selection)
- `A => Unit` (consumption)

Assume we have a state `case class State(componentState = ComponentState(), ...)`
and we want to focus on the component substate.

To create a subscription we first `select` a slice of the state
and `subscribe` to changes:

```scala
store.select(_.componentState).subscribe { cs => 
  ... // use changed componentState cs
}
```

If your downstream consumers are functions or side effecting methods,
subscriptions become

```scala
store.select(_.componentState).subscribe(myComponent.update)
```

If a message is received by the store, after the state is reduced
the following mechanics are invoked:

- compute the slice of each subscription (selection)
- compare each slice with the slice of the previous state
- if changed, invoke the downstream function (consumption) with the slice as parameter

`select` thus slices the state for readonly functionality with a "getter" function
To "upgrade" the slice for adding behaviour use `modifying` which takes a "setter" function

```scala
val sliced = store
  .select(_.componentState)
  .modifying((s, cs) => s.copy(componentState = cs))
// results in something like `Store[ComponentState, M]
```

"Getter" and "setter" can also be combined with the `lens` method

```scala
val lensed = store.lens(_.componentState)((s, a) => s.copy(componentState = a))
// equivalent to "sliced"
```

This state is now able to add reducers and subscribe to changes based on the slice of the state

```scala
lensed.reduce(compState => {
  case SomeMessage => compState.doFoo() // returns a ComponentState
    ...
})
```

```scala
lensed.subscribe(myComponent.update)
```

# Error Handling

`Store` offers two APIs for error handling: `addErrorListener` and `addErrorHandler`

Any exceptions occuring in the reduction steps or at downstream consumption
can be either consumed for e.g. logging (`addErrorListener`)
or used to update the state in a certain way e.g. alerts (`addErrorHandler`)

The functions dealing with errors are assumed to be fail safe - any exceptions
will not be handled further.

# Full Example with Fluent API

```scala    
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
  .addMessageReducer[SevenWasConfirmedEvent.type]((_, _) => "The 7 was confirmed")

// init all components by pushing the state manually
store.push()
```

# Cookbook

## Usage for HTML output

No assumptions on output formatting is been made here. Use your subscriptions
as you wish. It could be HTML outputting or console logging, etc.

## Functional Initialisation

To initialize the store "in one go" and have typesafe (exhaustiveness)
reduction, use the builder DSL

```scala
val store = Store.accepting[MyMessage].reducing(MyState(...))((s, m) => m match {
   ...
}) // = Store[MyState, MyMessage]
```

## Async

*TODO* describe better

There is no extra magic to support async behaviour.
Use a `Listener` to catch messages that should result in async calls (eg. XHR)
and feed the result back into the store by dispatching to it in the async callback.

## Connect multiple Stores (Parent - Child)

*TODO* better example via listeners and dispatch?

```scala
val parent = Store(Nil) // accepts Any message
val child = Store.accepting[TestMessage].init(Nil)

child.addListener(parent) // parent is now notified by all child messages
```
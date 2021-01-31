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

# Usage

*TODO artifact coordinates*

---

Get started by instantiating a `Store[S, M]`.
Store is a (stateful)
central processing unit typed to hold a state `S` 
and accept messages of type `M`.

```scala
val store = new Store[MyState, MyMessage](MyState())
```

or preferably

```scala
val store = Store.accepting[MyMessage].init(MyState())
```

If the type of the messages is not important or even should be ignored 
(since the messages may eventually stem from components 
independent from one another, not sharing a supertype) 
a "generic" Store can be constructed via just an initial state.

```scala
val store = Store(MyState()) // : Store[MyState, Any]
```

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

The following examples assumes some software component
which is capable of issuing a `Message` having two concrete types
(message is a "sum-type"):
```scala
// i.e. Message = NameChange | NameConfirmation
sealed trait ComponentMessage
object ComponentMessage {
  final case class NameChange(newName: String) extends ComponentMessage
  case object NameConfirmation extends ComponentMessage
}
```

## Behaviour

Behaviour is installed in the store by adding reducers.
Have a look at the internal definition of a store's reducer

```scala
type Reducer = (S, M) => S 
```

to install a reducer for a particular message type use `installReducer`

```scala
store.installReducer[NameChange] { (state, message) => // message is guaranteed to be of type "NameChanged"
    state.copy(nameState = message.newName)
    ...
})
```

While this is a nice way to add behaviour for a simple message,
`Store` offers APIs to handle add complex behaviour in an easy way.

The `Reducer` is the curried form of a 
pure function which takes a state and a message
and returns the resulting or "next" state.

```scala
type ReducerCurried = S => M => S 
```

Enter the methods `reduce` and `reduceMessage`

`reduce` is flexible and convenient syntactically. 
A `Reducer` in its curried form can be installed as
`S => PartialFunction[M, S]` which gives us the possibility
to non-exhaustively list all messages that should be handled. 

So an example could be:

```scala
store.reduce(state => {
   case NameChange(newName) => state.copy(nameState = ...)
   case OtherMessage => ???
   ...
   // NameConfirmed does not need to be handled and leaves the state unchanged
})
```

and can be read as: "given a state `state` use it to reduce all listed messages"

Whereas by using `reduceMessage` you may exactly specify what
type of message handling is added to the store and
benefit from type safety (e.g. exhaustiveness matching)

An example of handling all messages issued by the component:

```scala
store.reduceMessage[ComponentMessage](state => {
    case NameChange(newName) => state.copy(nameState = ...)
    // warning/error since NameConfirmed is not handled
})
```

After a message is received by the store all `Reducers` 
are invoked in the order of registration in one 
reduction step "transactionally" (TODO explain). 
That implies that later registered state transitions of some message M
are possibily overriding previously installed behaviour.

The state after each invocation 
is fed into the next `Reducer` resulting in a `foldLeft` semantic.

## Output

The current state which is the last sucessfully reduced state is
available via `store.state`.

Furthermore the downstream of the store can take two forms:
`Subscription` (targeted state downstream) or 
`Listener` (targeted message downstream).

### Subscription

Via subscriptions (sliced) state changes can be consumed.

A subscription is composed of essentially two functions
`S => A` (selection) and `A => Unit` (side effecting usage) 
as one (`S => Unit`).
`A` is a slice of the state or "`Selection`". 
Internally a trait is used

```scala
trait Subscription extends (S => Unit) {
  type Selection
  ...
}
```

If a message is received by the store, after the state is reduced 
the following mechanics are invoked:

- compute the slice of each subscription
- compare each slice with the slice of the previous state
- if changed, invoke the subscription (usage) with the slice as parameter

The API of create a subscription (`select(..)`) is thus:

```scala
// assume a state with field "componentState"
store.select(_.componentState) { cs => 
  ... // use changed componentState cs
}
```

Which creates a subscription of `MyState.componentState` 
and pushes changes to the handler function of type `A => Unit` (`cs => ...`)
effectively creating a conditional side effecting function invocation `S => Unit`

If your downstream consumers are functions or side effecting methods,
subscriptions become

```scala
store.select(_.componentState)(myComponent.update)
```

### Listeners

The `Listener` is a side-effecting function which gives the store
the capability to react to messages.

```scala
type Listener = M => Unit
```

To install listeners, `Store` offers `listen` and `listenTo`
behaving similar to `reduce` and `reduceMessage`.

e.g.

```scala
store.listenTo[NameChange] { message =>
  console.log(s"name ${message.name} has been entered and processed to be ${store.state.sanitizedName}")
}
```

Note that the reduced state after receiving the message `NameChange` 
is accessed via `store.state`.

 `listen` again is accepting a parameter of type
`PartialFunction[M, Unit]` allowing to handle cases of `M`
within one syntactical closure, whereas `listenTo` allows for exhaustiveness checks

Dispatching a message from within a listener is possible and encouraged 
(e.g. for messages resulting in ajax calls). If the current "digest" is not finished
(not all listeners are processed) the dispatched message is queued to ensure a 
clean ordering of messages.

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
Use a `Listener` to catch messages that results in async calls (eg. XHR)
and feed the result back into the store by dispatching to it in the async callback.

*TODO* example 

## Connect multiple Stores (Parent - Child)

*TODO* example via listeners and dispatch

# TODO for Future
- error channel? catch non reduced? allow reducers to return a defined boundary (Try)?
- force error handler on listeners?
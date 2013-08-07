rediscala [![Build Status](https://travis-ci.org/etaty/rediscala.png)](https://travis-ci.org/etaty/rediscala) [![Coverage Status](https://coveralls.io/repos/etaty/rediscala/badge.png?branch=master)](https://coveralls.io/r/etaty/rediscala?branch=master)
=========

A [Redis](http://redis.io/) client for Scala (2.10+) and (AKKA 2.2+) with non-blocking and asynchronous I/O operations.

 * Reactive : Redis requests/replies are wrapped in Futures.

 * Typesafe : Redis types are mapped to Scala types.

 * Fast : Rediscala uses redis pipelining. Blocking redis commands are moved into their own connection. 
A worker actor handles I/O operations (I/O bounds), another handles decoding of Redis replies (CPU bounds).

### Set up your project dependencies

If you use SBT, you just have to edit `build.sbt` and add the following:

```scala
resolvers += "rediscala" at "https://github.com/etaty/rediscala-mvn/raw/master/snapshots/"

libraryDependencies ++= Seq(
  "com.etaty.rediscala" %% "rediscala" % "0.6-SNAPSHOT"
)
```

### Connect to the database

```scala
import redis.RedisClient
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

object Main extends App {
  implicit val akkaSystem = akka.actor.ActorSystem()

  val redis = RedisClient()

  val futurePong = redis.ping()
  println("Ping sent!")
  futurePong.map(pong => {
    println(s"Redis replied with a $pong")
  })
  Await.result(futurePong, 5 seconds)

  akkaSystem.shutdown()
}
```

### Basic Example

https://github.com/etaty/rediscala-demo

You can fork with : `git clone git@github.com:etaty/rediscala-demo.git` then run it, with `sbt run`


### Redis Commands

Supported :
* [Keys](http://redis.io/commands#generic) ([scaladoc](http://etaty.github.io/rediscala/latest/api/index.html#redis.commands.Keys))
* [Strings](http://redis.io/commands#string) ([scaladoc](http://etaty.github.io/rediscala/latest/api/index.html#redis.commands.Strings))
* [Hashes](http://redis.io/commands#hash) ([scaladoc](http://etaty.github.io/rediscala/latest/api/index.html#redis.commands.Hashes))
* [Lists](http://redis.io/commands#list) 
  * non-blocking ([scaladoc](http://etaty.github.io/rediscala/latest/api/index.html#redis.commands.Lists))
  * blocking ([scaladoc](http://etaty.github.io/rediscala/latest/api/index.html#redis.commands.BLists))
* [Sets](http://redis.io/commands#set) ([scaladoc](http://etaty.github.io/rediscala/latest/api/index.html#redis.commands.Sets))
* [Sorted Sets](http://redis.io/commands#sorted_set) ([scaladoc](http://etaty.github.io/rediscala/latest/api/index.html#redis.commands.SortedSets))
* [Pub/Sub](http://redis.io/commands#pubsub) ([scaladoc](http://etaty.github.io/rediscala/latest/api/index.html#redis.commands.Publish))
* [Transactions](http://redis.io/commands#transactions) ([scaladoc](http://etaty.github.io/rediscala/latest/api/index.html#redis.commands.Transactions))
* [Connection](http://redis.io/commands#connection) ([scaladoc](http://etaty.github.io/rediscala/latest/api/index.html#redis.commands.Connection))

Soon :
* [Scripting](http://redis.io/commands#scripting)
* [Server](http://redis.io/commands#server) ([scaladoc](http://etaty.github.io/rediscala/latest/api/index.html#redis.commands.Server)) (work in progress)


### Blocking commands

[RedisBlockingClient](http://etaty.github.io/rediscala/latest/api/index.html#redis.RedisBlockingClient) is the instance allowing access to blocking commands :
* blpop
* brpop
* brpopplush

```scala
  redisBlocking.blpop(Seq("workList", "otherKeyWithWork"), 5 seconds).map(result => {
    result.map(_.map({
      case (key, work) => println(s"list $key has work : ${work.utf8String}")
    }))
  })
```
Full example: [ExampleRediscalaBlocking](https://github.com/etaty/rediscala-demo/blob/master/src/main/scala/ExampleRediscalaBlocking.scala)

You can fork with: `git clone git@github.com:etaty/rediscala-demo.git` then run it, with `sbt run`


### Transactions

The idea behind transactions in Rediscala is to start a transaction outside of a redis connection.
We use the [TransactionBuilder](http://etaty.github.io/rediscala/latest/api/index.html#redis.commands.TransactionBuilder) to store call to redis commands (and for each command we give back a future).
When `exec` is called, `TransactionBuilder` will build and send all the commands together to the server. Then the futures will be completed.
By doing that we can use a normal connection with pipelining, and avoiding to trap a command from outside, in the transaction...

```scala
  val redisTransaction = redis.transaction() // new TransactionBuilder
  redisTransaction.watch("key")
  val set = redisTransaction.set("key", "abcValue")
  val decr = redisTransaction.decr("key")
  val get = redisTransaction.get("key")
  redisTransaction.exec()
```

Full example: [ExampleTransaction](https://github.com/etaty/rediscala-demo/blob/master/src/main/scala/ExampleTransaction.scala)

You can fork with : `git clone git@github.com:etaty/rediscala-demo.git` then run it, with `sbt run`

[TransactionsSpec](https://github.com/etaty/rediscala/blob/master/src/test/scala/redis/commands/TransactionsSpec.scala) will reveal even more gems of the API.

### Pub/Sub

You can use a case class with callbacks [RedisPubSub](http://etaty.github.io/rediscala/latest/api/index.html#redis.RedisPubSub)
or extend the actor [RedisSubscriberActor](http://etaty.github.io/rediscala/latest/api/index.html#redis.actors.RedisSubscriberActor) as shown in the example below

```scala
object ExamplePubSub extends App {
  implicit val akkaSystem = akka.actor.ActorSystem()

  val redis = RedisClient()

  // publish after 2 seconds every 2 or 5 seconds
  akkaSystem.scheduler.schedule(2 seconds, 2 seconds)(redis.publish("time", System.currentTimeMillis()))
  akkaSystem.scheduler.schedule(2 seconds, 5 seconds)(redis.publish("pattern.match", "pattern value"))
  // shutdown Akka in 20 seconds
  akkaSystem.scheduler.scheduleOnce(20 seconds)(akkaSystem.shutdown())

  val channels = Seq("time")
  val patterns = Seq("pattern.*")
  // create SubscribeActor instance
  akkaSystem.actorOf(Props(classOf[SubscribeActor], channels, patterns).withDispatcher("rediscala.rediscala-client-worker-dispatcher"))

}

class SubscribeActor(channels: Seq[String] = Nil, patterns: Seq[String] = Nil) extends RedisSubscriberActor(channels, patterns) {
  override val address: InetSocketAddress = new InetSocketAddress("localhost", 6379)

  def onMessage(message: Message) {
    println(s"message received: $message")
  }

  def onPMessage(pmessage: PMessage) {
    println(s"pattern message received: $pmessage")
  }
}
```

Full example: [ExamplePubSub](https://github.com/etaty/rediscala-demo/blob/master/src/main/scala/ExamplePubSub.scala)

You can fork with : `git clone git@github.com:etaty/rediscala-demo.git` then run it, with `sbt run`

[RedisPubSubSpec](https://github.com/etaty/rediscala/blob/master/src/test/scala/redis/RedisPubSubSpec.scala.scala) will reveal even more gems of the API.


### Scaladoc

[Rediscala scaladoc API](http://etaty.github.io/rediscala/latest/api/index.html#package)

### Performance

More than 200 000 requests/second

* [benchmark result from scalameter](http://bit.ly/12QZsRs)
* [sources directory](https://github.com/etaty/rediscala/tree/master/src/benchmark/scala/redis/bench)

The hardware used is a macbook retina (Intel Core i7, 2.6 GHz, 4 cores, 8 threads, 8GB)

You can run the bench with :

1. clone the repo `git clone git@github.com:etaty/rediscala.git`
2. run `sbt bench:test`
3. open the bench report `rediscala/tmp/report/index.html`


package redis

import akka.actor.{Props, ActorRef, ActorRefFactory}
import redis.actors.RedisClientActor
import java.net.InetSocketAddress
import scala.concurrent.{Future, ExecutionContext}
import redis.protocol.RedisReply
import redis.commands._


case class RedisServer(host: String = "localhost",
                       port: Int = 6379,
                       password: Option[String] = None,
                       db: Option[Int] = None)

abstract class RedisClientPoolLike(system: ActorRefFactory) extends RoundRobinPoolRequest {
  val redisServers: Seq[RedisServer]
  val name: String
  implicit val executionContext = system.dispatcher

  val redisConnectionPool: Seq[ActorRef] = redisServers.map(server => {
    system.actorOf(
      Props(classOf[RedisClientActor], new InetSocketAddress(server.host, server.port), getConnectOperations(server))
        .withDispatcher(Redis.dispatcher),
      name + '-' + Redis.tempName()
    )
  })

  def onConnect(redis: RedisCommands, server: RedisServer): Unit = {
    server.password.foreach(redis.auth(_)) // TODO log on auth failure
    server.db.foreach(redis.select(_))
  }

  def getConnectOperations(server: RedisServer): () => Seq[Operation[_, _]] = () => {
    val self = this
    val redis = new BufferedRequest with RedisCommands {
      implicit val executionContext: ExecutionContext = self.executionContext
    }
    onConnect(redis, server)
    redis.operations.result()
  }

  /**
   * Disconnect from the server (stop the actor)
   */
  def stop() {
    redisConnectionPool.foreach(redisConnection => {
      system stop redisConnection
    })
  }
}

case class RedisClientPool(redisServers: Seq[RedisServer],
                           name: String = "RedisClientPool")
                          (implicit _system: ActorRefFactory) extends RedisClientPoolLike(_system) with RedisCommands  with PipelinePool

case class RedisClientMasterSlaves(master: RedisServer,
                                   slaves: Seq[RedisServer])
                                  (implicit _system: ActorRefFactory) extends RedisCommands with Transactions with PipelineMasterSlaves {

  implicit val executionContext = _system.dispatcher

  val masterClient = RedisClient(master.host, master.port, master.password, master.db)

  val slavesClients = RedisClientPool(slaves)

  override def send[T](redisCommand: RedisCommand[_ <: RedisReply, T]): Future[T] = {
    if (redisCommand.isMasterOnly || slaves.isEmpty) {
      masterClient.send(redisCommand)
    } else {
      slavesClients.send(redisCommand)
    }
  }

  def redisConnection: ActorRef = masterClient.redisConnection
}

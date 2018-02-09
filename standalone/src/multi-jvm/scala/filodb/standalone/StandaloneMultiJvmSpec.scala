package filodb.standalone

import scala.collection.mutable.HashSet
import scala.concurrent.duration._

import akka.actor.ActorRef
import akka.remote.testkit.{MultiNodeConfig, MultiNodeSpec}
import akka.testkit.ImplicitSender
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.StrictLogging
import net.ceedubs.ficus.Ficus._
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures

import filodb.coordinator._
import filodb.coordinator.client.QueryCommands.{FilteredPartitionQuery, MostRecentTime, QueryResult}
import filodb.coordinator.client.{LocalClient, LogicalPlan}
import filodb.coordinator.NodeClusterActor.{DatasetResourceSpec, IngestionSource}
import filodb.core.metadata.Column.ColumnType
import filodb.core.query.Filter.Equals
import filodb.core.query.{ColumnFilter, ColumnInfo, Tuple, TupleResult}
import filodb.core.{DatasetRef, ErrorResponse}

/**
 * A trait used for MultiJVM tests based on starting the standalone FiloServer using timeseries-dev config
 * (ie pretty much the same as deployed setup)
 */
abstract class StandaloneMultiJvmSpec(config: MultiNodeConfig) extends MultiNodeSpec(config)
  with Suite with StrictLogging
  with ScalaFutures with FlatSpecLike with ImplicitSender
  with Matchers with BeforeAndAfterAll {
  override def initialParticipants: Int = roles.size

  import akka.testkit._

  lazy val watcher = TestProbe()

  val duration = 5.seconds.dilated
  val longDuration = 60.seconds
  val removedDuration = longDuration * 8

  // Ingestion Source section
  val source = ConfigFactory.parseFile(new java.io.File("conf/timeseries-dev-source.conf"))
  val dataset = DatasetRef(source.as[String]("dataset"))
  val numShards = source.as[Int]("num-shards")
  val resourceSpec = DatasetResourceSpec(numShards, source.as[Int]("min-num-nodes"))
  val sourceconfig = source.getConfig("sourceconfig")
  val ingestionSource = source.as[Option[String]]("sourcefactory").map { factory =>
    IngestionSource(factory, sourceconfig)
  }.get
  val chunkDuration = sourceconfig.as[FiniteDuration]("chunk-duration")

  override def beforeAll(): Unit = multiNodeSpecBeforeAll()

  override def afterAll(): Unit = multiNodeSpecAfterAll()

  /** Execute within a `runOn`. */
  def awaitNodeUp(server: FiloServer, within: FiniteDuration = duration): Unit = {
    server.start()
    awaitCond(server.cluster.isInitialized, within)
  }

  /** Execute within a `runOn`. */
  def awaitNodeDown(server: FiloServer, within: FiniteDuration = longDuration * 2): Unit = {
    server.shutdown()
    awaitCond(server.cluster.isTerminated, within)
  }

  def waitAllShardsIngestionActive(): Unit = {
    val activeShards = new HashSet[Int]()
    while (activeShards.size < numShards) {
      expectMsgPF(duration) {
        case ShardAssignmentStarted(_, shard, _) =>
        case IngestionStarted(_, shard, _) => activeShards += shard
      }
    }
  }

  /**
    * @param shards use when some are up and some down, to test different shard status
    */
  def validateShardStatus(client: LocalClient,
                          coordinator: Option[ActorRef] = None,
                          shards: Seq[Int] = Seq.empty)
                         (statusValidator: ShardStatus => Boolean): Unit = {
    client.getShardMapper(dataset) match {
      case Some(map) =>
        info(s"Shard map:  $map")
        info(s"Shard map nodes: ${map.allNodes}")
        if (coordinator.nonEmpty) coordinator forall (c => map.allNodes contains c) shouldEqual true
        map.allNodes.size shouldEqual 2 // only two nodes assigned
        map.shardValues.size shouldBe numShards
        shards match {
          case Seq() =>
           map.shardValues.forall { case (_, status) => statusValidator(status) } shouldEqual true
          case _ =>
            shards forall(shard => statusValidator(map.statusForShard(shard))) shouldEqual true
        }

      case _ =>
        fail(s"Unable to obtain status for dataset $dataset")
    }
  }

  def validateShardAssignments(client: LocalClient,
                               nodeCount: Int,
                               assignments: Seq[Int],
                               coordinator: akka.actor.ActorRef): Unit =
    client.getShardMapper(dataset) match {
      case Some(mapper) =>
        mapper.allNodes.size shouldEqual nodeCount
        mapper.assignedShards shouldEqual Seq(0, 1, 2, 3)
        mapper.unassignedShards shouldEqual Seq.empty
        val shards = mapper.shardsForCoord(coordinator)
        shards shouldEqual assignments
        for {
          shard <- shards
        } info(s"shard($shard) ${mapper.statusForShard(shard)} $coordinator")

      case _ =>

    }

  def setupDataset(client: LocalClient): Unit = {
    client.setupDataset(dataset, resourceSpec, ingestionSource).foreach {
      e: ErrorResponse => fail(s"Errors setting up dataset $dataset: $e")
    }
  }

  import LogicalPlan._

  def runQuery(client: LocalClient): Double = {
    // This is the promQL equivalent: sum(heap_usage{partition="P0"}[1000m])
    val filters = Vector(ColumnFilter("partition", Equals("P0")), ColumnFilter("__name__", Equals("heap_usage")))
    val plan = simpleAgg("sum", Nil, childPlan=
                         PartitionsRange(FilteredPartitionQuery(filters), MostRecentTime(60000000), Seq("value")))
    client.logicalPlanQuery(dataset, plan) match {
      case QueryResult(_, TupleResult(schema, Tuple(None, bRec))) =>
        schema shouldEqual Seq(ColumnInfo("result", ColumnType.DoubleColumn))
        info(s"Query Response was a TupleResult with bRec=$bRec and schema=$schema")
        bRec.getDouble(0)
    }
  }
}

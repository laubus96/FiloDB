package filodb.query.exec

import scala.collection.mutable

import monix.eval.Task
import monix.reactive.Observable

import filodb.core.query._
import filodb.memory.format.RowReader
import filodb.query._
import filodb.query.Query.qLogger

object StitchRvsExec {

  def stitch(v1: RangeVector, v2: RangeVector): RangeVector = {
    val rows = StitchRvsExec.merge(Seq(v1.rows(), v2.rows()))
    IteratorBackedRangeVector(v1.key, rows, RvRange.union(v1.outputRange, v2.outputRange))
  }

  def merge(vectors: Iterable[RangeVectorCursor]): RangeVectorCursor = {
    // This is an n-way merge without using a heap.
    // Heap is not used since n is expected to be very small (almost always just 1 or 2)
    new RangeVectorCursor {
      val bVectors = vectors.map(_.buffered)
      val mins = new mutable.ArrayBuffer[BufferedIterator[RowReader]](2)
      val nanResult = new TransientRow(0, Double.NaN)
      override def hasNext: Boolean = bVectors.exists(_.hasNext)

      override def next(): RowReader = {
        mins.clear()
        var minTime = Long.MaxValue
        bVectors.foreach { r =>
          if (r.hasNext) {
            val t = r.head.getLong(0)
            if (mins.isEmpty) {
              minTime = t
              mins += r
            }
            else if (t < minTime) {
              mins.clear()
              mins += r
              minTime = t
            } else if (t == minTime) {
              mins += r
            }
          }
        }
        if (mins.size == 1) mins.head.next()
        else if (mins.isEmpty) throw new IllegalStateException("next was called when no element")
        else {
          nanResult.timestamp = minTime
          // until we have a different indicator for "unable-to-calculate", use NaN when multiple values seen
          val minRows = mins.map(it => if (it.hasNext) it.next() else nanResult) // move iterators forward
          val minsWithoutNan = minRows.filter(!_.getDouble(1).isNaN)
          if (minsWithoutNan.size == 1) minsWithoutNan.head else nanResult
        }
      }

      override def close(): Unit = vectors.foreach(_.close())
    }
  }
}

/**
  * Use when data for same time series spans multiple shards, or clusters.
  */
final case class StitchRvsExec(queryContext: QueryContext,
                               dispatcher: PlanDispatcher,
                               children: Seq[ExecPlan]) extends NonLeafExecPlan {
  require(children.nonEmpty)

  protected def args: String = ""

  protected[exec] def compose(childResponses: Observable[(QueryResponse, Int)],
                        firstSchema: Task[ResultSchema],
                        querySession: QuerySession): Observable[RangeVector] = {
    qLogger.debug(s"StitchRvsExec: Stitching results:")
    val stitched = childResponses.map {
      case (QueryResult(_, _, result, _, _, _), _) => result
      case (QueryError(_, _, ex), _)         => throw ex
    }.toListL.map(_.flatten).map { srvs =>
      val groups = srvs.groupBy(_.key.labelValues)
      groups.mapValues { toMerge =>
        val rows = StitchRvsExec.merge(toMerge.map(_.rows()))
        val key = toMerge.head.key
        val outputRange = toMerge.map(_.outputRange).reduce { (rv1Range, rv2Range) =>
          RvRange.union(rv1Range, rv2Range)
        }
        IteratorBackedRangeVector(key, rows, outputRange)
      }.values
    }.map(Observable.fromIterable)
    Observable.fromTask(stitched).flatten
  }

  // overriden since stitch can reduce schemas with different vector lengths as long as the columns are same
  override def reduceSchemas(rs: ResultSchema, resp: QueryResult): ResultSchema =
    IgnoreFixedVectorLenAndColumnNamesSchemaReducer.reduceSchema(rs, resp)
}

/**
  * Range Vector Transformer version of StitchRvsExec
  */
final case class StitchRvsMapper() extends RangeVectorTransformer {

  def apply(source: Observable[RangeVector],
            querySession: QuerySession,
            limit: Int,
            sourceSchema: ResultSchema, paramResponse: Seq[Observable[ScalarRangeVector]]): Observable[RangeVector] = {
    qLogger.debug(s"StitchRvsMapper: Stitching results:")
    val stitched = source.toListL.map { rvs =>
      val groups = rvs.groupBy(_.key)
      groups.mapValues { toMerge =>
        val rows = StitchRvsExec.merge(toMerge.map(_.rows))
        val key = toMerge.head.key
        val outputRange = toMerge.map(_.outputRange).reduce { (rv1Range, rv2Range) =>
          RvRange.union(rv1Range, rv2Range)
        }
        IteratorBackedRangeVector(key, rows, outputRange)
      }.values
    }.map(Observable.fromIterable)
    Observable.fromTask(stitched).flatten
  }

  override protected[query] def args: String = ""

  override def funcParams: Seq[FuncArgs] = Nil
}

package mesosphere.marathon
package core.launchqueue

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{Keep, Sink, Source}
import com.typesafe.scalalogging.StrictLogging
import java.time.Clock

import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.launcher.OfferMatchResult
import mesosphere.marathon.core.launchqueue.impl.OfferMatchStatistics.RunSpecOfferStatistics
import mesosphere.marathon.core.launchqueue.impl.{OfferMatchStatistics, RateLimiter}
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.{PathId, RunSpec, RunSpecConfigRef, Timestamp}
import mesosphere.marathon.stream.{EnrichedSink, LiveFold}
import mesosphere.mesos.NoOfferMatchReason

import scala.concurrent.{ExecutionContext, Future}
import scala.async.Async._

/**
  * See LaunchStats$.apply
  */
class LaunchStats private [launchqueue] (
    getRunSpec: PathId => Option[RunSpec],
    delays: LiveFold.Folder[Map[RunSpecConfigRef, Timestamp]],
    instanceTracker: InstanceTracker,
    runSpecStatistics: LiveFold.Folder[Map[PathId, RunSpecOfferStatistics]],
    noMatchStatistics: LiveFold.Folder[Map[PathId, Map[String, OfferMatchResult.NoMatch]]])(implicit ec: ExecutionContext) {

  def getStatistics(): Future[Seq[LaunchStats.QueuedInstanceInfoWithStatistics]] = async {
    /**
      * Quick sanity check. These streams should run for the duration of Marathon. In the off chance they aren't, make
      * it obvious.
      */
    require(!runSpecStatistics.finalResult.isCompleted, s"RunSpecStatistics should not be completed; ${runSpecStatistics.finalResult}")
    require(!noMatchStatistics.finalResult.isCompleted, s"NoMatchStatistics should not be completed, ${noMatchStatistics.finalResult}")
    require(!delays.finalResult.isCompleted, s"Delays should not be completed; ${delays.finalResult}")

    val launchingInstancesByRunSpec = await(instanceTracker.instancesBySpec()).allInstances
      .filter { instance => instance.isScheduled || instance.isProvisioned }
      .groupBy(_.instance.runSpecId)
    val currentDelays = await(delays.readCurrentResult())
    val lastNoMatches = await(noMatchStatistics.readCurrentResult())
    val currentRunSpecStatistics = await(runSpecStatistics.readCurrentResult())

    val results = for {
      (path, instances) <- launchingInstancesByRunSpec
      runSpec <- getRunSpec(path)
    } yield {
      val lastOffers: Seq[OfferMatchResult.NoMatch] = lastNoMatches.get(path).map(_.values.toVector).getOrElse(Nil)
      val statistics = currentRunSpecStatistics.getOrElse(path, RunSpecOfferStatistics.empty)
      val startedAt = if (instances.nonEmpty) instances.iterator.map(_.state.since).min else Timestamp.now()

      LaunchStats.QueuedInstanceInfoWithStatistics(
        runSpec = runSpec,
        inProgress = true,
        finalInstanceCount = runSpec.instances,
        instancesLeftToLaunch = instances.size,
        backOffUntil = currentDelays.get(runSpec.configRef),
        startedAt = startedAt,
        rejectSummaryLastOffers = lastOfferSummary(lastOffers),
        rejectSummaryLaunchAttempt = statistics.rejectSummary,
        processedOffersCount = statistics.processedOfferCount,
        unusedOffersCount = statistics.unusedOfferCount,
        lastMatch = statistics.lastMatch,
        lastNoMatch = statistics.lastNoMatch,
        lastNoMatches = lastOffers)
    }
    results.toList
  }

  private def lastOfferSummary(lastOffers: Seq[OfferMatchResult.NoMatch]): Map[NoOfferMatchReason, Int] = {
    lastOffers.withFilter(_.reasons.nonEmpty)
      .map(_.reasons.minBy(OfferMatchStatistics.reasonFunnelPriority))
      .groupBy(identity).map { case (id, reasons) => id -> reasons.size }
  }
}

object LaunchStats extends StrictLogging {
  // Current known list of delays
  private val delayFold = EnrichedSink.liveFold(Map.empty[RunSpecConfigRef, Timestamp])({ (delays, delayUpdate: RateLimiter.DelayUpdate) =>
    delayUpdate.delayUntil match {
      case Some(instant) =>
        delays + (delayUpdate.ref -> instant)
      case None =>
        delays - delayUpdate.ref
    }
  })

  /**
    * Given a source of instance updates, delay updates, and offer match statistics updates, materialize the streams and
    * aggregate the resulting data to produce the data returned by /v2/queue
    *
    * @param groupManager
    * @param clock
    * @param instanceTracker InstanceTracker
    * @param delayUpdates RateLimiter state subscription stream.
    * @param offerMatchUpdates Series of OfferMatchStatistic updates, as emitted by TaskLauncherActor
    *
    * @return LaunchStats instance used to query the current aggregate match state
    */
  def apply(
    groupManager: GroupManager,
    clock: Clock,
    instanceTracker: InstanceTracker,
    delayUpdates: Source[RateLimiter.DelayUpdate, NotUsed],
    offerMatchUpdates: Source[OfferMatchStatistics.OfferMatchUpdate, NotUsed],
  )(implicit mat: Materializer, ec: ExecutionContext): LaunchStats = {

    val delays = delayUpdates.runWith(delayFold)

    val (runSpecStatistics, noMatchStatistics) =
      offerMatchUpdates
        .alsoToMat(OfferMatchStatistics.runSpecStatisticsSink)(Keep.right)
        .toMat(OfferMatchStatistics.noMatchStatisticsSink)(Keep.both)
        .run
    new LaunchStats(groupManager.runSpec(_), delays, instanceTracker, runSpecStatistics, noMatchStatistics)
  }

  /**
    * @param runSpec the associated runSpec
    * @param inProgress true if the launch queue currently tries to launch more instances
    * @param instancesLeftToLaunch number of instances to launch
    * @param finalInstanceCount the final number of instances currently targeted
    * @param backOffUntil timestamp until which no further launch attempts will be made
    */
  case class QueuedInstanceInfoWithStatistics(
    runSpec: RunSpec,
    inProgress: Boolean,
    instancesLeftToLaunch: Int,
    finalInstanceCount: Int,
    backOffUntil: Option[Timestamp],
    startedAt: Timestamp,
    rejectSummaryLastOffers: Map[NoOfferMatchReason, Int],
    rejectSummaryLaunchAttempt: Map[NoOfferMatchReason, Int],
    processedOffersCount: Int,
    unusedOffersCount: Int,
    lastMatch: Option[OfferMatchResult.Match],
    lastNoMatch: Option[OfferMatchResult.NoMatch],
    lastNoMatches: Seq[OfferMatchResult.NoMatch]
  )
}

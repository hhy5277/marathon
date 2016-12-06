package mesosphere.marathon
package upgrade

import akka.actor.SupervisorStrategy.Stop
import akka.actor._
import akka.event.EventStream
import mesosphere.marathon.MarathonSchedulerActor.{CommandFailed, DeploymentStarted, RetrieveRunningDeployments, RunningDeployments}
import mesosphere.marathon.core.health.HealthCheckManager
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.readiness.{ReadinessCheckExecutor, ReadinessCheckResult}
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.termination.KillService
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.state.{PathId, RootGroup, Timestamp}
import mesosphere.marathon.storage.repository.DeploymentRepository
import org.apache.mesos.SchedulerDriver
import org.slf4j.LoggerFactory

import scala.async.Async.{async, await}
import scala.collection.immutable.Seq
import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.util.control.NonFatal

class DeploymentManager(
  taskTracker: InstanceTracker,
  killService: KillService,
  launchQueue: LaunchQueue,
  scheduler: SchedulerActions,
  storage: StorageProvider,
  healthCheckManager: HealthCheckManager,
  eventBus: EventStream,
  readinessCheckExecutor: ReadinessCheckExecutor,
  marathonSchedulerDriverHolder: MarathonSchedulerDriverHolder,
  deploymentRepository: DeploymentRepository,
  deploymentActorProps: (ActorRef, ActorRef, SchedulerDriver, KillService, SchedulerActions, DeploymentPlan, InstanceTracker, LaunchQueue, StorageProvider, HealthCheckManager, EventStream, ReadinessCheckExecutor) => Props = DeploymentActor.props)
    extends Actor with ActorLogging {
  import context.dispatcher
  import mesosphere.marathon.upgrade.DeploymentManager._

  private[this] val log = LoggerFactory.getLogger(getClass)

  val runningDeployments: mutable.Map[String, DeploymentInfo] =
    mutable.Map.empty.withDefaultValue(DeploymentInfo(None, DeploymentPlan.empty, Scheduled, None))
  val deploymentStatus: mutable.Map[String, DeploymentStepInfo] = mutable.Map.empty

  override def supervisorStrategy: SupervisorStrategy = OneForOneStrategy() {
    case NonFatal(e) => Stop
  }

  @SuppressWarnings(Array("OptionGet"))
  def driver: SchedulerDriver = marathonSchedulerDriverHolder.driver.get

  def receive: Receive = {

    case StopAllDeployments =>
      for ((_, DeploymentInfo(Some(ref), _, _, _)) <- runningDeployments)
        ref ! DeploymentActor.Shutdown
      runningDeployments.clear()
      deploymentStatus.clear()

    case CancelDeployment(id) =>
      runningDeployments.get(id) match {
        case Some(DeploymentInfo(_, _, Scheduled, _)) =>
          log.info(s"Canceling scheduled deployment $id.")
          runningDeployments.remove(id)

        case Some(DeploymentInfo(Some(ref), _, Deploying, _)) =>
          log.info(s"Canceling deployment $id which is already in progress.")
          cancel(id)

        case Some(DeploymentInfo(_, _, Canceling, _)) =>
          log.warn(s"The deployment $id is already being canceled.")

        case Some(_) =>
          // This means we have a deployment with a [Deploying] status which has no DeploymentActor to cancel it.
          // This is clearly an invalid state and should never happen.
          log.error(s"Failed to cancel an invalid deployment ${runningDeployments.get(id)}")

        case None =>
          sender ! DeploymentFailed(
            DeploymentPlan(id, RootGroup.empty, RootGroup.empty, Nil, Timestamp.now()),
            new DeploymentCanceledException("The upgrade has been cancelled"))
      }

    case DeploymentFinished(plan) =>
      log.info(s"Removing ${plan.id} from list of running deployments")
      runningDeployments -= plan.id
      deploymentStatus -= plan.id
      deploymentRepository.delete(plan.id)

    case LaunchDeploymentActor(plan, origSender) if isScheduledDeployment(plan.id) =>
      log.info(s"Launching DeploymentActor for ${plan.id}")
      launch(plan, origSender)

    case LaunchDeploymentActor(plan, _) =>
      log.info(s"Deployment ${plan.id} was already canceled or overridden by another one. Not proceeding with it")

    case stepInfo: DeploymentStepInfo => deploymentStatus += stepInfo.plan.id -> stepInfo

    case ReadinessCheckUpdate(id, result) => deploymentStatus.get(id).foreach { info =>
      deploymentStatus += id -> info.copy(readinessChecks = info.readinessChecks.updated(result.taskId, result))
    }

    case RetrieveRunningDeployments =>
      sender() ! RunningDeployments(deploymentStatus.values.to[Seq])

    // If the new deployment plan has no conflicts we simply store it in the repository and proceed with the deployment.
    case StartDeployment(plan, origSender, force) if !hasConflicts(plan) =>
      log.info(s"Received new deployment plan ${plan.id}, no conflicts detected")
      val recipient = sender()
      schedule(plan) // 1. Save new plan as [Scheduled]

      async {
        await(deploymentRepository.store(plan)) // 2. Store new plan
        log.info(s"Stored new deployment plan ${plan.id}")

        if (origSender != Actor.noSender) origSender ! DeploymentStarted(plan) // 2.1 Send response to original sender

        self ! LaunchDeploymentActor(plan, recipient) // 3. Proceed with the deployment
      }

    // If the deployment has conflicts but is not forced, we merely inform the sender about all the deployments
    // that are conflicting with the current one.
    case StartDeployment(plan, origSender, force) if hasConflicts(plan) && !force =>
      log.info(s"Received new deployment plan ${plan.id}. Conflicts are detected and it is not forced, so it will not start")
      origSender ! CommandFailed(
        MarathonSchedulerActor.Deploy(plan, force),
        AppLockedException(conflictingDeployments(plan).map(_.plan.id)))

    // Otherwise we have conflicts and the deployment is forced:
    case StartDeployment(plan, origSender, force) =>
      log.info(s"Received new forced deployment plan ${plan.id}. Proceeding with canceling conflicts.")

      val recipient = sender()

      // 1. Find all conflicting deployments
      val conflicts = conflictingDeployments(plan)
      log.info(s"Found conflicting deployments ${conflicts.map(_.plan.id)} with the current plan ${plan.id}")

      // 2. Remove all [Scheduled] deployments (they haven't been started yet, so there is nothing to cancel)
      // and cancel (spawn StopActor and mark as [Canceling]) all [Deploying] deployments.
      conflicts.foreach{
        case DeploymentInfo(_, p, Scheduled, _) => runningDeployments.remove(p.id)
        case DeploymentInfo(_, p, Deploying, _) => cancel(p.id)
        case DeploymentInfo(_, _, Canceling, _) => // Nothing to do here - this deployment is already being canceled
      }

      // 3. Save new plan as [Scheduled]
      schedule(plan)

      // 4. Delete all conflicts from the repository first and store the new plan afterwards. In this order even
      // if the master crashes we shouldn't have any conflicts stored. However in the worst case (a crash after delete()
      // and before store() we could end up with old deployment canceled and new one not existing.
      //
      // Note: delete() removes conflicting deployment before the actual deployments are canceled. This way the target
      // state of the system is safely saved and even in a case of a crash the new master should be able to reconcile
      // from it.
      async {
        // 4.1 Delete conflicting plans
        await(Future.sequence(conflicts.map(p => deploymentRepository.delete(p.plan.id))))
        log.info(s"Removed conflicting deployments ${conflicts.map(_.plan.id)} from the repository")

        // 4.2 Store new plan
        await(deploymentRepository.store(plan))
        log.info(s"Stored new deployment plan ${plan.id}")

        // 4.3 Only after the deployment is stored we can send the original sender a positive response
        if (origSender != Actor.noSender) origSender ! DeploymentStarted(plan)

        // 5. Proceed with the deployment. This is done as an extra message since we're inside a future and completely
        // asynchronous to the actor. While we were waiting for the repository.store() another forced deployment
        // could've canceled this one. To synchronize with the actor and prevent launching canceled deployments (see
        // isScheduledDeployment() check) we send ourselves a message.
        self ! CancelConflictingDeployments(plan, conflicts, recipient)
      }

    case CancelConflictingDeployments(plan, conflicts, origSender) if isScheduledDeployment(plan.id) =>
      // 6. Get old conflicting deployments cancellation futures (status = [Canceling]) that aren't yet
      // completed and bind an onComplete callback.
      val toCancel = conflicts.filter(_.status == Canceling)
      val cancellations: Seq[Future[Boolean]] = toCancel
        .flatMap(_.cancel)
        .filter(!_.isCompleted)

      // When all the conflicting deployments are canceled, proceed with the deployment
      Future.sequence(cancellations).onComplete { _ =>
        log.info(s"Conflicting deployments ${toCancel.map(_.plan.id)} for deployment ${plan.id} have been canceled")
        self ! LaunchDeploymentActor(plan, origSender)
      }

    // TODO(AD): do we need to throw a TimeoutException in case canceling conflicting deployments
    // TODO(AD): takes too long? (like MarathonSchedulerActor used to do)?
  }

  /** Method saves new DeploymentInfo with status = [Scheduled] */
  private def schedule(plan: DeploymentPlan) = {
    runningDeployments += plan.id -> DeploymentInfo(plan = plan, status = Scheduled)
  }

  /** Method spawns a DeploymentActor for the passed plan and saves new DeploymentInfo with status = [Scheduled] */
  private def launch(plan: DeploymentPlan, origSender: ActorRef) = {
    val ref = context.actorOf(
      deploymentActorProps(
        self,
        origSender,
        driver,
        killService,
        scheduler,
        plan,
        taskTracker,
        launchQueue,
        storage,
        healthCheckManager,
        eventBus,
        readinessCheckExecutor
      ),
      plan.id
    )
    runningDeployments.update(plan.id, runningDeployments(plan.id).copy(ref = Some(ref), status = Deploying))
  }

  /** Method spawns a StopActor for the passed plan Id and saves new DeploymentInfo with status = [Canceling] */
  private def cancel(id: String) = {
    val info = runningDeployments(id)
    val stopFuture = stopActor(info.ref.get, new DeploymentCanceledException("The upgrade has been cancelled"))
    runningDeployments.update(id, info.copy(status = Canceling, cancel = Some(stopFuture)))
  }

  def stopActor(ref: ActorRef, reason: Throwable): Future[Boolean] = {
    val promise = Promise[Boolean]()
    context.actorOf(Props(classOf[StopActor], ref, promise, reason))
    promise.future
  }

  def isScheduledDeployment(id: String): Boolean = {
    runningDeployments.contains(id) && runningDeployments(id).status == Scheduled
  }

  def hasConflicts(plan: DeploymentPlan): Boolean = {
    conflictingDeployments(plan).nonEmpty
  }

  /**
    * Methods return all deployments that are conflicting with passed plan.
    */
  def conflictingDeployments(thisPlan: DeploymentPlan): Seq[DeploymentInfo] = {
    def intersectsWith(thatPlan: DeploymentPlan): Boolean = {
      thatPlan.affectedRunSpecIds.intersect(thisPlan.affectedRunSpecIds).nonEmpty
    }
    runningDeployments.values.filter(info => intersectsWith(info.plan)).to[Seq]
  }
}

object DeploymentManager {
  case class StartDeployment(plan: DeploymentPlan, origSender: ActorRef, force: Boolean = false)
  case class CancelDeployment(id: String)
  case object StopAllDeployments
  case class CancelConflictingDeployments(plan: DeploymentPlan, conflicts: Seq[DeploymentInfo], origSender: ActorRef)
  case class DeploymentFinished(plan: DeploymentPlan)
  case class DeploymentFailed(plan: DeploymentPlan, reason: Throwable)
  case class ReadinessCheckUpdate(deploymentId: String, result: ReadinessCheckResult)
  case class LaunchDeploymentActor(plan: DeploymentPlan, origSender: ActorRef)

  case class DeploymentStepInfo(
      plan: DeploymentPlan,
      step: DeploymentStep,
      nr: Int,
      readinessChecks: Map[Task.Id, ReadinessCheckResult] = Map.empty) {
    lazy val readinessChecksByApp: Map[PathId, Seq[ReadinessCheckResult]] = {
      readinessChecks.values.groupBy(_.taskId.runSpecId).mapValues(_.to[Seq]).withDefaultValue(Seq.empty)
    }
  }

  case class DeploymentInfo(
    ref: Option[ActorRef] = None, // An ActorRef to the DeploymentActor if status = [Deploying]
    plan: DeploymentPlan, // Deployment plan
    status: DeploymentStatus, // Status can be [Scheduled], [Canceling] or [Deploying]
    cancel: Option[Future[Boolean]] = None) // Cancellation future if status = [Canceling]

  sealed trait DeploymentStatus
  case object Scheduled extends DeploymentStatus
  case object Canceling extends DeploymentStatus
  case object Deploying extends DeploymentStatus

  def props(
    taskTracker: InstanceTracker,
    killService: KillService,
    launchQueue: LaunchQueue,
    scheduler: SchedulerActions,
    storage: StorageProvider,
    healthCheckManager: HealthCheckManager,
    eventBus: EventStream,
    readinessCheckExecutor: ReadinessCheckExecutor,
    marathonSchedulerDriverHolder: MarathonSchedulerDriverHolder,
    deploymentRepository: DeploymentRepository,
    deploymentActorProps: (ActorRef, ActorRef, SchedulerDriver, KillService, SchedulerActions, DeploymentPlan, InstanceTracker, LaunchQueue, StorageProvider, HealthCheckManager, EventStream, ReadinessCheckExecutor) => Props = DeploymentActor.props): Props = {
    Props(new DeploymentManager(taskTracker, killService, launchQueue,
      scheduler, storage, healthCheckManager, eventBus, readinessCheckExecutor, marathonSchedulerDriverHolder, deploymentRepository, deploymentActorProps))
  }

}

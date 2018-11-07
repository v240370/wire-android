package com.waz.background

import java.util.concurrent.TimeUnit

import android.content.Context
import androidx.work.ListenableWorker.Result
import androidx.work._
import com.waz.ZLog.{error, verbose}
import com.waz.background.WorkManagerSyncRequestService.SyncJobWorker
import com.waz.model.{Uid, UserId}
import com.waz.service.ZMessaging
import com.waz.service.push.PushService.FetchFromJob
import com.waz.utils.events.EventContext
import com.waz.zclient.{Injectable, Injector}

import scala.collection.JavaConversions._
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.control.NonFatal

class NotificationWorkManagerService(implicit inj: Injector, cxt: Context, eventContext: EventContext) extends Injectable {
  import NotificationWorkManagerService._

  private lazy val wm = WorkManager.getInstance()

  def fetchNotifications(notId: Option[Uid] = None): Future[Unit] = {

    val triggeredByNotification = notId.isDefined

    //TODO backoff policy should be determined by if in foreground or not
    val fetchBackoffPolicy = if (triggeredByNotification) BackoffPolicy.EXPONENTIAL else BackoffPolicy.LINEAR

    val fetchWork = new OneTimeWorkRequest.Builder(classOf[FetchWorker])
      .setConstraints(new Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build())
      .setBackoffCriteria(fetchBackoffPolicy, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
      .build()

    val decryptWork = new OneTimeWorkRequest.Builder(classOf[DecryptWorker])
      .setBackoffCriteria(BackoffPolicy.LINEAR, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
      .build()

    val pipelineWork = new OneTimeWorkRequest.Builder(classOf[PipelineWorker])
      .setBackoffCriteria(BackoffPolicy.LINEAR, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
      .build()

    Future {
      wm.beginUniqueWork("fetch-job", ExistingWorkPolicy.KEEP, List(fetchWork, decryptWork, pipelineWork))
        .enqueue()
        .get()
    }
  }
}

object NotificationWorkManagerService {

  private val AccountId      = "AccountId"
  private val NotificationId = "NotificationId"

  class FetchWorker(context: Context, params: WorkerParameters) extends Worker(context, params) with Injectable {
    override def doWork(): ListenableWorker.Result = {
      val d = getInputData
      val accountId      = UserId(d.getString(AccountId))
      val notificationId = Option(d.getString(NotificationId)).map(Uid(_))

      verbose(s"doWork: account: $accountId, notification: $notificationId")

      val result =
        ZMessaging.accountsService.flatMap(_.getZms(accountId)).flatMap {
          case Some(zms) => zms.push.syncHistory(FetchFromJob(notificationId), withRetries = false)
          case _ => Future.failed(new Exception(s"No active zms for account: $accountId"))
        }

      try {
        Await.result(result, 10.minutes) //Give the job a long time to complete
        Result.SUCCESS
      } catch {
        case NonFatal(e) =>
          error("FetchJob failed", e)
          Result.RETRY
      }
    }
  }

  class DecryptWorker(context: Context, params: WorkerParameters) extends Worker(context, params) with Injectable {
    override def doWork(): ListenableWorker.Result = {
      verbose("doWork!!")
      Result.SUCCESS
    }
  }

  class PipelineWorker(context: Context, params: WorkerParameters) extends Worker(context, params) with Injectable {
    override def doWork(): ListenableWorker.Result = {
      verbose("doWork!!")
      Result.SUCCESS
    }
  }

}

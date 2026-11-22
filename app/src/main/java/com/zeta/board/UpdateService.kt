package com.zeta.board

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import java.io.File

/** What the background update download is doing right now. Read and changed on the main thread only. */
object UpdateJob {
    enum class S { IDLE, RUNNING, DONE, FAILED }

    var state = S.IDLE
    var code = 0L
    var pct = 0
    var got = 0L
    var total = 0L
    var file: File? = null
    var task: Updater.Task? = null

    private val listeners = ArrayList<() -> Unit>()

    /** Calls [l] on every change. Returns a function that stops listening. */
    fun listen(l: () -> Unit): () -> Unit {
        listeners.add(l)
        return { listeners.remove(l) }
    }

    fun fire() {
        for (l in ArrayList(listeners)) l()
    }
}

/**
 * Downloads an update in the background: the download goes on when the update window is closed or the app is left.
 * A notification shows the progress (with a Cancel button) and, when it is done, "tap to install".
 */
class UpdateService : Service() {

    companion object {
        private const val ACTION_CANCEL = "com.zeta.board.UPDATE_CANCEL"
        private const val CHANNEL = "update_download"
        private const val ID_PROGRESS = 7101

        /** Starts the download of [info] (does nothing when it is already running). */
        fun start(ctx: Context, info: Updater.Info) {
            val i = Intent(ctx, UpdateService::class.java)
            i.putExtra("url", info.apkUrl)
            i.putExtra("code", info.code)
            i.putExtra("name", info.name)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun cancel(ctx: Context) {
            UpdateJob.task?.cancelled = true
        }

        private fun channel(ctx: Context): NotificationManager {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL) == null) {
                val ch = NotificationChannel(CHANNEL, "Update download", NotificationManager.IMPORTANCE_LOW)
                ch.description = "Shows the progress while a new Zeta Board version is downloading"
                ch.setShowBadge(false)
                nm.createNotificationChannel(ch)
            }
            return nm
        }
    }

    private var name = ""

    override fun onBind(intent: Intent?): IBinder? = null

    private fun openApp(): PendingIntent {
        val open = Intent(this, MainActivity::class.java)
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(this, 11, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun progressNotification(pct: Int, got: Long, total: Long): Notification {
        val cancel = PendingIntent.getService(
            this, 12, Intent(this, UpdateService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val mb = { b: Long -> String.format(java.util.Locale.US, "%.1f", b / 1048576.0) }
        val text = if (total > 0) mb(got) + " / " + mb(total) + " MB" else "Starting\u2026"
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(Pal.PURPLE)
            .setContentTitle("Downloading Zeta Board" + if (name.isNotEmpty()) " v$name" else "")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setProgress(100, pct, total <= 0)
            .setContentIntent(openApp())
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_notif), "Cancel", cancel).build())
            .build()
    }

    private fun goForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID_PROGRESS, n)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        channel(this)
        if (intent?.action == ACTION_CANCEL) {
            UpdateJob.task?.cancelled = true
            if (UpdateJob.state != UpdateJob.S.RUNNING) stopSelf()
            return START_NOT_STICKY
        }
        val url = intent?.getStringExtra("url").orEmpty()
        name = intent?.getStringExtra("name").orEmpty()
        goForeground(progressNotification(UpdateJob.pct, UpdateJob.got, UpdateJob.total))
        if (UpdateJob.state == UpdateJob.S.RUNNING) return START_NOT_STICKY // already downloading
        if (url.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        UpdateJob.state = UpdateJob.S.RUNNING
        UpdateJob.code = intent?.getLongExtra("code", 0L) ?: 0L
        UpdateJob.pct = 0
        UpdateJob.got = 0L
        UpdateJob.total = 0L
        UpdateJob.file = null
        UpdateJob.fire()

        var shownPct = -1
        val nm = channel(this)
        UpdateJob.task = Updater.download(this, url, { pct, got, total ->
            UpdateJob.pct = pct
            UpdateJob.got = got
            UpdateJob.total = total
            UpdateJob.fire()
            if (pct != shownPct) {
                shownPct = pct
                try {
                    nm.notify(ID_PROGRESS, progressNotification(pct, got, total))
                } catch (e: Exception) {
                }
            }
        }, { f ->
            val cancelled = UpdateJob.task?.cancelled == true
            UpdateJob.task = null
            if (f != null) {
                UpdateJob.state = UpdateJob.S.DONE
                UpdateJob.file = f
            } else {
                UpdateJob.state = if (cancelled) UpdateJob.S.IDLE else UpdateJob.S.FAILED
            }
            UpdateJob.fire()
            stopForeground(STOP_FOREGROUND_REMOVE)
            // the update window installs it right away when it is on screen; otherwise tell the user with a notification
            val watching = Updater.dialogOpen && Updater.foreground
            if (f != null && !watching) UpdateNotifier.showReady(this, name)
            if (f == null && !cancelled) UpdateNotifier.showDownloadFailed(this)
            stopSelf()
        })
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}

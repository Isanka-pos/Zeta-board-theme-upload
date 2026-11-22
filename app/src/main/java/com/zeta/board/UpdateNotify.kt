package com.zeta.board

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper

object UpdateScheduler {
    private const val ID = 4108
    private const val OLD_ID = 4107 // the old 6-hour job, removed on upgrade
    const val INTERVAL_MIN = 15L
    private const val INTERVAL_MS = INTERVAL_MIN * 60L * 1000L

    /** Checks for a new version every 15 minutes, the shortest interval Android allows (needs network, survives reboot). */
    fun schedule(ctx: Context) {
        try {
            val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            js.cancel(OLD_ID)
            val existing = js.getPendingJob(ID)
            if (existing != null && existing.isPeriodic && existing.intervalMillis == INTERVAL_MS) return
            val info = JobInfo.Builder(ID, ComponentName(ctx, UpdateJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(INTERVAL_MS)
                .setPersisted(true)
                .build()
            js.schedule(info)
        } catch (e: Exception) {
        }
    }
}

/** A second 15-minute timer that runs while the keyboard (or the app) process is alive, so checks are not missed. */
object UpdateWatcher {
    const val INTERVAL_MS = 15L * 60L * 1000L
    private const val MIN_GAP_MS = 12L * 60L * 1000L
    private const val SOON_GAP_MS = 4L * 60L * 1000L

    private val handler = Handler(Looper.getMainLooper())
    private var appCtx: Context? = null
    private var started = false

    private val tick = object : Runnable {
        override fun run() {
            val c = appCtx ?: return
            checkAndNotify(c, false) {}
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    fun start(ctx: Context) {
        appCtx = ctx.applicationContext
        if (started) return
        started = true
        handler.postDelayed(tick, INTERVAL_MS)
    }

    /**
     * Quick extra check used whenever the keyboard opens: a new version is announced within moments of the phone
     * being used, not only on the 15-minute rhythm. At most one request every 4 minutes.
     */
    fun checkSoon(ctx: Context) {
        val app = ctx.applicationContext
        val now = System.currentTimeMillis()
        val gap = now - Prefs.getL(app, "last_soon_check", 0L)
        if (gap in 0 until SOON_GAP_MS) return
        Prefs.setL(app, "last_soon_check", now)
        checkAndNotify(app, true) {}
    }

    /** Looks for a new version and shows a notification once per version. [done] is always called on the main thread. */
    fun checkAndNotify(ctx: Context, force: Boolean, done: () -> Unit) {
        val app = ctx.applicationContext
        val now = System.currentTimeMillis()
        val gap = now - Prefs.getL(app, "last_bg_check", 0L)
        if (!force && gap >= 0L && gap < MIN_GAP_MS) {
            done()
            return
        }
        Updater.check(app) { info, err ->
            if (err == null) {
                val t = System.currentTimeMillis()
                Prefs.setL(app, "last_bg_check", t)
                Prefs.setL(app, "last_ok_check", t)
                if (info == null) UpdateStore.dropRetracted(app)
            }
            if (info != null) {
                UpdateStore.save(app, info)
                if (!Updater.dialogOpen && info.code > Prefs.getI(app, "notified_code", 0).toLong()) {
                    Prefs.setI(app, "notified_code", info.code.toInt())
                    UpdateNotifier.show(app, info)
                }
            }
            done()
        }
    }
}

class UpdateJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        UpdateWatcher.checkAndNotify(this, true) { jobFinished(params, false) }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}

/** Remembers the newest version found online, so the in-app warning banner and the "what's new" screen can use it. */
object UpdateStore {
    fun save(c: Context, i: Updater.Info) {
        val app = c.applicationContext
        Prefs.setL(app, "avail_code", i.code)
        Prefs.setS(app, "avail_name", i.name)
        Prefs.setS(app, "avail_notes", i.notes)
        Prefs.setS(app, "avail_url", i.apkUrl)
    }

    /** The update that is waiting to be installed, or null when the app is already up to date. */
    fun pending(c: Context): Updater.Info? {
        val app = c.applicationContext
        val code = Prefs.getL(app, "avail_code", 0L)
        if (code <= Updater.currentCode(app)) return null
        val url = Prefs.getS(app, "avail_url", "")
        if (url.isEmpty()) return null
        return Updater.Info(code, Prefs.getS(app, "avail_name", "1.0.$code"), Prefs.getS(app, "avail_notes", ""), url)
    }

    /** A successful check found nothing newer, so an update that was announced earlier has been withdrawn. */
    fun dropRetracted(c: Context) {
        val app = c.applicationContext
        if (Prefs.getL(app, "avail_code", 0L) > Updater.currentCode(app)) Prefs.setL(app, "avail_code", 0L)
    }
}

object UpdateNotifier {
    private const val CHANNEL = "updates_alert"
    private const val OLD_CHANNEL = "updates"
    const val ID_AVAILABLE = 7001
    const val ID_INSTALLED = 7002

    private fun manager(ctx: Context): NotificationManager? {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.areNotificationsEnabled()) return null
        if (nm.getNotificationChannel(CHANNEL) == null) {
            // HIGH importance + sound + vibration makes Android show the notification as a heads-up banner at the top
            val ch = NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "Tells you when a new Zeta Board version is ready or was installed"
            ch.enableLights(true)
            ch.lightColor = Pal.PURPLE
            ch.enableVibration(true)
            ch.vibrationPattern = longArrayOf(0L, 250L, 150L, 250L)
            ch.setShowBadge(true)
            ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            ch.setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            nm.createNotificationChannel(ch)
            // replaces the older "updates" channel, whose settings Android does not let an app change afterwards
            if (nm.getNotificationChannel(OLD_CHANNEL) != null) nm.deleteNotificationChannel(OLD_CHANNEL)
        }
        return nm
    }

    private fun openApp(ctx: Context, req: Int): PendingIntent {
        val open = Intent(ctx, MainActivity::class.java)
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        open.putExtra("from_update", true)
        return PendingIntent.getActivity(ctx, req, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** "A new version is ready" - the warning notification. */
    fun show(ctx: Context, info: Updater.Info) {
        try {
            val nm = manager(ctx) ?: return
            val pi = openApp(ctx, 1)
            val notes = info.notes.ifBlank { "Improvements and bug fixes" }
            val action = Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_notif), "Update now", pi).build()
            val n = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setLargeIcon(Logo.square(ctx, 192))
                .setColor(Pal.PURPLE)
                .setCategory(Notification.CATEGORY_RECOMMENDATION)
                .setPriority(Notification.PRIORITY_HIGH)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setShowWhen(true)
                .setContentTitle("\u26A0 Update available: Zeta Board v" + info.name)
                .setContentText("Update now to get the latest fixes and features.")
                .setSubText("Update available")
                .setStyle(
                    Notification.BigTextStyle()
                        .setBigContentTitle("Zeta Board v" + info.name + " is ready")
                        .bigText("Please update to stay on the newest, most stable version.\n\nWhat's new:\n" + notes)
                )
                .addAction(action)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(ID_AVAILABLE, n)
        } catch (e: Exception) {
        }
    }

    const val ID_READY = 7003

    private fun installIntent(ctx: Context, req: Int): PendingIntent {
        val open = Intent(ctx, MainActivity::class.java)
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        open.putExtra("install_update", true)
        return PendingIntent.getActivity(ctx, req, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** "The update was downloaded" - tapping it starts the install. */
    fun showReady(ctx: Context, name: String) {
        try {
            val nm = manager(ctx) ?: return
            val pi = installIntent(ctx, 3)
            val n = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setLargeIcon(Logo.square(ctx, 192))
                .setColor(Pal.GREEN)
                .setCategory(Notification.CATEGORY_STATUS)
                .setContentTitle("Update downloaded \u2713")
                .setContentText("Tap to install Zeta Board" + if (name.isNotEmpty()) " v$name" else "")
                .setSubText("Ready to install")
                .addAction(Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_notif), "Install", pi).build())
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(ID_READY, n)
        } catch (e: Exception) {
        }
    }

    fun showDownloadFailed(ctx: Context) {
        try {
            val nm = manager(ctx) ?: return
            val n = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setColor(Pal.PURPLE)
                .setCategory(Notification.CATEGORY_STATUS)
                .setContentTitle("Update download failed")
                .setContentText("Tap to try again")
                .setContentIntent(openApp(ctx, 4))
                .setAutoCancel(true)
                .build()
            nm.notify(ID_READY, n)
        } catch (e: Exception) {
        }
    }

    fun cancelReady(ctx: Context) {
        try {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID_READY)
        } catch (e: Exception) {
        }
    }

    /** "Zeta Board was updated" - shown right after a new version has been installed. */
    fun showInstalled(ctx: Context, name: String, notes: String) {
        try {
            val nm = manager(ctx) ?: return
            val pi = openApp(ctx, 2)
            val n = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setLargeIcon(Logo.square(ctx, 192))
                .setColor(Pal.GREEN)
                .setCategory(Notification.CATEGORY_STATUS)
                .setContentTitle("Zeta Board updated \u2713")
                .setContentText("You are now on v" + name + ". Tap to see what's new.")
                .setSubText("Update installed")
                .setStyle(
                    Notification.BigTextStyle()
                        .setBigContentTitle("Zeta Board v" + name + " is installed")
                        .bigText("What's new:\n" + notes)
                )
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(ID_INSTALLED, n)
        } catch (e: Exception) {
        }
    }

    fun cancelAvailable(ctx: Context) {
        try {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID_AVAILABLE)
        } catch (e: Exception) {
        }
    }

    fun cancelInstalled(ctx: Context) {
        try {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID_INSTALLED)
        } catch (e: Exception) {
        }
    }
}

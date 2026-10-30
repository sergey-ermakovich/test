package com.zeekrprobe

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

/**
 * App entry point. Does two startup chores so every screen and the sampling service
 * inherit them:
 *  1. Lifts Android's hidden-API reflection filter, without which the ECARX AdaptAPI
 *     members in /system/framework are invisible (see EcarxProbe).
 *  2. Registers the notification channel the foreground sampling service runs under.
 */
class JournalApp : Application() {

    override fun onCreate() {
        super.onCreate()
        liftHiddenApiFilter()
        createChannel()
    }

    private fun liftHiddenApiFilter() {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("")
            }
        } catch (_: Throwable) {
            // Nothing we can do; EcarxProbe will simply report no readable members.
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val mgr = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Бортжурнал",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Фоновый сбор данных и автоопределение зарядки" }
            mgr?.createNotificationChannel(ch)
        }
    }

    companion object {
        const val CHANNEL_ID = "journal"
    }
}

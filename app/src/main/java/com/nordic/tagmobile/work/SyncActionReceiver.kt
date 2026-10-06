package com.nordic.tagmobile.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.nordic.tagmobile.R
import com.nordic.tagmobile.TagApp
import com.nordic.tagmobile.log.LogCategory
import com.nordic.tagmobile.log.TagLogger

/** Handles "Sync all" from cloud sync notifications. */
class SyncActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_SYNC_ALL -> {
                TagLogger.log(LogCategory.APP, "CLOUD_NOTIFY_SYNC_ALL", "from notification")
                val app = context.applicationContext
                if (app is TagApp) {
                    app.syncAllFromUi()
                } else {
                    UploadSessionWorker.enqueueAll(app)
                }
                Toast.makeText(
                    app,
                    R.string.notify_sync_all_started_toast,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    companion object {
        const val ACTION_SYNC_ALL = "com.nordic.tagmobile.action.SYNC_ALL"
    }
}

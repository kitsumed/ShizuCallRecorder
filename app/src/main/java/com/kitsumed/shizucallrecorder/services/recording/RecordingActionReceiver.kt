package com.kitsumed.shizucallrecorder.services.recording

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import com.kitsumed.shizucallrecorder.R
import com.kitsumed.shizucallrecorder.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class RecordingActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SCR:RecordingActionReceiver"
        const val ACTION_DELETE_SAVED_RECORDING = "com.kitsumed.shizucallrecorder.DELETE_SAVED_RECORDING"
        const val EXTRA_RECORDING_URI = "recording_uri"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_DELETE_SAVED_RECORDING -> {
                val uriString = intent.getStringExtra(EXTRA_RECORDING_URI) ?: return
                val uri = Uri.parse(uriString)

                // Dismiss notification
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(uri.hashCode())

                // Delete the file using DocumentFile
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val documentFile = DocumentFile.fromSingleUri(context, uri)
                        if (documentFile != null && documentFile.exists()) {
                            val success = documentFile.delete()
                            if (success) {
                                AppLogger.d(TAG, "Successfully deleted recording from notification: $uri")
                                CoroutineScope(Dispatchers.Main).launch {
                                    Toast.makeText(context, R.string.recording_toast_deleted, Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                AppLogger.e(TAG, "Failed to delete recording from notification: $uri")
                            }
                        }
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Error while deleting recording", e)
                    }
                }
            }
        }
    }
}

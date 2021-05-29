package com.impressions.videoencoding

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.impressions.videoencoding.MainActivity
import com.impressions.videoencoding.MainActivity.Companion.FFMPEG_FAILURE_MSG
import com.impressions.videoencoding.MainActivity.Companion.FFMPEG_OUTPUT_FILE
import com.impressions.videoencoding.MainActivity.Companion.MESSENGER_INTENT_KEY
import com.impressions.videoencoding.MainActivity.Companion.OUTPUT_MIMETYPE
import nl.bravobit.ffmpeg.ExecuteBinaryResponseHandler
import protect.videoeditor.IFFmpegProcessService
import java.io.File
import kotlin.math.floor

class FFmpegProcessService : Service() {
    private var _activityMessenger: Messenger? = null

    /**
     * When the app's MainActivity is created, it starts this service. This is so that the
     * activity and this service can communicate back and forth. See "setUiCallback()"
     */
    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.i(
            TAG,
            "Received start command from activity"
        )
        _activityMessenger = intent.getParcelableExtra(MESSENGER_INTENT_KEY)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        Log.i(TAG, "Received binding.")
        return mBinder
    }

    private val mBinder: IFFmpegProcessService.Stub = object : IFFmpegProcessService.Stub() {
        @Throws(RemoteException::class)
        override fun startEncode(
            ffmpegArgs: List<String>,
            outputFile: String,
            mimetype: String,
            durationMs: Int
        ): Boolean {
            val result = FFmpegUtil.init(applicationContext)
            if (result) {
                val args = ffmpegArgs.toTypedArray()
                sendMessage(MessageId.JOB_START_MSG, outputFile)
                val handler: ExecuteBinaryResponseHandler =
                    object : ExecuteBinaryResponseHandler() {
                        override fun onFailure(s: String) {
                            Log.d(
                                TAG,
                                "Failed with output : $s"
                            )
                            clearNotification()

                            // The last line of the output should be the failure message
                            val lines =
                                s.split("\n".toRegex()).toTypedArray()
                            val failureMg =
                                lines[lines.size - 1].trim { it <= ' ' }
                            val bundle = Bundle()
                            bundle.putString(FFMPEG_FAILURE_MSG, failureMg)
                            sendMessage(MessageId.JOB_FAILED_MSG, bundle)
                        }

                        override fun onSuccess(s: String) {
                            Log.d(
                                TAG,
                                "Success with output : $s"
                            )
                            clearNotification()
                            val bundle = Bundle()
                            bundle.putString(FFMPEG_OUTPUT_FILE, outputFile)
                            bundle.putString(OUTPUT_MIMETYPE, mimetype)
                            sendMessage(MessageId.JOB_SUCCEDED_MSG, bundle)
                        }

                        override fun onProgress(s: String) {
                            // Progress updates look like the following:
                            // frame=   15 fps=7.1 q=2.0 size=      26kB time=00:00:00.69 bitrate= 309.4kbits/s dup=6 drop=0 speed=0.329x
                            var currentTimeMs: Long? = null
                            val split =
                                s.split(" ".toRegex()).toTypedArray()
                            for (item in split) {
                                if (item.startsWith("time=")) {
                                    val x = item.replace("time=", "")
                                    currentTimeMs = FFmpegUtil.timestampToMs(x)
                                    break
                                }
                            }
                            var percentComplete: Int? = null
                            if (currentTimeMs != null && currentTimeMs > 0) {
                                percentComplete = floor(
                                    currentTimeMs * 100 / durationMs.toDouble()
                                ).toInt()
                            }
                            sendMessage(MessageId.JOB_PROGRESS_MSG, percentComplete)
                        }
                    }
                if (outputFile != null) {
                    setNotification(File(outputFile).name)
                }
                FFmpegUtil.call(args, handler)
            } else {
                sendMessage(MessageId.FFMPEG_UNSUPPORTED_MSG, null)
            }
            return result
        }

        @Throws(RemoteException::class)
        override fun cancel() {
            Log.i(TAG, "Received cancel")
            clearNotification()
            FFmpegUtil.cancelCall()
            val bundle = Bundle()
            bundle.putString(FFMPEG_FAILURE_MSG, resources.getString(R.string.encodeCanceled))
            sendMessage(MessageId.JOB_FAILED_MSG, bundle)
        }

        @Throws(RemoteException::class)
        override fun isEncoding(): Boolean {
            return FFmpegUtil.isFFmpegRunning()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
    }

    override fun onDestroy() {
        super.onDestroy()
        _activityMessenger = null
        FFmpegUtil.cancelCall()
        Log.i(TAG, "Service destroyed")
    }

    private fun setNotification(filename: String) {
        val message =
            String.format(getString(R.string.encodingNotification), filename)
        var channelId = ""
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            channelId = createNotificationChannel()
        }
        val builder = NotificationCompat.Builder(this, channelId)
            .setOngoing(true)
            .setSmallIcon(R.drawable.encoding_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(message)

        // Creates an explicit intent for the Activity
        val resultIntent = Intent(this, MainActivity::class.java)
        val resultPendingIntent = PendingIntent.getActivity(
            this, 0,
            resultIntent, 0
        )
        builder.setContentIntent(resultPendingIntent)
        startForeground(NOTIFICATION_ID, builder.build())
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel(): String {
        val channelId = NOTIFICATION_CHANNEL_ID
        val channelName = getString(R.string.notificationChannelName)
        val chan = NotificationChannel(
            channelId,
            channelName, NotificationManager.IMPORTANCE_LOW
        )
        chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        val service =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        service?.createNotificationChannel(chan)
            ?: Log.w(
                TAG,
                "Could not get NotificationManager"
            )
        return channelId
    }

    private fun clearNotification() {
        stopForeground(true)
    }

    private fun sendMessage(messageId: MessageId, params: Any?) {
        // If this service is launched by the JobScheduler, there's no callback Messenger. It
        // only exists when the MainActivity calls startService() with the callback in the Intent.
        if (_activityMessenger == null) {
            Log.d(
                TAG,
                "Service is bound, not started. There's no callback to send a message to."
            )
            return
        }
        val m = Message.obtain()
        m.what = messageId.ordinal
        m.obj = params
        try {
            _activityMessenger!!.send(m)
        } catch (e: RemoteException) {
            Log.e(
                TAG,
                "Error passing service object back to activity.",
                e
            )
        }
    }

    companion object {
        private const val TAG = "VideoTranscoder"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CHANNEL_ID = TAG
    }
}
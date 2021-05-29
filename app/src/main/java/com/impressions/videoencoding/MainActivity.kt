package com.impressions.videoencoding

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.preference.PreferenceManager
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.AdapterView.OnItemSelectedListener
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.nononsenseapps.filepicker.BuildConfig
import com.nononsenseapps.filepicker.FilePickerActivity
import com.nononsenseapps.filepicker.Utils
import kotlinx.android.synthetic.main.activity_main.*
import org.javatuples.Triplet
import protect.videoeditor.IFFmpegProcessService
import java.io.File
import java.util.*

class MainActivity : AppCompatActivity() {

    private val TAG = "VideoTranscoder"

    private val SHARED_PREFS_KEY = "protect.videotranscoder"
    private val PICKER_DIR_PREF = "picker-start-path"
    private val SEND_INTENT_TMP_FILENAME: String = TAG + "-send-intent-file.tmp"
    private var ffmpegService: IFFmpegProcessService? = null
    private var videoInfo: MediaInfo? = null

    private val SEND_INTENT_TMP_FILE = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
        SEND_INTENT_TMP_FILENAME
    )


    companion object {
        val MESSENGER_INTENT_KEY =
            BuildConfig.APPLICATION_ID + ".MESSENGER_INTENT_KEY"
        val FFMPEG_OUTPUT_FILE =
            BuildConfig.APPLICATION_ID + ".FFMPEG_OUTPUT_FILE"
        val FFMPEG_FAILURE_MSG =
            BuildConfig.APPLICATION_ID + ".FFMPEG_FAILURE_MSG"
        val OUTPUT_MIMETYPE = BuildConfig.APPLICATION_ID + ".OUTPUT_MIMETYPE"
    }

    // A callback when a permission check is attempted and succeeds.
    private var permissionSuccessCallback: ResultCallbackHandler<Boolean>? = null

    private val READ_WRITE_PERMISSION_REQUEST = 1

    private val SELECT_FILE_REQUEST = 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        encodeProgress.isEnabled = false

        selectVideoButton.setOnClickListener {
            getPermission(
                ResultCallbackHandler { selectVideo() }
            )
        }

        if (FFmpegUtil.init(applicationContext) === false) {
            showUnsupportedExceptionDialog()
        }

        val serviceIntent = Intent(this, FFmpegProcessService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, ffmpegServiceConnection, BIND_AUTO_CREATE)
    }

    /**
     * Request read/write permissions, and if they are granted invokes a callback.
     * @param successCallback
     */
    private fun getPermission(successCallback: ResultCallbackHandler<Boolean>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            successCallback.onResult(true)
            return
        }
        var params: Array<String>? = null
        val writeExternalStorage = Manifest.permission.WRITE_EXTERNAL_STORAGE
        val readExternalStorage = Manifest.permission.READ_EXTERNAL_STORAGE
        val hasWriteExternalStoragePermission: Int =
            ActivityCompat.checkSelfPermission(this, writeExternalStorage)
        val hasReadExternalStoragePermission: Int =
            ActivityCompat.checkSelfPermission(this, readExternalStorage)
        val permissions: MutableList<String> =
            ArrayList()
        if (hasWriteExternalStoragePermission != PackageManager.PERMISSION_GRANTED) {
            permissions.add(writeExternalStorage)
        }
        if (hasReadExternalStoragePermission != PackageManager.PERMISSION_GRANTED) {
            permissions.add(readExternalStorage)
        }
        if (!permissions.isEmpty()) {
            params = permissions.toTypedArray()
        }
        if (params != null && params.isNotEmpty()) {
            permissionSuccessCallback = successCallback
            ActivityCompat.requestPermissions(
                this@MainActivity,
                params,
                READ_WRITE_PERMISSION_REQUEST
            )
        } else {
            successCallback.onResult(true)
            permissionSuccessCallback = null
        }
    }

    /**
     * Opening gallery for selecting video file
     */
    private fun selectVideo() {
        val i = Intent(this, FastScrollerFilePickerActivity::class.java)
        i.putExtra(FilePickerActivity.EXTRA_ALLOW_MULTIPLE, false)
        i.putExtra(FilePickerActivity.EXTRA_ALLOW_CREATE_DIR, false)
        i.putExtra(FilePickerActivity.EXTRA_MODE, FilePickerActivity.MODE_FILE)
        val searchFolder: String?
        var prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val fileSearchPath = prefs.getString("fileSearchPath", "last-used")
        when (fileSearchPath) {
            "last-used" -> {
                prefs = getSharedPreferences(SHARED_PREFS_KEY, MODE_PRIVATE)
                searchFolder = prefs.getString(
                    PICKER_DIR_PREF,
                    Environment.getExternalStorageDirectory().path
                )
            }
            "external" -> searchFolder =
                Environment.getExternalStorageDirectory().path
            else -> {
                prefs = getSharedPreferences(SHARED_PREFS_KEY, MODE_PRIVATE)
                searchFolder = prefs.getString(
                    PICKER_DIR_PREF,
                    Environment.getExternalStorageDirectory().path
                )
            }
        }
        i.putExtra(FilePickerActivity.EXTRA_START_PATH, searchFolder)
        startActivityForResult(i, SELECT_FILE_REQUEST)
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        intent: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, intent)
        if (requestCode == SELECT_FILE_REQUEST && resultCode == Activity.RESULT_OK) {
            // Use the provided utility method to parse the result
            val files =
                Utils.getSelectedFilesFromResult(intent!!)

            // There should be at most once result
            if (files.size > 0) {
                val file =
                    Utils.getFileForUri(files[0])

                // Save the directory where the file was selected, so the next
                // file search will start in that directory.
                val parentDir = file.parent
                val prefs = getSharedPreferences(
                    SHARED_PREFS_KEY,
                    Context.MODE_PRIVATE
                )
                prefs.edit().putString(PICKER_DIR_PREF, parentDir).apply()
                Log.i(TAG, "Selected file: " + file.absolutePath)

                FFmpegUtil.getMediaDetails(File(file.absolutePath)) { result ->
                    videoInfo = result
                    if (result != null) {
                        videoInfo?.fileBaseName = null
                    } else {
                        Toast.makeText(this@MainActivity, R.string.invalidMediaFile, Toast.LENGTH_LONG).show()
                    }

                    startEncode()
                }
            }
        }
    }

    fun startEncode() {

        if(videoInfo == null)
        {
            Toast.makeText(this, R.string.selectFileFirst, Toast.LENGTH_LONG).show();
            return;
        }

        val outputDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);

        if(!outputDir.exists())
        {
            val result = outputDir.mkdirs();
            if(!result)
            {
                Log.w(TAG, "Unable to create destination dir: " + outputDir.absolutePath);
            }
        }

        val fileBaseName = videoInfo?.fileBaseName;

        val inputFilePath = videoInfo?.file?.absolutePath;

        var destination = File(outputDir, "$fileBaseName.mp4");
        var fileNo = 0;
        while (destination.exists())
        {
            fileNo++;
            destination = File(outputDir, fileBaseName + "_" + fileNo + ".mp4");
        }

        val startTimeSec = 0
        val endTimeSec = 20

        val durationSec = (videoInfo?.durationMs!!/1000).toInt()

        startEncode(inputFilePath!!, startTimeSec, endTimeSec, durationSec, videoInfo?.container!!, VideoCodec.H264,
                    1, "960x540", "24", AudioCodec.AAC, 44100, "2",
                    64, destination.absolutePath);
    }


    /**
     * Handling response for permission request
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == READ_WRITE_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                permissionSuccessCallback!!.onResult(true)
                permissionSuccessCallback = null
            } else {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(R.string.writePermissionExplanation)
                    .setCancelable(true)
                    .setPositiveButton(R.string.ok
                    ) { dialog, _ -> dialog.dismiss() }
                    .setNegativeButton(
                        R.string.permissionRequestAgain
                    ) { dialog, _ ->
                        dialog.dismiss()
                        getPermission(permissionSuccessCallback!!)
                    }
                    .show()
            }
        }
    }

    private fun showUnsupportedExceptionDialog() {
        AlertDialog.Builder(this)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setTitle(R.string.notSupportedTitle)
            .setMessage(R.string.notSupportedMessage)
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok
            ) { _, _ -> finish() }
            .create()
            .show()
    }

    private val ffmpegServiceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            Log.i(TAG, "Bound to service")
            ffmpegService = IFFmpegProcessService.Stub.asInterface(service)
            try {
                // Check if a job is still being processed in the background from a previous
                // launch. If so, skip to that UI layout.
                if (ffmpegService!!.isEncoding) {
                    updateUiForEncoding()
                } else {
                    selectVideoButton.isEnabled = true
                }
                val intent = intent
                if (intent != null) {
                    val action = intent.action
                    if (action != null) {
                        if (action == "protect.videotranscoder.ENCODE") {
                            getPermission(
                                ResultCallbackHandler { b ->
                                    handleEncodeIntent(
                                        intent
                                    )
                                }
                            )
                        }
                        if (Intent.ACTION_SEND == action) {
                            getPermission(
                                ResultCallbackHandler { b ->
                                    processSendIntent(
                                        intent
                                    )
                                }
                            )
                        }
                    }
                }
            } catch (e: RemoteException) {
                Log.w(TAG, "Failed to query service", e)
                e.printStackTrace()
            }
        }

        override fun onServiceDisconnected(className: ComponentName) {
            ffmpegService = null
            // This method is only invoked when the service quits from the other end or gets killed
            // Invoking exit() from the AIDL interface makes the Service kill itself, thus invoking this.
            Log.i(TAG, "Service disconnected")
        }
    }

    private fun handleEncodeIntent(intent: Intent) {
        val inputFilePath = intent.getStringExtra("inputVideoFilePath")
        val destinationFilePath = intent.getStringExtra("outputFilePath")
        val mediaContainerStr = intent.getStringExtra("mediaContainer")
        val container = MediaContainer.fromName(mediaContainerStr)
        val videoCodecStr = intent.getStringExtra("videoCodec")
        val videoCodec = VideoCodec.fromName(videoCodecStr)
        val tmpCideoBitrateK = intent.getIntExtra("videoBitrateK", -1)
        val videoBitrateK = if (tmpCideoBitrateK != -1) tmpCideoBitrateK else null
        val resolution = intent.getStringExtra("resolution")
        val fps = intent.getStringExtra("fps")
        val audioCodecStr = intent.getStringExtra("audioCodec")
        val audioCodec = AudioCodec.fromName(audioCodecStr)
        val tmpAudioSampleRate = intent.getIntExtra("audioSampleRate", -1)
        val audioSampleRate = if (tmpAudioSampleRate != -1) tmpAudioSampleRate else null
        val audioChannel = intent.getStringExtra("audioChannel")
        val tmpAudioBitrateK = intent.getIntExtra("audioBitrateK", -1)
        val audioBitrateK = if (tmpAudioBitrateK != -1) tmpAudioBitrateK else null
        val skipDialog = intent.getBooleanExtra("skipDialog", false)
        val nullChecks: MutableList<Triplet<Any?, Int, String>> =
            LinkedList()
        nullChecks.add(
            Triplet(
                inputFilePath,
                R.string.fieldMissingError,
                "inputFilePath"
            )
        )
        nullChecks.add(
            Triplet(
                destinationFilePath,
                R.string.fieldMissingError,
                "outputFilePath"
            )
        )
        nullChecks.add(
            Triplet(
                container,
                R.string.fieldMissingOrInvalidError,
                "mediaContainer"
            )
        )
        if (container != null && container.supportedVideoCodecs.size > 0) {
            nullChecks.add(
                Triplet(
                    videoCodec,
                    R.string.fieldMissingOrInvalidError,
                    "videoCodec"
                )
            )
            nullChecks.add(
                Triplet(
                    videoBitrateK,
                    R.string.fieldMissingError,
                    "videoBitrateK missing"
                )
            )
            nullChecks.add(
                Triplet(
                    resolution,
                    R.string.fieldMissingError,
                    "resolution"
                )
            )
            nullChecks.add(
                Triplet(
                    fps,
                    R.string.fieldMissingError,
                    "fps"
                )
            )
        }
        if (container != null && container.supportedAudioCodecs.size > 0) {
            nullChecks.add(
                Triplet(
                    audioCodec,
                    R.string.fieldMissingOrInvalidError,
                    "audioCodec"
                )
            )
            nullChecks.add(
                Triplet(
                    audioSampleRate,
                    R.string.fieldMissingError,
                    "audioSampleRate"
                )
            )
            nullChecks.add(
                Triplet(
                    audioChannel,
                    R.string.fieldMissingError,
                    "audioChannel"
                )
            )
            nullChecks.add(
                Triplet(
                    audioBitrateK,
                    R.string.fieldMissingError,
                    "audioBitrateK"
                )
            )
        }
        for (check in nullChecks) {
            if (check.value0 == null) {
                val submsg =
                    String.format(getString(check.value1), check.value2)
                val message =
                    String.format(getString(R.string.cannotEncodeFile), submsg)
                Log.i(TAG, message)
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                finish()
                return
            }
        }
        val message = String.format(
            getString(R.string.encodeStartConfirmation),
            inputFilePath,
            destinationFilePath
        )
        val positiveButtonListener =
            DialogInterface.OnClickListener { dialog, which ->
                FFmpegUtil.getMediaDetails(
                    File(inputFilePath)
                ) { result ->
                    if (result != null) {
                        val durationSec = (result.durationMs / 1000).toInt()
                        startEncode(
                            inputFilePath!!,
                            0,
                            durationSec,
                            durationSec,
                            container,
                            videoCodec!!,
                            videoBitrateK!!,
                            resolution!!,
                            fps!!,
                            audioCodec!!,
                            audioSampleRate!!,
                            audioChannel!!,
                            audioBitrateK!!,
                            destinationFilePath!!
                        )
                    } else {
                        val message = String.format(
                            getString(R.string.transcodeFailed),
                            getString(R.string.couldNotFindFileSubmsg)
                        )
                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG)
                            .show()
                        finish()
                        dialog.dismiss()
                    }
                }
            }
        val dialog =
            AlertDialog.Builder(this)
                .setMessage(message)
                .setCancelable(false)
                .setNegativeButton(
                    R.string.cancel
                ) { dialog, which ->
                    finish()
                    dialog.dismiss()
                }
                .setPositiveButton(R.string.encode, positiveButtonListener).create()
        dialog.show()
        if (skipDialog) {
            positiveButtonListener.onClick(dialog, 0)
            dialog.dismiss()
        }
    }

    private fun getFfmpegEncodingArgs(
        inputFilePath: String, startTimeSec: Int?, endTimeSec: Int?, fullDurationSec: Int,
        container: MediaContainer, videoCodec: VideoCodec, videoBitrateK: Int,
        resolution: String, fps: String, audioCodec: AudioCodec, audioSampleRate: Int,
        audioChannel: String, audioBitrateK: Int, destinationFilePath: String
    ): List<String>? {
        val command: MutableList<String> =
            LinkedList()

        // If the output exists, overwrite it
        command.add("-y")

        // Input file
        command.add("-i")
        command.add(inputFilePath)
        if (startTimeSec != null && startTimeSec != 0) {
            // Start time offset
            command.add("-ss")
            command.add(startTimeSec.toString())
        }
        if (startTimeSec != null && endTimeSec != null) {
            val subDurationSec = endTimeSec - startTimeSec
            if (fullDurationSec != subDurationSec) {
                // Duration of media file
                command.add("-t")
                command.add(subDurationSec.toString())
            }
        }
        if (container.supportedVideoCodecs.size > 0) {
            // These options only apply when not using GIF
            if (videoCodec !== VideoCodec.GIF) {
                // Video codec
                command.add("-vcodec")
                command.add(videoCodec.ffmpegName)

                // Video bitrate
                command.add("-b:v")
                command.add(videoBitrateK.toString() + "k")
            }
            command.addAll(videoCodec.extraFfmpegArgs)

            // Frame size
            command.add("-s")
            command.add(resolution)

            // Frame rate
            command.add("-r")
            command.add(fps)
        } else {
            // No video
            command.add("-vn")
        }
        if (container.supportedAudioCodecs.size > 0 && audioCodec !== AudioCodec.NONE) {
            // Audio codec
            command.add("-acodec")
            command.add(audioCodec.ffmpegName)
            command.addAll(audioCodec.extraFfmpegArgs)

            // Sample rate
            command.add("-ar")
            command.add(Integer.toString(audioSampleRate))

            // Channels
            command.add("-ac")
            command.add(audioChannel)

            // Audio bitrate
            command.add("-b:a")
            command.add(audioBitrateK.toString() + "k")
        } else {
            // No audio
            command.add("-an")
        }

        // Output file
        command.add(destinationFilePath)
        return command
    }


    private fun startEncode(
        inputFilePath: String, startTimeSec: Int, endTimeSec: Int, fullDurationSec: Int,
        container: MediaContainer, videoCodec: VideoCodec, videoBitrateK: Int,
        resolution: String, fps: String, audioCodec: AudioCodec, audioSampleRate: Int,
        audioChannel: String, audioBitrateK: Int, destinationFilePath: String
    ) {
        val args: List<String>? = getFfmpegEncodingArgs(
            inputFilePath, startTimeSec, endTimeSec, fullDurationSec,
            container, videoCodec, videoBitrateK, resolution, fps, audioCodec, audioSampleRate,
            audioChannel, audioBitrateK, destinationFilePath
        )
        updateUiForEncoding()
        var success = false
        try {
            Log.d(TAG, "Sending encode request to service")
            val durationSec = endTimeSec - startTimeSec
            success = ffmpegService!!.startEncode(
                args,
                destinationFilePath,
                container.mimetype,
                durationSec * 1000
            )
        } catch (e: RemoteException) {
            Log.w(TAG, "Failed to send encode request to service", e)
        }
        if (!success) {
            updateUiForVideoSettings()
        }
    }

    private fun updateUiForVideoSettings() {
        selectVideoButton.visibility = View.VISIBLE
        encodeProgress.visibility = View.GONE
    }

    private fun updateUiForEncoding() {

        encodeProgress.progress = 0
        encodeProgress.isIndeterminate = true
        selectVideoButton.visibility = View.GONE
        encodeProgress.visibility = View.VISIBLE
    }

    private fun processSendIntent(intent: Intent) {
        val dataUri =
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        var filename: String? = null
        val path = dataUri?.path
        if (path != null) {
            filename = File(path).name
            if (filename.contains(".")) {
                filename = filename.substring(0, filename.lastIndexOf("."))
            }
        }
        val actualBaseName = filename
        val callback =
            ResultCallbackHandler<Boolean> { result ->
                if (result) {
                    Log.i(
                        TAG,
                        "Copied file from share intent: " + SEND_INTENT_TMP_FILE.getAbsolutePath()
                    )
                    /*setSelectMediaFile(
                        SEND_INTENT_TMP_FILE.absolutePath,
                        actualBaseName
                    )*/


                } else {
                    Log.w(TAG, "Failed to received file from send intent")
                    Toast.makeText(this, R.string.failedToReceiveSharedData, Toast.LENGTH_LONG)
                        .show()
                }
            }
        if (dataUri != null) {
            val saveTask =
                UriSaveTask(this, dataUri, SEND_INTENT_TMP_FILE, callback)
            saveTask.execute()
        } else {
            callback.onResult(false)
        }
    }
}
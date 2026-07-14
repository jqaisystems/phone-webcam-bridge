package com.jq.phonewebcam

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat

/**
 * Thin UI over [WebcamService]. The service owns the camera, HTTP server, and beacon,
 * so streaming continues with the screen off or this activity gone; this class only
 * renders the preview, shows the PIN/URL, and forwards button presses.
 */
class MainActivity : ComponentActivity() {
    private val uiHandler = Handler(Looper.getMainLooper())
    private val uiRefresh = object : Runnable {
        override fun run() {
            updateUi()
            uiHandler.postDelayed(this, UiRefreshIntervalMs)
        }
    }

    private lateinit var previewView: PreviewView
    private lateinit var pinText: TextView
    private lateinit var urlText: TextView
    private lateinit var statusText: TextView
    private lateinit var profileButton: Button
    private lateinit var cameraButton: Button
    private lateinit var mirrorButton: Button
    private lateinit var torchButton: Button
    private lateinit var micButton: Button

    private var service: WebcamService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as WebcamService.LocalBinder).service
            service?.attachPreview(previewView.surfaceProvider)
            updateUi()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // Camera is mandatory; the notification permission is best-effort (denying it
        // only hides the ongoing notification, streaming still works).
        if (results[Manifest.permission.CAMERA] != false && hasCameraPermission()) {
            startWebcamService()
        } else {
            statusText.text = "Camera permission is required."
        }
    }

    // Requested lazily the first time the mic is turned on.
    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            applyControl(ControlCommand(mic = true))
        } else {
            statusText.text = "Microphone permission is required for audio."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        updateUi()
        uiHandler.postDelayed(uiRefresh, UiRefreshIntervalMs)

        val wanted = mutableListOf<String>()
        if (!hasCameraPermission()) {
            wanted.add(Manifest.permission.CAMERA)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission()) {
            // Without this the foreground-service notification (with its Stop button)
            // is invisible on Android 13+.
            wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (wanted.isEmpty()) {
            startWebcamService()
        } else {
            permissionLauncher.launch(wanted.toTypedArray())
        }
    }

    override fun onStart() {
        super.onStart()
        if (hasCameraPermission()) {
            bindService(Intent(this, WebcamService::class.java), connection, Context.BIND_AUTO_CREATE)
            bound = true
        }
    }

    override fun onStop() {
        service?.detachPreview()
        if (bound) {
            unbindService(connection)
            bound = false
        }
        service = null
        super.onStop()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(uiRefresh)
        super.onDestroy()
    }

    private fun startWebcamService() {
        val intent = Intent(this, WebcamService::class.java)
        // Must start while visible: Android 11+ only grants camera access to a
        // foreground service started from a visible app.
        ContextCompat.startForegroundService(this, intent)
        if (!bound) {
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
            bound = true
        }
    }

    private fun applyControl(command: ControlCommand) {
        service?.applyControl(command)
        updateUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#111317"))
        }

        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        root.addView(previewView)

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(18))
            setBackgroundColor(Color.parseColor("#1B1F26"))
        }

        pinText = labelText(size = 28f, color = "#F4F7FB").apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }
        urlText = labelText(size = 14f, color = "#A9B4C3").apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(4), 0, dp(10))
        }
        statusText = labelText(size = 14f, color = "#A9B4C3").apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(12))
        }

        panel.addView(pinText)
        panel.addView(urlText)
        panel.addView(statusText)

        val rowOne = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val rowTwo = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        profileButton = actionButton()
        cameraButton = actionButton()
        mirrorButton = actionButton()
        torchButton = actionButton()
        micButton = actionButton()

        profileButton.setOnClickListener {
            val current = service?.settings ?: return@setOnClickListener
            val currentIndex = VideoProfiles.all.indexOf(current.profile).coerceAtLeast(0)
            val next = VideoProfiles.all[(currentIndex + 1) % VideoProfiles.all.size]
            applyControl(ControlCommand(profileId = next.id))
        }
        cameraButton.setOnClickListener {
            val current = service?.settings ?: return@setOnClickListener
            val next = if (current.camera == CameraFacing.FRONT) CameraFacing.BACK else CameraFacing.FRONT
            applyControl(ControlCommand(camera = next))
        }
        mirrorButton.setOnClickListener {
            val current = service?.settings ?: return@setOnClickListener
            applyControl(ControlCommand(mirror = !current.mirror))
        }
        torchButton.setOnClickListener {
            val current = service?.settings ?: return@setOnClickListener
            applyControl(ControlCommand(torch = !current.torch))
        }
        micButton.setOnClickListener {
            val current = service?.settings ?: return@setOnClickListener
            if (current.mic) {
                applyControl(ControlCommand(mic = false))
            } else if (hasMicPermission()) {
                applyControl(ControlCommand(mic = true))
            } else {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        rowOne.addView(profileButton)
        rowOne.addView(cameraButton)
        rowTwo.addView(mirrorButton)
        rowTwo.addView(micButton)
        rowTwo.addView(torchButton)
        panel.addView(rowOne)
        panel.addView(rowTwo)
        root.addView(panel)

        setContentView(root)
    }

    private fun updateUi() {
        val activeService = service
        if (activeService?.stopping == true) {
            // Notification Stop was tapped while we're bound: release the service and close.
            finish()
            return
        }
        val snapshot = activeService?.settings ?: StreamSettings()
        val ip = NetworkUtils.localIpv4Address()
        pinText.text = "PIN ${activeService?.pin ?: AppIdentity.pin(this)}"
        urlText.text = ip?.let { "http://$it:${WebcamHttpServer.PHONE_PORT}" } ?: "Waiting for network"
        statusText.text = activeService?.statusMessage
            ?: "${snapshot.profile.label} | ${snapshot.camera.name.lowercase()}"
        profileButton.text = snapshot.profile.label
        cameraButton.text = if (snapshot.camera == CameraFacing.FRONT) "Front camera" else "Back camera"
        mirrorButton.text = if (snapshot.mirror) "Mirror on" else "Mirror off"
        micButton.text = if (snapshot.mic) "Mic on" else "Mic off"
        torchButton.text = if (snapshot.torch) "Torch on" else "Torch off"
        // Torch only exists on the back camera (front has no flash), so hide it entirely
        // on the front instead of leaving a dead button that appears to do nothing.
        torchButton.visibility = if (snapshot.camera == CameraFacing.BACK) View.VISIBLE else View.GONE
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun labelText(size: Float, color: String): TextView {
        return TextView(this).apply {
            textSize = size
            setTextColor(Color.parseColor(color))
            includeFontPadding = true
        }
    }

    private fun actionButton(): Button {
        return Button(this).apply {
            minHeight = dp(46)
            setTextColor(Color.parseColor("#07110E"))
            setBackgroundColor(Color.parseColor("#42C9A7"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val UiRefreshIntervalMs = 3000L
    }
}

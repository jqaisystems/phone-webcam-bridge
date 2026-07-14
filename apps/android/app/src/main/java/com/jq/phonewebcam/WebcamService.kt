package com.jq.phonewebcam

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.camera2.CaptureRequest
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Foreground service that owns the camera, the HTTP server, and the discovery beacon,
 * so streaming keeps running with the screen off or the app in the background.
 * MainActivity is only a thin UI over this service.
 */
class WebcamService : LifecycleService() {
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // Two JPEG encoders let capture and encode overlap (a single thread caps ~21 fps).
    // SynchronousQueue = no backlog: when both are busy the frame is skipped, so
    // latency never creeps up.
    private val encodeExecutor = ThreadPoolExecutor(
        2, 2, 30, TimeUnit.SECONDS, SynchronousQueue()
    ).apply { allowCoreThreadTimeOut(true) }
    private val frameCounter = AtomicLong(0)
    private val frameStore = FrameStore()
    private val audioStore = AudioStore()
    private val audioCapture = AudioCapture(audioStore)
    private val mainHandler = Handler(Looper.getMainLooper())

    val pin: String by lazy { AppIdentity.pin(this) }

    @Volatile
    var settings = StreamSettings()
        private set

    /** Last server/camera problem worth showing in the UI, or null when healthy. */
    @Volatile
    var statusMessage: String? = null
        private set

    /** True once the notification Stop action fired; the bound UI closes itself. */
    @Volatile
    var stopping = false
        private set

    private var server: WebcamHttpServer? = null
    private var beacon: DiscoveryBeacon? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var previewSurfaceProvider: Preview.SurfaceProvider? = null
    private var nextFrameAt = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var started = false

    // Live camera adjustments requested via /control; reapplied after every rebind.
    private var requestedZoomRatio: Float? = null
    private var requestedExposureIndex: Int? = null

    inner class LocalBinder : Binder() {
        val service: WebcamService get() = this@WebcamService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            handleStop()
            return Service.START_NOT_STICKY
        }

        if (!started && !stopping) {
            started = true
            startAsForeground()
            acquireLocks()
            startLocalServer()
            startCamera()
        }
        return Service.START_STICKY
    }

    /**
     * Stops streaming immediately even if the activity is still bound (a bound service
     * ignores stopSelf until every client unbinds; the UI watches [stopping] and closes).
     */
    private fun handleStop() {
        if (stopping) {
            return
        }
        stopping = true
        teardown()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun teardown() {
        audioCapture.stop()
        beacon?.stop()
        beacon = null
        server?.stop()
        server = null
        cameraProvider?.unbindAll()
        camera = null
        preview = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    override fun onDestroy() {
        teardown()
        cameraExecutor.shutdown()
        encodeExecutor.shutdown()
        super.onDestroy()
    }

    // region public API for MainActivity

    /** Renders the camera preview into the activity's PreviewView while it is visible. */
    fun attachPreview(surfaceProvider: Preview.SurfaceProvider) {
        mainHandler.post {
            previewSurfaceProvider = surfaceProvider
            preview?.setSurfaceProvider(surfaceProvider)
        }
    }

    /** Stops rendering the preview (analysis keeps producing frames). */
    fun detachPreview() {
        mainHandler.post {
            previewSurfaceProvider = null
            preview?.setSurfaceProvider(null)
        }
    }

    fun applyControl(command: ControlCommand) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyControlNow(command)
        } else {
            mainHandler.post { applyControlNow(command) }
        }
    }

    // endregion

    private fun startAsForeground() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL,
                    "Phone Webcam",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }

        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, WebcamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Phone Webcam is streaming")
            .setContentText("PIN $pin - tap to open")
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .build()

        // Only declare types whose permissions are actually granted: declaring the
        // microphone type without RECORD_AUDIO throws on Android 14+.
        var types = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, types)
    }

    private fun acquireLocks() {
        // The camera foreground service type keeps camera access with the screen off,
        // but JPEG encode + the HTTP server still need CPU; the partial wake lock stops
        // aggressive OEMs (MIUI) from throttling them.
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "PhoneWebcam:stream"
        ).apply { acquire() }

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wifiManager.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "PhoneWebcam:wifi"
        ).apply { acquire() }
    }

    private fun startLocalServer(attempt: Int = 0) {
        try {
            // A previous instance can still hold the port briefly after a quick
            // relaunch, making the bind fail silently. Stop leftovers and retry.
            server?.stop()
            server = null
            server = WebcamHttpServer(
                context = this,
                frameStore = frameStore,
                pinProvider = { pin },
                settingsProvider = { settings },
                onControl = ::applyControl,
                cameraStateProvider = ::cameraLiveState,
                audioStore = audioStore
            ).also { it.start(NanoHttpServerTimeoutMs, false) }
            statusMessage = null

            if (beacon == null) {
                beacon = DiscoveryBeacon(
                    context = this,
                    phonePort = WebcamHttpServer.PHONE_PORT,
                    discoveryPort = 47887,
                    pinProvider = { pin }
                ).also { it.start() }
            }
        } catch (error: Exception) {
            if (attempt < MaxServerBindAttempts - 1) {
                statusMessage = "Server port busy, retrying (${attempt + 2}/$MaxServerBindAttempts)..."
                mainHandler.postDelayed({ startLocalServer(attempt + 1) }, ServerBindRetryDelayMs)
            } else {
                statusMessage = "Server failed: ${error.message}"
            }
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener(
            {
                cameraProvider = providerFuture.get()
                bindCamera()
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        val snapshot = settings
        val profile = snapshot.profile
        val selector = if (snapshot.camera == CameraFacing.FRONT) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        // Hard cap the analysis resolution. setTargetResolution is only a hint and some
        // front cameras ignore it entirely (this Xiaomi handed back full-sensor 1944x1944,
        // ~4x the pixels of 720p, which crushed the encode rate). ResolutionStrategy forces
        // the closest supported size to the profile instead.
        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(profile.width, profile.height),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                )
            )
            .build()

        val newPreview = Preview.Builder()
            .setResolutionSelector(resolutionSelector)
            .build()
            .also { it.setSurfaceProvider(previewSurfaceProvider) }

        val analysisBuilder = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)

        // Ask the sensor to actually run at the profile fps. Without this CameraX picks
        // its own (often low) rate and the software throttle below can only drop frames,
        // never raise them. Allow the low end to drop to 15 so dim scenes don't go black.
        Camera2Interop.Extender(analysisBuilder)
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                Range(minOf(15, profile.fps), profile.fps)
            )

        val analysis = analysisBuilder.build()

        analysis.setAnalyzer(cameraExecutor) { image ->
            try {
                val activeSettings = settings
                val now = SystemClock.elapsedRealtime()
                // Deadline pacing instead of "interval since last frame": camera frames
                // jitter around 33ms, and the old check dropped any that arrived a hair
                // early, cutting 30fps delivery to ~19. Advancing a deadline keeps the
                // long-run average exactly at profile fps regardless of jitter.
                val minInterval = 1000L / activeSettings.profile.fps
                if (now < nextFrameAt) {
                    return@setAnalyzer
                }
                nextFrameAt = maxOf(nextFrameAt + minInterval, now)
                // Copy the pixels out quickly (must happen before image.close()),
                // then encode off the camera thread so capture and encode overlap.
                val captured = image.captureNv21(mirror = activeSettings.mirror)
                val producerSeq = frameCounter.incrementAndGet()
                val quality = activeSettings.profile.jpegQuality
                try {
                    encodeExecutor.execute {
                        frameStore.update(captured.encodeJpeg(quality), producerSeq)
                    }
                } catch (_: RejectedExecutionException) {
                    // Both encoders busy: skip this frame rather than queue latency.
                }
            } finally {
                image.close()
            }
        }

        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(this, selector, newPreview, analysis)
            preview = newPreview
            statusMessage = null
        } catch (error: Exception) {
            // Camera unavailable (another app holds it, or the service was restarted
            // from the background where camera access is denied on Android 11+).
            statusMessage = "Camera unavailable: ${error.message}. Open the app to resume."
            return
        }
        setTorch(snapshot.torch)
        applyZoomAndExposure()
    }

    private fun applyControlNow(command: ControlCommand) {
        var next = settings
        var needsRebind = false

        command.profileId?.let { profileId ->
            VideoProfiles.byId(profileId)?.let { profile ->
                if (next.profile != profile) {
                    next = next.copy(profile = profile)
                    needsRebind = true
                }
            }
        }

        command.camera?.let { cameraFacing ->
            if (next.camera != cameraFacing) {
                next = next.copy(
                    camera = cameraFacing,
                    torch = if (cameraFacing == CameraFacing.FRONT) false else next.torch
                )
                needsRebind = true
            }
        }

        command.mirror?.let { next = next.copy(mirror = it) }
        command.torch?.let { next = next.copy(torch = it && next.camera == CameraFacing.BACK) }
        command.zoomRatio?.let { requestedZoomRatio = it }
        command.exposureIndex?.let { requestedExposureIndex = it }
        command.mic?.let { wantMic ->
            next = next.copy(mic = if (wantMic) startMic() else stopMic())
        }

        if (command.camera != null && needsRebind) {
            // Zoom/exposure ranges differ per camera; reset when switching.
            requestedZoomRatio = null
            requestedExposureIndex = null
        }

        settings = next
        if (needsRebind) {
            bindCamera()
        } else {
            setTorch(next.torch)
            applyZoomAndExposure()
        }
    }

    private fun startMic(): Boolean {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            statusMessage = "Microphone permission is required for audio."
            return false
        }
        val startedOk = audioCapture.start()
        if (startedOk) {
            statusMessage = null
            // Re-declare the foreground types now that the mic permission is in use, so
            // Android keeps granting mic access with the screen off (Android 14+).
            startAsForeground()
        } else {
            statusMessage = "Microphone could not be started."
        }
        return startedOk
    }

    private fun stopMic(): Boolean {
        audioCapture.stop()
        return false
    }

    private fun setTorch(enabled: Boolean) {
        val activeCamera = camera ?: return
        if (activeCamera.cameraInfo.hasFlashUnit()) {
            activeCamera.cameraControl.enableTorch(enabled)
        }
    }

    private fun applyZoomAndExposure() {
        val activeCamera = camera ?: return
        requestedZoomRatio?.let { requested ->
            val zoomState = activeCamera.cameraInfo.zoomState.value
            val clamped = requested.coerceIn(
                zoomState?.minZoomRatio ?: 1f,
                zoomState?.maxZoomRatio ?: 1f
            )
            activeCamera.cameraControl.setZoomRatio(clamped)
        }
        requestedExposureIndex?.let { requested ->
            val exposureState = activeCamera.cameraInfo.exposureState
            if (exposureState.isExposureCompensationSupported) {
                val range = exposureState.exposureCompensationRange
                activeCamera.cameraControl.setExposureCompensationIndex(
                    requested.coerceIn(range.lower, range.upper)
                )
            }
        }
    }

    /** Live zoom/exposure values and ranges for /status (drives the dashboard sliders). */
    private fun cameraLiveState(): CameraLiveState? {
        val activeCamera = camera ?: return null
        val zoomState = activeCamera.cameraInfo.zoomState.value
        val exposureState = activeCamera.cameraInfo.exposureState
        return CameraLiveState(
            zoomRatio = zoomState?.zoomRatio ?: 1f,
            maxZoomRatio = zoomState?.maxZoomRatio ?: 1f,
            exposureIndex = exposureState.exposureCompensationIndex,
            minExposureIndex = exposureState.exposureCompensationRange.lower,
            maxExposureIndex = exposureState.exposureCompensationRange.upper
        )
    }

    companion object {
        const val ACTION_STOP = "com.jq.phonewebcam.action.STOP"
        private const val NOTIFICATION_CHANNEL = "webcam"
        private const val NOTIFICATION_ID = 1001
        private const val NanoHttpServerTimeoutMs = 5000
        private const val MaxServerBindAttempts = 5
        private const val ServerBindRetryDelayMs = 2000L
    }
}

package com.jq.phonewebcam

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.InputStream
import java.security.SecureRandom
import java.util.Collections

class WebcamHttpServer(
    private val context: Context,
    private val frameStore: FrameStore,
    private val pinProvider: () -> String,
    private val settingsProvider: () -> StreamSettings,
    private val onControl: (ControlCommand) -> Unit,
    private val cameraStateProvider: () -> CameraLiveState? = { null },
    private val audioStore: AudioStore? = null
) : NanoHTTPD(PHONE_PORT) {
    private val tokens = Collections.synchronizedSet(mutableSetOf<String>())
    private val random = SecureRandom()

    override fun serve(session: IHTTPSession): Response {
        return try {
            when (session.uri) {
                "/status" -> json(statusJson(tokenValidity(session)))
                "/pair" -> handlePair(session)
                "/snapshot.jpg" -> handleSnapshot(session)
                "/stream.mjpg" -> handleStream(session)
                "/audio.wav" -> handleAudio(session)
                "/control" -> handleControl(session)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "Not found")
            }
        } catch (error: Exception) {
            json(
                JSONObject()
                    .put("ok", false)
                    .put("message", error.message ?: "Request failed"),
                Response.Status.INTERNAL_ERROR
            )
        }
    }

    private fun handlePair(session: IHTTPSession): Response {
        if (session.method != Method.POST) {
            return json(JSONObject().put("ok", false).put("message", "POST required"), Response.Status.METHOD_NOT_ALLOWED)
        }

        val body = readBody(session)
        val pin = if (body.isBlank()) "" else JSONObject(body).optString("pin")
        if (pin != pinProvider()) {
            return json(JSONObject().put("ok", false).put("message", "Invalid PIN"), Response.Status.UNAUTHORIZED)
        }

        val token = newToken()
        tokens.add(token)
        return json(
            JSONObject()
                .put("ok", true)
                .put("token", token)
                .put("status", statusJson())
        )
    }

    private fun handleSnapshot(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return unauthorized()
        }

        val snapshot = frameStore.latest()
            ?: return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                NanoHTTPD.MIME_PLAINTEXT,
                "No frame yet"
            )

        return newFixedLengthResponse(
            Response.Status.OK,
            "image/jpeg",
            snapshot.bytes.inputStream(),
            snapshot.bytes.size.toLong()
        ).withNoCache()
    }

    private fun handleStream(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return unauthorized()
        }

        return newChunkedResponse(
            Response.Status.OK,
            "multipart/x-mixed-replace; boundary=frame",
            MjpegInputStream(frameStore)
        ).withNoCache()
    }

    private fun handleAudio(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return unauthorized()
        }
        val store = audioStore
            ?: return json(
                JSONObject().put("ok", false).put("message", "Audio is not available"),
                Response.Status.SERVICE_UNAVAILABLE
            )
        if (!settingsProvider().mic) {
            return json(
                JSONObject().put("ok", false).put("message", "Mic is off"),
                Response.Status.SERVICE_UNAVAILABLE
            )
        }
        return newChunkedResponse(
            Response.Status.OK,
            "audio/wav",
            WavPcmInputStream(store)
        ).withNoCache()
    }

    private fun handleControl(session: IHTTPSession): Response {
        if (session.method != Method.POST) {
            return json(JSONObject().put("ok", false).put("message", "POST required"), Response.Status.METHOD_NOT_ALLOWED)
        }
        if (!isAuthorized(session)) {
            return unauthorized()
        }

        val body = readBody(session)
        val payload = if (body.isBlank()) JSONObject() else JSONObject(body)
        val command = ControlCommand(
            profileId = payload.optString("profileId").takeIf { it.isNotBlank() },
            camera = payload.optString("camera").takeIf { it.isNotBlank() }?.let {
                if (it.equals("back", ignoreCase = true)) CameraFacing.BACK else CameraFacing.FRONT
            },
            mirror = if (payload.has("mirror")) payload.optBoolean("mirror") else null,
            torch = if (payload.has("torch")) payload.optBoolean("torch") else null,
            zoomRatio = if (payload.has("zoomRatio")) payload.optDouble("zoomRatio").toFloat() else null,
            exposureIndex = if (payload.has("exposureIndex")) payload.optInt("exposureIndex") else null,
            mic = if (payload.has("mic")) payload.optBoolean("mic") else null
        )

        onControl(command)
        return json(JSONObject().put("ok", true).put("status", statusJson()))
    }

    private fun statusJson(tokenValid: Boolean? = null): JSONObject {
        val settings = settingsProvider()
        val profile = settings.profile
        val localIp = NetworkUtils.localIpv4Address()
        return JSONObject()
            .put("ok", true)
            .put("online", true)
            // Lets the PC bridge notice its session token died (app restart wipes
            // the in-memory token set) and silently re-pair with the saved PIN.
            .apply { tokenValid?.let { put("tokenValid", it) } }
            .put("deviceId", AppIdentity.deviceId(context))
            .put("deviceName", android.os.Build.MODEL ?: "Android Phone")
            .put("port", PHONE_PORT)
            .put("baseUrl", localIp?.let { "http://$it:$PHONE_PORT" } ?: JSONObject.NULL)
            .put("pinRequired", true)
            .put("hasFrame", frameStore.latest() != null)
            .put("actualFps", Math.round(frameStore.currentFps() * 10.0) / 10.0)
            .put(
                "settings",
                JSONObject()
                    .put("profileId", profile.id)
                    .put("profileLabel", profile.label)
                    .put("width", profile.width)
                    .put("height", profile.height)
                    .put("fps", profile.fps)
                    .put("jpegQuality", profile.jpegQuality)
                    .put("camera", settings.camera.name.lowercase())
                    .put("mirror", settings.mirror)
                    .put("torch", settings.torch)
                    .put("mic", settings.mic)
            )
            .put(
                "audio",
                JSONObject()
                    .put("available", audioStore != null)
                    .put("sampleRate", AudioCapture.SampleRate)
                    .put("channels", AudioCapture.Channels)
                    .put("bitsPerSample", AudioCapture.BitsPerSample)
            )
            .apply {
                cameraStateProvider()?.let { state ->
                    put(
                        "cameraState",
                        JSONObject()
                            .put("zoomRatio", Math.round(state.zoomRatio * 100.0) / 100.0)
                            .put("maxZoomRatio", Math.round(state.maxZoomRatio * 100.0) / 100.0)
                            .put("exposureIndex", state.exposureIndex)
                            .put("minExposureIndex", state.minExposureIndex)
                            .put("maxExposureIndex", state.maxExposureIndex)
                    )
                }
            }
    }

    private fun isAuthorized(session: IHTTPSession): Boolean {
        return tokenValidity(session) == true
    }

    /** null = no token presented; true/false = presented token is (in)valid. */
    private fun tokenValidity(session: IHTTPSession): Boolean? {
        val queryToken = session.parameters["token"]?.firstOrNull()
        val bearerToken = session.headers["authorization"]
            ?.removePrefix("Bearer ")
            ?.removePrefix("bearer ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        if (queryToken == null && bearerToken == null) {
            return null
        }
        return tokens.contains(queryToken) || tokens.contains(bearerToken)
    }

    private fun readBody(session: IHTTPSession): String {
        val files = HashMap<String, String>()
        session.parseBody(files)
        return files["postData"].orEmpty()
    }

    private fun unauthorized(): Response {
        return json(JSONObject().put("ok", false).put("message", "Unauthorized"), Response.Status.UNAUTHORIZED)
    }

    private fun json(payload: JSONObject, status: Response.Status = Response.Status.OK): Response {
        return newFixedLengthResponse(status, "application/json; charset=utf-8", payload.toString())
            .withNoCache()
    }

    private fun newToken(): String {
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun Response.withNoCache(): Response {
        addHeader("Cache-Control", "no-store, no-cache, must-revalidate")
        addHeader("Pragma", "no-cache")
        addHeader("Access-Control-Allow-Origin", "*")
        return this
    }

    private class MjpegInputStream(
        private val frameStore: FrameStore
    ) : InputStream() {
        private var lastSequence = -1L
        private var packet = ByteArray(0)
        private var offset = 0

        override fun read(): Int {
            val one = ByteArray(1)
            val count = read(one, 0, 1)
            return if (count <= 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, byteOffset: Int, byteCount: Int): Int {
            if (offset >= packet.size && !loadNextPacket()) {
                return -1
            }

            val count = minOf(byteCount, packet.size - offset)
            System.arraycopy(packet, offset, buffer, byteOffset, count)
            offset += count
            return count
        }

        private fun loadNextPacket(): Boolean {
            val snapshot = frameStore.waitForNext(lastSequence, 10000) ?: return false
            lastSequence = snapshot.sequence
            val header = (
                "--frame\r\n" +
                    "Content-Type: image/jpeg\r\n" +
                    "Content-Length: ${snapshot.bytes.size}\r\n\r\n"
                ).toByteArray()
            packet = header + snapshot.bytes + "\r\n".toByteArray()
            offset = 0
            return true
        }
    }

    /**
     * An endless WAV stream: a 44-byte header with 0xFFFFFFFF sizes, then raw PCM
     * chunks as the mic produces them. Decoders treat it as a live stream.
     */
    private class WavPcmInputStream(
        private val audioStore: AudioStore
    ) : InputStream() {
        private var lastSequence = -1L
        private var packet = wavHeader()
        private var offset = 0

        override fun read(): Int {
            val one = ByteArray(1)
            val count = read(one, 0, 1)
            return if (count <= 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, byteOffset: Int, byteCount: Int): Int {
            if (offset >= packet.size && !loadNextPacket()) {
                return -1
            }
            val count = minOf(byteCount, packet.size - offset)
            System.arraycopy(packet, offset, buffer, byteOffset, count)
            offset += count
            return count
        }

        private fun loadNextPacket(): Boolean {
            val chunk = audioStore.waitForNext(lastSequence, 10000) ?: return false
            lastSequence = chunk.sequence
            packet = chunk.bytes
            offset = 0
            return true
        }

        private fun wavHeader(): ByteArray {
            val sampleRate = AudioCapture.SampleRate
            val channels = AudioCapture.Channels
            val bitsPerSample = AudioCapture.BitsPerSample
            val byteRate = sampleRate * channels * bitsPerSample / 8
            val blockAlign = channels * bitsPerSample / 8

            val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(-1) // 0xFFFFFFFF: unknown length (live stream)
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16)
            header.putShort(1) // PCM
            header.putShort(channels.toShort())
            header.putInt(sampleRate)
            header.putInt(byteRate)
            header.putShort(blockAlign.toShort())
            header.putShort(bitsPerSample.toShort())
            header.put("data".toByteArray())
            header.putInt(-1) // 0xFFFFFFFF: unknown length
            return header.array()
        }
    }

    companion object {
        const val PHONE_PORT = 47888
    }
}

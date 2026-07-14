package com.jq.phonewebcam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

/**
 * A camera frame copied out of the ImageProxy so the proxy can be closed immediately.
 * The mirror flip is already baked into the copy; only rotation is left for encode time.
 */
class Nv21Frame(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int
)

/**
 * Copies the frame into an NV21 buffer (with mirror applied) on the camera thread.
 * Fast: bulk row copies, no JPEG work. The expensive encode happens later, off the
 * camera thread, via [Nv21Frame.encodeJpeg].
 */
fun ImageProxy.captureNv21(mirror: Boolean): Nv21Frame {
    return Nv21Frame(
        bytes = toNv21(mirror),
        width = width,
        height = height,
        rotationDegrees = imageInfo.rotationDegrees
    )
}

/**
 * Encodes the captured frame to a JPEG. Safe to run on any thread; the front camera
 * no longer pays a second decode-transform-encode pass because the mirror flip was
 * applied during capture. Only a non-zero sensor rotation still needs a bitmap
 * re-encode (rare: the sensor usually reports 0 for a phone in a stand).
 */
fun Nv21Frame.encodeJpeg(jpegQuality: Int): ByteArray {
    val jpeg = ByteArrayOutputStream()
    YuvImage(bytes, ImageFormat.NV21, width, height, null)
        .compressToJpeg(Rect(0, 0, width, height), jpegQuality, jpeg)

    if (rotationDegrees == 0) {
        return jpeg.toByteArray()
    }

    // Uncommon path: sensor reports a rotation, apply it with a single re-encode.
    val sourceBytes = jpeg.toByteArray()
    val bitmap = BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size)
        ?: return sourceBytes
    val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    val output = ByteArrayOutputStream()
    rotated.compress(Bitmap.CompressFormat.JPEG, jpegQuality, output)
    bitmap.recycle()
    rotated.recycle()
    return output.toByteArray()
}

/**
 * Copies the YUV_420_888 planes into a contiguous NV21 buffer, applying a horizontal
 * flip in-place when [mirror] is set. The luma plane uses a bulk row copy on the common
 * pixelStride == 1 layout instead of per-pixel ByteBuffer reads.
 */
private fun ImageProxy.toNv21(mirror: Boolean): ByteArray {
    val w = width
    val h = height
    val output = ByteArray(w * h * 3 / 2)
    var outPos = 0

    val yPlane = planes[0]
    val yBuffer = yPlane.buffer
    val yRowStride = yPlane.rowStride
    val yPixelStride = yPlane.pixelStride

    if (yPixelStride == 1) {
        val rowBuf = ByteArray(w)
        for (row in 0 until h) {
            yBuffer.position(row * yRowStride)
            yBuffer.get(rowBuf, 0, w)
            if (mirror) {
                var col = w - 1
                while (col >= 0) {
                    output[outPos++] = rowBuf[col]
                    col--
                }
            } else {
                System.arraycopy(rowBuf, 0, output, outPos, w)
                outPos += w
            }
        }
    } else {
        for (row in 0 until h) {
            val rowOffset = row * yRowStride
            if (mirror) {
                var col = w - 1
                while (col >= 0) {
                    output[outPos++] = yBuffer.get(rowOffset + col * yPixelStride)
                    col--
                }
            } else {
                for (col in 0 until w) {
                    output[outPos++] = yBuffer.get(rowOffset + col * yPixelStride)
                }
            }
        }
    }

    val chromaHeight = h / 2
    val chromaWidth = w / 2
    val uPlane = planes[1]
    val vPlane = planes[2]
    val uBuffer = uPlane.buffer
    val vBuffer = vPlane.buffer
    val uRowStride = uPlane.rowStride
    val vRowStride = vPlane.rowStride
    val uPixelStride = uPlane.pixelStride
    val vPixelStride = vPlane.pixelStride

    for (row in 0 until chromaHeight) {
        val uRowOffset = row * uRowStride
        val vRowOffset = row * vRowStride
        if (mirror) {
            var col = chromaWidth - 1
            while (col >= 0) {
                output[outPos++] = vBuffer.get(vRowOffset + col * vPixelStride)
                output[outPos++] = uBuffer.get(uRowOffset + col * uPixelStride)
                col--
            }
        } else {
            for (col in 0 until chromaWidth) {
                output[outPos++] = vBuffer.get(vRowOffset + col * vPixelStride)
                output[outPos++] = uBuffer.get(uRowOffset + col * uPixelStride)
            }
        }
    }

    return output
}

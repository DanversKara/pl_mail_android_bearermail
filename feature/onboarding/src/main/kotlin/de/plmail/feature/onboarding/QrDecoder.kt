package de.plmail.feature.onboarding

import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * BearerMail: reads a QR code with ZXing, a pure-Java decoder.
 *
 * It replaced ML Kit, which crashed inside its own initialisation on some phones (an internal
 * component it looks up was null), and which also brought Google Play services libraries into the
 * `foss` build. ZXing needs neither Play services nor a native library.
 */
internal object QrDecoder {

    private val hints =
        mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true,
        )

    /** The text of the QR code in [source], or null when there is none. */
    fun decode(source: LuminanceSource): String? =
        try {
            QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (_: ReaderException) {
            null
        }

    /**
     * Decodes a camera frame. Only the Y plane is used: it is the greyscale image, which is all a
     * QR decoder needs, and CameraX's analysis frames are YUV_420_888.
     */
    fun decode(image: ImageProxy): String? {
        val plane = image.planes[0]
        val buffer = plane.buffer.duplicate().apply { rewind() }
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height

        // The last row can be shorter than rowStride, so the buffer may end early; pad with zeros.
        val data = ByteArray(rowStride * height)
        buffer.get(data, 0, minOf(buffer.remaining(), data.size))

        return decode(PlanarYUVLuminanceSource(data, rowStride, height, 0, 0, width, height, false))
    }
}

package com.minesafear.certificate

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Renders a certificate payload as a QR bitmap using ZXing core. Runs entirely
 * offline; call it off the main thread for large sizes.
 */
object QrCodeGenerator {

    private const val DEFAULT_SIZE_PX = 512

    /**
     * @param sizePx target edge length. ZXing may return a slightly larger matrix
     *   when the requested size is not a clean multiple of the module count, so the
     *   bitmap is built from the matrix dimensions rather than [sizePx].
     * @param quietZoneModules margin in QR modules; the spec asks for 4, but 2 keeps
     *   small on-screen renders legible without wasting space.
     */
    fun encodeAsBitmap(
        content: String,
        sizePx: Int = DEFAULT_SIZE_PX,
        quietZoneModules: Int = 2,
        foregroundColor: Int = Color.BLACK,
        backgroundColor: Int = Color.WHITE,
    ): Bitmap {
        val hints = mapOf<EncodeHintType, Any>(
            // M tolerates the scuffing a printed card picks up on site.
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to quietZoneModules,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )

        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)

        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                pixels[rowOffset + x] = if (matrix.get(x, y)) foregroundColor else backgroundColor
            }
        }

        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    fun encodeAsBitmap(payload: CertificatePayload, sizePx: Int = DEFAULT_SIZE_PX): Bitmap =
        encodeAsBitmap(content = payload.encode(), sizePx = sizePx)
}

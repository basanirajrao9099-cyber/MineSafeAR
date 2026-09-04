package com.minesafear.certificate

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.minesafear.R
import com.minesafear.data.entity.CertificateEntity
import java.text.DateFormat
import java.util.Date

/**
 * Renders a certificate as a shareable PNG and puts it in the device's picture
 * gallery.
 *
 * The image is a card, not a bare QR code: a scan-only image is useless to anyone
 * without the app, and a printed helmet card has to be readable by a supervisor
 * holding it. So the QR sits above the holder's name, score, expiry and id.
 *
 * Both [render] and [saveToPictures] allocate multi-megabyte bitmaps and touch
 * disk. Call them from a background dispatcher.
 */
object CertificateImage {

    private const val TAG = "CertificateImage"

    private const val QR_SIZE_PX = 720
    private const val PADDING_PX = 56
    private const val QR_CAPTION_GAP_PX = 44
    private const val LINE_GAP_PX = 18
    private const val TITLE_TEXT_PX = 34f
    private const val NAME_TEXT_PX = 52f
    private const val DETAIL_TEXT_PX = 32f
    private const val ID_TEXT_PX = 26f
    private const val MIN_TEXT_PX = 16f

    /** Album inside Pictures/, so a worker's gallery is not littered. */
    private const val ALBUM_NAME = "MineSafeAR"

    fun render(context: Context, certificate: CertificateEntity): Bitmap {
        val qr = QrCodeGenerator.encodeAsBitmap(certificate.toPayload(), sizePx = QR_SIZE_PX)
        val width = qr.width + PADDING_PX * 2
        val maxTextWidth = width - PADDING_PX * 2

        val lines = captionLines(context, certificate)
        val paints = lines.map { line -> fittedPaint(line, maxTextWidth) }
        val captionHeight = paints.sumOf { paint ->
            (paint.fontMetrics.descent - paint.fontMetrics.ascent).toDouble()
        }.toFloat() + LINE_GAP_PX * (paints.size - 1).coerceAtLeast(0)

        val height = PADDING_PX + qr.height + QR_CAPTION_GAP_PX + captionHeight + PADDING_PX

        val card = Bitmap.createBitmap(width, height.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(card)
        // Opaque white, not transparent: a PNG with an alpha background loses its
        // quiet zone the moment it lands on a dark chat bubble, and an unscannable
        // QR is worse than an ugly one.
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(qr, PADDING_PX.toFloat(), PADDING_PX.toFloat(), null)

        val centreX = width / 2f
        var baselineY = (PADDING_PX + qr.height + QR_CAPTION_GAP_PX).toFloat()
        paints.forEachIndexed { index, paint ->
            baselineY -= paint.fontMetrics.ascent
            canvas.drawText(lines[index].text, centreX, baselineY, paint)
            baselineY += paint.fontMetrics.descent + LINE_GAP_PX
        }

        return card
    }

    /**
     * Writes [bitmap] into `Pictures/MineSafeAR` and returns its content Uri, or
     * null if the write failed.
     *
     * Uses MediaStore rather than a raw file path, which on API 29+ means no storage
     * permission is needed and the image survives the app being uninstalled. The
     * returned Uri is also directly shareable, so no `FileProvider` is required.
     *
     * `IS_PENDING` hides the row until the bytes are written, so the gallery never
     * shows a half-decoded image.
     */
    fun saveToPictures(context: Context, bitmap: Bitmap, fileName: String): Uri? {
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val details = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$fileName.png")
            put(MediaStore.Images.Media.MIME_TYPE, MIME_TYPE)
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/$ALBUM_NAME",
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = runCatching { resolver.insert(collection, details) }
            .onFailure { Log.e(TAG, "Could not create a gallery entry", it) }
            .getOrNull()
            ?: return null

        val written = runCatching {
            resolver.openOutputStream(uri)?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY_IGNORED, stream)
            } ?: false
        }.onFailure { Log.e(TAG, "Could not write the certificate PNG", it) }.getOrDefault(false)

        if (!written) {
            // Leaving the pending row behind would show as a permanently broken
            // thumbnail in the gallery.
            runCatching { resolver.delete(uri, null, null) }
            return null
        }

        details.clear()
        details.put(MediaStore.Images.Media.IS_PENDING, 0)
        runCatching { resolver.update(uri, details, null, null) }
            .onFailure { Log.e(TAG, "Could not publish the certificate PNG", it) }

        return uri
    }

    fun shareIntent(uri: Uri, subject: String): Intent = Intent(Intent.ACTION_SEND).apply {
        type = MIME_TYPE
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, subject)
        // Without this the receiving app gets a Uri it is not allowed to read.
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Deliberately not localised: a file name is not user-facing copy. */
    fun fileNameFor(certificate: CertificateEntity): String =
        "minesafear-certificate-${certificate.certId.take(SHORT_ID_CHARS)}"

    private fun captionLines(
        context: Context,
        certificate: CertificateEntity,
    ): List<CaptionLine> {
        val expiry = DateFormat.getDateInstance(DateFormat.MEDIUM)
            .format(Date(certificate.expiryDate))
        return listOf(
            CaptionLine(context.getString(R.string.certificate_image_title), TITLE_TEXT_PX, false),
            CaptionLine(certificate.userName, NAME_TEXT_PX, true),
            CaptionLine(
                context.getString(R.string.certificate_score, certificate.score),
                DETAIL_TEXT_PX,
                false,
            ),
            CaptionLine(
                context.getString(R.string.certificate_expires_on, expiry),
                DETAIL_TEXT_PX,
                false,
            ),
            CaptionLine(
                context.getString(R.string.certificate_id_line, certificate.certId),
                ID_TEXT_PX,
                false,
            ),
        )
    }

    /**
     * Shrinks a line until it fits rather than clipping it. A long name or a longer
     * translation of the same label would otherwise run off the edge of the card,
     * and a truncated name on a credential is a defect.
     */
    private fun fittedPaint(line: CaptionLine, maxWidthPx: Int): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textAlign = Paint.Align.CENTER
            typeface = if (line.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            textSize = line.textSizePx
            while (textSize > MIN_TEXT_PX && measureText(line.text) > maxWidthPx) {
                textSize -= 2f
            }
        }

    private class CaptionLine(val text: String, val textSizePx: Float, val bold: Boolean)

    private const val MIME_TYPE = "image/png"
    private const val SHORT_ID_CHARS = 8

    /** PNG is lossless, so `compress` ignores the quality argument. */
    private const val PNG_QUALITY_IGNORED = 100
}

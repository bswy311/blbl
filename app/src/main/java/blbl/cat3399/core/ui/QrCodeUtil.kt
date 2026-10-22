package blbl.cat3399.core.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import blbl.cat3399.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.EnumMap

/**
 * Minimal QR code rendering on top of zxing core. Used by the LAN log sharing dialog
 * so a phone/PC can scan the download URL instead of typing the IP with a remote.
 */
object QrCodeUtil {
    /**
     * Renders [content] into a square [sizePx] bitmap. Dark modules use [onColor],
     * light background uses [offColor]. Returns null on failure.
     */
    fun toBitmap(content: String, sizePx: Int, onColor: Int, offColor: Int): Bitmap? {
        val text = content.trim()
        if (text.isBlank() || sizePx <= 0) return null
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java)
        hints[EncodeHintType.MARGIN] = 1
        hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
        hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.M
        val matrix: com.google.zxing.common.BitMatrix =
            runCatching {
                QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            }.getOrNull() ?: return null

        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(offColor)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = onColor }
        val cell = sizePx.toFloat() / matrix.width.toFloat()
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix.get(x, y)) {
                    canvas.drawRect(x * cell, y * cell, (x + 1) * cell, (y + 1) * cell, paint)
                }
            }
        }
        return bitmap
    }

    /** Builds a high-contrast QR bitmap for the current theme. */
    fun themed(context: android.content.Context, content: String, sizePx: Int): Bitmap? {
        val onColor = ThemeColor.resolve(context, android.R.attr.textColorPrimary, android.R.color.white)
        val offColor = ThemeColor.resolve(context, R.attr.blblDialogSurface, android.R.color.black)
        return toBitmap(content = content, sizePx = sizePx, onColor = onColor, offColor = offColor)
    }
}

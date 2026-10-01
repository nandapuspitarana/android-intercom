package com.intercom.video.twoway.ui.pairing

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/** QR code generation and scanning for pairing. The code holds no secret (see PairingLink). */
object QrPairing {
    /** Renders [text] as a square black-on-white QR bitmap of [sizePx] pixels. */
    fun bitmap(text: String, sizePx: Int = 512): Bitmap {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val pixels = IntArray(sizePx * sizePx)
        for (y in 0 until sizePx) {
            for (x in 0 until sizePx) pixels[y * sizePx + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
    }

    /** The scanner screen options: QR codes only, no beep. */
    fun scanOptions(prompt: String): ScanOptions = ScanOptions()
        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        .setPrompt(prompt)
        .setBeepEnabled(false)
        .setOrientationLocked(false)
}

/**
 * Returns a function that opens the camera scanner (which asks for the camera permission itself) and reports the
 * scanned text to [onResult]. Cancelling reports nothing.
 */
@Composable
fun rememberQrScanner(prompt: String, onResult: (String) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let(onResult)
    }
    return { launcher.launch(QrPairing.scanOptions(prompt)) }
}

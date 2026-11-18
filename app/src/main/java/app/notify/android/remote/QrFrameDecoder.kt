package app.notify.android.remote

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

class QrFrameDecoder {
    private val reader = QRCodeReader()

    fun decode(bytes: ByteArray, width: Int, height: Int, rowStride: Int, pixelStride: Int): String? {
        require(width in 1..4096 && height in 1..4096 && pixelStride in 1..4 &&
            rowStride.toLong() >= width.toLong() * pixelStride &&
            (height - 1L) * rowStride + (width - 1L) * pixelStride < bytes.size) { "Invalid camera frame." }
        val pixels = ByteArray(width * height)
        for (y in 0 until height) for (x in 0 until width)
            pixels[y * width + x] = bytes[y * rowStride + x * pixelStride]
        val source = PlanarYUVLuminanceSource(pixels, width, height, 0, 0, width, height, false)
        return try {
            reader.decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.TRY_HARDER to true)).text
        } catch (_: ReaderException) { null }
        finally { reader.reset() }
    }
}

package app.notify.android.remote

import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import org.junit.Assert.*
import org.junit.Test

class QrFrameDecoderTest {
    @Test fun decodesOfflineQrWithCameraPlanePaddingAndRotation() {
        val text = "https://notify-remote.onrender.com/#setup=test&mac=mac&key=synthetic"
        val matrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, 240, 240)
        val stride = 496
        val bytes = ByteArray(stride * 240) { 127 }
        for (y in 0 until 240) for (x in 0 until 240)
            bytes[y * stride + x * 2] = if (matrix[239 - y, x]) 0 else 255.toByte()
        assertEquals(text, QrFrameDecoder().decode(bytes, 240, 240, stride, 2))
        assertNull(QrFrameDecoder().decode(ByteArray(240 * 240) { 255.toByte() }, 240, 240, 240, 1))
    }

    @Test fun incompletePlaneIsRejectedRatherThanReadingOutsideBuffer() {
        assertThrows(IllegalArgumentException::class.java) {
            QrFrameDecoder().decode(ByteArray(12), 240, 240, 240, 1)
        }
    }
}

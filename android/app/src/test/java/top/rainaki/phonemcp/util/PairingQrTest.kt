package top.rainaki.phonemcp.util

import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.DataInputStream

class PairingQrTest {
    @Test fun pythonGeneratedQrDecodesWithAndroidScannerLibrary() {
        DataInputStream(javaClass.getResourceAsStream("/pairing-test.luma")).use { input ->
            val width = input.readInt()
            val height = input.readInt()
            val pixels = ByteArray(width * height).also { input.readFully(it) }
            val source = PlanarYUVLuminanceSource(pixels, width, height, 0, 0, width, height, false)
            val decoded = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source))).text
            assertEquals(javaClass.getResource("/pairing-test.json")!!.readText(), decoded)
        }
    }
}

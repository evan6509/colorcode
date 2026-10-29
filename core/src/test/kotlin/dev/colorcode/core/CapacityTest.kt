package dev.colorcode.core

import org.junit.Assert.*
import org.junit.Test

/** Fixed standard QR-L byte-mode budgets, no ECI, structured append or compression. */
internal data class QrBudget(val version: Int, val totalWords: Int, val dataWords: Int, val parityPerBlock: Int, val dataBlockSizes: List<Int>) {
    val grid get() = 17 + 4 * version
    val payloadBytes get() = (dataWords * 8 - 4 - 16) / 8 // v10..40 use a 16-bit byte count
    fun byteModeData(bytes: ByteArray): ByteArray {
        if (bytes.size > payloadBytes) throw IllegalArgumentException("QR byte-mode overflow")
        val bits = mutableListOf<Int>()
        fun put(value: Int, length: Int) { for (i in length - 1 downTo 0) bits.add((value ushr i) and 1) }
        put(4, 4); put(bytes.size, 16)
        bytes.forEach { put(it.toInt() and 255, 8) }
        repeat(minOf(4, dataWords * 8 - bits.size)) { bits.add(0) }
        while (bits.size % 8 != 0) bits.add(0)
        var pad = 0
        while (bits.size < dataWords * 8) { put(if (pad++ % 2 == 0) 0xec else 0x11, 8) }
        return ByteArray(dataWords) { at -> (0..7).fold(0) { value, bit -> (value shl 1) or bits[at * 8 + bit] }.toByte() }
    }
}
internal val qrBudgets = listOf(
    QrBudget(10, 346, 274, 18, listOf(68, 68, 69, 69)),
    QrBudget(18, 901, 721, 30, List(5) { 120 } + 121),
    QrBudget(28, 1921, 1531, 30, List(3) { 117 } + List(10) { 118 })
)

class CapacityTest {
    @Test fun standardQrReferenceBudgetsAreConsistent() {
        assertEquals(listOf(271, 718, 1528), qrBudgets.map { it.payloadBytes })
        for (qr in qrBudgets) {
            // QR total codewords from the function-pattern geometry formula.
            val v = qr.version; val align = v / 7 + 2
            val rawModules = (16 * v + 128) * v + 64 - ((25 * align - 10) * align - 55) - 36
            assertEquals(qr.totalWords, rawModules / 8)
            assertEquals(qr.dataWords, qr.dataBlockSizes.sum())
            assertEquals(qr.totalWords, qr.dataWords + qr.dataBlockSizes.size * qr.parityPerBlock)
            assertEquals(qr.dataWords, qr.byteModeData(ByteArray(qr.payloadBytes)).size)
            try { qr.byteModeData(ByteArray(qr.payloadBytes + 1)); fail("QR overflow accepted") } catch (_: IllegalArgumentException) {}
        }
    }
    @Test fun colorMaxPayloadSurvivesActualRasterAtSameDimensions() {
        for (qr in qrBudgets) {
            val capacity = ColorCode.capacity(qr.grid)
            assertTrue(capacity > qr.payloadBytes)
            val bytes = ByteArray(capacity) { (it * 131 + 17).toByte() }
            val image = SquareRenderer.render(ColorCode.encode(bytes, qr.grid), 8)
            assertEquals((qr.grid + 8) * 8, image.width)
            assertArrayEquals(bytes, ColorCode.decode(image).bytes)
        }
    }
}

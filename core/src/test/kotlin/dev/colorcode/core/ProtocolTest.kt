package dev.colorcode.core

import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import java.util.zip.CRC32

class ProtocolTest {
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected CodeException") } catch (_: CodeException) { }
    }
    @Test fun rsFixedParityVector() {
        // Generated with a separate Python carryless-polynomial implementation.
        val expected = "f57095816f2872728e21354bc5470fa6e675cbed022673091964a0bde3fb0bb4"
        val code = ReedSolomon.encode(ByteArray(32) { it.toByte() })
        assertEquals(expected, code.takeLast(32).joinToString("") { "%02x".format(it.toInt() and 255) })
    }
    @Test fun randomRsErrorsIncludingParityAndShortening() {
        val random = Random(947)
        for (size in listOf(1, 17, 97, 223)) repeat(24) { trial ->
            val data = ByteArray(size).also(random::nextBytes)
            val encoded = ReedSolomon.encode(data)
            val errors = trial % 17
            val positions = encoded.indices.shuffled(kotlin.random.Random(trial * 31 + size)).take(errors)
            for (pos in positions) encoded[pos] = (encoded[pos].toInt() xor (1 + random.nextInt(255))).toByte()
            val result = ReedSolomon.correct(encoded)
            assertArrayEquals("size=$size errors=$errors", data, result.first)
            assertEquals(errors, result.second)
        }
    }
    @Test fun rsMatchesIndependentPolynomialReference() {
        // Reference uses carryless multiplication and descending polynomial long division;
        // it shares neither the production log tables nor the generator implementation.
        fun multiply(aa: Int, bb: Int): Int {
            var a = aa; var b = bb; var product = 0
            while (b != 0) {
                if (b and 1 != 0) product = product xor a
                a = a shl 1; if (a and 256 != 0) a = a xor 0x11d
                b = b ushr 1
            }
            return product
        }
        var polynomial = intArrayOf(1)
        var root = 1
        repeat(32) {
            val next = IntArray(polynomial.size + 1)
            for (i in polynomial.indices) {
                next[i] = next[i] xor polynomial[i]
                next[i + 1] = next[i + 1] xor multiply(polynomial[i], root)
            }
            polynomial = next; root = multiply(root, 2)
        }
        val data = ByteArray(223) { (it * 17 + 3).toByte() }
        val division = IntArray(255) { if (it < data.size) data[it].toInt() and 255 else 0 }
        for (i in data.indices) {
            val coefficient = division[i]
            for (j in polynomial.indices) division[i + j] = division[i + j] xor multiply(coefficient, polynomial[j])
        }
        assertArrayEquals(data + ByteArray(32) { division[data.size + it].toByte() }, ReedSolomon.encode(data))
    }
    @Test fun rsDoesNotMutateInput() {
        val encoded = ReedSolomon.encode(ByteArray(200) { it.toByte() })
        encoded[12] = (encoded[12].toInt() xor 71).toByte()
        val copy = encoded.copyOf(); ReedSolomon.correct(encoded)
        assertArrayEquals(copy, encoded)
        rejects { ReedSolomon.encode(byteArrayOf()) }
    }
    @Test fun versionAppearanceAndReservedHeaderGuards() {
        for ((offset, value) in listOf(0 to 0, 4 to 2, 5 to 2, 6 to 2, 7 to 1)) {
            val symbol = reframe(ColorCode.encode(byteArrayOf(1, 2, 3))) { it[offset] = value.toByte() }
            rejects { ColorCode.decode(SquareRenderer.render(symbol, 4)) }
        }
        rejects { ColorCode.encode(byteArrayOf(), appearanceVersion = 2) }
        rejects { SquareRenderer.render(ColorCode.encode(byteArrayOf()).copy(appearanceVersion = 2)) }
    }
    @Test fun invalidLengthWithValidRsRejected() {
        for (length in listOf(-1, 600, Int.MAX_VALUE)) {
            val symbol = reframe(ColorCode.encode(byteArrayOf(2))) { frame ->
                repeat(4) { frame[8 + it] = (length ushr (24 - 8 * it)).toByte() }
            }
            rejects { ColorCode.decode(SquareRenderer.render(symbol, 4)) }
        }
    }
    @Test fun crcRejectsChangedPayloadEvenWithValidRs() {
        val symbol = reframe(ColorCode.encode(byteArrayOf(1, 2, 3)), updateCrc = false) { it[17] = 99 }
        rejects { ColorCode.decode(SquareRenderer.render(symbol, 4)) }
    }
    @Test fun canonicalPaddingChecked() {
        val symbol = reframe(ColorCode.encode(byteArrayOf(1))) { it[it.lastIndex] = 1 }
        rejects { ColorCode.decode(SquareRenderer.render(symbol, 4)) }
    }
    @Test fun metadataAndPaletteIsolation() {
        val symbol = ColorCode.encode("metadata".toByteArray(), 89)
        val colors = SquareRenderer.palette; colors.fill(0)
        val value = ColorCode.decode(SquareRenderer.render(symbol, 3))
        assertEquals(1, value.formatVersion); assertEquals(1, value.appearanceVersion); assertEquals(89, value.grid)
        assertEquals(0, value.correctedBytes)
        assertArrayEquals("metadata".toByteArray(), value.bytes)
    }
    @Test fun randomPayloadsAndDeterministicEncoding() {
        val random = Random(82)
        for (grid in ColorCode.grids) repeat(15) {
            val bytes = ByteArray(random.nextInt(ColorCode.capacity(grid) + 1)).also(random::nextBytes)
            val symbol = ColorCode.encode(bytes, grid)
            assertArrayEquals(symbol.cells, ColorCode.encode(bytes, grid).cells)
            assertArrayEquals(bytes, ColorCode.decode(SquareRenderer.render(symbol, 2)).bytes)
        }
    }
    @Test fun malformedSymbolsRejected() {
        rejects { SquareRenderer.render(Symbol(Int.MAX_VALUE, intArrayOf())) }
        rejects { SquareRenderer.render(Symbol(57, IntArray(57 * 57) { 6 })) }
        rejects { SquareRenderer.render(Symbol(57, IntArray(57))) }
        rejects { ColorCode.capacity(-1) }
        val transparent = SquareRenderer.render(ColorCode.encode(byteArrayOf(7)), 4)
        transparent.pixels.indices.forEach { transparent.pixels[it] = transparent.pixels[it] and 0xffffff }
        rejects { ColorCode.decode(transparent) }
    }
    @Test fun localizedBurstCorrectedAfterInterleaving() {
        val symbol = ColorCode.encode(ByteArray(580) { (it * 23).toByte() })
        val positions = Layout(57).positions
        // 42 consecutive stream bytes become 14 damaged bytes in each of three blocks.
        for (i in 0 until 42 * 4) symbol.cells[positions[i]] = symbol.cells[positions[i]] xor 3
        val result = ColorCode.decode(SquareRenderer.render(symbol, 4))
        assertEquals(42, result.correctedBytes)
        assertArrayEquals(ByteArray(580) { (it * 23).toByte() }, result.bytes)
    }

    /** Build valid ECC around deliberately invalid protocol fields, so RS cannot hide guard bugs. */
    private fun reframe(symbol: Symbol, updateCrc: Boolean = true, change: (ByteArray) -> Unit): Symbol {
        val layout = Layout(symbol.grid)
        val frameSize = ColorCode.capacity(symbol.grid) + 16
        val sizes = generateSequence(frameSize) { (it - 223).takeIf { n -> n > 0 } }.map { minOf(it, 223) + 32 }.toList()
        val stream = ByteArray(sizes.sum()) { byte ->
            var v = 0
            repeat(4) { bit -> val pos = layout.positions[byte * 4 + bit]; v = (v shl 2) or (symbol.cells[pos] xor layout.mask(pos)) }
            v.toByte()
        }
        val blocks = sizes.map { ByteArray(it) }; var index = 0
        for (column in 0 until sizes.max()) for (block in blocks) if (column < block.size) block[column] = stream[index++]
        val frame = blocks.flatMap { ReedSolomon.correct(it).first.toList() }.toByteArray()
        change(frame)
        val length = (0..3).fold(0) { n, i -> (n shl 8) or (frame[8 + i].toInt() and 255) }
        if (updateCrc && length in 0..ColorCode.capacity(symbol.grid)) {
            val crc = CRC32().apply { update(frame, 0, 12); update(frame, 16, length) }.value
            repeat(4) { frame[12 + it] = (crc ushr (24 - 8 * it)).toByte() }
        }
        val fresh = frame.toList().chunked(223).map { ReedSolomon.encode(it.toByteArray()) }
        index = 0
        for (column in 0 until fresh.maxOf { it.size }) for (block in fresh) if (column < block.size) stream[index++] = block[column]
        for (i in stream.indices) repeat(4) { part ->
            val pos = layout.positions[i * 4 + part]
            symbol.cells[pos] = ((stream[i].toInt() ushr (6 - 2 * part)) and 3) xor layout.mask(pos)
        }
        return symbol
    }
}

package dev.colorcode.core

import org.junit.Assert.*
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Random
import javax.imageio.ImageIO

class FiveColorTest {
    private fun encode(bytes: ByteArray, grid: Int = 57) = ColorCode.encode(bytes, grid, formatVersion = 2)
    private fun rejects(block: () -> Unit) { try { block(); fail("Expected CodeException") } catch (_: CodeException) {} }

    @Test fun allFiveColorsCarryData() {
        val bytes = ByteArray(600) { (it * 37).toByte() }
        val symbol = encode(bytes)
        val layout = Layout(57, 2)
        for (color in listOf(0, 1, 2, 3, 6))
            assertTrue(layout.positions.count { symbol.cells[it] == color } > 100)
        val raster = SquareRenderer.render(symbol)
        assertTrue(raster.pixels.contains(0xffcc33cc.toInt()))
        val decoded = ColorCode.decode(raster)
        assertArrayEquals(bytes, decoded.bytes)
        assertEquals(2, decoded.formatVersion)
        assertEquals(1, decoded.appearanceVersion)
        assertEquals(0, decoded.correctedBytes)
    }

    @Test fun capacitiesAndPngRoundTrips() {
        for ((grid, expected) in listOf(57 to 664, 89 to 1820, 129 to 3998)) {
            assertEquals(expected, ColorCode.capacity(grid, 2))
            assertTrue(expected > ColorCode.capacity(grid))
            for (size in listOf(0, 1, 256, expected - 1, expected)) {
                val bytes = ByteArray(size) { it.toByte() }
                val raster = SquareRenderer.render(encode(bytes, grid), 4)
                val png = ByteArrayOutputStream()
                ImageIO.write(BufferedImage(raster.width, raster.height, BufferedImage.TYPE_INT_RGB).apply {
                    setRGB(0, 0, width, height, raster.pixels, 0, width)
                }, "png", png)
                val fresh = ImageIO.read(ByteArrayInputStream(png.toByteArray()))
                assertArrayEquals(bytes, ColorCode.decode(Raster(fresh.width, fresh.height,
                    fresh.getRGB(0, 0, fresh.width, fresh.height, null, 0, fresh.width))).bytes)
            }
            rejects { encode(ByteArray(expected + 1), grid) }
        }
    }

    @Test fun randomPayloadsAndDeterminism() {
        val random = Random(225)
        for (grid in ColorCode.grids) repeat(12) {
            val bytes = ByteArray(random.nextInt(ColorCode.capacity(grid, 2) + 1)).also(random::nextBytes)
            val symbol = encode(bytes, grid)
            assertArrayEquals(symbol.cells, encode(bytes, grid).cells)
            assertArrayEquals(bytes, ColorCode.decode(SquareRenderer.render(symbol, 2)).bytes)
        }
    }

    @Test fun independentlyUnpackedStreamHasValidFrameAndParity() {
        val bytes = "Magenta 🌈\u0000".toByteArray()
        val symbol = encode(bytes)
        val positions = Layout(57, 2).positions
        // Independently reconstruct base-5 words and the shortened RS blocks.
        val powers = intArrayOf(15625, 3125, 625, 125, 25, 5, 1)
        val stream = ArrayList<Byte>()
        for (group in 0 until 404) {
            var word = 0
            for (part in 0..6) {
                val pos = positions[group * 7 + part]
                val x = pos % 57; val y = pos / 57
                val digit = listOf(0, 1, 2, 3, 6).indexOf(symbol.cells[pos])
                word += Math.floorMod(digit - (x + 2 * y + (x * y) % 3), 5) * powers[part]
            }
            assertTrue(word <= 65535)
            stream.add((word / 256).toByte()); stream.add(word.toByte())
        }
        // Frame = 680 bytes: 223 + 223 + 223 + 11, stream = 808 bytes.
        val actualBlocks = listOf(ByteArray(255), ByteArray(255), ByteArray(255), ByteArray(43))
        var cursor = 0
        for (column in 0 until 255) for (block in actualBlocks)
            if (column < block.size) block[column] = stream[cursor++]
        val frame = actualBlocks.flatMap { ReedSolomon.correct(it).also { result -> assertEquals(0, result.second) }.first.toList() }.toByteArray()
        assertArrayEquals(byteArrayOf(0x43, 0x43, 0x4f, 0x44, 2, 1, 0, 0), frame.copyOfRange(0, 8))
        assertArrayEquals(bytes, frame.copyOfRange(16, 16 + bytes.size))
    }

    @Test fun damagedBaseFiveGroupsCanBeCorrected() {
        val bytes = ByteArray(600) { it.toByte() }
        val symbol = encode(bytes)
        val layout = Layout(57, 2)
        // Even out-of-range 16-bit words must reach ECC as suspect bytes.
        for (i in 0 until 7 * 7) {
            val pos = layout.positions[i]
            symbol.cells[pos] = layout.dataColors[(4 + layout.mask(pos)) % 5]
        }
        val decoded = ColorCode.decode(SquareRenderer.render(symbol, 4))
        assertArrayEquals(bytes, decoded.bytes)
        assertTrue(decoded.correctedBytes > 0)
    }

    @Test fun moderateColorCast() {
        val bytes = ByteArray(600) { it.toByte() }
        val raster = SquareRenderer.render(encode(bytes), 4)
        for (i in raster.pixels.indices) {
            val p = raster.pixels[i]
            if (p != -1 && p != 0xff000000.toInt()) raster.pixels[i] =
                0xff000000.toInt() or (((p shr 16 and 255) + 12).coerceAtMost(255) shl 16) or
                    (((p shr 8 and 255) - 8).coerceAtLeast(0) shl 8) or (p and 255)
        }
        assertArrayEquals(bytes, ColorCode.decode(raster).bytes)
    }

    @Test fun quarterTurnsAndReflection() {
        val bytes = "Five colors".toByteArray()
        var raster = SquareRenderer.render(encode(bytes), 4)
        repeat(4) {
            assertArrayEquals(bytes, ColorCode.decode(raster).bytes)
            val mirrored = Raster(raster.width, raster.height, IntArray(raster.pixels.size) { i ->
                raster.pixels[i / raster.width * raster.width + raster.width - 1 - i % raster.width]
            })
            assertArrayEquals(bytes, ColorCode.decode(mirrored).bytes)
            val old = raster
            raster = Raster(old.height, old.width, IntArray(old.pixels.size) { i ->
                old.pixels[(old.height - 1 - i % old.height) * old.width + i / old.height]
            })
        }
    }

    @Test fun originalV1MappingRemainsIdentical() {
        val symbol = ColorCode.encode(ByteArray(500) { (it * 37).toByte() })
        val hash = MessageDigest.getInstance("SHA-256").digest(symbol.cells.map { it.toByte() }.toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals("20f2f31ab7571ec646b318933ad0840431e50c93a9d09d1195b473a279d0338b", hash)
        assertFalse(SquareRenderer.render(symbol).pixels.contains(0xffcc33cc.toInt()))
    }

    @Test fun paletteIsolationAndUnsupportedFormats() {
        val palette = SquareRenderer.palette(2); palette.fill(0)
        assertEquals(0xffcc33cc.toInt(), SquareRenderer.palette(2)[6])
        rejects { ColorCode.capacity(57, 3) }
        rejects { ColorCode.encode(byteArrayOf(), formatVersion = 3) }
        rejects { SquareRenderer.render(encode(byteArrayOf()).copy(formatVersion = 3)) }
        rejects { ColorCode.encode(byteArrayOf(), appearanceVersion = 2, formatVersion = 2) }
        rejects { SquareRenderer.render(encode(byteArrayOf()).copy(cells = IntArray(57 * 57) { 7 })) }
    }
}

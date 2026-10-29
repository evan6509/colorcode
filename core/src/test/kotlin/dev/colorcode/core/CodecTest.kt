package dev.colorcode.core

import org.junit.Assert.*
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random
import javax.imageio.ImageIO

class CodecTest {
    private fun round(bytes: ByteArray, grid: Int = 57) = ColorCode.decode(SquareRenderer.render(ColorCode.encode(bytes, grid))).bytes
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: CodeException) { }
    }
    @Test fun emptyPayload() { assertArrayEquals(byteArrayOf(), round(byteArrayOf())) }
    @Test fun utf8Text() { val b = "Color is data. 🌈 Ελληνικά 日本語\u0000".toByteArray(); assertArrayEquals(b, round(b)) }
    @Test fun allByteValues() { val b = ByteArray(256) { it.toByte() }; assertArrayEquals(b, round(b)) }
    @Test fun exactPngRoundTrip() {
        val b = ByteArray(450).also { Random(7).nextBytes(it) }
        val image = SquareRenderer.render(ColorCode.encode(b), 6)
        val png = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB).apply {
            setRGB(0, 0, width, height, image.pixels, 0, width)
        }, "png", png)
        val fresh = ImageIO.read(ByteArrayInputStream(png.toByteArray()))
        assertArrayEquals(b, ColorCode.decode(Raster(fresh.width, fresh.height, fresh.getRGB(0, 0, fresh.width, fresh.height, null, 0, fresh.width))).bytes)
    }
    @Test fun everyCapacityBoundary() {
        for (grid in listOf(57, 89, 129)) {
            val cap = ColorCode.capacity(grid)
            for (size in listOf(cap - 1, cap)) {
                val b = ByteArray(size).also { Random(size.toLong()).nextBytes(it) }
                assertArrayEquals(b, round(b, grid))
            }
            rejects { ColorCode.encode(ByteArray(cap + 1), grid) }
        }
    }
    @Test fun capacityExceedsSameGridQrByteMode() {
        assertTrue(ColorCode.capacity(57) > 271) // DENSO QR v10-L byte mode; digital budget only.
        assertEquals(ColorCode.MAX_PAYLOAD, ColorCode.capacity(129))
    }
    @Test fun unsupportedGridRejected() { rejects { ColorCode.encode(byteArrayOf(), 58) } }
    @Test fun oversizedPayloadRejected() { rejects { ColorCode.encode(ByteArray(ColorCode.MAX_PAYLOAD + 1), 129) } }
    @Test fun allFourColorsCarryData() {
        val s = ColorCode.encode(ByteArray(500) { (it * 37).toByte() })
        for (color in 0..3) assertTrue(s.cells.count { it == color } > 100)
        val image = SquareRenderer.render(s)
        assertTrue(SquareRenderer.palette.take(4).all { image.pixels.contains(it) })
        assertArrayEquals(ByteArray(500) { (it * 37).toByte() }, ColorCode.decode(image).bytes)
    }
    @Test fun quietZoneAndMarkers() {
        val im = SquareRenderer.render(ColorCode.encode(byteArrayOf(3)), 3)
        assertEquals((57 + 8) * 3, im.width)
        assertEquals(0xffffffff.toInt(), im.pixels[0])
        assertEquals(0xff000000.toInt(), im.pixels[12 * im.width + 12])
    }
    @Test fun scaleBoundaries() {
        for (scale in listOf(3, 4, 12)) assertArrayEquals(byteArrayOf(42), ColorCode.decode(SquareRenderer.render(ColorCode.encode(byteArrayOf(42)), scale)).bytes)
        rejects { SquareRenderer.render(ColorCode.encode(byteArrayOf()), 0) }
        rejects { SquareRenderer.render(ColorCode.encode(byteArrayOf()), 1000) }
    }
    @Test fun quarterTurns() {
        var im = SquareRenderer.render(ColorCode.encode("rotate".toByteArray()), 4)
        repeat(4) {
            assertArrayEquals("rotate".toByteArray(), ColorCode.decode(im).bytes)
            im = Raster(im.height, im.width, IntArray(im.pixels.size) { i -> im.pixels[(im.height - 1 - i % im.height) * im.width + i / im.height] })
        }
    }
    @Test fun moderateColorCast() {
        val im = SquareRenderer.render(ColorCode.encode("calibration".toByteArray()), 4)
        for (i in im.pixels.indices) {
            val c = im.pixels[i]
            if (c != -1 && c != 0xff000000.toInt()) im.pixels[i] = 0xff000000.toInt() or (((c shr 16 and 255) + 12).coerceAtMost(255) shl 16) or (((c shr 8 and 255) - 8).coerceAtLeast(0) shl 8) or (c and 255)
        }
        assertArrayEquals("calibration".toByteArray(), ColorCode.decode(im).bytes)
    }
    @Test fun monochromeConversionRejected() {
        val im = SquareRenderer.render(ColorCode.encode("lost color".toByteArray()), 4)
        for (i in im.pixels.indices) { val c = im.pixels[i]; val g = ((c shr 16 and 255) + (c shr 8 and 255) + (c and 255)) / 3; im.pixels[i] = 0xff000000.toInt() or (g shl 16) or (g shl 8) or g }
        rejects { ColorCode.decode(im) }
    }
    @Test fun unknownPixelsRejected() {
        val im = SquareRenderer.render(ColorCode.encode(byteArrayOf(9)), 4)
        java.util.Arrays.fill(im.pixels, 0xffff00ff.toInt())
        rejects { ColorCode.decode(im) }
    }
    @Test fun blankAndNoiseRejected() {
        rejects { ColorCode.decode(Raster(300, 300, IntArray(90000) { -1 })) }
        rejects { ColorCode.decode(Raster(300, 300, IntArray(90000) { Random(it.toLong()).nextInt() or 0xff000000.toInt() })) }
    }
    @Test fun malformedRasterBounds() {
        for (im in listOf(Raster(0, 0, intArrayOf()), Raster(-1, 1, intArrayOf()), Raster(Int.MAX_VALUE, Int.MAX_VALUE, intArrayOf()), Raster(2, 2, intArrayOf(0)))) rejects { ColorCode.decode(im) }
    }
    @Test fun correctsSixteenByteErrorsPerBlock() {
        val input = ByteArray(223).also { Random(4).nextBytes(it) }
        val code = ReedSolomon.encode(input)
        repeat(16) { code[it * 13] = (code[it * 13].toInt() xor (it + 1)).toByte() }
        val (data, count) = ReedSolomon.correct(code)
        assertArrayEquals(input, data); assertEquals(16, count)
    }
    @Test fun shortenedRsBlocks() {
        for (size in listOf(1, 17, 97, 222, 223)) {
            val b = ByteArray(size) { (it * 11).toByte() }; val code = ReedSolomon.encode(b)
            code[code.lastIndex] = (code.last().toInt() xor 111).toByte()
            assertArrayEquals(b, ReedSolomon.correct(code).first)
        }
    }
    @Test fun excessiveRsErrorsRejected() {
        val code = ReedSolomon.encode(ByteArray(223) { it.toByte() })
        repeat(30) { code[it * 7] = (code[it * 7].toInt() xor 123).toByte() }
        rejects { ReedSolomon.correct(code) }
    }
    @Test fun rsInputBounds() {
        rejects { ReedSolomon.encode(ByteArray(224)) }; rejects { ReedSolomon.correct(ByteArray(32)) }; rejects { ReedSolomon.correct(ByteArray(256)) }
    }
    @Test fun damagedModulesCorrectedThroughImagePath() {
        val b = ByteArray(500) { it.toByte() }; val s = ColorCode.encode(b)
        var changed = 0
        for (i in s.cells.indices) if (s.cells[i] in 0..3 && changed < 20) { s.cells[i] = s.cells[i] xor 1; changed++ }
        val out = ColorCode.decode(SquareRenderer.render(s, 4))
        assertArrayEquals(b, out.bytes); assertTrue(out.correctedBytes > 0)
    }
    @Test fun heavilyCorruptedImageRejected() {
        val s = ColorCode.encode(ByteArray(500) { it.toByte() })
        for (i in s.cells.indices) if (s.cells[i] in 0..3 && i % 3 == 0) s.cells[i] = s.cells[i] xor 3
        rejects { ColorCode.decode(SquareRenderer.render(s, 4)) }
    }
    @Test fun finderDamageRejected() {
        val s = ColorCode.encode(byteArrayOf(1)); s.cells[0] = 5
        rejects { ColorCode.decode(SquareRenderer.render(s, 4)) }
    }
}

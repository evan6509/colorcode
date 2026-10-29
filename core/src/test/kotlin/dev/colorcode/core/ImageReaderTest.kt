package dev.colorcode.core

import org.junit.Assert.*
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random
import javax.imageio.ImageIO
import kotlin.math.*

class ImageReaderTest {
    private val payload = ByteArray(580).also { Random(388).nextBytes(it) }
    private fun image() = SquareRenderer.render(ColorCode.encode(payload), 8)
    private fun assertDecoded(image: Raster) = assertArrayEquals(payload, ColorCode.decode(image).bytes)
    private fun rejects(block: () -> Unit) { try { block(); fail("Expected CodeException") } catch (_: CodeException) {} }

    @Test fun translatedInRectangularCanvas() {
        val im = image()
        val width = im.width + 147; val height = im.height + 89
        val pixels = IntArray(width * height) { -1 }
        for (y in 0 until im.height) im.pixels.copyInto(pixels, (y + 31) * width + 79, y * im.width, (y + 1) * im.width)
        assertDecoded(Raster(width, height, pixels))
    }
    @Test fun croppedQuietZone() {
        val im = image(); val inset = 24; val side = im.width - inset * 2
        val pixels = IntArray(side * side) { i -> im.pixels[(i / side + inset) * im.width + i % side + inset] }
        assertDecoded(Raster(side, side, pixels))
    }
    @Test fun mirroredImage() {
        val im = image()
        assertDecoded(Raster(im.width, im.height, IntArray(im.pixels.size) { i -> im.pixels[i / im.width * im.width + im.width - 1 - i % im.width] }))
    }
    @Test fun arbitrarySmallRotation() {
        val theta = 9.0 * PI / 180
        assertDecoded(warp(image(), doubleArrayOf(cos(theta), -sin(theta), 120.0, sin(theta), cos(theta), 55.0, 0.0, 0.0)))
    }
    @Test fun syntheticPerspective() {
        assertDecoded(warp(image(), doubleArrayOf(1.05, 0.12, 65.0, 0.03, 1.1, 48.0, 0.00022, 0.00013)))
    }
    @Test fun perspectiveForLargerGrids() {
        for (grid in listOf(89, 129)) {
            val bytes = ByteArray(ColorCode.capacity(grid)) { (it * 47).toByte() }
            val im = SquareRenderer.render(ColorCode.encode(bytes, grid), 5)
            val warped = warp(im, doubleArrayOf(0.86, 0.035, 35.0, 0.02, 0.88, 28.0, 0.00004, 0.00002))
            assertArrayEquals(bytes, ColorCode.decode(warped).bytes)
        }
    }
    @Test fun syntheticJpegAtHighQuality() {
        val im = image()
        val image = BufferedImage(im.width, im.height, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, width, height, im.pixels, 0, width) }
        val output = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        ImageIO.createImageOutputStream(output).use { stream ->
            writer.output = stream
            val parameters = writer.defaultWriteParam.apply { compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT; compressionQuality = 0.95f }
            writer.write(null, javax.imageio.IIOImage(image, null, null), parameters)
        }
        writer.dispose()
        val fresh = ImageIO.read(ByteArrayInputStream(output.toByteArray()))
        assertDecoded(Raster(fresh.width, fresh.height, fresh.getRGB(0, 0, fresh.width, fresh.height, null, 0, fresh.width)))
    }
    @Test fun mildBoxBlur() {
        val im = image()
        val pixels = im.pixels.copyOf()
        for (y in 1 until im.height - 1) for (x in 1 until im.width - 1) {
            var r = 0; var g = 0; var b = 0
            for (dy in -1..1) for (dx in -1..1) {
                val p = im.pixels[(y + dy) * im.width + x + dx]
                r += p shr 16 and 255; g += p shr 8 and 255; b += p and 255
            }
            pixels[y * im.width + x] = (255 shl 24) or ((r / 9) shl 16) or ((g / 9) shl 8) or (b / 9)
        }
        assertDecoded(Raster(im.width, im.height, pixels))
    }
    @Test fun randomOpaqueImagesFailClosed() {
        val random = Random(69)
        repeat(20) {
            val side = 50 + it * 3
            rejects { ColorCode.decode(Raster(side, side + 4, IntArray(side * (side + 4)) { random.nextInt() or (255 shl 24) })) }
        }
    }
    @Test fun partialSymbolsRejected() {
        val im = image()
        val pixels = IntArray(im.width * (im.height / 2)) { im.pixels[it] }
        rejects { ColorCode.decode(Raster(im.width, im.height / 2, pixels)) }
    }
    // Inverse of an independently specified 3x3 projective matrix, nearest-neighbor resampling.
    private fun warp(im: Raster, h: DoubleArray): Raster {
        val width = 850; val height = 800
        val pixels = IntArray(width * height) { -1 }
        for (y in 0 until height) for (x in 0 until width) {
            val u = x + 0.5; val v = y + 0.5
            val a = h[0] - u * h[6]; val b = h[1] - u * h[7]; val c = u - h[2]
            val d = h[3] - v * h[6]; val e = h[4] - v * h[7]; val f = v - h[5]
            val det = a * e - b * d
            val sx = (c * e - b * f) / det; val sy = (a * f - c * d) / det
            if (sx >= 0 && sx < im.width && sy >= 0 && sy < im.height) pixels[y * width + x] = im.pixels[sy.toInt() * im.width + sx.toInt()]
        }
        return Raster(width, height, pixels)
    }
}

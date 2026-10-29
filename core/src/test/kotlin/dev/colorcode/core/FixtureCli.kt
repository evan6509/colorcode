package dev.colorcode.core

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

fun main(args: Array<String>) {
    val payload = ByteArray(588) { (it * 73 + 19).toByte() }
    val file = File(args[1])
    when (args[0]) {
        "encode" -> {
            val im = SquareRenderer.render(ColorCode.encode(payload), 8)
            ImageIO.write(BufferedImage(im.width, im.height, BufferedImage.TYPE_INT_RGB).apply {
                setRGB(0, 0, width, height, im.pixels, 0, width)
            }, "png", file)
            println("Fresh process encoded ${payload.size} bytes to ${file.path} (${im.width}x${im.height})")
        }
        "decode" -> {
            val im = ImageIO.read(file)
            val result = ColorCode.decode(Raster(im.width, im.height, im.getRGB(0, 0, im.width, im.height, null, 0, im.width)))
            check(payload.contentEquals(result.bytes))
            println("Fresh process decoded ${result.bytes.size} exact bytes; corrected=${result.correctedBytes}")
        }
    }
}

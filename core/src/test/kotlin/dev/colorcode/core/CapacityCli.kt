package dev.colorcode.core

import java.awt.image.BufferedImage
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import javax.imageio.ImageIO

/** Actual PNG round trips versus a QR reference bit budget; this is not a camera benchmark. */
fun main(args: Array<String>) {
    val directory = File(args.first()).apply { mkdirs() }
    val report = StringBuilder("ColorCode v1 density measurement\n")
    report.append("Same module pitch (8 pixels) and four-module quiet zone on every side.\n")
    report.append("QR reference: standard byte mode, level L, no ECI; bit-budget boundary tested.\n")
    report.append("No standard QR image encoder/decoder is cached; QR optical round trips were NOT measured.\n")
    report.append("grid | pixels | color bytes | QR-L bytes | ratio | color parity/stream | QR parity/stream\n")
    for (qr in qrBudgets) {
        val cap = ColorCode.capacity(qr.grid)
        val payload = ByteArray(cap) { (it * 131 + 17).toByte() }
        val raster = SquareRenderer.render(ColorCode.encode(payload, qr.grid), 8)
        val path = File(directory, "capacity-${qr.grid}.png")
        check(ImageIO.write(BufferedImage(raster.width, raster.height, BufferedImage.TYPE_INT_RGB).apply {
            setRGB(0, 0, width, height, raster.pixels, 0, width)
        }, "png", path))
        val fresh = ImageIO.read(path)
        val value = ColorCode.decode(Raster(fresh.width, fresh.height, fresh.getRGB(0, 0, fresh.width, fresh.height, null, 0, fresh.width)))
        check(value.bytes.contentEquals(payload) && value.correctedBytes == 0)
        val frame = cap + 16; val parity = ((frame + 222) / 223) * 32
        report.append(String.format(Locale.ROOT, "%d | %d×%d | %d | %d | %.3f× | %d/%d (%.2f%%) | %d/%d (%.2f%%)\n",
            qr.grid, raster.width, raster.height, cap, qr.payloadBytes, cap.toDouble() / qr.payloadBytes,
            parity, frame + parity, 100.0 * parity / (frame + parity), qr.totalWords - qr.dataWords, qr.totalWords,
            100.0 * (qr.totalWords - qr.dataWords) / qr.totalWords))
        report.append("  PNG ${path.name}: ${path.length()} file bytes; SHA-256 payload ${MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it.toInt() and 255) }}; exact round trip PASS\n")
    }
    report.append("Different ECC budgets: color RS corrects <=16 unknown byte errors per block; QR-L here <=9/15/15 per block.\n")
    report.append("Parity percentages do not establish equal optical robustness. Two-bit color decisions need distinguishable chroma.\n")
    report.append("PNG file length is NOT payload density. No compression, network fetch, base64 expansion or external payload store.\n")
    report.append("Reference tables for independent checking: https://www.qrcode.com/en/about/version.html\n")
    report.append("QR values are pinned reference inputs; this offline run did not fetch or independently encode a complete QR symbol.\n")
    File(directory, "density.txt").writeText(report.toString())
    print(report)
}

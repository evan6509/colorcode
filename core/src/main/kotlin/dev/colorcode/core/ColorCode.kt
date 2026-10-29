package dev.colorcode.core

import java.util.zip.CRC32

class CodeException(message: String) : IllegalArgumentException(message)
data class Raster(val width: Int, val height: Int, val pixels: IntArray)
data class Symbol(val grid: Int, val cells: IntArray, val appearanceVersion: Int = 1)
data class Decoded(
    val bytes: ByteArray, val correctedBytes: Int,
    val formatVersion: Int = 1, val appearanceVersion: Int = 1, val grid: Int = 57
)

/** ColorCode v1 is an independent, nonstandard format. See docs/FORMAT.md. */
object ColorCode {
    const val MAX_PAYLOAD = 3484
    const val FORMAT_VERSION = 1
    val grids: List<Int> get() = listOf(57, 89, 129)
    private const val HEADER = 16

    fun capacity(grid: Int): Int {
        val raw = Layout(grid).positions.size / 4
        var data = raw
        while (data + ((data + 222) / 223) * 32 > raw) data--
        return minOf(MAX_PAYLOAD, data - HEADER)
    }

    fun encode(bytes: ByteArray, grid: Int = 57, appearanceVersion: Int = 1): Symbol {
        if (appearanceVersion != 1) throw CodeException("Unsupported appearance: $appearanceVersion")
        val layout = Layout(grid)
        val cap = capacity(grid)
        if (bytes.size > cap) throw CodeException("${bytes.size} bytes exceeds $grid × $grid capacity of $cap bytes")
        val frame = ByteArray(cap + HEADER)
        byteArrayOf(0x43, 0x43, 0x4f, 0x44).copyInto(frame)
        frame[4] = FORMAT_VERSION.toByte()
        frame[5] = appearanceVersion.toByte()
        frame[6] = grids.indexOf(grid).toByte()
        putInt(frame, 8, bytes.size)
        bytes.copyInto(frame, HEADER)
        putInt(frame, 12, checksum(frame, bytes).toInt())
        val blocks = frame.toList().chunked(223).map { ReedSolomon.encode(it.toByteArray()) }
        val stream = interleave(blocks)
        val cells = layout.fixed.copyOf()
        for ((i, pos) in layout.positions.withIndex()) {
            val value = if (i / 4 < stream.size) (stream[i / 4].toInt() ushr (6 - (i % 4) * 2)) and 3 else 0
            cells[pos] = value xor layout.mask(pos)
        }
        return Symbol(grid, cells, appearanceVersion)
    }

    fun decode(image: Raster): Decoded = ImageReader.decode(image)

    internal fun decodeCells(grid: Int, cells: IntArray): Decoded {
        val layout = Layout(grid)
        val frameSize = capacity(grid) + HEADER
        val sizes = generateSequence(frameSize) { (it - 223).takeIf { n -> n > 0 } }
            .map { minOf(223, it) + 32 }.toList()
        val stream = ByteArray(sizes.sum())
        for (i in stream.indices) {
            var v = 0
            repeat(4) { part ->
                val pos = layout.positions[i * 4 + part]
                v = (v shl 2) or (cells[pos] xor layout.mask(pos))
            }
            stream[i] = v.toByte()
        }
        val blocks = sizes.map { ByteArray(it) }
        var cursor = 0
        for (column in 0 until sizes.max()) for (block in blocks) {
            if (column < block.size) block[column] = stream[cursor++]
        }
        val frame = ByteArray(frameSize)
        var offset = 0
        var corrected = 0
        for (block in blocks) {
            val (data, count) = ReedSolomon.correct(block)
            data.copyInto(frame, offset)
            offset += data.size
            corrected += count
        }
        if (!frame.copyOfRange(0, 4).contentEquals(byteArrayOf(0x43, 0x43, 0x4f, 0x44)))
            throw CodeException("Invalid ColorCode signature")
        if (frame[4].toInt() != FORMAT_VERSION) throw CodeException("Unsupported format version")
        if (frame[5].toInt() != 1) throw CodeException("Unsupported appearance version")
        if (frame[6].toInt() != grids.indexOf(grid) || frame[7].toInt() != 0)
            throw CodeException("Invalid grid or reserved flags")
        val length = getInt(frame, 8)
        if (length !in 0..capacity(grid)) throw CodeException("Invalid payload length")
        val bytes = frame.copyOfRange(HEADER, HEADER + length)
        if ((getInt(frame, 12).toLong() and 0xffffffffL) != checksum(frame, bytes))
            throw CodeException("Payload checksum failed")
        if ((HEADER + length until frame.size).any { frame[it].toInt() != 0 })
            throw CodeException("Invalid frame padding")
        return Decoded(bytes, corrected, FORMAT_VERSION, 1, grid)
    }

    private fun checksum(frame: ByteArray, payload: ByteArray) = CRC32().apply {
        update(frame, 0, 12); update(payload)
    }.value

    private fun interleave(blocks: List<ByteArray>): ByteArray {
        val result = ByteArray(blocks.sumOf { it.size })
        var i = 0
        for (column in 0 until blocks.maxOf { it.size }) for (block in blocks)
            if (column < block.size) result[i++] = block[column]
        return result
    }

    private fun putInt(a: ByteArray, at: Int, value: Int) {
        repeat(4) { a[at + it] = (value ushr (24 - 8 * it)).toByte() }
    }
    private fun getInt(a: ByteArray, at: Int): Int {
        var value = 0
        repeat(4) { value = (value shl 8) or (a[at + it].toInt() and 255) }
        return value
    }
}

/** Stable geometry for appearance 1. -1 marks data; 0..3 colors, 4 black, 5 white. */
internal class Layout(val grid: Int) {
    val fixed: IntArray
    val positions: IntArray
    init {
        if (grid !in ColorCode.grids) throw CodeException("Unsupported grid: $grid")
        fixed = IntArray(grid * grid) { -1 }
        for (oy in listOf(0, grid - 8)) for (ox in listOf(0, grid - 8)) {
            val startX = if (ox == 0) 0 else grid - 7
            val startY = if (oy == 0) 0 else grid - 7
            for (y in oy until oy + 8) for (x in ox until ox + 8) fixed[y * grid + x] = 5
            for (dy in 0..6) for (dx in 0..6) {
                fixed[(startY + dy) * grid + startX + dx] =
                    if (dx == 0 || dx == 6 || dy == 0 || dy == 6 || (dx in 2..4 && dy in 2..4)) 4 else 5
            }
        }
        for (i in 8 until grid - 8) {
            fixed[6 * grid + i] = if (i % 2 == 0) 4 else 5
            fixed[i * grid + 6] = if (i % 2 == 0) 4 else 5
        }
        for (c in 0..3) for (y in grid - 4 until grid) for (x in 9 + 4 * c until 13 + 4 * c)
            fixed[y * grid + x] = c
        positions = fixed.indices.filter { fixed[it] < 0 }.toIntArray()
    }
    fun mask(pos: Int): Int {
        val x = pos % grid; val y = pos / grid
        return (x + 2 * y + (x * y) % 3) and 3
    }
}

/** Renderer is separate from framing so later appearances can be registered explicitly. */
object SquareRenderer {
    private val colors = intArrayOf(0xff203c80.toInt(), 0xff008577.toInt(), 0xffd54d45.toInt(), 0xffe3b638.toInt(), 0xff000000.toInt(), 0xffffffff.toInt())
    val palette: IntArray get() = colors.copyOf()
    fun render(symbol: Symbol, scale: Int = 8): Raster {
        if (symbol.appearanceVersion != 1) throw CodeException("Unsupported appearance version")
        if (symbol.grid !in ColorCode.grids || symbol.cells.size != symbol.grid * symbol.grid || symbol.cells.any { it !in 0..5 })
            throw CodeException("Malformed symbol")
        if (scale !in 1..16) throw CodeException("Module scale must be 1–16 pixels")
        val side = (symbol.grid + 8) * scale
        val pixels = IntArray(side * side) { colors[5] }
        for (y in 0 until symbol.grid) for (x in 0 until symbol.grid) {
            val color = colors[symbol.cells[y * symbol.grid + x]]
            for (dy in 0 until scale) java.util.Arrays.fill(pixels,
                ((y + 4) * scale + dy) * side + (x + 4) * scale,
                ((y + 4) * scale + dy) * side + (x + 5) * scale, color)
        }
        return Raster(side, side, pixels)
    }
}

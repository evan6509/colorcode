package dev.colorcode.core

import kotlin.math.*

/** Bounded, dependency-free finder detector and projective sampler, shared by Android and JVM. */
internal object ImageReader {
    private data class Point(val x: Double, val y: Double)
    private data class Finder(var x: Double, var y: Double, var unit: Double, var hits: Int)

    fun decode(image: Raster): Decoded {
        if (image.width !in 1..4096 || image.height !in 1..4096 ||
            image.width.toLong() * image.height != image.pixels.size.toLong())
            throw CodeException("Invalid raster bounds (maximum 4096 × 4096)")
        var lastError: CodeException? = null
        fun attempt(grid: Int, map: (Double, Double) -> Point): Decoded? {
            return try { sample(image, grid, map) } catch (e: CodeException) { lastError = e; null }
        }
        // Exact exports take this inexpensive path, including quarter turns and reflections.
        if (image.width == image.height) for (grid in ColorCode.grids) {
            val unit = image.width.toDouble() / (grid + 8)
            for (mirror in listOf(false, true)) for (turn in 0..3) {
                val result = attempt(grid) { gx, gy ->
                    var x = (gx + 4) * unit
                    var y = (gy + 4) * unit
                    if (mirror) x = image.width - x
                    repeat(turn) { val old = x; x = image.width - y; y = old }
                    Point(x, y)
                }
                if (result != null) return result
            }
        }
        val finders = locate(image).sortedByDescending { it.hits }.take(10)
        // Four corner finders allow a true projective transform, not just an affine estimate.
        for (a in 0 until finders.size - 3) for (b in a + 1 until finders.size - 2)
            for (c in b + 1 until finders.size - 1) for (d in c + 1 until finders.size) {
                val group = listOf(finders[a], finders[b], finders[c], finders[d])
                val cx = group.sumOf { it.x } / 4; val cy = group.sumOf { it.y } / 4
                val ordered = group.sortedBy { atan2(it.y - cy, it.x - cx) }.map { Point(it.x, it.y) }
                if (!convex(ordered)) continue
                for (grid in ColorCode.grids) {
                    // Finder size gives a useful rejection before expensive sampling.
                    val span = ordered.indices.sumOf { i -> distance(ordered[i], ordered[(i + 1) % 4]) } / 4
                    val expectedUnit = span / (grid - 7)
                    if (group.any { it.unit / expectedUnit !in 0.55..1.8 }) continue
                    for (mirror in listOf(false, true)) for (turn in 0..3) {
                        val points = List(4) { ordered[((if (mirror) -it else it) + turn + 8) % 4] }
                        val h = homography(grid, points) ?: continue
                        val result = attempt(grid) { x, y -> project(h, x, y) }
                        if (result != null) return result
                    }
                }
            }
        throw CodeException("No valid ColorCode v1 found. Keep all four markers visible, sharp and in color. ${lastError?.message ?: "No four-marker geometry."}")
    }

    private fun sample(image: Raster, grid: Int, map: (Double, Double) -> Point): Decoded {
        val layout = Layout(grid)
        fun color(x: Double, y: Double): Int {
            var r = 0; var g = 0; var b = 0
            for ((dx, dy) in offsets) {
                val p = map(x + dx, y + dy)
                if (!p.x.isFinite() || !p.y.isFinite() || p.x < 0 || p.y < 0 || p.x >= image.width || p.y >= image.height)
                    throw CodeException("Symbol extends outside image")
                val pixel = image.pixels[p.y.toInt() * image.width + p.x.toInt()]
                if (pixel ushr 24 < 240) throw CodeException("Transparent symbol pixels are unsupported")
                r += pixel shr 16 and 255; g += pixel shr 8 and 255; b += pixel and 255
            }
            return (0xff shl 24) or ((r / 5) shl 16) or ((g / 5) shl 8) or (b / 5)
        }
        // Every black/white structural module is checked. RS protects data, not the finders.
        for (pos in layout.fixed.indices) if (layout.fixed[pos] >= 4) {
            val p = color(pos % grid + 0.5, pos / grid + 0.5)
            val luma = ((p shr 16 and 255) * 3 + (p shr 8 and 255) * 6 + (p and 255)) / 10
            if ((layout.fixed[pos] == 4 && luma > 100) || (layout.fixed[pos] == 5 && luma < 155))
                throw CodeException("Finder, separator or timing pattern damaged")
        }
        val calibration = IntArray(4) { c -> color(11.0 + 4 * c, grid - 2.0) }
        for (p in calibration) {
            val components = intArrayOf(p shr 16 and 255, p shr 8 and 255, p and 255)
            if (components.max() - components.min() < 35) throw CodeException("Color information missing")
        }
        for (a in 0..3) for (b in a + 1..3) if (colorDistance(calibration[a], calibration[b]) < 2500)
            throw CodeException("Calibration colors are indistinguishable")
        // Retain palette identity: arbitrary recoloring is a future appearance, not v1.
        val palette = SquareRenderer.palette
        for (i in 0..3) if (colorDistance(calibration[i], palette[i]) > 15000)
            throw CodeException("Unsupported color appearance or excessive color cast")
        val cells = IntArray(grid * grid)
        for (pos in layout.positions) {
            val p = color(pos % grid + 0.5, pos / grid + 0.5)
            cells[pos] = (0..3).minBy { colorDistance(p, calibration[it]) }
        }
        return ColorCode.decodeCells(grid, cells)
    }

    private val offsets = arrayOf(0.0 to 0.0, -0.16 to 0.0, 0.16 to 0.0, 0.0 to -0.16, 0.0 to 0.16)
    private fun colorDistance(a: Int, b: Int): Int {
        val r = (a shr 16 and 255) - (b shr 16 and 255)
        val g = (a shr 8 and 255) - (b shr 8 and 255)
        val bl = (a and 255) - (b and 255)
        return r * r + g * g + bl * bl
    }
    private fun black(pixel: Int): Boolean =
        ((pixel shr 16 and 255) * 3 + (pixel shr 8 and 255) * 6 + (pixel and 255)) < 1000

    private fun locate(image: Raster): List<Finder> {
        val found = mutableListOf<Finder>()
        val step = maxOf(1, minOf(image.width, image.height) / 900)
        for (y in 0 until image.height step step) {
            val starts = mutableListOf<Int>()
            val lengths = mutableListOf<Int>()
            val colors = mutableListOf<Boolean>()
            var start = 0
            var state = black(image.pixels[y * image.width])
            for (x in 1..image.width) {
                val next = x < image.width && black(image.pixels[y * image.width + x])
                if (x == image.width || next != state) {
                    starts.add(start); lengths.add(x - start); colors.add(state)
                    start = x; state = next
                }
            }
            for (i in 0 until lengths.size - 4) {
                if (!colors[i]) continue
                val run = IntArray(5) { lengths[i + it] }
                if (!ratio(run)) continue
                val unit = run.sum() / 7.0
                val centerX = starts[i] + run[0] + run[1] + run[2] / 2.0
                val vertical = vertical(image, centerX.toInt(), y, unit) ?: continue
                val centerY = vertical.first
                val estimate = (unit + vertical.second) / 2
                val existing = found.firstOrNull { hypot(it.x - centerX, it.y - centerY) < maxOf(it.unit, estimate) * 2 }
                if (existing == null) found.add(Finder(centerX, centerY, estimate, 1)) else {
                    existing.x = (existing.x * existing.hits + centerX) / (existing.hits + 1)
                    existing.y = (existing.y * existing.hits + centerY) / (existing.hits + 1)
                    existing.unit = (existing.unit * existing.hits + estimate) / (existing.hits + 1)
                    existing.hits++
                }
                // Bound work on adversarial images full of finder-like stripes.
                if (found.size > 128) return emptyList()
            }
        }
        return found.filter { it.hits >= 2 }
    }

    private fun ratio(run: IntArray): Boolean {
        val unit = run.sum() / 7.0
        if (unit < 1) return false
        return run.indices.all { abs(run[it] - unit * if (it == 2) 3 else 1) <= unit * if (it == 2) 1.1 else 0.65 }
    }
    private fun vertical(im: Raster, x: Int, y: Int, unit: Double): Pair<Double, Double>? {
        if (x !in 0 until im.width) return null
        fun at(row: Int) = black(im.pixels[row * im.width + x])
        if (!at(y)) return null
        val run = IntArray(5)
        var top = y
        while (top >= 0 && at(top) && run[2] < unit * 5) { run[2]++; top-- }
        var bottom = y + 1
        while (bottom < im.height && at(bottom) && run[2] < unit * 5) { run[2]++; bottom++ }
        val center = (top + 1 + bottom) / 2.0
        while (top >= 0 && !at(top) && run[1] < unit * 3) { run[1]++; top-- }
        while (top >= 0 && at(top) && run[0] < unit * 3) { run[0]++; top-- }
        while (bottom < im.height && !at(bottom) && run[3] < unit * 3) { run[3]++; bottom++ }
        while (bottom < im.height && at(bottom) && run[4] < unit * 3) { run[4]++; bottom++ }
        return if (ratio(run) && abs(run.sum() / 7.0 - unit) <= unit * 0.65) center to (run.sum() / 7.0) else null
    }

    private fun distance(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)
    private fun convex(p: List<Point>): Boolean {
        val cross = p.indices.map { i ->
            val a = p[i]; val b = p[(i + 1) % 4]; val c = p[(i + 2) % 4]
            (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
        }
        return cross.all { it > 100 } || cross.all { it < -100 }
    }
    private fun homography(grid: Int, points: List<Point>): DoubleArray? {
        val lo = 3.5; val hi = grid - 3.5
        val source = listOf(Point(lo, lo), Point(hi, lo), Point(hi, hi), Point(lo, hi))
        val m = Array(8) { DoubleArray(9) }
        for (i in 0..3) {
            val x = source[i].x; val y = source[i].y
            val u = points[i].x; val v = points[i].y
            m[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
            m[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
        }
        for (col in 0..7) {
            val pivot = (col..7).maxBy { abs(m[it][col]) }
            if (abs(m[pivot][col]) < 1e-9) return null
            val tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp
            val factor = m[col][col]
            for (j in col..8) m[col][j] /= factor
            for (row in 0..7) if (row != col) {
                val f = m[row][col]
                for (j in col..8) m[row][j] -= f * m[col][j]
            }
        }
        return DoubleArray(8) { m[it][8] }
    }
    private fun project(h: DoubleArray, x: Double, y: Double): Point {
        val d = h[6] * x + h[7] * y + 1
        return Point((h[0] * x + h[1] * y + h[2]) / d, (h[3] * x + h[4] * y + h[5]) / d)
    }
}

package dev.colorcode.core

/** Shortened systematic RS(255,223), GF(256), primitive polynomial 0x11d, roots α^0..α^31. */
object ReedSolomon {
    private val exp = IntArray(512)
    private val log = IntArray(256)
    init {
        var x = 1
        for (i in 0..254) {
            exp[i] = x; log[x] = i
            x = x shl 1
            if (x and 256 != 0) x = x xor 0x11d
        }
        for (i in 255..511) exp[i] = exp[i - 255]
    }
    private fun mul(a: Int, b: Int) = if (a == 0 || b == 0) 0 else exp[log[a] + log[b]]
    private fun div(a: Int, b: Int): Int {
        if (b == 0) throw CodeException("Invalid error locator")
        return if (a == 0) 0 else exp[(log[a] - log[b] + 255) % 255]
    }
    // Polynomials used here have coefficients in ascending power order.
    private fun evaluate(p: IntArray, x: Int): Int {
        var result = 0
        for (i in p.indices.reversed()) result = mul(result, x) xor p[i]
        return result
    }
    private val generator: IntArray by lazy {
        var g = intArrayOf(1)
        repeat(32) { root ->
            val next = IntArray(g.size + 1)
            for (i in g.indices) {
                next[i] = next[i] xor mul(g[i], exp[root])
                next[i + 1] = next[i + 1] xor g[i]
            }
            g = next
        }
        g.reversedArray() // encoder long division uses descending powers
    }

    fun encode(data: ByteArray): ByteArray {
        if (data.size !in 1..223) throw CodeException("RS data length must be 1–223 bytes")
        val work = IntArray(data.size + 32)
        data.forEachIndexed { i, b -> work[i] = b.toInt() and 255 }
        for (i in data.indices) {
            val coefficient = work[i]
            if (coefficient != 0) for (j in generator.indices) work[i + j] = work[i + j] xor mul(generator[j], coefficient)
        }
        return data + ByteArray(32) { work[data.size + it].toByte() }
    }

    fun correct(code: ByteArray): Pair<ByteArray, Int> {
        if (code.size !in 33..255) throw CodeException("RS code length must be 33–255 bytes")
        val received = IntArray(code.size) { code[it].toInt() and 255 }
        fun syndromes(): IntArray = IntArray(32) { root ->
            var s = 0
            for (v in received) s = mul(s, exp[root]) xor v
            s
        }
        val syndrome = syndromes()
        if (syndrome.all { it == 0 }) return code.copyOf(code.size - 32) to 0
        // Berlekamp–Massey: Λ(z) = product(1 + errorLocation * z).
        var locator = IntArray(33).apply { this[0] = 1 }
        var previous = locator.copyOf()
        var degree = 0
        var shift = 1
        var lastDiscrepancy = 1
        for (n in syndrome.indices) {
            var discrepancy = syndrome[n]
            for (i in 1..degree) discrepancy = discrepancy xor mul(locator[i], syndrome[n - i])
            if (discrepancy == 0) { shift++; continue }
            val saved = locator.copyOf()
            val factor = div(discrepancy, lastDiscrepancy)
            for (i in 0 until 33 - shift) locator[i + shift] = locator[i + shift] xor mul(factor, previous[i])
            if (2 * degree <= n) {
                degree = n + 1 - degree
                previous = saved
                lastDiscrepancy = discrepancy
                shift = 1
            } else shift++
        }
        if (degree !in 1..16) throw CodeException("Too many damaged bytes in RS block")
        locator = locator.copyOf(degree + 1)
        val positions = received.indices.filter { pos ->
            val power = received.lastIndex - pos
            evaluate(locator, exp[(255 - power) % 255]) == 0
        }
        if (positions.size != degree) throw CodeException("Uncorrectable RS block")
        // Solve the first t syndrome equations for magnitudes (t <= 16).
        val matrix = Array(degree) { row -> IntArray(degree + 1) { col ->
            if (col == degree) syndrome[row] else exp[((received.lastIndex - positions[col]) * row) % 255]
        } }
        for (col in 0 until degree) {
            val pivot = (col until degree).firstOrNull { matrix[it][col] != 0 }
                ?: throw CodeException("Singular RS correction")
            val tmp = matrix[col]; matrix[col] = matrix[pivot]; matrix[pivot] = tmp
            val inverse = div(1, matrix[col][col])
            for (j in col..degree) matrix[col][j] = mul(matrix[col][j], inverse)
            for (row in 0 until degree) if (row != col) {
                val factor = matrix[row][col]
                for (j in col..degree) matrix[row][j] = matrix[row][j] xor mul(factor, matrix[col][j])
            }
        }
        positions.forEachIndexed { i, pos -> received[pos] = received[pos] xor matrix[i][degree] }
        if (syndromes().any { it != 0 }) throw CodeException("RS correction verification failed")
        return ByteArray(code.size - 32) { received[it].toByte() } to degree
    }
}

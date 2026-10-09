package com.aamus.powerpdf.engine

/**
 * Comparing two documents' words (desktop features: Compare two PDFs, decisions §45 there): the
 * words of each document in reading order, compared after folding (case, Arabic letter forms and
 * vowel marks), with Myers' difference algorithm; changes are grouped with a little context.
 */
data class DocWord(val text: String, val page: Int)

/** One change: what the first document had here and what the second has instead. */
data class Change(
    val oldPage: Int,
    val newPage: Int,
    val before: String,
    val removed: String,
    val added: String,
    val after: String,
)

object TextDiff {
    enum class Op { Same, Del, Ins }

    /**
     * Rounds of Myers' search allowed: its memory grows with their square, so two documents that
     * differ in more places than this are reported as too different (review finding 2026-10-01:
     * keeping every round whole took hundreds of megabytes).
     */
    const val MAX_D = 3000

    /**
     * The edit script turning [a] into [b], or null when they differ too much. "Patience" first
     * (desktop compare.ts): words that occur exactly once on each side of a stretch anchor the two
     * sides to each other, so a long document with changes all through it is split into short
     * stretches; Myers' search runs only between anchors. Before, one search over the whole
     * document gave up past [MAX_D] changes. Null only when nothing anchors the two at all and
     * Myers' search gives up too (unrelated documents).
     */
    fun diff(a: List<String>, b: List<String>): List<Pair<Op, Int>>? {
        val matches = ArrayList<Long>()
        if (!patience(a, b, 0, a.size, 0, b.size, matches, top = true)) return null
        val out = ArrayList<Pair<Op, Int>>(a.size + b.size)
        var i = 0
        var j = 0
        for (m in matches) {
            val mi = (m ushr 32).toInt()
            val mj = (m and 0xFFFFFFFFL).toInt()
            while (i < mi) out.add(Op.Del to i++)
            while (j < mj) out.add(Op.Ins to j++)
            out.add(Op.Same to mi)
            i = mi + 1
            j = mj + 1
        }
        while (i < a.size) out.add(Op.Del to i++)
        while (j < b.size) out.add(Op.Ins to j++)
        return out
    }

    private fun pair(i: Int, j: Int): Long = (i.toLong() shl 32) or (j.toLong() and 0xFFFFFFFFL)

    /**
     * Adds the matched pairs of a[a0, a1) and b[b0, b1) to [out], in order. False when [top] (the
     * whole of both documents) has no anchor and Myers' search gives up: too different.
     */
    private fun patience(a: List<String>, b: List<String>, a0In: Int, a1In: Int, b0In: Int, b1In: Int, out: ArrayList<Long>, top: Boolean = false): Boolean {
        var a0 = a0In
        var a1 = a1In
        var b0 = b0In
        var b1 = b1In
        // The same words at both ends match directly.
        while (a0 < a1 && b0 < b1 && a[a0] == b[b0]) out.add(pair(a0++, b0++))
        val tail = ArrayList<Long>()
        while (a0 < a1 && b0 < b1 && a[a1 - 1] == b[b1 - 1]) tail.add(pair(--a1, --b1))
        if (a0 < a1 && b0 < b1) {
            // Words that occur exactly once on each side of this stretch, in order on both sides.
            class Count(var na: Int = 0, var nb: Int = 0, var ai: Int = -1, var bi: Int = -1)
            val count = HashMap<String, Count>()
            for (i in a0 until a1) count.getOrPut(a[i]) { Count() }.let { it.na++; it.ai = i }
            for (j in b0 until b1) count[b[j]]?.let { it.nb++; it.bi = j }
            val unique = count.values.filter { it.na == 1 && it.nb == 1 }.sortedBy { it.ai }
            val anchors = longestIncreasing(unique.map { it.bi }).map { unique[it] }
            if (anchors.isEmpty()) {
                val core = myers(a.subList(a0, a1), b.subList(b0, b1))
                if (core == null) {
                    // Too many changes in one stretch: all of it counts as changed.
                    if (top) return false
                } else {
                    // Walked in order: each "same" is the next word on both sides.
                    var x = a0
                    var y = b0
                    for ((op, _) in core) when (op) {
                        Op.Same -> out.add(pair(x++, y++))
                        Op.Del -> x++
                        Op.Ins -> y++
                    }
                }
            } else {
                var pa = a0
                var pb = b0
                for (anchor in anchors) {
                    patience(a, b, pa, anchor.ai, pb, anchor.bi, out)
                    out.add(pair(anchor.ai, anchor.bi))
                    pa = anchor.ai + 1
                    pb = anchor.bi + 1
                }
                patience(a, b, pa, a1, pb, b1, out)
            }
        }
        for (k in tail.indices.reversed()) out.add(tail[k])
        return true
    }

    /** The longest strictly increasing subsequence of [seq], as indices into it. */
    private fun longestIncreasing(seq: List<Int>): List<Int> {
        val tails = IntArray(seq.size)
        var len = 0
        val prev = IntArray(seq.size) { -1 }
        for (i in seq.indices) {
            var lo = 0
            var hi = len
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (seq[tails[mid]] < seq[i]) lo = mid + 1 else hi = mid
            }
            if (lo > 0) prev[i] = tails[lo - 1]
            tails[lo] = i
            if (lo == len) len++
        }
        val out = ArrayList<Int>(len)
        var k = if (len > 0) tails[len - 1] else -1
        while (k >= 0) {
            out.add(k)
            k = prev[k]
        }
        return out.asReversed()
    }

    /** Myers' difference (O((N+M)·D) time); each round keeps only its own diagonals (O(D²) memory). */
    private fun myers(a: List<String>, b: List<String>): List<Pair<Op, Int>>? {
        val n = a.size
        val m = b.size
        if (n == 0 && m == 0) return emptyList()
        val limit = minOf(n + m, MAX_D)
        val offset = limit + 1
        val v = IntArray(2 * limit + 3)
        // trace[d]: the furthest x on each diagonal k = -d..d after round d.
        val trace = ArrayList<IntArray>()
        var end = -1
        for (d in 0..limit) {
            var k = -d
            var found = false
            while (k <= d) {
                var x = if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) v[offset + k + 1] else v[offset + k - 1] + 1
                var y = x - k
                while (x < n && y < m && a[x] == b[y]) { x++; y++ }
                v[offset + k] = x
                if (x >= n && y >= m) { found = true; break }
                k += 2
            }
            trace.add(IntArray(2 * d + 1) { v[offset - d + it] })
            if (found) {
                end = d
                break
            }
        }
        if (end < 0) return null
        // Walk back through the rounds.
        val out = ArrayList<Pair<Op, Int>>()
        var x = n
        var y = m
        for (d in end downTo 1) {
            val prev = trace[d - 1]
            fun at(k: Int) = prev[k + d - 1]
            val k = x - y
            val prevK = if (k == -d || (k != d && at(k - 1) < at(k + 1))) k + 1 else k - 1
            val prevX = at(prevK)
            val prevY = prevX - prevK
            while (x > prevX && y > prevY) { out.add(Op.Same to x - 1); x--; y-- }
            if (x == prevX) out.add(Op.Ins to y - 1) else out.add(Op.Del to x - 1)
            x = prevX
            y = prevY
        }
        while (x > 0 && y > 0) { out.add(Op.Same to x - 1); x--; y-- }
        return out.asReversed()
    }

    /** Folding for comparing: case, Arabic letter forms and marks, Arabic-Indic digits. */
    fun fold(word: String): String = RedactPatterns.westernDigits(TextLogic.foldString(word)).lowercase()

    /** Groups the differences into changes, each with up to [context] words either side; null if too different. */
    fun changes(a: List<DocWord>, b: List<DocWord>, context: Int = 5): List<Change>? {
        val ka = a.map { fold(it.text) }
        val script = diff(ka, b.map { fold(it.text) }) ?: return null
        // Each run of differences: words a[a0, a1) became b[b0, b1).
        val runs = ArrayList<IntArray>()
        var i = 0
        // Positions in a and b as the script is walked.
        var ai = 0
        var bi = 0
        while (i < script.size) {
            if (script[i].first == Op.Same) { ai++; bi++; i++; continue }
            val startA = ai
            val startB = bi
            while (i < script.size && script[i].first != Op.Same) {
                if (script[i].first == Op.Del) ai++ else bi++
                i++
            }
            runs.add(intArrayOf(startA, ai, startB, bi))
        }
        return joinRewrites(runs, a, b, ka).map { (a0, a1, b0, b1) ->
            Change(
                oldPage = a.getOrNull(a0)?.page ?: a.lastOrNull()?.page ?: 0,
                newPage = b.getOrNull(b0)?.page ?: b.lastOrNull()?.page ?: 0,
                before = a.subList(maxOf(0, a0 - context), a0).joinToString(" ") { it.text },
                removed = a.subList(a0, a1).joinToString(" ") { it.text },
                added = b.subList(b0, b1).joinToString(" ") { it.text },
                after = a.subList(a1, minOf(a.size, a1 + context)).joinToString(" ") { it.text },
            )
        }
    }

    /** The most equal words, each this short at most, that two changes may lie apart and still be one. */
    private const val BRIDGE_WORDS = 2
    private const val BRIDGE_LETTERS = 3

    /**
     * One rewritten sentence is one change (desktop compare.ts joinRewrites, decisions §52). A diff
     * matches whatever it can, so a short common word that both versions happen to use ("in",
     * "the", "في") split a rewritten sentence into two changes around it (seen on the owner's
     * phone, 2026-10-09). Changes with only one or two such short words between them are joined,
     * those words included; a longer word, or three words, keeps them apart. Never across a page.
     */
    private fun joinRewrites(runs: List<IntArray>, a: List<DocWord>, b: List<DocWord>, ka: List<String>): List<IntArray> {
        val out = ArrayList<IntArray>()
        for (c in runs) {
            val last = out.lastOrNull()
            val between = if (last == null) -1 else c[0] - last[1]
            val bridge = last != null &&
                between in 1..BRIDGE_WORDS &&
                c[2] - last[3] == between &&
                (last[1] until c[0]).all { k -> ka[k].isNotEmpty() && ka[k].codePointCount(0, ka[k].length) <= BRIDGE_LETTERS } &&
                samePage(a, last[0], c[1]) &&
                samePage(b, last[2], c[3])
            if (bridge) out[out.lastIndex] = intArrayOf(last!![0], c[1], last[2], c[3]) else out.add(c)
        }
        return out
    }

    /** True when the words from..to (exclusive) are all on one page. */
    private fun samePage(words: List<DocWord>, from: Int, to: Int): Boolean =
        (from until minOf(to, words.size)).map { words[it].page }.distinct().size <= 1
}

/** Every word of the document in reading order, with its page. */
fun PdfDoc.words(progress: (Int) -> Unit = {}): List<DocWord> {
    val out = ArrayList<DocWord>()
    for (p in 0 until pageCount) {
        progress(p)
        for (line in lines(p)) for (w in line.text.split(Regex("\\s+"))) if (w.isNotBlank()) out.add(DocWord(w, p))
    }
    return out
}

package com.gravity.ide.core

/**
 * Myers O(ND) line diff. Used by the change-review screen, checkpoint restore previews
 * and the reviewer agent (which receives a unified diff of the task's edits).
 */
object LineDiff {

    enum class Op { SAME, ADD, DEL }

    data class Line(val op: Op, val text: String, val oldNo: Int?, val newNo: Int?)

    /**
     * Above this edit distance the diff falls back to "replace everything". The trace keeps
     * only the live diagonal band per step (≈ D² ints), so 2000 caps memory at ~16 MB.
     */
    private const val MAX_D = 2000

    fun diff(oldText: String, newText: String): List<Line> = diff(splitLines(oldText), splitLines(newText))

    fun splitLines(text: String): List<String> = if (text.isEmpty()) emptyList() else text.split("\n")

    fun diff(a: List<String>, b: List<String>): List<Line> {
        val n = a.size
        val m = b.size
        val max = n + m
        if (max == 0) return emptyList()
        val offset = max + 1
        val v = IntArray(2 * max + 4)
        val trace = ArrayList<IntArray>()
        var reached = false
        var d = 0
        while (d <= max) {
            if (d > MAX_D) return replaceAll(a, b)
            // Snapshot of v for diagonals -d-1..d+1 (all that backtracking at this step reads).
            trace.add(v.copyOfRange(offset - d - 1, offset + d + 2))
            var k = -d
            while (k <= d) {
                var x = if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) {
                    v[offset + k + 1]
                } else {
                    v[offset + k - 1] + 1
                }
                var y = x - k
                while (x < n && y < m && a[x] == b[y]) {
                    x++
                    y++
                }
                v[offset + k] = x
                if (x >= n && y >= m) {
                    reached = true
                    break
                }
                k += 2
            }
            if (reached) break
            d++
        }

        val ops = ArrayList<Pair<Op, String>>()
        var x = n
        var y = m
        for (step in trace.size - 1 downTo 0) {
            val vv = trace[step]
            fun at(diag: Int) = vv[diag + step + 1]
            val k = x - y
            val prevK = if (k == -step || (k != step && at(k - 1) < at(k + 1))) k + 1 else k - 1
            val prevX = at(prevK)
            val prevY = prevX - prevK
            while (x > prevX && y > prevY) {
                ops.add(Op.SAME to a[x - 1])
                x--
                y--
            }
            if (step > 0) {
                if (x == prevX) {
                    ops.add(Op.ADD to b[y - 1])
                    y--
                } else {
                    ops.add(Op.DEL to a[x - 1])
                    x--
                }
            }
        }
        ops.reverse()
        return number(ops)
    }

    private fun replaceAll(a: List<String>, b: List<String>): List<Line> =
        number(a.map { Op.DEL to it } + b.map { Op.ADD to it })

    private fun number(ops: List<Pair<Op, String>>): List<Line> {
        var oldNo = 1
        var newNo = 1
        return ops.map { (op, text) ->
            when (op) {
                Op.SAME -> Line(op, text, oldNo++, newNo++)
                Op.DEL -> Line(op, text, oldNo++, null)
                Op.ADD -> Line(op, text, null, newNo++)
            }
        }
    }

    data class Stats(val added: Int, val removed: Int)

    fun stats(lines: List<Line>) = Stats(lines.count { it.op == Op.ADD }, lines.count { it.op == Op.DEL })

    /** Standard unified diff with [context] lines around each hunk. */
    fun unified(oldName: String, newName: String, oldText: String, newText: String, context: Int = 3): String {
        val lines = diff(oldText, newText)
        if (lines.none { it.op != Op.SAME }) return ""
        val sb = StringBuilder()
        sb.append("--- ").append(oldName).append('\n')
        sb.append("+++ ").append(newName).append('\n')
        var i = 0
        while (i < lines.size) {
            if (lines[i].op == Op.SAME) {
                i++
                continue
            }
            val start = maxOf(0, i - context)
            var end = i
            // Extend the hunk while changes are within 2*context of each other.
            var lastChange = i
            while (end < lines.size) {
                if (lines[end].op != Op.SAME) lastChange = end
                if (end - lastChange > 2 * context) break
                end++
            }
            val stop = minOf(lines.size, lastChange + context + 1)
            val hunk = lines.subList(start, stop)
            val oldStart = hunk.firstOrNull { it.oldNo != null }?.oldNo ?: 0
            val newStart = hunk.firstOrNull { it.newNo != null }?.newNo ?: 0
            val oldCount = hunk.count { it.op != Op.ADD }
            val newCount = hunk.count { it.op != Op.DEL }
            sb.append("@@ -").append(oldStart).append(',').append(oldCount)
                .append(" +").append(newStart).append(',').append(newCount).append(" @@\n")
            for (l in hunk) {
                sb.append(
                    when (l.op) {
                        Op.SAME -> ' '
                        Op.ADD -> '+'
                        Op.DEL -> '-'
                    }
                ).append(l.text).append('\n')
            }
            i = stop
        }
        return sb.toString()
    }
}

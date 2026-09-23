package com.example.trust

/** A rectangle in component space. Doubles, because the rounding belongs at the paint call. */
data class TreemapRect(val x: Double, val y: Double, val w: Double, val h: Double)

data class Tile<T>(val value: T, val rect: TreemapRect)

/**
 * Squarified treemap layout: area proportional to weight, rectangles kept as square as possible.
 *
 * The "squarified" part is the whole point. A naive treemap slices strips and produces slivers
 * that are impossible to read, label or click. This is Bruls, Huizing and van Wijk's method:
 * fill the shorter side one row at a time, and stop adding to a row the moment doing so would
 * make its worst aspect ratio worse.
 */
object Squarify {

    fun <T> layout(items: List<T>, weight: (T) -> Double, bounds: TreemapRect): List<Tile<T>> {
        val weighted = items.map { it to weight(it) }.filter { it.second > 0 }
        if (weighted.isEmpty() || bounds.w <= 0 || bounds.h <= 0) return emptyList()

        val total = weighted.sumOf { it.second }
        val scale = (bounds.w * bounds.h) / total

        val out = ArrayList<Tile<T>>(weighted.size)
        val queue = ArrayDeque(weighted.sortedByDescending { it.second })

        var x = bounds.x
        var y = bounds.y
        var w = bounds.w
        var h = bounds.h

        while (queue.isNotEmpty()) {
            val short = minOf(w, h)
            if (short <= 0) break

            val row = ArrayList<Pair<T, Double>>()
            var best = Double.MAX_VALUE
            while (queue.isNotEmpty()) {
                val candidate = row + queue.first()
                val ratio = worstRatio(candidate.map { it.second }, short, scale)
                if (ratio > best) break
                best = ratio
                row.add(queue.removeFirst())
            }
            if (row.isEmpty()) row.add(queue.removeFirst())

            val rowArea = row.sumOf { it.second } * scale
            val thickness = (rowArea / short).coerceAtMost(maxOf(w, h))

            var along = 0.0
            for ((item, value) in row) {
                val length = if (thickness > 0) (value * scale) / thickness else 0.0
                out += if (w >= h) {
                    Tile(item, TreemapRect(x, y + along, thickness, length))
                } else {
                    Tile(item, TreemapRect(x + along, y, length, thickness))
                }
                along += length
            }

            if (w >= h) {
                x += thickness
                w -= thickness
            } else {
                y += thickness
                h -= thickness
            }
        }
        return out
    }

    /** The worst (largest) aspect ratio in a row, which is what the algorithm minimises. */
    private fun worstRatio(values: List<Double>, short: Double, scale: Double): Double {
        val areas = values.map { it * scale }
        val sum = areas.sum()
        if (sum <= 0.0) return Double.MAX_VALUE
        val max = areas.max()
        val min = areas.min()
        if (min <= 0.0) return Double.MAX_VALUE
        return maxOf((short * short * max) / (sum * sum), (sum * sum) / (short * short * min))
    }
}

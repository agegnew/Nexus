package com.example.trust

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The geometry the picture depends on.
 *
 * A treemap that gets the areas wrong is worse than no treemap: it is a chart that lies
 * confidently, and nobody checks a picture with a calculator.
 */
class SquarifyTest {

    private val bounds = TreemapRect(0.0, 0.0, 400.0, 300.0)

    private fun area(tile: Tile<*>) = tile.rect.w * tile.rect.h

    @Test
    fun `every tile gets area in proportion to its weight`() {
        val tiles = Squarify.layout(listOf(50, 30, 20), { it.toDouble() }, bounds)

        val total = bounds.w * bounds.h
        val byValue = tiles.associate { it.value to area(it) }

        assertEquals(total * 0.5, byValue.getValue(50), 1.0)
        assertEquals(total * 0.3, byValue.getValue(30), 1.0)
        assertEquals(total * 0.2, byValue.getValue(20), 1.0)
    }

    @Test
    fun `the tiles fill the box exactly, with no gap and no overlap in area`() {
        val tiles = Squarify.layout((1..17).toList(), { it.toDouble() }, bounds)

        assertEquals(bounds.w * bounds.h, tiles.sumOf { area(it) }, 1.0)
    }

    @Test
    fun `nothing is laid out beyond the edges it was given`() {
        val box = TreemapRect(12.0, 40.0, 200.0, 120.0)
        val tiles = Squarify.layout((1..23).toList(), { it.toDouble() }, box)

        tiles.forEach {
            assertTrue("left edge of ${it.value}", it.rect.x >= box.x - 0.001)
            assertTrue("top edge of ${it.value}", it.rect.y >= box.y - 0.001)
            assertTrue("right edge of ${it.value}", it.rect.x + it.rect.w <= box.x + box.w + 0.001)
            assertTrue("bottom edge of ${it.value}", it.rect.y + it.rect.h <= box.y + box.h + 0.001)
        }
    }

    @Test
    fun `rectangles stay roughly square rather than degenerating into slivers`() {
        // The whole reason for squarifying: equal weights must not produce 400x0.7 strips,
        // because a sliver cannot be labelled, hovered or clicked.
        val tiles = Squarify.layout((1..16).map { 10 }, { it.toDouble() }, bounds)

        val worst = tiles.maxOf { maxOf(it.rect.w / it.rect.h, it.rect.h / it.rect.w) }
        assertTrue("worst aspect ratio was $worst", worst < 3.0)
    }

    @Test
    fun `weightless items are dropped instead of taking up room`() {
        val tiles = Squarify.layout(listOf(10, 0, 5), { it.toDouble() }, bounds)

        assertEquals(listOf(10, 5), tiles.map { it.value })
    }

    @Test
    fun `a single item takes the whole box`() {
        val tiles = Squarify.layout(listOf(7), { it.toDouble() }, bounds)

        assertEquals(1, tiles.size)
        assertEquals(bounds.w * bounds.h, area(tiles.single()), 0.001)
    }

    @Test
    fun `an empty box produces nothing rather than dividing by zero`() {
        assertTrue(Squarify.layout(listOf(1, 2), { it.toDouble() }, TreemapRect(0.0, 0.0, 0.0, 50.0)).isEmpty())
        assertTrue(Squarify.layout(emptyList<Int>(), { 1.0 }, bounds).isEmpty())
    }
}

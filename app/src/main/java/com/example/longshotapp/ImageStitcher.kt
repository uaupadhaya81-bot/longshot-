package com.example.longshotapp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object ImageStitcher {

    fun stitch(bitmaps: List<Bitmap>): Bitmap? {
        if (bitmaps.isEmpty()) return null

        val normalized = bitmaps.map { bmp ->
            if (bmp.config == Bitmap.Config.ARGB_8888) bmp
            else bmp.copy(Bitmap.Config.ARGB_8888, false)
        }

        if (normalized.size == 1) return normalized.first()

        var base = normalized.first()
        for (i in 1 until normalized.size) {
            val next = normalized[i]
            val overlap = findBestOverlap(base, next)
            base = mergeWithOverlap(base, next, overlap)
        }

        return base
    }

    private fun findBestOverlap(bitmapA: Bitmap, bitmapB: Bitmap): Int {
        val width = min(bitmapA.width, bitmapB.width)
        val heightA = bitmapA.height
        val heightB = bitmapB.height
        val maxPossible = min(heightA, heightB) - 1

        if (maxPossible < 20) return 0

        val expected = (min(heightA, heightB) * 0.28f).toInt().coerceAtLeast(60)
        val searchMin = max(20, (expected * 0.60f).toInt())
        val searchMax = min(maxPossible, (expected * 1.45f).toInt())

        if (searchMin > searchMax) return 0

        val sampleXs = buildSampleXs(width)
        var bestOverlap = 0
        var bestScore = Double.MAX_VALUE

        for (overlap in searchMax downTo searchMin) {
            val score = overlapScore(bitmapA, bitmapB, overlap, sampleXs)
            if (score < bestScore) {
                bestScore = score
                bestOverlap = overlap
            }
        }

        return if (bestScore <= 30.0) bestOverlap else 0
    }

    private fun overlapScore(
        bitmapA: Bitmap,
        bitmapB: Bitmap,
        overlap: Int,
        sampleXs: IntArray
    ): Double {
        val heightA = bitmapA.height
        var total = 0L
        var count = 0L

        var rowOffset = 0
        while (rowOffset < overlap) {
            val rowA = heightA - overlap + rowOffset
            val rowB = rowOffset

            for (x in sampleXs) {
                val a = bitmapA.getPixel(x, rowA)
                val b = bitmapB.getPixel(x, rowB)

                total += abs(Color.red(a) - Color.red(b))
                total += abs(Color.green(a) - Color.green(b))
                total += abs(Color.blue(a) - Color.blue(b))
                count += 3
            }

            rowOffset += 3
        }

        return if (count == 0L) Double.MAX_VALUE else total.toDouble() / count.toDouble()
    }

    private fun buildSampleXs(width: Int): IntArray {
        val xs = ArrayList<Int>()
        val step = max(12, width / 14)

        var x = step / 2
        while (x < width) {
            xs.add(x)
            x += step
        }

        if (xs.isEmpty()) xs.add(width / 2)
        return xs.toIntArray()
    }

    private fun mergeWithOverlap(
        baseBitmap: Bitmap,
        nextBitmap: Bitmap,
        overlapRows: Int
    ): Bitmap {
        val width = min(baseBitmap.width, nextBitmap.width)
        val newHeight = baseBitmap.height + nextBitmap.height - overlapRows
        val out = Bitmap.createBitmap(width, newHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        canvas.drawBitmap(
            baseBitmap,
            Rect(0, 0, width, baseBitmap.height),
            Rect(0, 0, width, baseBitmap.height),
            null
        )

        val srcTop = overlapRows.coerceIn(0, nextBitmap.height)
        val srcRect = Rect(0, srcTop, width, nextBitmap.height)
        val dstRect = Rect(0, baseBitmap.height, width, newHeight)

        canvas.drawBitmap(nextBitmap, srcRect, dstRect, null)

        return out
    }
}

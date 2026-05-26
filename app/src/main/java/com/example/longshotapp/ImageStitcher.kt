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
        var previousOverlap = 0

        for (i in 1 until normalized.size) {
            val next = normalized[i]
            val overlap = findBestOverlap(base, next, previousOverlap)
            
            // SMART FALLBACK: If matching fails (returns 0), fall back to the 
            // previous successful overlap or a smart 28% frame estimate.
            val finalOverlap = if (overlap > 0) {
                previousOverlap = overlap
                overlap
            } else {
                if (previousOverlap > 0) previousOverlap 
                else (next.height * 0.28f).toInt().coerceAtLeast(60)
            }
            
            base = mergeWithOverlap(base, next, finalOverlap)
        }

        return base
    }

    private fun findBestOverlap(
        bitmapA: Bitmap,
        bitmapB: Bitmap,
        previousOverlap: Int
    ): Int {
        val width = min(bitmapA.width, bitmapB.width)
        val maxPossible = min(bitmapA.height, bitmapB.height) - 1
        if (maxPossible < 20) return 0

        // Corresponds with the 28% calculation inside ScreenCaptureService
        val expected = when {
            previousOverlap > 0 -> previousOverlap
            else -> (min(bitmapA.height, bitmapB.height) * 0.28f).toInt().coerceAtLeast(60)
        }

        // Expand search boundaries slightly to tolerate scrolling variance
        val searchMin = max(20, (expected * 0.65f).toInt())
        val searchMax = min(maxPossible, (expected * 1.35f).toInt())
        if (searchMin > searchMax) return 0

        val sampleXs = buildSampleXs(width)

        var bestOverlap = 0
        var bestScore = Double.MAX_VALUE

        for (overlap in searchMax downTo searchMin) {
            val score = overlapScore(bitmapA, bitmapB, overlap, sampleXs, expected)
            if (score < bestScore) {
                bestScore = score
                bestOverlap = overlap
            }
        }

        // A relaxed threshold combined with the solid color penalty prevents false rejections
        return if (bestScore <= 25.0) bestOverlap else 0
    }

    private fun overlapScore(
        bitmapA: Bitmap,
        bitmapB: Bitmap,
        overlap: Int,
        sampleXs: IntArray,
        expected: Int
    ): Double {
        val heightA = bitmapA.height
        var totalDiff = 0.0
        var count = 0.0

        // Visual Variance Variables
        var firstPixelColor: Int? = null
        var isSolidColorZone = true

        var rowOffset = 0
        while (rowOffset < overlap) {
            val rowA = heightA - overlap + rowOffset
            val rowB = rowOffset

            for (x in sampleXs) {
                val a = bitmapA.getPixel(x, rowA)
                val b = bitmapB.getPixel(x, rowB)

                // Track if the scanning window contains distinct texture/colors
                if (firstPixelColor == null) {
                    firstPixelColor = a
                } else if (a != firstPixelColor) {
                    isSolidColorZone = false
                }

                totalDiff += abs(Color.red(a) - Color.red(b))
                totalDiff += abs(Color.green(a) - Color.green(b))
                totalDiff += abs(Color.blue(a) - Color.blue(b))
                count += 3.0
            }

            rowOffset += 4 // Performance optimization stride
        }

        val pixelDiff = if (count == 0.0) Double.MAX_VALUE else totalDiff / count

        // SOLID BACKGROUND PENALTY: If the zone is uniform color, heavily penalize it.
        // This forces the algorithm to prioritize areas with text or UI elements.
        val solidColorPenalty = if (isSolidColorZone) 1000.0 else 0.0

        // Tie-breaker penalty to favor overlaps matching the physics engine configuration
        val distancePenalty = abs(overlap - expected) * 0.05

        return pixelDiff + distancePenalty + solidColorPenalty
    }

    private fun buildSampleXs(width: Int): IntArray {
        val xs = ArrayList<Int>()

        // Capture elements closer to the margins while avoiding side scrollbars
        val left = (width * 0.12f).toInt()
        val right = (width * 0.88f).toInt()
        val step = max(8, (width * 0.05f).toInt()) // Tighter steps for enhanced reliability

        var x = left
        while (x <= right) {
            xs.add(x.coerceIn(0, width - 1))
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

        // Draw top image
        canvas.drawBitmap(
            baseBitmap,
            Rect(0, 0, width, baseBitmap.height),
            Rect(0, 0, width, baseBitmap.height),
            null
        )

        // Seamlessly append the lower frame from the computed junction row
        val srcTop = overlapRows.coerceIn(0, nextBitmap.height)
        val srcRect = Rect(0, srcTop, width, nextBitmap.height)
        val dstRect = Rect(0, baseBitmap.height, width, newHeight)

        canvas.drawBitmap(nextBitmap, srcRect, dstRect, null)

        return out
    }
}

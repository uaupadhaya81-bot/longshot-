package com.example.longshotapp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.abs
import kotlin.math.sqrt

object ImageStitcher {

    fun stitchExact(frames: List<CaptureFrame>): Bitmap? {
        if (frames.isEmpty()) return null
        if (frames.size == 1) return frames.first().bitmap

        val width = frames.first().bitmap.width
        val actualOffsets = mutableListOf<Int>()
        actualOffsets.add(0)

        var currentYOffset = 0
        var totalHeight = frames.first().bitmap.height

        for (i in 1 until frames.size) {
            val prevBitmap = frames[i - 1].bitmap
            val currentBitmap = frames[i].bitmap
            val expectedScroll = frames[i].scrollDistance

            // Find the perfect pixel-level adjustment using the dynamic background scanner
            val adjustedScroll = findMicroAlignment(prevBitmap, currentBitmap, expectedScroll)
            
            currentYOffset += adjustedScroll
            actualOffsets.add(currentYOffset)
            
            totalHeight = currentYOffset + currentBitmap.height
        }

        val resultBitmap = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        for (i in frames.indices) {
            canvas.drawBitmap(frames[i].bitmap, 0f, actualOffsets[i].toFloat(), paint)
        }

        return resultBitmap
    }

    /**
     * Looks at a tiny 40-pixel window around the expected mathematical join point.
     * Uses dynamic color frequency to ignore the background, supporting Dark Mode perfectly.
     */
    private fun findMicroAlignment(prevBitmap: Bitmap, currentBitmap: Bitmap, expectedScroll: Int): Int {
        val width = prevBitmap.width
        val expectedOverlap = prevBitmap.height - expectedScroll
        
        if (expectedOverlap < 50) return expectedScroll

        val searchMin = (expectedOverlap - 20).coerceAtLeast(1)
        val searchMax = (expectedOverlap + 20).coerceAtMost(prevBitmap.height - 1).coerceAtMost(currentBitmap.height - 1)

        val sampleXs = IntArray(10) { i -> width * (i + 1) / 11 }
        
        // 1. Find the most frequent background color dynamically
        val dominantBgColor = extractDominantBackgroundColor(prevBitmap)

        var bestOverlap = expectedOverlap
        var lowestDiff = Double.MAX_VALUE

        // 2. Scan the narrow window
        for (testOverlap in searchMin..searchMax) {
            var diff = 0.0
            var activePixels = 0 
            
            for (rowOffset in 0 until 10) {
                val rowPrev = prevBitmap.height - testOverlap + rowOffset
                val rowCurr = rowOffset
                
                if (rowPrev >= prevBitmap.height || rowCurr >= currentBitmap.height) continue

                for (x in sampleXs) {
                    val p1 = prevBitmap.getPixel(x, rowPrev)
                    val p2 = currentBitmap.getPixel(x, rowCurr)

                    // Check if pixels fall inside the "Color Cube" of the background color
                    val isP1Bg = isColorSimilar(p1, dominantBgColor)
                    val isP2Bg = isColorSimilar(p2, dominantBgColor)

                    // If BOTH pixels are the background color, ignore them. 
                    // Only calculate difference if we hit text/images.
                    if (!isP1Bg || !isP2Bg) {
                        diff += abs(Color.red(p1) - Color.red(p2))
                        diff += abs(Color.green(p1) - Color.green(p2))
                        diff += abs(Color.blue(p1) - Color.blue(p2))
                        activePixels++
                    }
                }
            }

            val finalScore = if (activePixels > 0) diff / activePixels else Double.MAX_VALUE

            if (finalScore < lowestDiff) {
                lowestDiff = finalScore
                bestOverlap = testOverlap
            }
        }

        return prevBitmap.height - bestOverlap
    }

    /**
     * Finds the most frequent color by sampling the left and right margins.
     * This naturally identifies the background color, whether it's Dark Mode, Light Mode, or Sepia.
     */
    private fun extractDominantBackgroundColor(bitmap: Bitmap): Int {
        val colorCounts = HashMap<Int, Int>()
        val step = (bitmap.height / 20).coerceAtLeast(1)
        
        // Sample down the left and right margins
        for (y in 0 until bitmap.height step step) {
            val leftColor = bitmap.getPixel(5, y)
            val rightColor = bitmap.getPixel(bitmap.width - 5, y)
            
            colorCounts[leftColor] = colorCounts.getOrDefault(leftColor, 0) + 1
            colorCounts[rightColor] = colorCounts.getOrDefault(rightColor, 0) + 1
        }
        
        // Return the color that appeared the most
        return colorCounts.maxByOrNull { it.value }?.key ?: Color.WHITE
    }

    /**
     * Calculates the Euclidean distance between two colors in the RGB "Color Cube".
     * If the distance is less than 30, it is considered the same shade.
     */
    private fun isColorSimilar(c1: Int, c2: Int): Boolean {
        val rDiff = Color.red(c1) - Color.red(c2)
        val gDiff = Color.green(c1) - Color.green(c2)
        val bDiff = Color.blue(c1) - Color.blue(c2)
        
        // 3D Distance formula
        val distance = sqrt((rDiff * rDiff + gDiff * gDiff + bDiff * bDiff).toDouble())
        
        // A threshold of 30 allows for slight gradients or JPEG compression artifacts
        return distance < 30.0
    }
}

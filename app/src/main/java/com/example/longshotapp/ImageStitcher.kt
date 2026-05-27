package com.example.longshotapp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.abs

object ImageStitcher {

    fun stitchExact(frames: List<CaptureFrame>): Bitmap? {
        if (frames.isEmpty()) return null
        if (frames.size == 1) return frames.first().bitmap

        val width = frames.first().bitmap.width
        
        // Use a dynamic list to hold the perfectly calculated offsets
        val actualOffsets = mutableListOf<Int>()
        actualOffsets.add(0) // First image is at Y=0

        var currentYOffset = 0
        var totalHeight = frames.first().bitmap.height

        // Calculate the PERFECT micro-aligned offset for each frame
        for (i in 1 until frames.size) {
            val prevBitmap = frames[i - 1].bitmap
            val currentBitmap = frames[i].bitmap
            val expectedScroll = frames[i].scrollDistance

            // Find the perfect pixel-level adjustment
            val adjustedScroll = findMicroAlignment(prevBitmap, currentBitmap, expectedScroll)
            
            currentYOffset += adjustedScroll
            actualOffsets.add(currentYOffset)
            
            // Expand the total height required for the final canvas
            totalHeight = currentYOffset + currentBitmap.height
        }

        // Create the final canvas
        val resultBitmap = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        // Draw everything at the micro-aligned coordinates
        for (i in frames.indices) {
            canvas.drawBitmap(frames[i].bitmap, 0f, actualOffsets[i].toFloat(), paint)
        }

        return resultBitmap
    }

    /**
     * Looks at a tiny 40-pixel window around the expected mathematical join point 
     * to find the absolute perfect pixel alignment, preventing sliced text.
     */
    private fun findMicroAlignment(prevBitmap: Bitmap, currentBitmap: Bitmap, expectedScroll: Int): Int {
        val width = prevBitmap.width
        
        // We expect the images to overlap by this much
        val expectedOverlap = prevBitmap.height - expectedScroll
        
        // If the overlap is too small, trust the math blindly
        if (expectedOverlap < 50) return expectedScroll

        // Search window: 20 pixels above and 20 pixels below the expected mathematical overlap
        val searchMin = (expectedOverlap - 20).coerceAtLeast(1)
        val searchMax = (expectedOverlap + 20).coerceAtMost(prevBitmap.height - 1).coerceAtMost(currentBitmap.height - 1)

        // Sample pixels down the middle of the screen
        val sampleXs = intArrayOf(width / 4, width / 2, width * 3 / 4)
        
        var bestOverlap = expectedOverlap
        var lowestDiff = Double.MAX_VALUE

        // Scan the narrow window for the lowest pixel difference
        for (testOverlap in searchMin..searchMax) {
            var diff = 0.0
            
            // Check a 10-pixel tall strip at this specific test overlap
            for (rowOffset in 0 until 10) {
                val rowPrev = prevBitmap.height - testOverlap + rowOffset
                val rowCurr = rowOffset
                
                if (rowPrev >= prevBitmap.height || rowCurr >= currentBitmap.height) continue

                for (x in sampleXs) {
                    val p1 = prevBitmap.getPixel(x, rowPrev)
                    val p2 = currentBitmap.getPixel(x, rowCurr)

                    diff += abs(Color.red(p1) - Color.red(p2))
                    diff += abs(Color.green(p1) - Color.green(p2))
                    diff += abs(Color.blue(p1) - Color.blue(p2))
                }
            }

            if (diff < lowestDiff) {
                lowestDiff = diff
                bestOverlap = testOverlap
            }
        }

        // Return the perfectly adjusted scroll distance
        return prevBitmap.height - bestOverlap
    }
}

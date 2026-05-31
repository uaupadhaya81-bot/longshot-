package com.example.longshotapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object ImageStitcher {

    fun stitchExact(frames: List<CaptureFrame>, windowSize: Int): Bitmap? {
        if (frames.isEmpty()) return null

        val validFrames = mutableListOf<CaptureFrame>()
        val offsets = mutableListOf<Int>()
        
        validFrames.add(frames[0])
        offsets.add(0)

        var currentYOffset = 0
        
        // Load the very first frame to start the stream
        var prevBitmap = loadBitmap(frames[0].filePath) ?: return null
        var totalHeight = prevBitmap.height
        val width = prevBitmap.width

        try {
            // ==========================================
            // PASS 1: STREAM AND CALCULATE MATH ONLY
            // ==========================================
            for (i in 1 until frames.size) {
                val currentBitmap = loadBitmap(frames[i].filePath) ?: break

                // Dead-End Pruning: If identical, we hit the bottom of the scroll.
                if (areBitmapsIdenticalFast(prevBitmap, currentBitmap)) {
                    currentBitmap.recycle() // Throw it away instantly
                    break // Stop calculating, we are done scrolling
                }

                validFrames.add(frames[i])
                val expectedScroll = frames[i].scrollDistance
                val isLastFrame = (i == frames.size - 1)

                val adjustedScroll = findMicroAlignment(
                    prevBitmap = prevBitmap,
                    currentBitmap = currentBitmap,
                    expectedScroll = expectedScroll,
                    windowSize = windowSize,
                    isLastFrame = isLastFrame
                ).coerceAtLeast(1)

                currentYOffset += adjustedScroll
                offsets.add(currentYOffset)
                totalHeight = currentYOffset + currentBitmap.height

                // CRITICAL MEMORY FIX: Recycle the previous frame, move current to previous
                prevBitmap.recycle()
                prevBitmap = currentBitmap
            }
        } finally {
            // Ensure the last holding bitmap is freed
            if (!prevBitmap.isRecycled) {
                prevBitmap.recycle()
            }
        }

        if (width <= 0 || totalHeight <= 0) return null

        // Hardware Failsafe: Max Android Canvas height is typically 16384px.
        if (totalHeight > 16384) {
            totalHeight = 16384
        }

        // ==========================================
        // PASS 2: STREAM AND DRAW TO CANVAS
        // ==========================================
        return try {
            // CRITICAL MEMORY FIX: RGB_565 uses 50% less RAM than ARGB_8888
            val resultBitmap = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.RGB_565)
            val canvas = Canvas(resultBitmap)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

            for (i in validFrames.indices) {
                // If we hit our safety limit, stop drawing
                if (offsets[i] >= 16384) break 
                
                val bitmap = loadBitmap(validFrames[i].filePath)
                if (bitmap != null) {
                    canvas.drawBitmap(bitmap, 0f, offsets[i].toFloat(), paint)
                    // CRITICAL MEMORY FIX: Recycle instantly after drawing
                    bitmap.recycle()
                }
            }

            resultBitmap
        } catch (e: OutOfMemoryError) {
            // If the final stitched bitmap STILL crashes it (device has very low RAM)
            null
        }
    }

    private fun areBitmapsIdenticalFast(b1: Bitmap, b2: Bitmap): Boolean {
        if (b1.width != b2.width || b1.height != b2.height) return false

        val random = java.util.Random(42)
        val w = b1.width
        val h = b1.height

        for (i in 0 until 1000) {
            val x = random.nextInt(w)
            val y = random.nextInt(h)
            if (b1.getPixel(x, y) != b2.getPixel(x, y)) {
                return false
            }
        }
        return true
    }

    private fun loadBitmap(path: String): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                // CRITICAL MEMORY FIX: Force RGB_565 (2 bytes per pixel instead of 4)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeFile(path, options)
        } catch (_: Exception) {
            null
        }
    }

    private fun findMicroAlignment(
        prevBitmap: Bitmap,
        currentBitmap: Bitmap,
        expectedScroll: Int,
        windowSize: Int,
        isLastFrame: Boolean
    ): Int {
        val width = prevBitmap.width
        val expectedOverlap = prevBitmap.height - expectedScroll

        if (expectedOverlap < 50 && !isLastFrame) return expectedScroll

        val searchMin: Int
        val searchMax: Int

        if (isLastFrame) {
            searchMin = 1
            searchMax = (prevBitmap.height - 1).coerceAtMost(currentBitmap.height - 1)
        } else {
            searchMin = (expectedOverlap - windowSize).coerceAtLeast(1)
            searchMax = (expectedOverlap + windowSize)
                .coerceAtMost(prevBitmap.height - 1)
                .coerceAtMost(currentBitmap.height - 1)
        }

        val sampleXs = IntArray(10) { i ->
            val rawX = width * (i + 1) / 11
            rawX.coerceIn(0, max(0, width - 1))
        }

        val dominantBgColor = extractDominantBackgroundColor(prevBitmap)

        var bestOverlap = expectedOverlap
        var lowestDiff = Double.MAX_VALUE

        for (testOverlap in searchMin..searchMax) {
            var diff = 0.0
            var activePixels = 0

            for (rowOffset in 0 until 10) {
                val rowPrev = prevBitmap.height - testOverlap + rowOffset
                val rowCurr = rowOffset

                if (rowPrev !in 0 until prevBitmap.height) continue
                if (rowCurr !in 0 until currentBitmap.height) continue

                for (x in sampleXs) {
                    val p1 = prevBitmap.getPixel(x, rowPrev)
                    val p2 = currentBitmap.getPixel(x, rowCurr)

                    val isP1Bg = isColorSimilar(p1, dominantBgColor)
                    val isP2Bg = isColorSimilar(p2, dominantBgColor)

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

    private fun extractDominantBackgroundColor(bitmap: Bitmap): Int {
        val colorCounts = HashMap<Int, Int>()
        val step = (bitmap.height / 20).coerceAtLeast(1)

        val sampleLeftX = 5.coerceAtMost(max(0, bitmap.width - 1))
        val sampleRightX = (bitmap.width - 5).coerceIn(0, max(0, bitmap.width - 1))

        for (y in 0 until bitmap.height step step) {
            val leftColor = bitmap.getPixel(sampleLeftX, y)
            val rightColor = bitmap.getPixel(sampleRightX, y)

            colorCounts[leftColor] = colorCounts.getOrDefault(leftColor, 0) + 1
            colorCounts[rightColor] = colorCounts.getOrDefault(rightColor, 0) + 1
        }

        return colorCounts.maxByOrNull { it.value }?.key ?: Color.WHITE
    }

    private fun isColorSimilar(c1: Int, c2: Int): Boolean {
        val rDiff = Color.red(c1) - Color.red(c2)
        val gDiff = Color.green(c1) - Color.green(c2)
        val bDiff = Color.blue(c1) - Color.blue(c2)

        val squaredDistance = rDiff * rDiff + gDiff * gDiff + bDiff * bDiff
        return squaredDistance < 900
    }
}

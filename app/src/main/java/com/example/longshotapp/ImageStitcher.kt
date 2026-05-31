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

    private data class LoadedFrame(
        val frame: CaptureFrame,
        val bitmap: Bitmap
    )

    fun stitchExact(frames: List<CaptureFrame>, windowSize: Int): Bitmap? {
        if (frames.isEmpty()) return null

        val loadedFrames = ArrayList<LoadedFrame>(frames.size)

        try {
            // 1. Load all frames into memory
            for (frame in frames) {
                val bitmap = loadBitmap(frame.filePath) ?: return null
                loadedFrames.add(LoadedFrame(frame, bitmap))
            }

            // 2. Prune identical dead frames from the end (Failsafe for reaching the bottom)
            pruneDeadEndFrames(loadedFrames)

            // If pruning removed everything except one frame, just return it
            if (loadedFrames.size == 1) {
                return loadedFrames.first().bitmap
            }

            // 3. Calculate Stitching Offsets
            val width = loadedFrames.first().bitmap.width
            val offsets = IntArray(loadedFrames.size)
            offsets[0] = 0

            var currentYOffset = 0
            var totalHeight = loadedFrames.first().bitmap.height

            for (i in 1 until loadedFrames.size) {
                val prevBitmap = loadedFrames[i - 1].bitmap
                val currentBitmap = loadedFrames[i].bitmap

                val expectedScroll = loadedFrames[i].frame.scrollDistance
                
                // NEW: Check if this is the very last frame in the sequence
                val isLastFrame = (i == loadedFrames.size - 1)

                val adjustedScroll = findMicroAlignment(
                    prevBitmap = prevBitmap,
                    currentBitmap = currentBitmap,
                    expectedScroll = expectedScroll,
                    windowSize = windowSize,
                    isLastFrame = isLastFrame
                ).coerceAtLeast(1)

                currentYOffset += adjustedScroll
                offsets[i] = currentYOffset
                totalHeight = currentYOffset + currentBitmap.height
            }

            if (width <= 0 || totalHeight <= 0) return null

            // 4. Draw the final image
            val resultBitmap = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(resultBitmap)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

            for (i in loadedFrames.indices) {
                val bitmap = loadedFrames[i].bitmap
                canvas.drawBitmap(bitmap, 0f, offsets[i].toFloat(), paint)
            }

            return resultBitmap
        } catch (_: Exception) {
            return null
        } finally {
            recycleAll(loadedFrames)
        }
    }

    /**
     * Cascades backwards from the end of the list.
     * If the last two frames are identical, it discards the last one and repeats.
     */
    private fun pruneDeadEndFrames(loadedFrames: ArrayList<LoadedFrame>) {
        while (loadedFrames.size >= 2) {
            val lastFrame = loadedFrames[loadedFrames.size - 1]
            val secondLastFrame = loadedFrames[loadedFrames.size - 2]

            if (areBitmapsIdenticalFast(secondLastFrame.bitmap, lastFrame.bitmap)) {
                // The images are identical (scroll hit the bottom). 
                // Recycle the duplicate bitmap to free RAM, then remove it from the list.
                lastFrame.bitmap.recycle()
                loadedFrames.removeAt(loadedFrames.size - 1)
            } else {
                // We found two distinct images! The cascading check is complete.
                break
            }
        }
    }

    /**
     * Samples 1000 fixed-random pixels. Extremely fast because it exits 
     * on the very first mismatched pixel it finds.
     */
    private fun areBitmapsIdenticalFast(b1: Bitmap, b2: Bitmap): Boolean {
        // If dimensions don't match, they obviously aren't identical
        if (b1.width != b2.width || b1.height != b2.height) return false

        // Use a fixed seed (e.g., 42). This ensures that every time this function runs,
        // it checks the exact same 1000 coordinate spread, guaranteeing consistency.
        val random = java.util.Random(42)
        val w = b1.width
        val h = b1.height

        for (i in 0 until 1000) {
            val x = random.nextInt(w)
            val y = random.nextInt(h)

            // The absolute fastest way to check: exit immediately upon first failure
            if (b1.getPixel(x, y) != b2.getPixel(x, y)) {
                return false
            }
        }

        // If it survives 1000 randomized coordinate checks without a single mismatch, 
        // the images are identical.
        return true
    }

    private fun loadBitmap(path: String): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(path, options)
        } catch (_: Exception) {
            null
        }
    }

    private fun recycleAll(frames: List<LoadedFrame>) {
        for (loaded in frames) {
            try {
                if (!loaded.bitmap.isRecycled) {
                    loaded.bitmap.recycle()
                }
            } catch (_: Exception) {
            }
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

        // --- NEW LOGIC: Dynamic Search Bounds ---
        val searchMin: Int
        val searchMax: Int

        if (isLastFrame) {
            // It's the last frame! It might be a partial scroll.
            // Ignore the windowSize and search the ENTIRE overlapping height.
            searchMin = 1
            searchMax = (prevBitmap.height - 1).coerceAtMost(currentBitmap.height - 1)
        } else {
            // Normal frame. It scrolled fully, so keep it fast and search only the tiny window.
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

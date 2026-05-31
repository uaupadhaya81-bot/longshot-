package com.example.longshotapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
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
        
        // We need the full dimensions of the first image to start
        val firstOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(frames[0].filePath, firstOptions)
        val width = firstOptions.outWidth
        var totalHeight = firstOptions.outHeight
        var prevHeight = firstOptions.outHeight

        try {
            // ==========================================
            // PASS 1: SLICE LOADING (REGION DECODING)
            // ==========================================
            for (i in 1 until frames.size) {
                val prevPath = validFrames.last().filePath
                val currPath = frames[i].filePath

                val isLastFrame = (i == frames.size - 1)
                val expectedScroll = frames[i].scrollDistance
                val expectedOverlap = prevHeight - expectedScroll

                // If it's the last frame, we might need a huge slice. Otherwise, just the window.
                val sliceHeightNeeded = if (isLastFrame) prevHeight else (expectedOverlap + windowSize + 10)

                // 1. Decode ONLY the bottom slice of the previous image
                val decoderPrev = BitmapRegionDecoder.newInstance(prevPath, false) ?: continue
                val rectPrev = Rect(0, max(0, decoderPrev.height - sliceHeightNeeded), width, decoderPrev.height)
                val slicePrev = decoderPrev.decodeRegion(rectPrev, getHardwareOptions())
                decoderPrev.recycle()

                // 2. Decode ONLY the top slice of the current image
                val decoderCurr = BitmapRegionDecoder.newInstance(currPath, false) ?: continue
                val rectCurr = Rect(0, 0, width, min(decoderCurr.height, sliceHeightNeeded))
                val sliceCurr = decoderCurr.decodeRegion(rectCurr, getHardwareOptions())
                val currFullHeight = decoderCurr.height
                decoderCurr.recycle()

                if (slicePrev == null || sliceCurr == null) break

                // Dead-End Pruning using the slices
                if (areBitmapsIdenticalFast(slicePrev, sliceCurr)) {
                    slicePrev.recycle()
                    sliceCurr.recycle()
                    break 
                }

                validFrames.add(frames[i])

                // Do the heavy math on the TINY slices instead of full images
                val adjustedScrollFromSlice = findMicroAlignment(
                    prevBitmap = slicePrev,
                    currentBitmap = sliceCurr,
                    expectedOverlap = expectedOverlap,
                    windowSize = windowSize,
                    isLastFrame = isLastFrame
                )

                // The result is based on the slice, convert it back to the absolute scroll distance
                val absoluteScroll = currFullHeight - adjustedScrollFromSlice

                currentYOffset += absoluteScroll.coerceAtLeast(1)
                offsets.add(currentYOffset)
                
                totalHeight = currentYOffset + currFullHeight
                prevHeight = currFullHeight

                // Clean up the tiny slices instantly
                slicePrev.recycle()
                sliceCurr.recycle()
            }
        } catch (e: Exception) {
            // Failsafe catch
        }

        if (width <= 0 || totalHeight <= 0) return null

        if (totalHeight > 16384) totalHeight = 16384

        // ==========================================
        // PASS 2: STREAM AND DRAW TO CANVAS
        // ==========================================
        return try {
            val resultBitmap = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.RGB_565)
            val canvas = Canvas(resultBitmap)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

            for (i in validFrames.indices) {
                if (offsets[i] >= 16384) break 
                
                val options = getHardwareOptions()
                val bitmap = BitmapFactory.decodeFile(validFrames[i].filePath, options)
                if (bitmap != null) {
                    canvas.drawBitmap(bitmap, 0f, offsets[i].toFloat(), paint)
                    bitmap.recycle()
                }
            }

            resultBitmap
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    private fun getHardwareOptions(): BitmapFactory.Options {
        return BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
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
            if (b1.getPixel(x, y) != b2.getPixel(x, y)) return false
        }
        return true
    }

    private fun findMicroAlignment(
        prevBitmap: Bitmap,
        currentBitmap: Bitmap,
        expectedOverlap: Int,
        windowSize: Int,
        isLastFrame: Boolean
    ): Int {
        val width = prevBitmap.width

        if (expectedOverlap < 50 && !isLastFrame) return expectedOverlap

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

        return bestOverlap
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

        return (rDiff * rDiff + gDiff * gDiff + bDiff * bDiff) < 900
    }
}

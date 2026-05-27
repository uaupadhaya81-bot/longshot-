package com.example.longshotapp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.abs
import kotlin.math.sqrt

object ImageStitcher {

    /**
     * Stitches captured frames together using a precise alignment window provided 
     * dynamically by the user configuration sliders.
     */
    fun stitchExact(frames: List<CaptureFrame>, windowSize: Int): Bitmap? {
        [cite_start]if (frames.isEmpty()) return null[span_0](end_span)
        [span_1](start_span)if (frames.size == 1) return frames.first().bitmap[span_1](end_span)

        [span_2](start_span)val width = frames.first().bitmap.width[span_2](end_span)
        [span_3](start_span)val actualOffsets = mutableListOf<Int>()[span_3](end_span)
        [span_4](start_span)actualOffsets.add(0)[span_4](end_span)

        [span_5](start_span)var currentYOffset = 0[span_5](end_span)
        [span_6](start_span)var totalHeight = frames.first().bitmap.height[span_6](end_span)

        for (i in 1 until frames.size) {
            [span_7](start_span)val prevBitmap = frames[i - 1].bitmap[span_7](end_span)
            [span_8](start_span)val currentBitmap = frames[i].bitmap[span_8](end_span)
            [span_9](start_span)val expectedScroll = frames[i].scrollDistance[span_9](end_span)

            // Find the perfect pixel-level adjustment passing the dynamic window configuration parameter
            val adjustedScroll = findMicroAlignment(prevBitmap, currentBitmap, expectedScroll, windowSize)
            
            [span_10](start_span)currentYOffset += adjustedScroll[span_10](end_span)
            [span_11](start_span)actualOffsets.add(currentYOffset)[span_11](end_span)
            
            [span_12](start_span)totalHeight = currentYOffset + currentBitmap.height[span_12](end_span)
        }

        [span_13](start_span)val resultBitmap = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)[span_13](end_span)
        [span_14](start_span)val canvas = Canvas(resultBitmap)[span_14](end_span)
        [span_15](start_span)val paint = Paint(Paint.FILTER_BITMAP_FLAG)[span_15](end_span)

        for (i in frames.indices) {
            [span_16](start_span)canvas.drawBitmap(frames[i].bitmap, 0f, actualOffsets[i].toFloat(), paint)[span_16](end_span)
        }

        [span_17](start_span)return resultBitmap[span_17](end_span)
    }

    /**
     * Scans a dynamic user-defined pixel window around the expected mathematical join point.
     * [span_18](start_span)Uses dynamic color frequency to ignore the background, supporting Dark Mode perfectly.[span_18](end_span)
     */
    private fun findMicroAlignment(prevBitmap: Bitmap, currentBitmap: Bitmap, expectedScroll: Int, windowSize: Int): Int {
        [span_19](start_span)val width = prevBitmap.width[span_19](end_span)
        [span_20](start_span)val expectedOverlap = prevBitmap.height - expectedScroll[span_20](end_span)
        
        [span_21](start_span)if (expectedOverlap < 50) return expectedScroll[span_21](end_span)

        // --- DYNAMIC SEARCH LIMIT MODIFICATION ---
        // Swapped out the static 20px limitation for the slider threshold parameter
        val searchMin = (expectedOverlap - windowSize).coerceAtLeast(1)
        val searchMax = (expectedOverlap + windowSize).coerceAtMost(prevBitmap.height - 1).coerceAtMost(currentBitmap.height - 1)

        [span_22](start_span)val sampleXs = IntArray(10) { i -> width * (i + 1) / 11 }[span_22](end_span)
        
        [span_23](start_span)// Find the most frequent background color dynamically[span_23](end_span)
        [span_24](start_span)val dominantBgColor = extractDominantBackgroundColor(prevBitmap)[span_24](end_span)

        [span_25](start_span)var bestOverlap = expectedOverlap[span_25](end_span)
        [span_26](start_span)var lowestDiff = Double.MAX_VALUE[span_26](end_span)

        // Scan the custom window boundaries
        for (testOverlap in searchMin..searchMax) {
            [span_27](start_span)var diff = 0.0[span_27](end_span)
            [span_28](start_span)var activePixels = 0[span_28](end_span)
            
            for (rowOffset in 0 until 10) {
                [span_29](start_span)val rowPrev = prevBitmap.height - testOverlap + rowOffset[span_29](end_span)
                [span_30](start_span)val rowCurr = rowOffset[span_30](end_span)
                
                [span_31](start_span)if (rowPrev >= prevBitmap.height || rowCurr >= currentBitmap.height) continue[span_31](end_span)

                for (x in sampleXs) {
                    [span_32](start_span)val p1 = prevBitmap.getPixel(x, rowPrev)[span_32](end_span)
                    [span_33](start_span)val p2 = currentBitmap.getPixel(x, rowCurr)[span_33](end_span)

                    [span_34](start_span)// Check if pixels fall inside the "Color Cube" of the background color[span_34](end_span)
                    [span_35](start_span)val isP1Bg = isColorSimilar(p1, dominantBgColor)[span_35](end_span)
                    [span_36](start_span)val isP2Bg = isColorSimilar(p2, dominantBgColor)[span_36](end_span)

                    [span_37](start_span)// If BOTH pixels are the background color, ignore them.[span_37](end_span)
                    [span_38](start_span)// Only calculate difference if we hit text/images.[span_38](end_span)
                    if (!isP1Bg || !isP2Bg) {
                        [span_39](start_span)diff += abs(Color.red(p1) - Color.red(p2))[span_39](end_span)
                        [span_40](start_span)diff += abs(Color.green(p1) - Color.green(p2))[span_40](end_span)
                        [span_41](start_span)diff += abs(Color.blue(p1) - Color.blue(p2))[span_41](end_span)
                        [span_42](start_span)activePixels++[span_42](end_span)
                    }
                }
            }

            [span_43](start_span)val finalScore = if (activePixels > 0) diff / activePixels else Double.MAX_VALUE[span_43](end_span)

            if (finalScore < lowestDiff) {
                [span_44](start_span)lowestDiff = finalScore[span_44](end_span)
                [span_45](start_span)bestOverlap = testOverlap[span_45](end_span)
            }
        }

        [span_46](start_span)return prevBitmap.height - bestOverlap[span_46](end_span)
    }

    /**
     * [span_47](start_span)Finds the most frequent color by sampling the left and right margins.[span_47](end_span)
     * [span_48](start_span)This naturally identifies the background color, whether it's Dark Mode, Light Mode, or Sepia.[span_48](end_span)
     */
    private fun extractDominantBackgroundColor(bitmap: Bitmap): Int {
        [span_49](start_span)val colorCounts = HashMap<Int, Int>()[span_49](end_span)
        [span_50](start_span)val step = (bitmap.height / 20).coerceAtLeast(1)[span_50](end_span)
        
        [span_51](start_span)// Sample down the left and right margins[span_51](end_span)
        for (y in 0 until bitmap.height step step) {
            [span_52](start_span)val leftColor = bitmap.getPixel(5, y)[span_52](end_span)
            [span_53](start_span)val rightColor = bitmap.getPixel(bitmap.width - 5, y)[span_53](end_span)
            
            [span_54](start_span)colorCounts[leftColor] = colorCounts.getOrDefault(leftColor, 0) + 1[span_54](end_span)
            [span_55](start_span)colorCounts[rightColor] = colorCounts.getOrDefault(rightColor, 0) + 1[span_55](end_span)
        }
        
        [span_56](start_span)// Return the color that appeared the most[span_56](end_span)
        [span_57](start_span)return colorCounts.maxByOrNull { it.value }?.key ?: Color.WHITE[span_57](end_span)
    }

    /**
     * [span_58](start_span)Calculates the Euclidean distance between two colors in the RGB "Color Cube".[span_58](end_span)
     * [span_59](start_span)If the distance is less than 30, it is considered the same shade.[span_59](end_span)
     */
    private fun isColorSimilar(c1: Int, c2: Int): Boolean {
        [span_60](start_span)val rDiff = Color.red(c1) - Color.red(c2)[span_60](end_span)
        [span_61](start_span)val gDiff = Color.green(c1) - Color.green(c2)[span_61](end_span)
        [span_62](start_span)val bDiff = Color.blue(c1) - Color.blue(c2)[span_62](end_span)
        
        [span_63](start_span)// 3D Distance formula[span_63](end_span)
        [span_64](start_span)val distance = sqrt((rDiff * rDiff + gDiff * gDiff + bDiff * bDiff).toDouble())[span_64](end_span)
        
        [span_65](start_span)// A threshold of 30 allows for slight gradients or JPEG compression artifacts[span_65](end_span)
        [span_66](start_span)return distance < 30.0[span_66](end_span)
    }
}

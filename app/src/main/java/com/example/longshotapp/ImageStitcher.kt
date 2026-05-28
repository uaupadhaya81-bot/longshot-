package com.example.longshotapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object ImageStitcher {

    fun stitchExact(
        frames: List<CaptureFrame>,
        normalWindowSize: Int
    ): Bitmap? {
        return stitchExact(frames, normalWindowSize, normalWindowSize)
    }

    fun stitchExact(
        frames: List<CaptureFrame>,
        normalWindowSize: Int,
        lastFrameWindowSize: Int
    ): Bitmap? {
        if (frames.isEmpty()) return null

        val loaded = mutableListOf<LoadedFrame>()
        try {
            for (frame in frames) {
                val bitmap = decodeBitmap(frame.filePath) ?: continue
                loaded.add(LoadedFrame(frame, bitmap))
            }

            if (loaded.isEmpty()) return null
            if (loaded.size == 1) return loaded.first().bitmap

            val overlaps = IntArray(loaded.size)
            overlaps[0] = 0

            var totalHeight = loaded.first().bitmap.height

            for (i in 1 until loaded.size) {
                val prev = loaded[i - 1]
                val curr = loaded[i]

                val expectedOverlap = estimateExpectedOverlap(
                    prev.bitmap.height,
                    curr.frame.scrollDistance,
                    curr.bitmap.height
                )

                val searchWindow = if (i == loaded.lastIndex) {
                    lastFrameWindowSize
                } else {
                    normalWindowSize
                }

                val bestOverlap = findBestOverlap(
                    prev.bitmap,
                    curr.bitmap,
                    expectedOverlap,
                    searchWindow
                )

                overlaps[i] = bestOverlap
                totalHeight += (curr.bitmap.height - bestOverlap)
            }

            val width = loaded.minOf { it.bitmap.width }
            if (width <= 0 || totalHeight <= 0) return null

            val stitched = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(stitched)

            var yOffset = 0

            for (i in loaded.indices) {
                val bitmap = loaded[i].bitmap
                val cropTop = overlaps[i].coerceIn(0, bitmap.height - 1)

                val pieceHeight = bitmap.height - cropTop
                if (pieceHeight <= 0) continue

                val cropped = if (cropTop == 0 && width == bitmap.width) {
                    bitmap
                } else {
                    Bitmap.createBitmap(bitmap, 0, cropTop, width, pieceHeight)
                }

                canvas.drawBitmap(cropped, 0f, yOffset.toFloat(), null)
                yOffset += cropped.height

                if (cropped !== bitmap && !cropped.isRecycled) {
                    cropped.recycle()
                }
            }

            return stitched
        } catch (_: Exception) {
            return null
        } finally {
            for (item in loaded) {
                try {
                    if (!item.bitmap.isRecycled) {
                        item.bitmap.recycle()
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    private data class LoadedFrame(
        val frame: CaptureFrame,
        val bitmap: Bitmap
    )

    private fun decodeBitmap(path: String): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(File(path).absolutePath, options)
        } catch (_: Exception) {
            null
        }
    }

    private fun estimateExpectedOverlap(
        prevHeight: Int,
        scrollDistance: Int,
        currHeight: Int
    ): Int {
        val expected = prevHeight - scrollDistance
        return expected.coerceIn(0, min(prevHeight, currHeight))
    }

    private fun findBestOverlap(
        prev: Bitmap,
        curr: Bitmap,
        expectedOverlap: Int,
        windowSize: Int
    ): Int {
        val maxPossible = min(prev.height, curr.height)
        if (maxPossible <= 0) return 0

        val searchStart = max(0, expectedOverlap - windowSize)
        val searchEnd = min(maxPossible, expectedOverlap + windowSize)

        var bestOverlap = expectedOverlap.coerceIn(0, maxPossible)
        var bestScore = Int.MAX_VALUE

        for (candidate in searchStart..searchEnd) {
            val score = scoreOverlap(prev, curr, candidate)
            if (score < bestScore) {
                bestScore = score
                bestOverlap = candidate
            }
        }

        return bestOverlap.coerceIn(0, maxPossible)
    }

    private fun scoreOverlap(prev: Bitmap, curr: Bitmap, overlap: Int): Int {
        if (overlap <= 0) return 0

        val sampleXs = intArrayOf(
            (prev.width * 1) / 10,
            (prev.width * 3) / 10,
            prev.width / 2,
            (prev.width * 7) / 10,
            (prev.width * 9) / 10
        ).map { it.coerceIn(0, prev.width - 1) }

        val sampleYs = intArrayOf(
            0,
            overlap / 3,
            (overlap * 2) / 3,
            overlap - 1
        ).map { it.coerceIn(0, overlap - 1) }

        val dominantColor = estimateDominantColorForBand(prev, prev.height - overlap, prev.height)

        var total = 0
        var count = 0

        for (y in sampleYs) {
            val prevY = (prev.height - overlap + y).coerceIn(0, prev.height - 1)
            val currY = y.coerceIn(0, curr.height - 1)

            for (x in sampleXs) {
                val p1 = prev.getPixel(x, prevY)
                val p2 = curr.getPixel(x, currY)

                if (isNearDominantColor(p1, dominantColor) && isNearDominantColor(p2, dominantColor)) {
                    continue
                }

                total += colorDistance(p1, p2)
                count++
            }
        }

        return if (count == 0) {
            Int.MAX_VALUE
        } else {
            total / count
        }
    }

    private fun estimateDominantColorForBand(bitmap: Bitmap, startY: Int, endY: Int): Int {
        val counts = HashMap<Int, Int>()
        val leftX = 5.coerceAtMost(max(0, bitmap.width - 1))
        val rightX = (bitmap.width - 5).coerceIn(0, max(0, bitmap.width - 1))
        val midX = (bitmap.width / 2).coerceIn(0, max(0, bitmap.width - 1))

        val step = max(1, (endY - startY) / 18)

        for (y in startY until endY step step) {
            val yy = y.coerceIn(0, bitmap.height - 1)
            val colors = intArrayOf(
                bitmap.getPixel(leftX, yy),
                bitmap.getPixel(midX, yy),
                bitmap.getPixel(rightX, yy)
            )

            for (c in colors) {
                counts[c] = counts.getOrDefault(c, 0) + 1
            }
        }

        return counts.maxByOrNull { it.value }?.key ?: Color.WHITE
    }

    private fun isNearDominantColor(color: Int, dominant: Int): Boolean {
        val dr = Color.red(color) - Color.red(dominant)
        val dg = Color.green(color) - Color.green(dominant)
        val db = Color.blue(color) - Color.blue(dominant)

        val dist = dr * dr + dg * dg + db * db
        return dist < 900
    }

    private fun colorDistance(c1: Int, c2: Int): Int {
        val dr = abs(Color.red(c1) - Color.red(c2))
        val dg = abs(Color.green(c1) - Color.green(c2))
        val db = abs(Color.blue(c1) - Color.blue(c2))
        return dr + dg + db
    }
}

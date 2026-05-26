package com.example.longshotapp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.abs

object ImageStitcher {

    private const val MAX_STATIC_MARGIN_FRACTION = 0.28f
    private const val ROW_SAMPLE_STEP = 24
    private const val COLOR_TOLERANCE = 18

    private data class CropBounds(
        val top: Int,
        val bottom: Int
    )

    fun stitch(bitmaps: List<Bitmap>): Bitmap? {
        if (bitmaps.isEmpty()) return null

        val normalized = bitmaps.map { bmp ->
            if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false)
        }

        val cropBounds = estimateStaticMargins(normalized)

        val croppedBitmaps = normalized.mapNotNull { bmp ->
            val top = cropBounds.top.coerceAtLeast(0)
            val bottom = cropBounds.bottom.coerceAtLeast(0)
            val safeHeight = bmp.height - top - bottom

            if (safeHeight <= 0) null
            else Bitmap.createBitmap(bmp, 0, top, bmp.width, safeHeight)
        }

        if (croppedBitmaps.isEmpty()) return null
        if (croppedBitmaps.size == 1) return croppedBitmaps.first()

        var baseBitmap = croppedBitmaps.first()

        for (i in 1 until croppedBitmaps.size) {
            val nextBitmap = croppedBitmaps[i]
            val overlapRows = findVerticalOverlap(baseBitmap, nextBitmap)

            baseBitmap = if (overlapRows > 0 && overlapRows < nextBitmap.height) {
                combineWithOverlap(baseBitmap, nextBitmap, overlapRows)
            } else {
                combineWithOverlap(baseBitmap, nextBitmap, 0)
            }
        }

        return baseBitmap
    }

    private fun estimateStaticMargins(bitmaps: List<Bitmap>): CropBounds {
        val first = bitmaps.first()
        val width = first.width
        val height = first.height

        val maxTop = (height * MAX_STATIC_MARGIN_FRACTION).toInt()
        val maxBottom = (height * MAX_STATIC_MARGIN_FRACTION).toInt()

        var top = 0
        while (top < maxTop && top < height && isStableRow(bitmaps, top, width)) {
            top++
        }

        var bottom = 0
        while (bottom < maxBottom && bottom < height - top && isStableRow(bitmaps, height - 1 - bottom, width)) {
            bottom++
        }

        return CropBounds(top = top, bottom = bottom)
    }

    private fun isStableRow(bitmaps: List<Bitmap>, row: Int, width: Int): Boolean {
        if (bitmaps.size < 2) return false
        if (row !in 0 until bitmaps.first().height) return false

        val reference = bitmaps.first()
        val sampleCols = sampleColumns(width)

        for (i in 1 until bitmaps.size) {
            val other = bitmaps[i]
            for (x in sampleCols) {
                if (!colorsClose(reference.getPixel(x, row), other.getPixel(x, row))) {
                    return false
                }
            }
        }
        return true
    }

    private fun sampleColumns(width: Int): IntArray {
        val cols = ArrayList<Int>()
        var x = 0
        while (x < width) {
            cols.add(x)
            x += ROW_SAMPLE_STEP
        }
        if (width > 0 && cols.lastOrNull() != width - 1) {
            cols.add(width - 1)
        }
        return cols.toIntArray()
    }

    private fun colorsClose(a: Int, b: Int): Boolean {
        return abs(Color.red(a) - Color.red(b)) <= COLOR_TOLERANCE &&
                abs(Color.green(a) - Color.green(b)) <= COLOR_TOLERANCE &&
                abs(Color.blue(a) - Color.blue(b)) <= COLOR_TOLERANCE &&
                abs(Color.alpha(a) - Color.alpha(b)) <= COLOR_TOLERANCE
    }

    private fun combineWithOverlap(
        baseBitmap: Bitmap,
        nextBitmap: Bitmap,
        overlapRows: Int
    ): Bitmap {
        val uniqueHeight = nextBitmap.height - overlapRows
        val newHeight = baseBitmap.height + uniqueHeight
        val combinedBitmap = Bitmap.createBitmap(baseBitmap.width, newHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(combinedBitmap)

        canvas.drawBitmap(baseBitmap, 0f, 0f, null)

        if (overlapRows > 0) {
            val srcRect = Rect(0, overlapRows, nextBitmap.width, nextBitmap.height)
            val destRect = Rect(0, baseBitmap.height, baseBitmap.width, newHeight)
            canvas.drawBitmap(nextBitmap, srcRect, destRect, null)
        } else {
            canvas.drawBitmap(nextBitmap, 0f, baseBitmap.height.toFloat(), null)
        }

        return combinedBitmap
    }

    private fun findVerticalOverlap(bitmapA: Bitmap, bitmapB: Bitmap): Int {
        val width = minOf(bitmapA.width, bitmapB.width)
        val heightA = bitmapA.height
        val heightB = bitmapB.height

        val maxScanHeight = minOf(heightA, heightB) / 2
        val samplePoints = (0 until width step 20).toList()
        val colorTolerance = 15

        for (overlap in maxScanHeight downTo 20) {
            var match = true

            for (rowOffset in 0 until overlap step 2) {
                val rowA = heightA - overlap + rowOffset
                val rowB = rowOffset

                for (col in samplePoints) {
                    val pixelA = bitmapA.getPixel(col, rowA)
                    val pixelB = bitmapB.getPixel(col, rowB)

                    val rDiff = abs(Color.red(pixelA) - Color.red(pixelB))
                    val gDiff = abs(Color.green(pixelA) - Color.green(pixelB))
                    val bDiff = abs(Color.blue(pixelA) - Color.blue(pixelB))

                    if (rDiff > colorTolerance || gDiff > colorTolerance || bDiff > colorTolerance) {
                        match = false
                        break
                    }
                }

                if (!match) break
            }

            if (match) return overlap
        }

        return 0
    }
}

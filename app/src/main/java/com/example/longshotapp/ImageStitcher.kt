package com.example.longshotapp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint

object ImageStitcher {

    /**
     * Deterministic Stitching:
     * Instead of guessing the overlap by comparing pixels, we use the exact physical 
     * distance the screen scrolled. We simply stack the images exactly where they belong.
     */
    fun stitchExact(frames: List<CaptureFrame>): Bitmap? {
        if (frames.isEmpty()) return null
        if (frames.size == 1) return frames.first().bitmap

        val width = frames.first().bitmap.width
        
        // 1. Calculate the total height of the final longshot.
        // The first image contributes its full height. 
        // Every image after that only adds new height equal to the distance it scrolled.
        var totalHeight = frames.first().bitmap.height
        for (i in 1 until frames.size) {
            totalHeight += frames[i].scrollDistance
        }

        // 2. Create the massive blank canvas
        val resultBitmap = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        var currentYOffset = 0f

        // 3. Draw each frame onto the canvas
        for (i in frames.indices) {
            val frame = frames[i]
            
            if (i > 0) {
                // Shift our drawing point down by the exact amount the screen scrolled
                currentYOffset += frame.scrollDistance
            }

            // Draw the frame. The overlapping top portion of this frame will perfectly 
            // overwrite the bottom portion of the previous frame because they are the exact same pixels!
            canvas.drawBitmap(frame.bitmap, 0f, currentYOffset, paint)
        }

        return resultBitmap
    }
}

package com.chestcount.app

import android.media.Image
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Experimental visual heuristic. It looks for a stable gold-coloured chest-like
 * region, then a burst of local visual change followed by settling.
 *
 * This is not a trained Fortnite chest model; gameplay effects can cause false
 * positives or missed openings. The thresholds will need tuning against real play.
 */
internal class ChestOpeningDetector {
    private val sampleWidth = 160
    private val sampleHeight = 90
    private var previousGray = IntArray(sampleWidth * sampleHeight)
    private var hasPrevious = false
    private var stableFrames = 0
    private var motionFrames = 0
    private var settledFrames = 0
    private var absentFrames = 0
    private var latched = false
    private var previousCenterX = -1
    private var previousCenterY = -1

    fun process(image: Image): Boolean {
        val plane = image.planes.firstOrNull() ?: return false
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.rewind()
        buffer.get(bytes)

        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val gray = IntArray(sampleWidth * sampleHeight)
        var minX = sampleWidth
        var minY = sampleHeight
        var maxX = -1
        var maxY = -1
        var goldPixels = 0

        for (sy in 0 until sampleHeight) {
            val y = (sy * image.height / sampleHeight).coerceIn(0, image.height - 1)
            for (sx in 0 until sampleWidth) {
                val x = (sx * image.width / sampleWidth).coerceIn(0, image.width - 1)
                val offset = y * rowStride + x * pixelStride
                if (offset + 2 >= bytes.size) continue
                val r = bytes[offset].toInt() and 0xff
                val g = bytes[offset + 1].toInt() and 0xff
                val b = bytes[offset + 2].toInt() and 0xff
                val index = sy * sampleWidth + sx
                gray[index] = (r * 30 + g * 59 + b * 11) / 100

                // Ignore most HUD areas; focus on the central gameplay view.
                if (sx in 24..140 && sy in 12..82 &&
                    r > 145 && g > 72 && r > g * 1.15 &&
                    r > b * 1.35 && b < 135
                ) {
                    goldPixels++
                    minX = min(minX, sx)
                    minY = min(minY, sy)
                    maxX = max(maxX, sx)
                    maxY = max(maxY, sy)
                }
            }
        }

        if (!hasPrevious) {
            previousGray = gray
            hasPrevious = true
            return false
        }

        val validCandidate = goldPixels >= 12 &&
            maxX - minX in 4..65 && maxY - minY in 3..42

        if (!validCandidate) {
            absentFrames++
            if (absentFrames >= 7) {
                latched = false
                stableFrames = 0
                motionFrames = 0
                settledFrames = 0
                previousCenterX = -1
                previousCenterY = -1
            }
            previousGray = gray
            return false
        }

        absentFrames = 0
        val centerX = (minX + maxX) / 2
        val centerY = (minY + maxY) / 2
        val moved = previousCenterX >= 0 &&
            (abs(centerX - previousCenterX) > 9 || abs(centerY - previousCenterY) > 8)
        if (moved) stableFrames = 0 else stableFrames++
        previousCenterX = centerX
        previousCenterY = centerY

        val left = max(0, minX - 5)
        val right = min(sampleWidth - 1, maxX + 5)
        val top = max(0, minY - 5)
        val bottom = min(sampleHeight - 1, maxY + 5)
        var changed = 0
        var compared = 0
        for (y in top..bottom step 2) {
            for (x in left..right step 2) {
                val i = y * sampleWidth + x
                if (abs(gray[i] - previousGray[i]) > 26) changed++
                compared++
            }
        }
        val motionRatio = if (compared == 0) 0f else changed.toFloat() / compared

        var detected = false
        if (!latched && stableFrames >= 3 && motionRatio > 0.18f) {
            motionFrames++
        } else if (!latched && motionFrames > 0 && motionRatio < 0.09f) {
            settledFrames++
        } else if (!latched && motionRatio > 0.12f) {
            settledFrames = 0
        }

        if (!latched && motionFrames >= 1 && settledFrames >= 2) {
            latched = true
            detected = true
        }

        previousGray = gray
        return detected
    }
}

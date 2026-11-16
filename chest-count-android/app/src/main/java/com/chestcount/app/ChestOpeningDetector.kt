package com.chestcount.app

import android.media.Image
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight temporal colour/motion heuristic. It is NOT a trained model and
 * cannot reliably distinguish every chest from other gold/orange game objects.
 */
internal class ChestOpeningDetector {
    private val width = 192
    private val height = 108
    private val size = width * height
    private var previousGray = IntArray(size)
    private var currentGray = IntArray(size)
    private val mask = BooleanArray(size)
    private val closed = BooleanArray(size)
    private val visited = BooleanArray(size)
    private val queue = IntArray(size)
    private var frameBytes = ByteArray(0)
    private var hasPrevious = false

    private var tracked: Box? = null
    private var stableFrames = 0
    private var missingFrames = 0
    private var motionSeen = false
    private var settledFrames = 0
    private var latched = false

    private data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val centerX get() = (left + right) / 2
        val centerY get() = (top + bottom) / 2
        val boxWidth get() = right - left + 1
        val boxHeight get() = bottom - top + 1
        val area get() = boxWidth * boxHeight
    }

    fun reset() {
        hasPrevious = false
        tracked = null
        stableFrames = 0
        missingFrames = 0
        motionSeen = false
        settledFrames = 0
        latched = false
        previousGray.fill(0)
        currentGray.fill(0)
        mask.fill(false)
        closed.fill(false)
        visited.fill(false)
    }

    fun process(image: Image): Boolean {
        val plane = image.planes.firstOrNull() ?: return false
        val buffer = plane.buffer
        buffer.rewind()
        val byteCount = buffer.remaining()
        if (frameBytes.size < byteCount) frameBytes = ByteArray(byteCount)
        buffer.get(frameBytes, 0, byteCount)

        currentGray.fill(0)
        mask.fill(false)
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        for (sy in 0 until height) {
            val sourceY = sy * image.height / height
            for (sx in 0 until width) {
                val sourceX = sx * image.width / width
                val offset = sourceY * rowStride + sourceX * pixelStride
                if (offset < 0 || offset + 2 >= byteCount) continue
                val red = frameBytes[offset].toInt() and 0xff
                val green = frameBytes[offset + 1].toInt() and 0xff
                val blue = frameBytes[offset + 2].toInt() and 0xff
                val index = sy * width + sx
                currentGray[index] = (red * 30 + green * 59 + blue * 11) / 100
                if (sx in 8..183 && sy in 7..100) {
                    mask[index] = red > 145 && green > 78 &&
                        red > green * 1.12 && red > blue * 1.28 && blue < 155
                }
            }
        }

        if (!hasPrevious) {
            swapGrayBuffers()
            hasPrevious = true
            return false
        }

        val candidate = findChestCandidate()
        var event = false
        if (candidate != null) {
            val old = tracked
            val sameObject = old != null && isSameObject(old, candidate)
            if (old == null || !sameObject) {
                if (old != null && stableFrames >= 5 && !latched &&
                    localDifference(old) > 0.14f) {
                    event = true
                    latched = true
                }
                tracked = candidate
                stableFrames = 1
                missingFrames = 0
                motionSeen = false
                settledFrames = 0
            } else {
                tracked = blend(old, candidate)
                stableFrames++
                missingFrames = 0
                val change = localDifference(expand(candidate, 4))
                if (!latched && stableFrames >= 3 && change >= 0.20f) {
                    motionSeen = true
                    settledFrames = 0
                } else if (!latched && motionSeen && change <= 0.10f) {
                    settledFrames++
                } else if (!latched && change > 0.13f) {
                    settledFrames = 0
                }
                if (!latched && motionSeen && settledFrames >= 2) {
                    event = true
                    latched = true
                }
            }
        } else {
            missingFrames++
            val old = tracked
            if (old != null && stableFrames >= 5 && !latched) {
                val disappearanceChange = localDifference(expand(old, 5))
                if (missingFrames >= 2 && disappearanceChange >= 0.13f) {
                    event = true
                    latched = true
                }
            }
            if (missingFrames >= 8) {
                tracked = null
                stableFrames = 0
                motionSeen = false
                settledFrames = 0
                latched = false
            }
        }
        swapGrayBuffers()
        return event
    }

    private fun swapGrayBuffers() {
        val old = previousGray
        previousGray = currentGray
        currentGray = old
    }

    private fun findChestCandidate(): Box? {
        closed.indices.forEach { i -> closed[i] = mask[i] }
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                if (!mask[i] && (
                    (mask[i - 1] && mask[i + 1]) ||
                    (mask[i - width] && mask[i + width]) ||
                    (mask[i - width - 1] && mask[i + width + 1])
                )) closed[i] = true
            }
        }

        visited.fill(false)
        var best: Box? = null
        var bestScore = 0
        for (start in closed.indices) {
            if (!closed[start] || visited[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var left = width
            var top = height
            var right = -1
            var bottom = -1
            var pixels = 0
            while (head < tail) {
                val index = queue[head++]
                val x = index % width
                val y = index / width
                pixels++
                left = min(left, x)
                top = min(top, y)
                right = max(right, x)
                bottom = max(bottom, y)
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 1 until width - 1 || ny !in 1 until height - 1) continue
                    val ni = ny * width + nx
                    if (closed[ni] && !visited[ni]) {
                        visited[ni] = true
                        queue[tail++] = ni
                    }
                }
            }
            val box = Box(left, top, right, bottom)
            val density = pixels.toFloat() / box.area.coerceAtLeast(1)
            if (pixels >= 10 && box.boxWidth in 5..72 && box.boxHeight in 4..54 &&
                box.area <= 2500 && density >= 0.035f) {
                val score = pixels + min(box.area, 600) / 5
                if (score > bestScore) {
                    bestScore = score
                    best = box
                }
            }
        }
        return best
    }

    private fun isSameObject(a: Box, b: Box): Boolean =
        abs(a.centerX - b.centerX) <= max(12, a.boxWidth / 2) &&
            abs(a.centerY - b.centerY) <= max(10, a.boxHeight / 2)

    private fun blend(old: Box, fresh: Box) = Box(
        (old.left * 2 + fresh.left) / 3,
        (old.top * 2 + fresh.top) / 3,
        (old.right * 2 + fresh.right) / 3,
        (old.bottom * 2 + fresh.bottom) / 3
    )

    private fun expand(box: Box, amount: Int) = Box(
        (box.left - amount).coerceAtLeast(0),
        (box.top - amount).coerceAtLeast(0),
        (box.right + amount).coerceAtMost(width - 1),
        (box.bottom + amount).coerceAtMost(height - 1)
    )

    private fun localDifference(box: Box): Float {
        var changed = 0
        var compared = 0
        for (y in box.top..box.bottom step 2) {
            for (x in box.left..box.right step 2) {
                val index = y * width + x
                if (abs(currentGray[index] - previousGray[index]) > 24) changed++
                compared++
            }
        }
        return if (compared == 0) 0f else changed.toFloat() / compared
    }
}

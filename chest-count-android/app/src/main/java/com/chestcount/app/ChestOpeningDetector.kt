package com.chestcount.app

import android.media.Image
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Temporal chest-event detector.
 *
 * It tracks a compact gold/orange object region rather than using one bounding
 * box for every gold pixel on the screen. It can report an opening animation
 * (local motion followed by settling) or a break/destruction (a tracked chest
 * disappears after a short local change).
 *
 * This is still a visual heuristic, not a trained Fortnite model. It must be
 * validated on real gameplay before its counts can be called accurate.
 */
internal class ChestOpeningDetector {
    private val width = 192
    private val height = 108
    private var previousGray = IntArray(width * height)
    private var hasPrevious = false

    private var tracked: Box? = null
    private var stableFrames = 0
    private var missingFrames = 0
    private var motionSeen = false
    private var settledFrames = 0
    private var latched = false
    private var lastLocalChange = 0f

    private data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val centerX get() = (left + right) / 2
        val centerY get() = (top + bottom) / 2
        val boxWidth get() = right - left + 1
        val boxHeight get() = bottom - top + 1
        val area get() = boxWidth * boxHeight
    }

    fun process(image: Image): Boolean {
        val plane = image.planes.firstOrNull() ?: return false
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.rewind()
        buffer.get(bytes)

        val gray = IntArray(width * height)
        val mask = BooleanArray(width * height)
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride

        for (sy in 0 until height) {
            val sourceY = (sy * image.height / height).coerceIn(0, image.height - 1)
            for (sx in 0 until width) {
                val sourceX = (sx * image.width / width).coerceIn(0, image.width - 1)
                val offset = sourceY * rowStride + sourceX * pixelStride
                if (offset + 2 >= bytes.size) continue

                val red = bytes[offset].toInt() and 0xff
                val green = bytes[offset + 1].toInt() and 0xff
                val blue = bytes[offset + 2].toInt() and 0xff
                val index = sy * width + sx
                gray[index] = (red * 30 + green * 59 + blue * 11) / 100

                // Skip the outer HUD bands, where map icons and status effects live.
                if (sx in 8..183 && sy in 7..100) {
                    val isChestGold = red > 145 && green > 78 &&
                        red > green * 1.12 && red > blue * 1.28 && blue < 155
                    mask[index] = isChestGold
                }
            }
        }

        if (!hasPrevious) {
            previousGray = gray
            hasPrevious = true
            return false
        }

        val candidate = findChestCandidate(mask)
        var event = false

        if (candidate != null) {
            val old = tracked
            val sameObject = old != null && isSameObject(old, candidate)

            if (old == null || !sameObject) {
                // A newly visible chest starts a fresh tracking window.
                if (old != null && stableFrames >= 5 && !latched &&
                    localDifference(gray, old) > 0.14f
                ) {
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

                val change = localDifference(gray, expand(candidate, 4))
                lastLocalChange = change

                if (!latched && stableFrames >= 3 && change >= 0.20f) {
                    motionSeen = true
                    settledFrames = 0
                } else if (!latched && motionSeen && change <= 0.10f) {
                    settledFrames++
                } else if (!latched && change > 0.13f) {
                    settledFrames = 0
                }

                // Opening animation: a local burst followed by a settled frame.
                if (!latched && motionSeen && settledFrames >= 2) {
                    event = true
                    latched = true
                }
            }
        } else {
            missingFrames++
            val old = tracked
            if (old != null && stableFrames >= 5 && !latched) {
                val disappearanceChange = localDifference(gray, expand(old, 5))
                if (missingFrames >= 2 && disappearanceChange >= 0.13f) {
                    // Chest was tracked steadily, then changed and disappeared.
                    event = true
                    latched = true
                }
            }

            // Require a clear gap before another chest can be counted.
            if (missingFrames >= 8) {
                tracked = null
                stableFrames = 0
                motionSeen = false
                settledFrames = 0
                latched = false
                lastLocalChange = 0f
            }
        }

        previousGray = gray
        return event
    }

    private fun findChestCandidate(mask: BooleanArray): Box? {
        // Close tiny gaps in the gold outline before connected-component scanning.
        val closed = mask.copyOf()
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                if (!mask[i] && (
                    mask[i - 1] && mask[i + 1] ||
                    mask[i - width] && mask[i + width] ||
                    mask[i - width - 1] && mask[i + width + 1]
                )) closed[i] = true
            }
        }

        val visited = BooleanArray(width * height)
        val queue = IntArray(width * height)
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

                for (dy in -1..1) {
                    for (dx in -1..1) {
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
            }

            val box = Box(left, top, right, bottom)
            val density = pixels.toFloat() / box.area.coerceAtLeast(1)
            if (pixels >= 10 && box.boxWidth in 5..72 && box.boxHeight in 4..54 &&
                box.area <= 2500 && density >= 0.035f
            ) {
                val score = pixels + min(box.area, 600) / 5
                if (score > bestScore) {
                    bestScore = score
                    best = box
                }
            }
        }
        return best
    }

    private fun isSameObject(a: Box, b: Box): Boolean {
        val dx = abs(a.centerX - b.centerX)
        val dy = abs(a.centerY - b.centerY)
        return dx <= max(12, a.boxWidth / 2) && dy <= max(10, a.boxHeight / 2)
    }

    private fun blend(old: Box, fresh: Box): Box = Box(
        (old.left * 2 + fresh.left) / 3,
        (old.top * 2 + fresh.top) / 3,
        (old.right * 2 + fresh.right) / 3,
        (old.bottom * 2 + fresh.bottom) / 3
    )

    private fun expand(box: Box, amount: Int): Box = Box(
        (box.left - amount).coerceAtLeast(0),
        (box.top - amount).coerceAtLeast(0),
        (box.right + amount).coerceAtMost(width - 1),
        (box.bottom + amount).coerceAtMost(height - 1)
    )

    private fun localDifference(current: IntArray, box: Box): Float {
        var changed = 0
        var compared = 0
        for (y in box.top..box.bottom step 2) {
            for (x in box.left..box.right step 2) {
                val index = y * width + x
                if (abs(current[index] - previousGray[index]) > 24) changed++
                compared++
            }
        }
        return if (compared == 0) 0f else changed.toFloat() / compared
    }
}

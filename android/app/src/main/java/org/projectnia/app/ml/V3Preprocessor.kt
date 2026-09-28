package org.projectnia.app.ml

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Exact Android port of the frozen Kaggle Feature V3 transform.
 *
 * Output layout per frame:
 *  0..141   body-relative XY (left hand, right hand, pose, lips)
 *  142..267 wrist/palm-normalized hand-local XYZ
 *  268..271 left/right/pose/lips presence
 */
class V3Preprocessor {
    companion object {
        const val SEQUENCE_LENGTH = 64
        const val FEATURE_COUNT = 272

        val POSE_INDICES = intArrayOf(0, 11, 12, 13, 14, 15, 16, 23, 24)
        val LIP_INDICES = intArrayOf(
            61, 185, 40, 39, 37, 0, 267, 269, 270, 409, 291,
            146, 91, 181, 84, 17, 314, 405, 321, 375,
        )

        private val POSE_SWAP = mapOf(1 to 2, 2 to 1, 3 to 4, 4 to 3, 5 to 6, 6 to 5, 7 to 8, 8 to 7)
        private val LIP_SWAP = mapOf(
            0 to 10, 10 to 0, 1 to 9, 9 to 1, 2 to 8, 8 to 2,
            3 to 7, 7 to 3, 4 to 6, 6 to 4, 5 to 5,
            11 to 19, 19 to 11, 12 to 18, 18 to 12,
            13 to 17, 17 to 13, 14 to 16, 16 to 14, 15 to 15,
        )
    }

    fun process(rawFrames: List<LandmarkFrame>): Array<FloatArray> {
        require(rawFrames.isNotEmpty()) { "At least one landmark frame is required" }

        val leftXyz = extract(rawFrames, { it.leftHand }, 21, 3)
        val rightXyz = extract(rawFrames, { it.rightHand }, 21, 3)
        val leftXy = extract(rawFrames, { it.leftHand }, 21, 2)
        val rightXy = extract(rawFrames, { it.rightHand }, 21, 2)
        val poseXy = extractSelected(rawFrames, { it.pose }, POSE_INDICES, 2)
        val lipsXy = extractSelected(rawFrames, { it.face }, LIP_INDICES, 2)

        var leftPresence = presence(leftXyz, 3)
        var rightPresence = presence(rightXyz, 3)
        var posePresence = presence(poseXy, 2)
        var lipsPresence = presence(lipsXy, 2)

        val crop = cropBounds(leftPresence, rightPresence)
        val left = slice(leftXyz, crop.first, crop.second)
        val right = slice(rightXyz, crop.first, crop.second)
        val leftBody = slice(leftXy, crop.first, crop.second)
        val rightBody = slice(rightXy, crop.first, crop.second)
        val pose = slice(poseXy, crop.first, crop.second)
        val lips = slice(lipsXy, crop.first, crop.second)
        leftPresence = leftPresence.copyOfRange(crop.first, crop.second)
        rightPresence = rightPresence.copyOfRange(crop.first, crop.second)
        posePresence = posePresence.copyOfRange(crop.first, crop.second)
        lipsPresence = lipsPresence.copyOfRange(crop.first, crop.second)

        val normalized = normalizeBodyXy(leftBody, rightBody, pose, lips)
        var body = concatenateBody(normalized)
        var localLeft = handLocal3d(left)
        var localRight = handLocal3d(right)

        body = resampleLinear(fillTemporalMissing(body), SEQUENCE_LENGTH)
        localLeft = resampleLinear(fillTemporalMissing(localLeft), SEQUENCE_LENGTH)
        localRight = resampleLinear(fillTemporalMissing(localRight), SEQUENCE_LENGTH)

        var masks = Array(SEQUENCE_LENGTH) { FloatArray(4) }
        val sourceMasks = arrayOf(leftPresence, rightPresence, posePresence, lipsPresence)
        for (m in sourceMasks.indices) {
            val sampled = resampleMask(sourceMasks[m], SEQUENCE_LENGTH)
            for (t in 0 until SEQUENCE_LENGTH) masks[t][m] = sampled[t]
        }

        maskBody(body, masks)
        maskLocal(localLeft, masks, 0)
        maskLocal(localRight, masks, 1)

        val canonical = canonicalize(body, localLeft, localRight, masks)
        body = canonical.body
        localLeft = canonical.localLeft
        localRight = canonical.localRight
        masks = canonical.presence

        return Array(SEQUENCE_LENGTH) { t ->
            FloatArray(FEATURE_COUNT).also { row ->
                body[t].copyInto(row, 0)
                localLeft[t].copyInto(row, 142)
                localRight[t].copyInto(row, 205)
                masks[t].copyInto(row, 268)
                require(row.all { it.isFinite() }) { "V3 produced a non-finite feature" }
            }
        }
    }

    private fun extract(
        frames: List<LandmarkFrame>,
        selector: (LandmarkFrame) -> List<Point3?>,
        count: Int,
        dimensions: Int,
    ): Array<FloatArray> = Array(frames.size) { t ->
        FloatArray(count * dimensions) { Float.NaN }.also { out ->
            val points = selector(frames[t])
            for (i in 0 until count) writePoint(out, i * dimensions, points.getOrNull(i), dimensions)
        }
    }

    private fun extractSelected(
        frames: List<LandmarkFrame>,
        selector: (LandmarkFrame) -> List<Point3?>,
        indices: IntArray,
        dimensions: Int,
    ): Array<FloatArray> = Array(frames.size) { t ->
        FloatArray(indices.size * dimensions) { Float.NaN }.also { out ->
            val points = selector(frames[t])
            indices.forEachIndexed { i, source ->
                writePoint(out, i * dimensions, points.getOrNull(source), dimensions)
            }
        }
    }

    private fun writePoint(out: FloatArray, offset: Int, point: Point3?, dimensions: Int) {
        if (point == null) return
        out[offset] = point.x
        out[offset + 1] = point.y
        if (dimensions == 3) out[offset + 2] = point.z
    }

    private fun presence(rows: Array<FloatArray>, dimensions: Int): FloatArray = FloatArray(rows.size) { t ->
        var found = false
        var i = 0
        while (i < rows[t].size) {
            if (rows[t][i].isFinite() || rows[t][i + 1].isFinite()) {
                found = true
                break
            }
            i += dimensions
        }
        if (found) 1f else 0f
    }

    private fun cropBounds(left: FloatArray, right: FloatArray): Pair<Int, Int> {
        val active = left.indices.filter { max(left[it], right[it]) > 0f }
        if (active.isEmpty()) return 0 to left.size
        var start = max(active.first() - 2, 0)
        var end = min(active.last() + 3, left.size)
        if (end - start < 6) {
            val center = (start + end) / 2
            start = max(center - 3, 0)
            end = min(start + 6, left.size)
        }
        return start to end
    }

    private fun slice(rows: Array<FloatArray>, start: Int, end: Int): Array<FloatArray> =
        Array(end - start) { rows[start + it].copyOf() }

    private fun normalizeBodyXy(
        left: Array<FloatArray>,
        right: Array<FloatArray>,
        pose: Array<FloatArray>,
        lips: Array<FloatArray>,
    ): Array<Array<FloatArray>> {
        val shoulderLeftOffset = POSE_INDICES.indexOf(11) * 2
        val shoulderRightOffset = POSE_INDICES.indexOf(12) * 2
        val centerX = FloatArray(pose.size) { Float.NaN }
        val centerY = FloatArray(pose.size) { Float.NaN }
        val scales = FloatArray(pose.size) { Float.NaN }
        for (t in pose.indices) {
            val lx = pose[t][shoulderLeftOffset]
            val ly = pose[t][shoulderLeftOffset + 1]
            val rx = pose[t][shoulderRightOffset]
            val ry = pose[t][shoulderRightOffset + 1]
            if (lx.isFinite() && ly.isFinite() && rx.isFinite() && ry.isFinite()) {
                centerX[t] = (lx + rx) / 2f
                centerY[t] = (ly + ry) / 2f
                val distance = sqrt((lx - rx) * (lx - rx) + (ly - ry) * (ly - ry))
                if (distance > 1e-4f) scales[t] = distance
            }
        }
        val cx = interpolateVector(centerX, 0.5f)
        val cy = interpolateVector(centerY, 0.5f)
        val scale = interpolateVector(scales, 0.30f).map { max(it, 0.05f) }.toFloatArray()

        fun norm(rows: Array<FloatArray>): Array<FloatArray> = Array(rows.size) { t ->
            FloatArray(rows[t].size) { i ->
                val value = rows[t][i]
                if (!value.isFinite()) Float.NaN
                else ((value - if (i % 2 == 0) cx[t] else cy[t]) / scale[t]).coerceIn(-5f, 5f)
            }
        }
        return arrayOf(norm(left), norm(right), norm(pose), norm(lips))
    }

    private fun concatenateBody(parts: Array<Array<FloatArray>>): Array<FloatArray> =
        Array(parts[0].size) { t ->
            FloatArray(142).also { row ->
                var offset = 0
                parts.forEach { part ->
                    part[t].copyInto(row, offset)
                    offset += part[t].size
                }
            }
        }

    private fun handLocal3d(hand: Array<FloatArray>): Array<FloatArray> = Array(hand.size) { t ->
        val out = FloatArray(63) { Float.NaN }
        val wrist = floatArrayOf(hand[t][0], hand[t][1], hand[t][2])
        val distances = listOf(5, 9, 13, 17).mapNotNull { index ->
            val offset = index * 3
            val p = floatArrayOf(hand[t][offset], hand[t][offset + 1], hand[t][offset + 2])
            if ((p + wrist).all { it.isFinite() }) {
                sqrt((p[0] - wrist[0]) * (p[0] - wrist[0]) +
                    (p[1] - wrist[1]) * (p[1] - wrist[1]) +
                    (p[2] - wrist[2]) * (p[2] - wrist[2]))
            } else null
        }
        val rawScale = if (distances.isEmpty()) Float.NaN else distances.average().toFloat()
        val scale = if (rawScale.isFinite() && rawScale > 1e-4f) rawScale else 1f
        for (i in 0 until 21) {
            for (d in 0 until 3) {
                val value = hand[t][i * 3 + d]
                val origin = wrist[d]
                if (value.isFinite() && origin.isFinite()) out[i * 3 + d] = ((value - origin) / scale).coerceIn(-4f, 4f)
            }
        }
        out
    }

    private fun interpolateVector(values: FloatArray, defaultValue: Float): FloatArray {
        val valid = values.indices.filter { values[it].isFinite() }
        if (valid.isEmpty()) return FloatArray(values.size) { defaultValue }
        if (valid.size == 1) return FloatArray(values.size) { values[valid.first()] }
        return FloatArray(values.size) { x ->
            if (x <= valid.first()) values[valid.first()]
            else if (x >= valid.last()) values[valid.last()]
            else {
                val hiPos = valid.indexOfFirst { it >= x }
                val hi = valid[hiPos]
                val lo = valid[hiPos - 1]
                val fraction = (x - lo).toFloat() / (hi - lo).toFloat()
                values[lo] + (values[hi] - values[lo]) * fraction
            }
        }
    }

    private fun fillTemporalMissing(rows: Array<FloatArray>): Array<FloatArray> {
        val out = Array(rows.size) { rows[it].copyOf() }
        for (feature in out[0].indices) {
            val values = FloatArray(out.size) { out[it][feature] }
            val filled = interpolateVector(values, 0f)
            for (t in out.indices) out[t][feature] = filled[t]
        }
        return out
    }

    private fun resampleLinear(rows: Array<FloatArray>, target: Int): Array<FloatArray> {
        if (rows.size == target) return Array(target) { rows[it].copyOf() }
        if (rows.size == 1) return Array(target) { rows[0].copyOf() }
        return Array(target) { targetIndex ->
            val sourcePosition = targetIndex.toDouble() * (rows.size - 1) / (target - 1)
            val lo = sourcePosition.toInt()
            val hi = min(lo + 1, rows.size - 1)
            val fraction = (sourcePosition - lo).toFloat()
            FloatArray(rows[0].size) { feature ->
                rows[lo][feature] + (rows[hi][feature] - rows[lo][feature]) * fraction
            }
        }
    }

    private fun resampleMask(mask: FloatArray, target: Int): FloatArray {
        if (mask.size == target) return mask.copyOf()
        if (mask.size == 1) return FloatArray(target) { mask[0] }
        return FloatArray(target) { i ->
            val position = i.toDouble() * (mask.size - 1) / (target - 1)
            mask[Math.rint(position).toInt().coerceIn(0, mask.lastIndex)]
        }
    }

    private fun maskBody(body: Array<FloatArray>, presence: Array<FloatArray>) {
        val ranges = arrayOf(0 until 42, 42 until 84, 84 until 102, 102 until 142)
        for (t in body.indices) for (m in ranges.indices) for (i in ranges[m]) body[t][i] *= presence[t][m]
    }

    private fun maskLocal(local: Array<FloatArray>, presence: Array<FloatArray>, maskIndex: Int) {
        for (t in local.indices) for (i in local[t].indices) local[t][i] *= presence[t][maskIndex]
    }

    private data class Canonical(
        val body: Array<FloatArray>,
        val localLeft: Array<FloatArray>,
        val localRight: Array<FloatArray>,
        val presence: Array<FloatArray>,
    )

    private fun canonicalize(
        originalBody: Array<FloatArray>,
        originalLeft: Array<FloatArray>,
        originalRight: Array<FloatArray>,
        originalPresence: Array<FloatArray>,
    ): Canonical {
        val leftScore = originalPresence.map { it[0] }.average()
        val rightScore = originalPresence.map { it[1] }.average()
        if (leftScore <= rightScore + 0.05) return Canonical(originalBody, originalLeft, originalRight, originalPresence)

        val body = Array(SEQUENCE_LENGTH) { originalBody[it].copyOf() }
        var left = Array(SEQUENCE_LENGTH) { originalLeft[it].copyOf() }
        var right = Array(SEQUENCE_LENGTH) { originalRight[it].copyOf() }
        val masks = Array(SEQUENCE_LENGTH) { originalPresence[it].copyOf() }

        for (t in 0 until SEQUENCE_LENGTH) {
            val oldLeft = body[t].copyOfRange(0, 42)
            val oldRight = body[t].copyOfRange(42, 84)
            mirrorX(oldLeft, 2)
            mirrorX(oldRight, 2)
            oldRight.copyInto(body[t], 0)
            oldLeft.copyInto(body[t], 42)

            swapMirroredGroup(body[t], 84, 9, 2, POSE_SWAP)
            swapMirroredGroup(body[t], 102, 20, 2, LIP_SWAP)
            val tmp = masks[t][0]
            masks[t][0] = masks[t][1]
            masks[t][1] = tmp
        }
        left.forEach { mirrorX(it, 3) }
        right.forEach { mirrorX(it, 3) }
        val swap = left
        left = right
        right = swap
        return Canonical(body, left, right, masks)
    }

    private fun mirrorX(flat: FloatArray, dimensions: Int) {
        var i = 0
        while (i < flat.size) {
            flat[i] *= -1f
            i += dimensions
        }
    }

    private fun swapMirroredGroup(
        row: FloatArray,
        offset: Int,
        count: Int,
        dimensions: Int,
        swaps: Map<Int, Int>,
    ) {
        val old = row.copyOfRange(offset, offset + count * dimensions)
        mirrorX(old, dimensions)
        val reordered = old.copyOf()
        swaps.forEach { (source, destination) ->
            for (d in 0 until dimensions) reordered[destination * dimensions + d] = old[source * dimensions + d]
        }
        reordered.copyInto(row, offset)
    }
}

package org.projectnia.app.ml

data class Point3(
    val x: Float,
    val y: Float,
    val z: Float = Float.NaN,
)

/** One unprocessed MediaPipe Holistic frame. Missing landmarks are null. */
data class LandmarkFrame(
    val leftHand: List<Point3?> = List(21) { null },
    val rightHand: List<Point3?> = List(21) { null },
    val pose: List<Point3?> = List(33) { null },
    val face: List<Point3?> = List(478) { null },
) {
    init {
        require(leftHand.size >= 21)
        require(rightHand.size >= 21)
        require(pose.size >= 25)
        require(face.size >= 410)
    }
}


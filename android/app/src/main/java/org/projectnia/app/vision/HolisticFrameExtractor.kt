package org.projectnia.app.vision

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.holisticlandmarker.HolisticLandmarker
import com.google.mediapipe.tasks.vision.holisticlandmarker.HolisticLandmarkerResult
import org.projectnia.app.ml.LandmarkFrame
import org.projectnia.app.ml.Point3
import java.io.Closeable

class HolisticFrameExtractor(context: Context) : Closeable {
    private val landmarker = HolisticLandmarker.createFromOptions(
        context,
        HolisticLandmarker.HolisticLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("holistic_landmarker.task").build())
            .setMinFaceDetectionConfidence(0.5f)
            .setMinFacePresenceConfidence(0.5f)
            .setMinPoseDetectionConfidence(0.5f)
            .setMinPosePresenceConfidence(0.5f)
            .setMinHandLandmarksConfidence(0.5f)
            .build(),
    )

    fun extract(bitmap: Bitmap, rotationDegrees: Int): LandmarkFrame {
        val image = BitmapImageBuilder(bitmap).build()
        return try {
            val options = ImageProcessingOptions.builder().setRotationDegrees(rotationDegrees).build()
            landmarker.detect(image, options).toFrame()
        } finally {
            image.close()
        }
    }

    override fun close() = landmarker.close()

    private fun HolisticLandmarkerResult.toFrame(): LandmarkFrame {
        val left = leftHandLandmarks()
        val right = rightHandLandmarks()
        val body = poseLandmarks()
        val facial = faceLandmarks()
        return LandmarkFrame(
            leftHand = List(HAND_LANDMARK_COUNT) { index ->
                left.getOrNull(index)?.let { Point3(it.x(), it.y(), it.z()) }
            },
            rightHand = List(HAND_LANDMARK_COUNT) { index ->
                right.getOrNull(index)?.let { Point3(it.x(), it.y(), it.z()) }
            },
            pose = List(POSE_LANDMARK_COUNT) { index ->
                body.getOrNull(index)?.let { Point3(it.x(), it.y(), it.z()) }
            },
            face = List(FACE_LANDMARK_COUNT) { index ->
                facial.getOrNull(index)?.let { Point3(it.x(), it.y(), it.z()) }
            },
        )
    }

    private companion object {
        const val HAND_LANDMARK_COUNT = 21
        const val POSE_LANDMARK_COUNT = 33
        const val FACE_LANDMARK_COUNT = 478
    }
}

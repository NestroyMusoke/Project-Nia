package org.projectnia.app.avatar

import android.content.Context
import android.util.AttributeSet
import android.view.Choreographer
import android.view.SurfaceView
import com.google.android.filament.Engine
import com.google.android.filament.IndirectLight
import com.google.android.filament.View
import com.google.android.filament.android.UiHelper
import com.google.android.filament.utils.Float3
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Physically based renderer for Nia's finger-rigged VRM humanoid.
 *
 * Motion retargeting is deliberately kept separate from model loading so an
 * unvalidated landmark recording can never become an agent reply implicitly.
 */
class RiggedSigningAvatarView @JvmOverloads constructor(
    context: Context,
    attributes: AttributeSet? = null,
) : SurfaceView(context, attributes), Choreographer.FrameCallback {
    private val modelViewer = ModelViewer(
        this,
        Engine.create(Engine.Backend.OPENGL),
        UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK),
        null,
    )
    @Volatile private var running = false
    @Volatile private var playlist: List<AvatarMotionClip> = emptyList()
    @Volatile private var playbackStartedAt = 0L
    private val restTransforms = linkedMapOf<String, FloatArray>()
    private val ambientLight: IndirectLight

    init {
        contentDescription = "Nia finger-rigged three-dimensional signing avatar"
        keepScreenOn = true
        modelViewer.view.antiAliasing = View.AntiAliasing.FXAA
        modelViewer.view.multiSampleAntiAliasingOptions =
            modelViewer.view.multiSampleAntiAliasingOptions.apply { enabled = true }
        modelViewer.view.dynamicResolutionOptions =
            modelViewer.view.dynamicResolutionOptions.apply {
                enabled = true
                quality = View.QualityLevel.MEDIUM
            }
        // ModelViewer supplies a sun light, but its default straight-down
        // direction leaves a front-facing signing avatar nearly black. Aim it
        // from above the camera and add diffuse fill so facial grammar and
        // individual fingers remain readable instead of disappearing into
        // shadow on a phone display.
        val lights = modelViewer.engine.lightManager
        val sun = lights.getInstance(modelViewer.light)
        lights.setDirection(sun, -0.35f, -0.55f, -0.76f)
        lights.setIntensity(sun, 120_000f)
        ambientLight = IndirectLight.Builder()
            .irradiance(3, AMBIENT_IRRADIANCE)
            .intensity(32_000f)
            .build(modelViewer.engine)
        modelViewer.scene.indirectLight = ambientLight
        modelViewer.renderer.clearOptions = modelViewer.renderer.clearOptions.apply {
            clearColor = doubleArrayOf(0.78, 0.85, 0.88, 1.0)
        }
        modelViewer.loadModelGlb(loadAvatar())
        hideSigningDistractions()
        // Deliberately crop below the knees and give the upper body most of the
        // phone stage. Full-body framing makes finger configurations too small
        // to read, while this distance leaves room for signs above the head.
        modelViewer.transformToUnitCube(Float3(AVATAR_X, AVATAR_Y, AVATAR_Z))
        orientAvatarTowardCamera()
        cacheRigTransforms()
        restoreRig()
    }

    private fun hideSigningDistractions() {
        val asset = modelViewer.asset ?: return
        val renderables = modelViewer.engine.renderableManager
        HIDDEN_ACCESSORY_MESHES.forEach { name ->
            val entity = asset.getFirstEntityByName(name)
            if (entity != 0 && renderables.hasComponent(entity)) {
                renderables.setLayerMask(renderables.getInstance(entity), 0xff, 0x00)
            }
        }
    }

    fun play(clips: List<AvatarMotionClip>) {
        playlist = clips.toList()
        playbackStartedAt = System.nanoTime()
    }

    fun clearMotion() {
        playlist = emptyList()
        restoreRig()
    }

    fun onResume() {
        if (running) return
        running = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun onPause() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        currentMotionFrame()?.let(::applyMotion)
        modelViewer.render(frameTimeNanos)
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun cacheRigTransforms() {
        val asset = modelViewer.asset ?: return
        val transforms = modelViewer.engine.transformManager
        ANIMATED_BONES.forEach { name ->
            val entity = asset.getFirstEntityByName(name)
            if (entity != 0 && transforms.hasComponent(entity)) {
                val matrix = FloatArray(16)
                transforms.getTransform(transforms.getInstance(entity), matrix)
                restTransforms[name] = matrix
            }
        }
    }

    private fun orientAvatarTowardCamera() {
        val asset = modelViewer.asset ?: return
        val transforms = modelViewer.engine.transformManager
        val instance = transforms.getInstance(asset.root)
        val fitted = FloatArray(16)
        transforms.getTransform(instance, fitted)
        // Alicia's VRM 0.x forward direction is opposite Filament's viewer
        // camera. Rotate around the fitted avatar centre, not world origin.
        val facingCamera = multiply(
            translation(AVATAR_X, AVATAR_Y, AVATAR_Z),
            multiply(
                rotationY(PI.toFloat()),
                multiply(translation(-AVATAR_X, -AVATAR_Y, -AVATAR_Z), fitted),
            ),
        )
        transforms.setTransform(instance, facingCamera)
    }

    private fun restoreRig() {
        val asset = modelViewer.asset ?: return
        val transforms = modelViewer.engine.transformManager
        transforms.openLocalTransformTransaction()
        restTransforms.forEach { (name, matrix) ->
            val entity = asset.getFirstEntityByName(name)
            if (entity != 0) {
                val neutralRotation = NEUTRAL_BONE_ROTATIONS[name]
                val value = if (neutralRotation == null) matrix else multiply(matrix, neutralRotation.toMatrix())
                transforms.setTransform(transforms.getInstance(entity), value)
            }
        }
        transforms.commitLocalTransformTransaction()
        asset.instance.animator.updateBoneMatrices()
        applyFace(NEUTRAL_FRAME)
    }

    private fun applyFace(frame: AvatarMotionFrame) {
        val asset = modelViewer.asset ?: return
        val entity = asset.getFirstEntityByName(FACE_MESH_NAME)
        if (entity == 0) return
        val renderables = modelViewer.engine.renderableManager
        if (!renderables.hasComponent(entity)) return
        val instance = renderables.getInstance(entity)
        val weights = FloatArray(renderables.getMorphTargetCount(instance))
        fun set(index: Int, value: Float) {
            if (index in weights.indices) weights[index] = value.coerceIn(0f, 1f)
        }
        set(MORPH_BLINK_LEFT, 1f - frame.face.eyeOpen)
        set(MORPH_BLINK_RIGHT, 1f - frame.face.eyeOpen)
        set(MORPH_BROW_RAISE, frame.face.browRaise)
        set(MORPH_MOUTH_A, frame.face.mouthOpen)
        renderables.setMorphWeights(instance, weights, 0)
    }

    private fun applyMotion(frame: AvatarMotionFrame) {
        val asset = modelViewer.asset ?: return
        val transforms = modelViewer.engine.transformManager
        val rotations = linkedMapOf<String, Quaternion>()
        collectArmRotations(frame, left = true, rotations)
        collectArmRotations(frame, left = false, rotations)

        transforms.openLocalTransformTransaction()
        restTransforms.forEach { (name, rest) ->
            val entity = asset.getFirstEntityByName(name)
            if (entity == 0) return@forEach
            val rotation = rotations[name] ?: NEUTRAL_BONE_ROTATIONS[name]
            val value = if (rotation == null) rest else multiply(rest, rotation.toMatrix())
            transforms.setTransform(transforms.getInstance(entity), value)
        }
        transforms.commitLocalTransformTransaction()
        asset.instance.animator.updateBoneMatrices()
        applyFace(frame)
    }

    private fun collectArmRotations(
        frame: AvatarMotionFrame,
        left: Boolean,
        output: MutableMap<String, Quaternion>,
    ) {
        val pose = frame.pose
        val shoulder = pose.getOrNull(if (left) 11 else 12) ?: return
        val elbow = pose.getOrNull(if (left) 13 else 14) ?: return
        val wrist = pose.getOrNull(if (left) 15 else 16) ?: return
        val side = if (left) "Left" else "Right"
        val restAxis = if (left) Vec3(-1f, 0f, 0f) else Vec3(1f, 0f, 0f)
        val upperDirection = modelDirection(shoulder, elbow) ?: return
        val forearmDirection = modelDirection(elbow, wrist) ?: return
        val upper = Quaternion.fromTo(restAxis, upperDirection)
        val forearmLocal = upper.inverse().rotate(forearmDirection)
        val forearm = Quaternion.fromTo(restAxis, forearmLocal)
        val forearmWorld = upper * forearm
        output["${side}Arm"] = upper
        output["${side}ForeArm"] = forearm

        val hand = if (left) frame.leftHand else frame.rightHand
        val handWrist = hand.getOrNull(0) ?: return
        val middleBase = hand.getOrNull(9) ?: return
        val indexBase = hand.getOrNull(5) ?: return
        val pinkyBase = hand.getOrNull(17) ?: return
        val handDirection = modelDirection(handWrist, middleBase) ?: return
        val handAcross = modelDirection(indexBase, pinkyBase) ?: return
        val localHandDirection = forearmWorld.inverse().rotate(handDirection)
        val localHandAcross = forearmWorld.inverse().rotate(handAcross)
        val restHandDirection = restHandDirection(left)
        val handRotation = alignFrame(
            restHandDirection,
            Vec3(0f, 0f, 1f),
            localHandDirection,
            localHandAcross,
        )
        output["${side}Hand"] = handRotation
        val handWorld = forearmWorld * handRotation

        collectFinger(hand, left, side, "Thumb", intArrayOf(1, 2, 3, 4), handWorld, output)
        collectFinger(hand, left, side, "Index", intArrayOf(5, 6, 7, 8), handWorld, output)
        collectFinger(hand, left, side, "Middle", intArrayOf(9, 10, 11, 12), handWorld, output)
        collectFinger(hand, left, side, "Ring", intArrayOf(13, 14, 15, 16), handWorld, output)
        collectFinger(hand, left, side, "Pinky", intArrayOf(17, 18, 19, 20), handWorld, output)
    }

    private fun collectFinger(
        hand: List<AvatarJoint?>,
        left: Boolean,
        side: String,
        finger: String,
        joints: IntArray,
        handWorld: Quaternion,
        output: MutableMap<String, Quaternion>,
    ) {
        val a = hand.getOrNull(joints[0]) ?: return
        val b = hand.getOrNull(joints[1]) ?: return
        val c = hand.getOrNull(joints[2]) ?: return
        val d = hand.getOrNull(joints[3]) ?: return
        val directions = listOf(
            modelDirection(a, b) ?: return,
            modelDirection(b, c) ?: return,
            modelDirection(c, d) ?: return,
        )
        val stem = "${side}Hand$finger"
        var parentWorld = handWorld
        directions.forEachIndexed { index, direction ->
            val localDirection = parentWorld.inverse().rotate(direction)
            val rotation = Quaternion.fromTo(restFingerDirection(left, finger, index), localDirection)
            output["$stem${index + 1}"] = rotation
            parentWorld = parentWorld * rotation
        }
    }

    private fun currentMotionFrame(): AvatarMotionFrame? {
        val clips = playlist
        if (clips.isEmpty()) return null
        // Signing must remain readable on a small screen. Motion assets retain
        // their captured timing, while this global presentation rate gives the
        // viewer time to resolve handshape, location, and movement.
        var elapsed = (System.nanoTime() - playbackStartedAt).coerceAtLeast(0L) /
            1_000_000_000f * SIGNING_PLAYBACK_RATE
        if (elapsed < PREPARE_SECONDS) {
            return interpolate(
                NEUTRAL_FRAME,
                clips.first().frames.first(),
                elapsed / PREPARE_SECONDS,
                staged = true,
            )
        }
        elapsed -= PREPARE_SECONDS
        clips.forEachIndexed { index, clip ->
            val duration = clip.frames.size.toFloat() / clip.framesPerSecond
            if (elapsed < duration) {
                val position = (elapsed * clip.framesPerSecond).coerceIn(0f, clip.frames.lastIndex.toFloat())
                val low = position.toInt().coerceIn(clip.frames.indices)
                val high = (low + 1).coerceAtMost(clip.frames.lastIndex)
                return interpolate(clip.frames[low], clip.frames[high], position - low, staged = false)
            }
            elapsed -= duration
            if (elapsed < POSTURE_HOLD_SECONDS) return clip.frames.last()
            elapsed -= POSTURE_HOLD_SECONDS

            val next = clips.getOrNull(index + 1)?.frames?.first() ?: NEUTRAL_FRAME
            if (elapsed < TRANSITION_SECONDS) {
                return interpolate(
                    clip.frames.last(),
                    next,
                    elapsed / TRANSITION_SECONDS,
                    staged = true,
                )
            }
            elapsed -= TRANSITION_SECONDS
        }
        return NEUTRAL_FRAME
    }

    private fun interpolate(
        a: AvatarMotionFrame,
        b: AvatarMotionFrame,
        amount: Float,
        staged: Boolean,
    ): AvatarMotionFrame {
        val bodyAmount = if (staged) smoothStep(amount) else amount
        // Establish the handshape before the arm reaches the sign location.
        // Independent channel timing follows the paper's per-bone increment
        // model and prevents fingers from trailing behind the movement.
        val handAmount = if (staged) smoothStep((amount / HANDSHAPE_PORTION).coerceIn(0f, 1f)) else amount
        return AvatarMotionFrame(
        pose = interpolateJoints(a.pose, b.pose, bodyAmount),
        leftHand = interpolateJoints(a.leftHand, b.leftHand, handAmount),
        rightHand = interpolateJoints(a.rightHand, b.rightHand, handAmount),
        face = AvatarFacePose(
            a.face.mouthOpen + (b.face.mouthOpen - a.face.mouthOpen) * bodyAmount,
            a.face.browRaise + (b.face.browRaise - a.face.browRaise) * bodyAmount,
            a.face.eyeOpen + (b.face.eyeOpen - a.face.eyeOpen) * bodyAmount,
        ),
        )
    }

    private fun smoothStep(amount: Float): Float {
        val value = amount.coerceIn(0f, 1f)
        return value * value * (3f - 2f * value)
    }

    private fun interpolateJoints(
        a: List<AvatarJoint?>,
        b: List<AvatarJoint?>,
        amount: Float,
    ): List<AvatarJoint?> = List(maxOf(a.size, b.size)) { index ->
        val first = a.getOrNull(index)
        val second = b.getOrNull(index)
        when {
            first != null && second != null -> AvatarJoint(
                first.x + (second.x - first.x) * amount,
                first.y + (second.y - first.y) * amount,
                first.z + (second.z - first.z) * amount,
            )
            amount < 0.5f -> first ?: second
            else -> second ?: first
        }
    }

    private fun modelDirection(a: AvatarJoint, b: AvatarJoint): Vec3? {
        // The avatar root is rotated 180 degrees to face the camera. Convert
        // camera-facing landmark coordinates back into the rig's model basis.
        return Vec3(-(b.x - a.x), b.y - a.y, -(b.z - a.z)).normalizedOrNull()
    }

    private fun restHandDirection(left: Boolean) = if (left) {
        Vec3(-0.049778f, 0.004642f, -0.001997f).normalized()
    } else {
        Vec3(0.048265f, 0.004796f, -0.002003f).normalized()
    }

    private fun restFingerDirection(left: Boolean, finger: String, segment: Int): Vec3 {
        val x = if (left) -1f else 1f
        if (finger == "Thumb") {
            val values = if (left) LEFT_THUMB_DIRECTIONS else RIGHT_THUMB_DIRECTIONS
            return values[segment].normalized()
        }
        return Vec3(x, if (segment == 0) -0.055f else -0.06f, 0f).normalized()
    }

    private fun alignFrame(
        restForward: Vec3,
        restAcross: Vec3,
        targetForward: Vec3,
        targetAcross: Vec3,
    ): Quaternion {
        val swing = Quaternion.fromTo(restForward, targetForward)
        val swungAcross = swing.rotate(restAcross).rejectFrom(targetForward).normalizedOrNull()
            ?: return swing
        val wantedAcross = targetAcross.rejectFrom(targetForward).normalizedOrNull() ?: return swing
        val cosine = swungAcross.dot(wantedAcross).coerceIn(-1f, 1f)
        val angle = acos(cosine) * if (targetForward.dot(swungAcross.cross(wantedAcross)) < 0f) -1f else 1f
        return Quaternion.axisAngle(targetForward, angle) * swing
    }

    /** Column-major matrix multiplication used by Filament transforms. */
    private fun multiply(a: FloatArray, b: FloatArray): FloatArray = FloatArray(16) { index ->
        val column = index / 4
        val row = index % 4
        (0..3).sumOf { k -> (a[k * 4 + row] * b[column * 4 + k]).toDouble() }.toFloat()
    }

    private fun rotationZ(angle: Float): FloatArray {
        val cosine = kotlin.math.cos(angle)
        val sine = kotlin.math.sin(angle)
        return floatArrayOf(
            cosine, sine, 0f, 0f,
            -sine, cosine, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )
    }

    private fun rotationY(angle: Float): FloatArray {
        val cosine = kotlin.math.cos(angle)
        val sine = kotlin.math.sin(angle)
        return floatArrayOf(
            cosine, 0f, -sine, 0f,
            0f, 1f, 0f, 0f,
            sine, 0f, cosine, 0f,
            0f, 0f, 0f, 1f,
        )
    }

    private fun translation(x: Float, y: Float, z: Float) = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        x, y, z, 1f,
    )

    private data class Vec3(val x: Float, val y: Float, val z: Float) {
        operator fun times(scale: Float) = Vec3(x * scale, y * scale, z * scale)
        operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
        fun dot(other: Vec3) = x * other.x + y * other.y + z * other.z
        fun cross(other: Vec3) = Vec3(
            y * other.z - z * other.y,
            z * other.x - x * other.z,
            x * other.y - y * other.x,
        )
        fun length() = sqrt(dot(this))
        fun normalizedOrNull(): Vec3? {
            val magnitude = length()
            return if (magnitude > 1e-5f && magnitude.isFinite()) this * (1f / magnitude) else null
        }
        fun normalized() = normalizedOrNull() ?: Vec3(1f, 0f, 0f)
        fun rejectFrom(axis: Vec3): Vec3 {
            val normal = axis.normalized()
            return this - normal * dot(normal)
        }
    }

    private data class Quaternion(val x: Float, val y: Float, val z: Float, val w: Float) {
        operator fun times(other: Quaternion) = Quaternion(
            w * other.x + x * other.w + y * other.z - z * other.y,
            w * other.y - x * other.z + y * other.w + z * other.x,
            w * other.z + x * other.y - y * other.x + z * other.w,
            w * other.w - x * other.x - y * other.y - z * other.z,
        ).normalized()

        fun inverse() = Quaternion(-x, -y, -z, w).normalized()

        fun rotate(value: Vec3): Vec3 {
            val vector = Quaternion(value.x, value.y, value.z, 0f)
            val rotated = this * vector * inverse()
            return Vec3(rotated.x, rotated.y, rotated.z)
        }

        fun normalized(): Quaternion {
            val magnitude = sqrt(x * x + y * y + z * z + w * w)
            return if (magnitude > 1e-6f) {
                Quaternion(x / magnitude, y / magnitude, z / magnitude, w / magnitude)
            } else IDENTITY
        }

        fun toMatrix(): FloatArray {
            val q = normalized()
            val xx = q.x * q.x
            val yy = q.y * q.y
            val zz = q.z * q.z
            val xy = q.x * q.y
            val xz = q.x * q.z
            val yz = q.y * q.z
            val wx = q.w * q.x
            val wy = q.w * q.y
            val wz = q.w * q.z
            return floatArrayOf(
                1f - 2f * (yy + zz), 2f * (xy + wz), 2f * (xz - wy), 0f,
                2f * (xy - wz), 1f - 2f * (xx + zz), 2f * (yz + wx), 0f,
                2f * (xz + wy), 2f * (yz - wx), 1f - 2f * (xx + yy), 0f,
                0f, 0f, 0f, 1f,
            )
        }

        companion object {
            val IDENTITY = Quaternion(0f, 0f, 0f, 1f)

            fun axisAngle(axis: Vec3, angle: Float): Quaternion {
                val normal = axis.normalized()
                val half = angle / 2f
                val sine = sin(half)
                return Quaternion(normal.x * sine, normal.y * sine, normal.z * sine, cos(half))
            }

            fun fromTo(from: Vec3, to: Vec3): Quaternion {
                val first = from.normalized()
                val second = to.normalized()
                val dot = first.dot(second).coerceIn(-1f, 1f)
                if (dot > 0.9999f) return IDENTITY
                if (dot < -0.9999f) {
                    val fallback = if (kotlin.math.abs(first.x) < 0.8f) {
                        Vec3(1f, 0f, 0f)
                    } else {
                        Vec3(0f, 1f, 0f)
                    }
                    return axisAngle(first.cross(fallback), PI.toFloat())
                }
                val cross = first.cross(second)
                return Quaternion(cross.x, cross.y, cross.z, 1f + dot).normalized()
            }
        }
    }

    private fun loadAvatar(): ByteBuffer {
        val bytes = context.assets.open(AVATAR_ASSET).use { it.readBytes() }
        return ByteBuffer.allocateDirect(bytes.size)
            .order(ByteOrder.nativeOrder())
            .apply {
                put(bytes)
                flip()
            }
    }

    private companion object {
        const val AVATAR_ASSET = "nia_avatar_nia.vrm"
        const val SIGNING_PLAYBACK_RATE = 0.42f
        const val PREPARE_SECONDS = 0.22f
        const val POSTURE_HOLD_SECONDS = 0.32f
        const val TRANSITION_SECONDS = 0.24f
        const val HANDSHAPE_PORTION = 0.65f
        const val AVATAR_X = 0f
        const val AVATAR_Y = -0.34f
        const val AVATAR_Z = -1.58f
        const val FACE_MESH_NAME = "face"
        const val MORPH_BLINK_LEFT = 28
        const val MORPH_BLINK_RIGHT = 29
        const val MORPH_BROW_RAISE = -1
        const val MORPH_MOUTH_A = 0
        val HIDDEN_ACCESSORY_MESHES = emptyList<String>()
        val NEUTRAL_BONE_ROTATIONS = mapOf(
            "LeftArm" to Quaternion.axisAngle(Vec3(0f, 0f, 1f), (PI / 2f).toFloat()),
            "RightArm" to Quaternion.axisAngle(Vec3(0f, 0f, 1f), (-PI / 2f).toFloat()),
        )

        val LEFT_THUMB_DIRECTIONS = listOf(
            Vec3(-0.028239f, -0.007388f, -0.014039f),
            Vec3(-0.019661f, -0.008030f, -0.001706f),
            Vec3(-0.004293f, -0.000785f, -0.000444f),
        )
        val RIGHT_THUMB_DIRECTIONS = listOf(
            Vec3(0.028215f, -0.007481f, -0.014037f),
            Vec3(0.019665f, -0.008023f, -0.001696f),
            Vec3(0.006728f, -0.002624f, 0.000999f),
        )

        val NEUTRAL_FRAME = AvatarMotionFrame(
            pose = emptyList(),
            leftHand = emptyList(),
            rightHand = emptyList(),
            face = AvatarFacePose(mouthOpen = 0f, browRaise = 0f, eyeOpen = 1f),
        )

        val ANIMATED_BONES = listOf(
            "LeftArm", "LeftForeArm", "LeftHand",
            "RightArm", "RightForeArm", "RightHand",
            "LeftHandThumb1", "LeftHandThumb2", "LeftHandThumb3",
            "LeftHandIndex1", "LeftHandIndex2", "LeftHandIndex3",
            "LeftHandMiddle1", "LeftHandMiddle2", "LeftHandMiddle3",
            "LeftHandRing1", "LeftHandRing2", "LeftHandRing3",
            "LeftHandPinky1", "LeftHandPinky2", "LeftHandPinky3",
            "RightHandThumb1", "RightHandThumb2", "RightHandThumb3",
            "RightHandIndex1", "RightHandIndex2", "RightHandIndex3",
            "RightHandMiddle1", "RightHandMiddle2", "RightHandMiddle3",
            "RightHandRing1", "RightHandRing2", "RightHandRing3",
            "RightHandPinky1", "RightHandPinky2", "RightHandPinky3",
        )

        // L0 spherical-harmonic term gives a soft, neutral studio fill. The
        // remaining coefficients are intentionally zero to avoid tinting skin.
        val AMBIENT_IRRADIANCE = floatArrayOf(
            1f, 1f, 1f,
            0f, 0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f, 0f,
        )

        init {
            Utils.init()
        }
    }
}

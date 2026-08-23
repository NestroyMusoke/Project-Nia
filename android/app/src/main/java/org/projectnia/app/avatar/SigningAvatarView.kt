package org.projectnia.app.avatar

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.AttributeSet
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class SigningAvatarView @JvmOverloads constructor(
    context: Context,
    attributes: AttributeSet? = null,
) : GLSurfaceView(context, attributes) {
    private val avatarRenderer = SigningAvatarRenderer()

    init {
        setEGLContextClientVersion(2)
        setRenderer(avatarRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        preserveEGLContextOnPause = true
        contentDescription = "Three-dimensional signing avatar"
    }

    fun play(clips: List<AvatarMotionClip>) {
        avatarRenderer.play(clips)
    }

    fun clearMotion() {
        avatarRenderer.play(emptyList())
    }
}

private class SigningAvatarRenderer : GLSurfaceView.Renderer {
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val viewProjection = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)

    private lateinit var sphere: AvatarMesh
    private lateinit var cylinder: AvatarMesh
    private var program = 0
    private var viewportWidth = 1
    private var viewportHeight = 1

    @Volatile private var playlist: List<AvatarMotionClip> = emptyList()
    @Volatile private var playbackStartedAt = 0L

    fun play(clips: List<AvatarMotionClip>) {
        playlist = clips.toList()
        playbackStartedAt = System.nanoTime()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.945f, 0.965f, 0.975f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        sphere = AvatarMesh.sphere(16, 20)
        cylinder = AvatarMesh.cylinder(18)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val ratio = viewportWidth.toFloat() / viewportHeight.toFloat()
        Matrix.perspectiveM(projection, 0, 34f, ratio, 0.1f, 20f)
        Matrix.setLookAtM(view, 0, 0f, 0.45f, 5.2f, 0f, 0.45f, 0f, 0f, 1f, 0f)
        Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0)

        GLES20.glUseProgram(program)
        GLES20.glUniform3f(GLES20.glGetUniformLocation(program, "uLightDirection"), -0.4f, 0.8f, 0.7f)

        drawGround()
        drawAvatar(currentFrame())
    }

    private fun currentFrame(): AvatarMotionFrame {
        val clips = playlist
        if (clips.isEmpty()) return IDLE_FRAME
        var elapsed = (System.nanoTime() - playbackStartedAt).coerceAtLeast(0L) / 1_000_000_000f
        clips.forEach { clip ->
            val duration = clip.frames.size.toFloat() / clip.framesPerSecond + CLIP_PAUSE_SECONDS
            if (elapsed <= duration) {
                val framePosition = (elapsed * clip.framesPerSecond).coerceIn(0f, clip.frames.lastIndex.toFloat())
                return interpolateFrames(clip.frames, framePosition)
            }
            elapsed -= duration
        }
        return clips.last().frames.last()
    }

    private fun interpolateFrames(frames: List<AvatarMotionFrame>, position: Float): AvatarMotionFrame {
        val low = position.toInt().coerceIn(frames.indices)
        val high = (low + 1).coerceAtMost(frames.lastIndex)
        val amount = position - low
        return AvatarMotionFrame(
            interpolateJoints(frames[low].pose, frames[high].pose, amount),
            interpolateJoints(frames[low].leftHand, frames[high].leftHand, amount),
            interpolateJoints(frames[low].rightHand, frames[high].rightHand, amount),
            AvatarFacePose(
                frames[low].face.mouthOpen + (frames[high].face.mouthOpen - frames[low].face.mouthOpen) * amount,
                frames[low].face.browRaise + (frames[high].face.browRaise - frames[low].face.browRaise) * amount,
                frames[low].face.eyeOpen + (frames[high].face.eyeOpen - frames[low].face.eyeOpen) * amount,
            ),
        )
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

    private fun drawAvatar(frame: AvatarMotionFrame) {
        val pose = frame.pose
        val leftShoulder = pose.point(11)
        val rightShoulder = pose.point(12)
        val leftHip = pose.point(23)
        val rightHip = pose.point(24)
        val shoulderCenter = midpoint(leftShoulder, rightShoulder)
        val hipCenter = midpoint(leftHip, rightHip)

        drawBone(shoulderCenter, hipCenter, 0.23f, SHIRT)
        drawBone(leftShoulder, rightShoulder, 0.15f, SHIRT)
        drawPoseChain(pose, intArrayOf(11, 13, 15), SHIRT, SKIN)
        drawPoseChain(pose, intArrayOf(12, 14, 16), SHIRT, SKIN)
        drawPoseChain(pose, intArrayOf(23, 25, 27), TROUSERS, TROUSERS)
        drawPoseChain(pose, intArrayOf(24, 26, 28), TROUSERS, TROUSERS)
        drawBone(leftHip, rightHip, 0.14f, TROUSERS)

        val nose = pose.point(0)
        val head = if (pose.getOrNull(0) != null) nose else AvatarJoint(0f, shoulderCenter.y + 0.66f, 0f)
        drawSphere(head, 0.27f, SKIN)
        val eyeRadius = 0.015f + frame.face.eyeOpen * 0.012f
        drawSphere(AvatarJoint(head.x - 0.09f, head.y + 0.035f, head.z + 0.245f), eyeRadius, EYES)
        drawSphere(AvatarJoint(head.x + 0.09f, head.y + 0.035f, head.z + 0.245f), eyeRadius, EYES)
        val browY = head.y + 0.105f + frame.face.browRaise * 0.035f
        drawBone(
            AvatarJoint(head.x - 0.145f, browY, head.z + 0.235f),
            AvatarJoint(head.x - 0.045f, browY + 0.01f, head.z + 0.25f),
            0.012f,
            EYES,
        )
        drawBone(
            AvatarJoint(head.x + 0.045f, browY + 0.01f, head.z + 0.25f),
            AvatarJoint(head.x + 0.145f, browY, head.z + 0.235f),
            0.012f,
            EYES,
        )
        val mouthGap = 0.012f + frame.face.mouthOpen * 0.055f
        drawBone(
            AvatarJoint(head.x - 0.075f, head.y - 0.09f + mouthGap, head.z + 0.253f),
            AvatarJoint(head.x + 0.075f, head.y - 0.09f + mouthGap, head.z + 0.253f),
            0.012f,
            MOUTH,
        )
        drawBone(
            AvatarJoint(head.x - 0.075f, head.y - 0.09f - mouthGap, head.z + 0.253f),
            AvatarJoint(head.x + 0.075f, head.y - 0.09f - mouthGap, head.z + 0.253f),
            0.012f,
            MOUTH,
        )

        drawHand(frame.leftHand)
        drawHand(frame.rightHand)
    }

    private fun drawPoseChain(
        pose: List<AvatarJoint?>,
        indices: IntArray,
        firstColor: FloatArray,
        secondColor: FloatArray,
    ) {
        drawBone(pose.point(indices[0]), pose.point(indices[1]), 0.105f, firstColor)
        drawBone(pose.point(indices[1]), pose.point(indices[2]), 0.085f, secondColor)
        drawSphere(pose.point(indices[1]), 0.1f, secondColor)
    }

    private fun drawHand(hand: List<AvatarJoint?>) {
        if (hand.getOrNull(0) == null) return
        HAND_EDGES.forEach { edge ->
            val start = hand.getOrNull(edge[0]) ?: return@forEach
            val end = hand.getOrNull(edge[1]) ?: return@forEach
            drawBone(start, end, if (edge[0] == 0) 0.038f else 0.026f, SKIN)
        }
        hand.forEachIndexed { index, joint ->
            if (joint != null) drawSphere(joint, if (index == 0) 0.07f else 0.035f, SKIN)
        }
    }

    private fun drawGround() {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, 0f, -1.85f, -0.25f)
        Matrix.scaleM(model, 0, 1.5f, 0.025f, 0.65f)
        drawMesh(cylinder, model, GROUND)
    }

    private fun drawBone(start: AvatarJoint, end: AvatarJoint, radius: Float, color: FloatArray) {
        val dx = end.x - start.x
        val dy = end.y - start.y
        val dz = end.z - start.z
        val length = sqrt(dx * dx + dy * dy + dz * dz)
        if (length < 0.001f) return

        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, (start.x + end.x) / 2f, (start.y + end.y) / 2f, (start.z + end.z) / 2f)
        val cosine = (dy / length).coerceIn(-1f, 1f)
        val angle = Math.toDegrees(acos(cosine).toDouble()).toFloat()
        val axisX = dz
        val axisZ = -dx
        if (axisX * axisX + axisZ * axisZ > 0.000001f) {
            Matrix.rotateM(model, 0, angle, axisX, 0f, axisZ)
        } else if (dy < 0f) {
            Matrix.rotateM(model, 0, 180f, 1f, 0f, 0f)
        }
        Matrix.scaleM(model, 0, radius, length, radius)
        drawMesh(cylinder, model, color)
    }

    private fun drawSphere(center: AvatarJoint, radius: Float, color: FloatArray) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, center.x, center.y, center.z)
        Matrix.scaleM(model, 0, radius, radius, radius)
        drawMesh(sphere, model, color)
    }

    private fun drawMesh(mesh: AvatarMesh, modelMatrix: FloatArray, color: FloatArray) {
        Matrix.multiplyMM(mvp, 0, viewProjection, 0, modelMatrix, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMvp"), 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uModel"), 1, false, modelMatrix, 0)
        GLES20.glUniform4fv(GLES20.glGetUniformLocation(program, "uColor"), 1, color, 0)
        mesh.draw(program)
    }

    private fun List<AvatarJoint?>.point(index: Int): AvatarJoint =
        getOrNull(index) ?: IDLE_FRAME.pose[index] ?: AvatarJoint(0f, 0f, 0f)

    private fun midpoint(a: AvatarJoint, b: AvatarJoint) = AvatarJoint(
        (a.x + b.x) / 2f,
        (a.y + b.y) / 2f,
        (a.z + b.z) / 2f,
    )

    private fun buildProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        return GLES20.glCreateProgram().also { result ->
            GLES20.glAttachShader(result, vertex)
            GLES20.glAttachShader(result, fragment)
            GLES20.glLinkProgram(result)
        }
    }

    private fun compileShader(type: Int, source: String): Int = GLES20.glCreateShader(type).also { shader ->
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
    }

    private companion object {
        const val CLIP_PAUSE_SECONDS = 0.35f
        val SKIN = floatArrayOf(0.55f, 0.31f, 0.20f, 1f)
        val SHIRT = floatArrayOf(0.04f, 0.33f, 0.40f, 1f)
        val TROUSERS = floatArrayOf(0.07f, 0.12f, 0.20f, 1f)
        val EYES = floatArrayOf(0.04f, 0.05f, 0.06f, 1f)
        val MOUTH = floatArrayOf(0.35f, 0.08f, 0.09f, 1f)
        val GROUND = floatArrayOf(0.72f, 0.83f, 0.85f, 1f)

        val HAND_EDGES = arrayOf(
            intArrayOf(0, 1), intArrayOf(1, 2), intArrayOf(2, 3), intArrayOf(3, 4),
            intArrayOf(0, 5), intArrayOf(5, 6), intArrayOf(6, 7), intArrayOf(7, 8),
            intArrayOf(5, 9), intArrayOf(9, 10), intArrayOf(10, 11), intArrayOf(11, 12),
            intArrayOf(9, 13), intArrayOf(13, 14), intArrayOf(14, 15), intArrayOf(15, 16),
            intArrayOf(13, 17), intArrayOf(17, 18), intArrayOf(18, 19), intArrayOf(19, 20),
            intArrayOf(0, 17),
        )

        val IDLE_FRAME: AvatarMotionFrame by lazy {
            val pose = MutableList<AvatarJoint?>(33) { null }
            pose[0] = AvatarJoint(0f, 1.18f, 0f)
            pose[11] = AvatarJoint(-0.5f, 0.55f, 0f)
            pose[12] = AvatarJoint(0.5f, 0.55f, 0f)
            pose[13] = AvatarJoint(-0.7f, 0.05f, 0f)
            pose[14] = AvatarJoint(0.7f, 0.05f, 0f)
            pose[15] = AvatarJoint(-0.62f, -0.42f, 0.08f)
            pose[16] = AvatarJoint(0.62f, -0.42f, 0.08f)
            pose[23] = AvatarJoint(-0.32f, -0.55f, 0f)
            pose[24] = AvatarJoint(0.32f, -0.55f, 0f)
            pose[25] = AvatarJoint(-0.34f, -1.15f, 0f)
            pose[26] = AvatarJoint(0.34f, -1.15f, 0f)
            pose[27] = AvatarJoint(-0.35f, -1.75f, 0.04f)
            pose[28] = AvatarJoint(0.35f, -1.75f, 0.04f)
            AvatarMotionFrame(pose, List(21) { null }, List(21) { null })
        }

        const val VERTEX_SHADER = """
            uniform mat4 uMvp;
            uniform mat4 uModel;
            uniform vec3 uLightDirection;
            attribute vec3 aPosition;
            attribute vec3 aNormal;
            varying float vLight;
            void main() {
                vec3 normal = normalize(mat3(uModel) * aNormal);
                vLight = 0.32 + 0.68 * max(dot(normal, normalize(uLightDirection)), 0.0);
                gl_Position = uMvp * vec4(aPosition, 1.0);
            }
        """

        const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 uColor;
            varying float vLight;
            void main() {
                gl_FragColor = vec4(uColor.rgb * vLight, uColor.a);
            }
        """
    }
}

private class AvatarMesh(
    vertices: FloatArray,
    normals: FloatArray,
) {
    private val vertexBuffer: FloatBuffer = vertices.toBuffer()
    private val normalBuffer: FloatBuffer = normals.toBuffer()
    private val vertexCount = vertices.size / 3

    fun draw(program: Int) {
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        val normal = GLES20.glGetAttribLocation(program, "aNormal")
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(normal)
        GLES20.glVertexAttribPointer(normal, 3, GLES20.GL_FLOAT, false, 0, normalBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(normal)
    }

    companion object {
        fun sphere(latitudeBands: Int, longitudeBands: Int): AvatarMesh {
            val vertices = mutableListOf<Float>()
            val normals = mutableListOf<Float>()
            fun point(latitude: Int, longitude: Int): FloatArray {
                val phi = PI * latitude / latitudeBands - PI / 2
                val theta = 2 * PI * longitude / longitudeBands
                val cp = cos(phi).toFloat()
                return floatArrayOf(cp * sin(theta).toFloat(), sin(phi).toFloat(), cp * cos(theta).toFloat())
            }
            for (latitude in 0 until latitudeBands) {
                for (longitude in 0 until longitudeBands) {
                    val points = arrayOf(
                        point(latitude, longitude), point(latitude + 1, longitude),
                        point(latitude + 1, longitude + 1), point(latitude, longitude),
                        point(latitude + 1, longitude + 1), point(latitude, longitude + 1),
                    )
                    points.forEach { point ->
                        vertices.addAll(point.toList())
                        normals.addAll(point.toList())
                    }
                }
            }
            return AvatarMesh(vertices.toFloatArray(), normals.toFloatArray())
        }

        fun cylinder(segments: Int): AvatarMesh {
            val vertices = mutableListOf<Float>()
            val normals = mutableListOf<Float>()
            for (segment in 0 until segments) {
                val a = 2 * PI * segment / segments
                val b = 2 * PI * (segment + 1) / segments
                val ax = cos(a).toFloat()
                val az = sin(a).toFloat()
                val bx = cos(b).toFloat()
                val bz = sin(b).toFloat()
                val points = arrayOf(
                    floatArrayOf(ax, -0.5f, az), floatArrayOf(ax, 0.5f, az), floatArrayOf(bx, 0.5f, bz),
                    floatArrayOf(ax, -0.5f, az), floatArrayOf(bx, 0.5f, bz), floatArrayOf(bx, -0.5f, bz),
                )
                points.forEachIndexed { index, point ->
                    vertices.addAll(point.toList())
                    val useA = index == 0 || index == 1 || index == 3
                    normals.addAll(if (useA) listOf(ax, 0f, az) else listOf(bx, 0f, bz))
                }
            }
            return AvatarMesh(vertices.toFloatArray(), normals.toFloatArray())
        }

        private fun FloatArray.toBuffer(): FloatBuffer = ByteBuffer
            .allocateDirect(size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(this@toBuffer).position(0) }
    }
}

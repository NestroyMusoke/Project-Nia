package org.projectnia.app.avatar

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class AvatarMotionStore(context: Context) {
    private val directory = File(context.filesDir, "avatar_motions").also { it.mkdirs() }

    fun saveDraft(clip: AvatarMotionClip) = write(clip.copy(signerValidated = false))

    fun markSignerValidated(gloss: String): Boolean {
        val clip = load(gloss) ?: return false
        write(clip.copy(signerValidated = true))
        return true
    }

    fun discardDraft(gloss: String) {
        val clip = load(gloss) ?: return
        if (!clip.signerValidated) fileFor(gloss).delete()
    }

    fun load(gloss: String): AvatarMotionClip? {
        val file = fileFor(gloss)
        if (!file.exists()) return null
        return runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                require(input.readInt() == FILE_VERSION)
                val storedGloss = input.readUTF()
                val validated = input.readBoolean()
                val fps = input.readInt()
                val frameCount = input.readInt().coerceIn(1, 600)
                val frames = List(frameCount) {
                    AvatarMotionFrame(
                        pose = readJoints(input, 33),
                        leftHand = readJoints(input, 21),
                        rightHand = readJoints(input, 21),
                        face = AvatarFacePose(input.readFloat(), input.readFloat(), input.readFloat()),
                    )
                }
                AvatarMotionClip(storedGloss, frames, validated, fps)
            }
        }.getOrNull()
    }

    fun availableGlosses(validatedOnly: Boolean = true): Set<String> = directory
        .listFiles { file -> file.extension == EXTENSION }
        .orEmpty()
        .mapNotNull { file -> load(file.nameWithoutExtension) }
        .filter { !validatedOnly || it.signerValidated }
        .mapTo(linkedSetOf()) { it.gloss }

    private fun write(clip: AvatarMotionClip) {
        val destination = fileFor(clip.gloss)
        val temporary = File(directory, destination.name + ".tmp")
        DataOutputStream(temporary.outputStream().buffered()).use { output ->
            output.writeInt(FILE_VERSION)
            output.writeUTF(clip.gloss)
            output.writeBoolean(clip.signerValidated)
            output.writeInt(clip.framesPerSecond)
            output.writeInt(clip.frames.size)
            clip.frames.forEach { frame ->
                writeJoints(output, frame.pose, 33)
                writeJoints(output, frame.leftHand, 21)
                writeJoints(output, frame.rightHand, 21)
                output.writeFloat(frame.face.mouthOpen)
                output.writeFloat(frame.face.browRaise)
                output.writeFloat(frame.face.eyeOpen)
            }
        }
        runCatching {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.getOrElse {
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun fileFor(gloss: String): File {
        val safe = gloss.lowercase().replace(Regex("[^a-z0-9_-]"), "_")
        return File(directory, "$safe.$EXTENSION")
    }

    private fun writeJoints(output: DataOutputStream, joints: List<AvatarJoint?>, count: Int) {
        repeat(count) { index ->
            val joint = joints.getOrNull(index)
            output.writeFloat(joint?.x ?: Float.NaN)
            output.writeFloat(joint?.y ?: Float.NaN)
            output.writeFloat(joint?.z ?: Float.NaN)
        }
    }

    private fun readJoints(input: DataInputStream, count: Int): List<AvatarJoint?> = List(count) {
        val x = input.readFloat()
        val y = input.readFloat()
        val z = input.readFloat()
        if (x.isFinite() && y.isFinite() && z.isFinite()) AvatarJoint(x, y, z) else null
    }

    private companion object {
        const val FILE_VERSION = 2
        const val EXTENSION = "niamotion"
    }
}

package org.projectnia.app.avatar

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class AvatarMotionStore(private val context: Context) {
    private val directory = File(context.filesDir, "avatar_motions").also { it.mkdirs() }

    fun saveDraft(clip: AvatarMotionClip) {
        reviewFileFor(clip.gloss).delete()
        write(clip.copy(signerValidated = false))
    }

    fun markSignerValidated(
        gloss: String,
        reviewerName: String,
        signLanguage: String,
        notes: String = "",
    ): Boolean {
        val cleanReviewer = reviewerName.trim()
        val cleanLanguage = signLanguage.trim()
        val cleanNotes = notes.trim()
        if (cleanReviewer.length !in 2..120 || cleanLanguage.length !in 2..40 || cleanNotes.length > 500) {
            return false
        }
        val clip = load(gloss) ?: return false
        write(clip.copy(signerValidated = true))
        val digest = motionDigest(gloss) ?: return false
        val review = SignerReview(
            reviewerName = cleanReviewer,
            signLanguage = cleanLanguage,
            reviewedAtEpochMillis = System.currentTimeMillis(),
            motionSha256 = digest,
            notes = cleanNotes,
        )
        return runCatching { writeReview(gloss, review) }.isSuccess
    }

    fun discardDraft(gloss: String) {
        val clip = load(gloss) ?: return
        if (!clip.signerValidated) {
            fileFor(gloss).delete()
            reviewFileFor(gloss).delete()
        }
    }

    fun load(gloss: String): AvatarMotionClip? {
        val bytes = motionBytes(gloss) ?: return null
        return runCatching {
            DataInputStream(ByteArrayInputStream(bytes).buffered()).use { input ->
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
                val approved = validated && loadReview(storedGloss)?.motionSha256 == sha256(bytes)
                AvatarMotionClip(storedGloss, frames, approved, fps)
            }
        }.getOrNull()
    }

    fun loadReview(gloss: String): SignerReview? {
        val file = reviewFileFor(gloss)
        if (!file.exists()) return null
        return runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                require(input.readInt() == REVIEW_FILE_VERSION)
                SignerReview(
                    reviewerName = input.readUTF(),
                    signLanguage = input.readUTF(),
                    reviewedAtEpochMillis = input.readLong(),
                    motionSha256 = input.readUTF(),
                    notes = input.readUTF(),
                )
            }
        }.getOrNull()
    }

    fun isSignerApproved(gloss: String): Boolean = load(gloss)?.signerValidated == true

    fun availableGlosses(validatedOnly: Boolean = true): Set<String> = (
        directory.listFiles { file -> file.extension == EXTENSION }.orEmpty().map { it.nameWithoutExtension } +
            context.assets.list(ASSET_DIRECTORY).orEmpty()
                .filter { it.endsWith(".$EXTENSION") }
                .map { it.substringBeforeLast('.') }
        )
        .distinct()
        .mapNotNull(::load)
        .filter { !validatedOnly || it.signerValidated }
        .mapTo(linkedSetOf()) { it.gloss }

    fun inventory(expectedGlosses: Collection<String> = emptyList()): List<MotionLibraryEntry> = (
        expectedGlosses + availableGlosses(validatedOnly = false)
        )
        .distinct()
        .map { gloss ->
            val clip = load(gloss)
            if (clip == null) {
                return@map MotionLibraryEntry(gloss = gloss, approved = false, available = false)
            }
            val review = loadReview(gloss)
            MotionLibraryEntry(
                gloss = clip.gloss,
                approved = clip.signerValidated,
                available = true,
                signLanguage = review?.signLanguage,
                reviewerName = review?.reviewerName,
            )
        }
        .sortedWith(
            compareBy<MotionLibraryEntry> {
                when {
                    it.available && !it.approved -> 0
                    !it.available -> 1
                    else -> 2
                }
            }.thenBy { it.gloss }
        )

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
        val safe = safeName(gloss)
        return File(directory, "$safe.$EXTENSION")
    }

    private fun reviewFileFor(gloss: String): File = File(directory, "${safeName(gloss)}.$REVIEW_EXTENSION")

    private fun safeName(gloss: String): String = gloss.lowercase().replace(Regex("[^a-z0-9_-]"), "_")

    private fun motionBytes(gloss: String): ByteArray? {
        val file = fileFor(gloss)
        return if (file.exists()) {
            runCatching { file.readBytes() }.getOrNull()
        } else {
            runCatching { context.assets.open("$ASSET_DIRECTORY/${file.name}").use { it.readBytes() } }.getOrNull()
        }
    }

    private fun motionDigest(gloss: String): String? = motionBytes(gloss)?.let(::sha256)

    private fun sha256(bytes: ByteArray): String = MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun writeReview(gloss: String, review: SignerReview) {
        val destination = reviewFileFor(gloss)
        val temporary = File(directory, destination.name + ".tmp")
        DataOutputStream(temporary.outputStream().buffered()).use { output ->
            output.writeInt(REVIEW_FILE_VERSION)
            output.writeUTF(review.reviewerName)
            output.writeUTF(review.signLanguage)
            output.writeLong(review.reviewedAtEpochMillis)
            output.writeUTF(review.motionSha256)
            output.writeUTF(review.notes)
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
        // Version 5 uses Alicia's camera-facing basis and converts MediaPipe's
        // downward screen Y axis into the avatar's upward world Y axis.
        const val FILE_VERSION = 5
        const val REVIEW_FILE_VERSION = 1
        const val EXTENSION = "niamotion"
        const val REVIEW_EXTENSION = "niareview"
        const val ASSET_DIRECTORY = "avatar_motions"
    }
}

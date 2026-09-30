package org.projectnia.app.avatar

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class MotionBundleImportResult(
    val imported: Int,
    val skippedExisting: Int,
)

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
            val decoded = decodeMotion(bytes)
            val approved = decoded.signerValidated && loadReview(decoded.gloss)?.motionSha256 == sha256(bytes)
            decoded.copy(signerValidated = approved)
        }.getOrNull()
    }

    fun loadReview(gloss: String): SignerReview? {
        val file = reviewFileFor(gloss)
        if (!file.exists()) return null
        return runCatching { decodeReview(file.readBytes()) }.getOrNull()
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

    fun exportApprovedBundle(output: OutputStream): Int {
        val approved = availableGlosses(validatedOnly = true).sorted()
        require(approved.isNotEmpty()) { "There are no approved motions to back up" }
        ZipOutputStream(output.buffered()).use { zip ->
            approved.forEach { gloss ->
                val motion = requireNotNull(motionBytes(gloss))
                val reviewFile = reviewFileFor(gloss)
                val review = requireNotNull(loadReview(gloss))
                require(reviewFile.exists() && review.motionSha256 == sha256(motion))
                val base = safeName(gloss)
                zip.putNextEntry(ZipEntry("$base.$EXTENSION"))
                zip.write(motion)
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("$base.$REVIEW_EXTENSION"))
                zip.write(reviewFile.readBytes())
                zip.closeEntry()
            }
        }
        return approved.size
    }

    fun importApprovedBundle(input: InputStream): MotionBundleImportResult {
        val entries = linkedMapOf<String, ByteArray>()
        var totalBytes = 0L
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && MotionBundlePolicy.isSafeEntryName(entry.name)) {
                    "Backup contains an unsupported file"
                }
                require(entries.size < MotionBundlePolicy.MAX_ENTRIES && entry.name !in entries) {
                    "Backup contains too many or duplicate files"
                }
                val bytes = readLimited(zip, MotionBundlePolicy.MAX_ENTRY_BYTES)
                totalBytes += bytes.size
                require(totalBytes <= MotionBundlePolicy.MAX_BUNDLE_BYTES) { "Backup is too large" }
                entries[entry.name] = bytes
                zip.closeEntry()
            }
        }
        MotionBundlePolicy.validateShape(entries.keys.toList())

        data class Verified(val gloss: String, val motion: ByteArray, val review: ByteArray)
        val verified = entries
            .filterKeys { it.endsWith(".$EXTENSION") }
            .map { (motionName, motionBytes) ->
                val base = motionName.removeSuffix(".$EXTENSION")
                val reviewBytes = requireNotNull(entries["$base.$REVIEW_EXTENSION"]) {
                    "Backup is missing the review for $base"
                }
                val clip = decodeMotion(motionBytes)
                require(clip.signerValidated && safeName(clip.gloss) == base) {
                    "Backup contains an invalid motion label"
                }
                val review = decodeReview(reviewBytes)
                require(review.motionSha256 == sha256(motionBytes)) {
                    "Backup review does not match the motion for ${clip.gloss}"
                }
                Verified(clip.gloss, motionBytes, reviewBytes)
            }
        check(entries.size == verified.size * 2)

        var imported = 0
        var skipped = 0
        val created = mutableListOf<Pair<File, File>>()
        try {
            verified.forEach { item ->
                val destination = fileFor(item.gloss)
                if (destination.exists()) {
                    skipped++
                } else {
                    val reviewDestination = reviewFileFor(item.gloss)
                    created += destination to reviewDestination
                    writeBytesAtomically(destination, item.motion)
                    writeBytesAtomically(reviewDestination, item.review)
                    check(load(item.gloss)?.signerValidated == true) { "Imported review verification failed" }
                    imported++
                }
            }
        } catch (error: Exception) {
            created.forEach { (motion, review) ->
                motion.delete()
                review.delete()
            }
            throw error
        }
        return MotionBundleImportResult(imported, skipped)
    }

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

    private fun decodeMotion(bytes: ByteArray): AvatarMotionClip =
        DataInputStream(ByteArrayInputStream(bytes).buffered()).use { input ->
            require(input.readInt() == FILE_VERSION)
            val storedGloss = input.readUTF()
            val validated = input.readBoolean()
            val fps = input.readInt()
            val frameCount = input.readInt()
            require(frameCount in 1..600)
            val frames = List(frameCount) {
                AvatarMotionFrame(
                    pose = readJoints(input, 33),
                    leftHand = readJoints(input, 21),
                    rightHand = readJoints(input, 21),
                    face = AvatarFacePose(input.readFloat(), input.readFloat(), input.readFloat()),
                )
            }
            require(input.read() == -1) { "Motion file has trailing data" }
            AvatarMotionClip(storedGloss, frames, validated, fps)
        }

    private fun decodeReview(bytes: ByteArray): SignerReview =
        DataInputStream(ByteArrayInputStream(bytes).buffered()).use { input ->
            require(input.readInt() == REVIEW_FILE_VERSION)
            val review = SignerReview(
                reviewerName = input.readUTF(),
                signLanguage = input.readUTF(),
                reviewedAtEpochMillis = input.readLong(),
                motionSha256 = input.readUTF(),
                notes = input.readUTF(),
            )
            require(input.read() == -1) { "Review file has trailing data" }
            review
        }

    private fun readLimited(input: InputStream, maximum: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            require(output.size() + read <= maximum) { "Backup entry is too large" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun writeBytesAtomically(destination: File, bytes: ByteArray) {
        val temporary = File(directory, destination.name + ".importing")
        temporary.outputStream().use { it.write(bytes) }
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

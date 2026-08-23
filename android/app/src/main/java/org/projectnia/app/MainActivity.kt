package org.projectnia.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import org.projectnia.app.agent.NiaAgentClient
import org.projectnia.app.avatar.AvatarMotionRetargeter
import org.projectnia.app.avatar.AvatarMotionStore
import org.projectnia.app.databinding.ActivityMainBinding
import org.projectnia.app.ml.ConfidenceGate
import org.projectnia.app.ml.LandmarkFrame
import org.projectnia.app.ml.ModelOutput
import org.projectnia.app.ml.NiaModel
import org.projectnia.app.ml.NiaVocabulary
import org.projectnia.app.ml.PersonalizationMemory
import org.projectnia.app.ml.PersonalizationStore
import org.projectnia.app.ml.V3Preprocessor
import org.projectnia.app.vision.HolisticFrameExtractor
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val networkExecutor = Executors.newSingleThreadExecutor()
    private val recordedFrames = mutableListOf<LandmarkFrame>()
    private val preprocessor = V3Preprocessor()
    private val confidenceGate = ConfidenceGate()

    private lateinit var frameExtractor: HolisticFrameExtractor
    private lateinit var model: NiaModel
    private lateinit var personalization: PersonalizationMemory
    private lateinit var personalizationStore: PersonalizationStore
    private lateinit var agentClient: NiaAgentClient
    private lateinit var avatarMotionStore: AvatarMotionStore

    @Volatile private var recording = false
    @Volatile private var lastOutput: ModelOutput? = null
    @Volatile private var lastCapturedFrames: List<LandmarkFrame> = emptyList()
    @Volatile private var lastRecognizedGloss: String? = null
    private val sessionId = UUID.randomUUID().toString()

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else binding.statusText.text = "Camera permission is required"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        frameExtractor = HolisticFrameExtractor(this)
        model = NiaModel(this)
        personalizationStore = PersonalizationStore(this)
        personalization = personalizationStore.load()
        agentClient = NiaAgentClient(BuildConfig.NIA_AGENT_BASE_URL)
        avatarMotionStore = AvatarMotionStore(this)

        binding.captureButton.setOnClickListener {
            if (recording) stopAndRecognize() else startRecording()
        }
        binding.teachButton.setOnClickListener { showCorrectionDialog() }
        binding.cameraModeButton.setOnClickListener { showCameraStage() }
        binding.avatarModeButton.setOnClickListener { previewLastAvatarMotion() }
        binding.validateMotionButton.setOnClickListener { confirmSignerValidation() }
        binding.signMessageButton.setOnClickListener { requestSignedMessage() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = binding.preview.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { useCase ->
                    useCase.setAnalyzer(cameraExecutor) { image ->
                        try {
                            val frame = frameExtractor.extract(image.toBitmap(), image.imageInfo.rotationDegrees)
                            if (recording) {
                                synchronized(recordedFrames) {
                                    if (recordedFrames.size < 240) recordedFrames += frame
                                    if (recordedFrames.size % 10 == 0) {
                                        runOnUiThread { binding.statusText.text = "Recording... ${recordedFrames.size} frames" }
                                    }
                                }
                            }
                        } catch (error: Exception) {
                            runOnUiThread { binding.statusText.text = "Landmark error: ${error.message}" }
                        } finally {
                            image.close()
                        }
                    }
                }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
            binding.statusText.text = "Ready - record one isolated sign"
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startRecording() {
        showCameraStage()
        synchronized(recordedFrames) { recordedFrames.clear() }
        lastOutput = null
        lastCapturedFrames = emptyList()
        lastRecognizedGloss = null
        recording = true
        binding.captureButton.text = "Stop and recognize"
        binding.teachButton.visibility = View.GONE
        binding.predictionText.text = "..."
        binding.confidenceText.text = "Keep your upper body and hands visible"
    }

    private fun stopAndRecognize() {
        recording = false
        binding.captureButton.isEnabled = false
        binding.captureButton.text = "Processing..."
        val frames = synchronized(recordedFrames) { recordedFrames.toList() }
        lastCapturedFrames = frames
        if (frames.size < 6) {
            showReady("Record at least six landmark frames")
            return
        }

        cameraExecutor.execute {
            try {
                val features = preprocessor.process(frames)
                val output = model.infer(features)
                lastOutput = output
                val probabilities = if (personalization.hasAny()) {
                    personalization.apply(output.generalProbabilities, output.embedding)
                } else output.generalProbabilities
                val recognition = confidenceGate.decide(probabilities)
                runOnUiThread {
                    if (recognition.needsClarification) {
                        binding.predictionText.text = "Not sure"
                        binding.statusText.text = "Please repeat the isolated sign"
                    } else {
                        val label = requireNotNull(recognition.label)
                        binding.predictionText.text = label.uppercase(Locale.ROOT)
                        binding.statusText.text = if (personalization.hasAny()) "Recognized with local personalization" else "Recognized offline"
                        saveAvatarDraft(label, frames)
                        sendToAgent(label, recognition.confidence, recognition.margin)
                    }
                    binding.confidenceText.text = String.format(
                        Locale.ROOT,
                        "Top guess: %s | confidence %.1f%% | margin %.1f%%",
                        recognition.topLabel,
                        recognition.confidence * 100f,
                        recognition.margin * 100f,
                    )
                    binding.teachButton.visibility = View.VISIBLE
                    binding.validateMotionButton.visibility = if (lastRecognizedGloss != null) View.VISIBLE else View.GONE
                    binding.captureButton.isEnabled = true
                    binding.captureButton.text = "Record another sign"
                }
            } catch (error: Exception) {
                runOnUiThread { showReady("Inference error: ${error.message}") }
            }
        }
    }

    private fun showCorrectionDialog() {
        val output = lastOutput ?: return
        AlertDialog.Builder(this)
            .setTitle("What sign did you make?")
            .setItems(NiaVocabulary.labels.toTypedArray()) { _, classId ->
                val correctedGloss = NiaVocabulary.labels[classId]
                val saved = personalization.addCorrection(classId, output.embedding)
                if (saved) personalizationStore.save(personalization)
                val count = personalization.count(classId)
                lastRecognizedGloss?.takeIf { it != correctedGloss }?.let(avatarMotionStore::discardDraft)
                saveAvatarDraft(correctedGloss, lastCapturedFrames)
                binding.statusText.text = if (saved) {
                    "Saved locally: $correctedGloss ($count/${PersonalizationMemory.SHOTS_PER_SIGN})"
                } else {
                    "$correctedGloss already has ${PersonalizationMemory.SHOTS_PER_SIGN} examples"
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sendToAgent(label: String, confidence: Float, margin: Float) {
        if (BuildConfig.NIA_AGENT_BASE_URL.isBlank()) return
        networkExecutor.execute {
            val playableGlosses = avatarMotionStore.availableGlosses(validatedOnly = true)
            val reply = runCatching {
                agentClient.interpret(label, confidence, margin, sessionId, playableGlosses)
            }.getOrNull()
            if (reply != null) runOnUiThread {
                binding.statusText.text = reply.text
                val clips = reply.signGlosses.mapNotNull(avatarMotionStore::load)
                    .filter { it.signerValidated }
                if (clips.isNotEmpty()) {
                    binding.avatarView.play(clips)
                    showAvatarStage("3D signing: ${reply.signGlosses.joinToString(" ")}")
                }
            }
        }
    }

    private fun requestSignedMessage() {
        val message = binding.hearingMessageInput.text.toString().trim()
        if (message.isBlank()) {
            binding.statusText.text = "Enter a message for the 3D signer"
            return
        }
        if (BuildConfig.NIA_AGENT_BASE_URL.isBlank()) {
            binding.statusText.text = "Connect the Nia agent service before translating a message"
            return
        }
        val vocabulary = avatarMotionStore.availableGlosses(validatedOnly = true)
        if (vocabulary.isEmpty()) {
            binding.statusText.text = "Signer-validate at least one avatar motion first"
            return
        }
        binding.signMessageButton.isEnabled = false
        binding.statusText.text = "Preparing signer-safe 3D motion..."
        networkExecutor.execute {
            val reply = runCatching {
                agentClient.signMessage(message, sessionId, vocabulary)
            }.getOrNull()
            runOnUiThread {
                binding.signMessageButton.isEnabled = true
                if (reply == null) {
                    binding.statusText.text = "Could not reach the Nia agent"
                    return@runOnUiThread
                }
                val clips = reply.signGlosses.mapNotNull(avatarMotionStore::load)
                    .filter { it.signerValidated }
                if (clips.isEmpty()) {
                    binding.statusText.text = "The validated avatar vocabulary cannot express that message yet"
                } else {
                    binding.avatarView.play(clips)
                    showAvatarStage("3D signing: ${reply.signGlosses.joinToString(" ")}")
                    binding.statusText.text = reply.text
                }
            }
        }
    }

    private fun saveAvatarDraft(gloss: String, frames: List<LandmarkFrame>) {
        if (frames.size < 6) return
        if (avatarMotionStore.load(gloss)?.signerValidated == true) {
            lastRecognizedGloss = gloss
            return
        }
        runCatching {
            avatarMotionStore.saveDraft(AvatarMotionRetargeter.fromLandmarks(gloss, frames))
            lastRecognizedGloss = gloss
        }
    }

    private fun previewLastAvatarMotion() {
        val gloss = lastRecognizedGloss
        val clip = gloss?.let(avatarMotionStore::load)
        if (clip == null) {
            showAvatarStage("No avatar motion captured yet. Record one isolated sign first.")
            binding.avatarView.clearMotion()
            return
        }
        binding.avatarView.play(listOf(clip))
        val status = if (clip.signerValidated) "signer-validated" else "draft - validation required"
        showAvatarStage("${clip.gloss.uppercase(Locale.ROOT)} | $status")
    }

    private fun confirmSignerValidation() {
        val gloss = lastRecognizedGloss ?: return
        AlertDialog.Builder(this)
            .setTitle("Validate this sign motion?")
            .setMessage(
                "Only continue if a fluent signer has watched the full 3D motion and confirms " +
                    "that it accurately communicates '$gloss'. This approval controls whether Nia may use it in replies."
            )
            .setPositiveButton("Signer confirms") { _, _ ->
                if (avatarMotionStore.markSignerValidated(gloss)) {
                    binding.statusText.text = "$gloss is approved for 3D replies"
                    previewLastAvatarMotion()
                }
            }
            .setNegativeButton("Keep as draft", null)
            .show()
    }

    private fun showCameraStage() {
        binding.preview.visibility = View.VISIBLE
        binding.avatarView.visibility = View.GONE
        binding.avatarCaptionText.visibility = View.GONE
    }

    private fun showAvatarStage(caption: String) {
        binding.preview.visibility = View.GONE
        binding.avatarView.visibility = View.VISIBLE
        binding.avatarCaptionText.text = caption
        binding.avatarCaptionText.visibility = View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) binding.avatarView.onResume()
    }

    override fun onPause() {
        if (::binding.isInitialized) binding.avatarView.onPause()
        super.onPause()
    }

    private fun showReady(message: String) {
        binding.statusText.text = message
        binding.captureButton.isEnabled = true
        binding.captureButton.text = "Start recording"
    }

    override fun onDestroy() {
        recording = false
        frameExtractor.close()
        model.close()
        cameraExecutor.shutdown()
        networkExecutor.shutdown()
        super.onDestroy()
    }
}

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

    @Volatile private var recording = false
    @Volatile private var lastOutput: ModelOutput? = null
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

        binding.captureButton.setOnClickListener {
            if (recording) stopAndRecognize() else startRecording()
        }
        binding.teachButton.setOnClickListener { showCorrectionDialog() }

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
                                        runOnUiThread { binding.statusText.text = "Recording… ${recordedFrames.size} frames" }
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
            binding.statusText.text = "Ready — record one isolated sign"
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startRecording() {
        synchronized(recordedFrames) { recordedFrames.clear() }
        lastOutput = null
        recording = true
        binding.captureButton.text = "Stop and recognize"
        binding.teachButton.visibility = View.GONE
        binding.predictionText.text = "…"
        binding.confidenceText.text = "Keep your upper body and hands visible"
    }

    private fun stopAndRecognize() {
        recording = false
        binding.captureButton.isEnabled = false
        binding.captureButton.text = "Processing…"
        val frames = synchronized(recordedFrames) { recordedFrames.toList() }
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
                        binding.predictionText.text = recognition.label!!.uppercase(Locale.ROOT)
                        binding.statusText.text = if (personalization.hasAny()) "Recognized with local personalization" else "Recognized offline"
                        sendToAgent(recognition.label, recognition.confidence, recognition.margin)
                    }
                    binding.confidenceText.text = String.format(
                        Locale.ROOT,
                        "Top guess: %s · confidence %.1f%% · margin %.1f%%",
                        recognition.topLabel,
                        recognition.confidence * 100f,
                        recognition.margin * 100f,
                    )
                    binding.teachButton.visibility = View.VISIBLE
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
                val saved = personalization.addCorrection(classId, output.embedding)
                if (saved) personalizationStore.save(personalization)
                val count = personalization.count(classId)
                binding.statusText.text = if (saved) {
                    "Saved locally: ${NiaVocabulary.labels[classId]} ($count/${PersonalizationMemory.SHOTS_PER_SIGN})"
                } else {
                    "${NiaVocabulary.labels[classId]} already has ${PersonalizationMemory.SHOTS_PER_SIGN} examples"
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sendToAgent(label: String, confidence: Float, margin: Float) {
        if (BuildConfig.NIA_AGENT_BASE_URL.isBlank()) return
        networkExecutor.execute {
            val reply = runCatching { agentClient.interpret(label, confidence, margin, sessionId) }.getOrNull()
            if (reply != null) runOnUiThread { binding.statusText.text = reply.text }
        }
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

package org.projectnia.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
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
import org.projectnia.app.avatar.MotionLibraryAction
import org.projectnia.app.avatar.MotionLibraryPresenter
import org.projectnia.app.avatar.SignedMessagePlanner
import org.projectnia.app.databinding.ActivityMainBinding
import org.projectnia.app.ml.ConfidenceGate
import org.projectnia.app.ml.CaptureQualityGate
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

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private lateinit var binding: ActivityMainBinding
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val networkExecutor = Executors.newSingleThreadExecutor()
    private val recordedFrames = mutableListOf<LandmarkFrame>()
    private val preprocessor = V3Preprocessor()
    private val confidenceGate = ConfidenceGate()
    private val captureQualityGate = CaptureQualityGate()

    private lateinit var frameExtractor: HolisticFrameExtractor
    private lateinit var model: NiaModel
    private lateinit var personalization: PersonalizationMemory
    private lateinit var personalizationStore: PersonalizationStore
    private lateinit var agentClient: NiaAgentClient
    private lateinit var avatarMotionStore: AvatarMotionStore
    private var textToSpeech: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeechReady = false

    @Volatile private var recording = false
    @Volatile private var lastOutput: ModelOutput? = null
    @Volatile private var lastCapturedFrames: List<LandmarkFrame> = emptyList()
    @Volatile private var lastRecognizedGloss: String? = null
    @Volatile private var commissioningGloss: String? = null
    private val sessionId = UUID.randomUUID().toString()

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else binding.statusText.text = "Camera permission is required"
    }

    private val requestMicrophone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startVoiceInput() else binding.statusText.text = "Microphone permission is required for spoken replies"
    }

    private val createMotionBackup = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        binding.statusText.text = "Saving approved avatar motions..."
        networkExecutor.execute {
            val result = runCatching {
                contentResolver.openOutputStream(uri)?.use(avatarMotionStore::exportApprovedBundle)
                    ?: error("Could not open the selected file")
            }
            runOnUiThread {
                binding.statusText.text = result.fold(
                    onSuccess = { count -> "Backed up $count approved avatar motion(s)" },
                    onFailure = { error -> "Backup failed: ${error.message ?: "unknown error"}" },
                )
            }
        }
    }

    private val openMotionBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        binding.statusText.text = "Checking avatar-motion backup..."
        networkExecutor.execute {
            val result = runCatching {
                contentResolver.openInputStream(uri)?.use(avatarMotionStore::importApprovedBundle)
                    ?: error("Could not open the selected file")
            }
            runOnUiThread {
                result.fold(
                    onSuccess = { imported ->
                        refreshMotionLibraryStatus()
                        binding.statusText.text =
                            "Restored ${imported.imported} approved motion(s); " +
                            "kept ${imported.skippedExisting} existing local motion(s) unchanged"
                    },
                    onFailure = { error ->
                        binding.statusText.text = "Restore rejected: ${error.message ?: "invalid backup"}"
                    },
                )
            }
        }
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
        textToSpeech = TextToSpeech(this, this)
        initializeSpeechRecognizer()

        binding.captureButton.setOnClickListener {
            if (recording) stopAndRecognize() else startRecording(commissioningGloss)
        }
        binding.teachButton.setOnClickListener { showCorrectionDialog() }
        binding.cameraModeButton.setOnClickListener { showCameraStage() }
        binding.avatarModeButton.setOnClickListener { previewLastAvatarMotion() }
        binding.validateMotionButton.setOnClickListener { confirmSignerValidation() }
        binding.reviewMotionsButton.setOnClickListener { showMotionLibrary() }
        binding.backupMotionsButton.setOnClickListener { showMotionBackupMenu() }
        binding.signMessageButton.setOnClickListener { requestSignedMessage() }
        binding.talkButton.setOnClickListener { requestVoiceInput() }
        refreshMotionLibraryStatus()

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
                                        val target = commissioningGloss?.uppercase(Locale.ROOT)?.let { "$it | " }.orEmpty()
                                        runOnUiThread {
                                            binding.statusText.text = "${target}Recording... ${recordedFrames.size} frames"
                                        }
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

    private fun startRecording(targetGloss: String? = null) {
        commissioningGloss = targetGloss
        showCameraStage()
        synchronized(recordedFrames) { recordedFrames.clear() }
        lastOutput = null
        lastCapturedFrames = emptyList()
        lastRecognizedGloss = null
        recording = true
        binding.captureButton.text = if (targetGloss == null) {
            "Stop and recognize"
        } else {
            "Stop recording ${targetGloss.uppercase(Locale.ROOT)}"
        }
        binding.teachButton.visibility = View.GONE
        binding.validateMotionButton.visibility = View.GONE
        binding.predictionText.text = targetGloss?.uppercase(Locale.ROOT) ?: "..."
        binding.statusText.text = if (targetGloss == null) {
            "Perform one isolated sign"
        } else {
            "Commissioning $targetGloss: perform that sign naturally"
        }
        binding.confidenceText.text = "Keep your upper body, hands, and face visible"
    }

    private fun stopAndRecognize() {
        recording = false
        binding.captureButton.isEnabled = false
        binding.captureButton.text = "Processing..."
        val frames = synchronized(recordedFrames) { recordedFrames.toList() }
        lastCapturedFrames = frames
        val quality = captureQualityGate.evaluate(frames)
        if (!quality.accepted) {
            binding.confidenceText.text = String.format(
                Locale.ROOT,
                "Hand visible %.0f%% | shoulders visible %.0f%%",
                quality.handVisibleRatio * 100f,
                quality.shouldersVisibleRatio * 100f,
            )
            showReady(quality.message)
            return
        }

        val targetGloss = commissioningGloss
        if (targetGloss != null) {
            cameraExecutor.execute {
                try {
                    val clip = AvatarMotionRetargeter.fromLandmarks(targetGloss, frames)
                    avatarMotionStore.saveDraft(clip)
                    runOnUiThread {
                        commissioningGloss = null
                        lastRecognizedGloss = targetGloss
                        binding.predictionText.text = targetGloss.uppercase(Locale.ROOT)
                        binding.statusText.text =
                            "Draft captured. Preview the full motion; a fluent signer must approve it."
                        binding.confidenceText.text = String.format(
                            Locale.ROOT,
                            "Capture accepted | hand visible %.0f%% | shoulders visible %.0f%%",
                            quality.handVisibleRatio * 100f,
                            quality.shouldersVisibleRatio * 100f,
                        )
                        binding.avatarView.play(listOf(clip))
                        showAvatarStage("${targetGloss.uppercase(Locale.ROOT)} | DRAFT - not available for replies")
                        binding.validateMotionButton.visibility = View.VISIBLE
                        binding.captureButton.isEnabled = true
                        binding.captureButton.text = "Record another sign"
                        refreshMotionLibraryStatus()
                    }
                } catch (error: Exception) {
                    runOnUiThread { showReady("Motion capture error: ${error.message}") }
                }
            }
            return
        }

        cameraExecutor.execute {
            try {
                val features = preprocessor.process(frames)
                val output = model.infer(features)
                lastOutput = output
                val personalizationReady = personalization.isFrozenProtocolComplete()
                val probabilities = if (personalizationReady) {
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
                        binding.statusText.text = if (personalizationReady) "Recognized with frozen-protocol personalization" else "Recognized offline"
                        speakEnglish(label)
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
                    binding.validateMotionButton.visibility = if (
                        lastRecognizedGloss?.let { avatarMotionStore.load(it)?.signerValidated == false } == true
                    ) View.VISIBLE else View.GONE
                    refreshMotionLibraryStatus()
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
                refreshMotionLibraryStatus()
                binding.statusText.text = if (saved) {
                    "Saved calibration: $correctedGloss ($count/${PersonalizationMemory.SHOTS_PER_SIGN}). " +
                        "Personalization activates after all 32 signs are calibrated."
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
        val previewVocabulary = avatarMotionStore.availableGlosses(validatedOnly = true)
        val localPlan = SignedMessagePlanner.plan(message, previewVocabulary)
        if (localPlan.isPlayable) {
            val clips = localPlan.glosses.mapNotNull { gloss ->
                avatarMotionStore.load(gloss)?.takeIf { it.signerValidated }
            }
            if (clips.size == localPlan.glosses.size) {
                binding.avatarView.play(clips)
                showAvatarStage("Sign preview: ${localPlan.glosses.joinToString(" ").uppercase(Locale.ROOT)}")
                binding.statusText.text = if (localPlan.usesFingerspelling) {
                    "Signing locally with verified signs and fingerspelling"
                } else {
                    "Signing locally with signer-validated motion"
                }
                return
            }
        }
        if (BuildConfig.NIA_AGENT_BASE_URL.isBlank()) {
            val missing = localPlan.unsupportedWords.joinToString(", ")
            binding.statusText.text = if (missing.isBlank()) {
                "That message is not in Nia's verified avatar vocabulary yet"
            } else {
                "No verified sign or complete fingerspelling motion for: $missing"
            }
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

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            binding.statusText.text = "English speech output is unavailable on this phone"
            return
        }
        val result = textToSpeech?.setLanguage(Locale.ENGLISH) ?: TextToSpeech.LANG_NOT_SUPPORTED
        textToSpeechReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        if (!textToSpeechReady) binding.statusText.text = "Install an English text-to-speech voice to hear translations"
    }

    private fun speakEnglish(gloss: String) {
        if (!textToSpeechReady) return
        val spokenMeaning = gloss.replace('_', ' ').trim()
        textToSpeech?.speak(spokenMeaning, TextToSpeech.QUEUE_FLUSH, null, "nia-sign-result")
    }

    private fun requestVoiceInput() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startVoiceInput()
        } else {
            requestMicrophone.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun initializeSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            binding.talkButton.isEnabled = false
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).also { recognizer ->
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    binding.statusText.text = "Listening to the hearing person..."
                }

                override fun onBeginningOfSpeech() {
                    binding.statusText.text = "Listening..."
                }

                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    binding.statusText.text = "Turning speech into text..."
                }

                override fun onError(error: Int) {
                    binding.talkButton.isEnabled = true
                    binding.statusText.text = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "I could not understand that. Tap Talk and try again."
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard. Tap Talk and try again."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required"
                        else -> "Voice input failed. You can still type the message."
                    }
                }

                override fun onResults(results: Bundle?) {
                    binding.talkButton.isEnabled = true
                    val message = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    if (message.isBlank()) {
                        binding.statusText.text = "I could not understand that. You can type the message."
                    } else {
                        binding.hearingMessageInput.setText(message)
                        binding.statusText.text = "Speech captured. Check the message, then tap Sign it."
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
        }
    }

    private fun startVoiceInput() {
        val recognizer = speechRecognizer
        if (recognizer == null) {
            binding.statusText.text = "Speech recognition is unavailable. Please type the message."
            return
        }
        binding.talkButton.isEnabled = false
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.ENGLISH.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        recognizer.startListening(intent)
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
            refreshMotionLibraryStatus()
        }
    }

    private fun refreshMotionLibraryStatus() {
        val summary = MotionLibraryPresenter.summarize(avatarMotionStore.inventory(NiaVocabulary.labels))
        binding.motionLibraryText.text = summary.displayText()
    }

    private fun showMotionLibrary() {
        val entries = avatarMotionStore.inventory(NiaVocabulary.labels)
        refreshMotionLibraryStatus()
        if (entries.isEmpty()) {
            binding.statusText.text = "No avatar motions yet. Record an isolated sign to create a review draft."
            return
        }
        val labels = entries.map(MotionLibraryPresenter::label).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Avatar motion library")
            .setItems(labels) { _, index ->
                val selected = entries[index]
                when (MotionLibraryPresenter.actionFor(selected)) {
                    MotionLibraryAction.PREVIEW_APPROVED -> {
                        lastRecognizedGloss = selected.gloss
                        binding.hearingMessageInput.setText(selected.gloss)
                        previewLastAvatarMotion()
                    }
                    MotionLibraryAction.REVIEW_DRAFT -> showDraftActions(selected.gloss)
                    MotionLibraryAction.START_CAPTURE -> confirmCommissioningCapture(selected.gloss)
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showDraftActions(gloss: String) {
        AlertDialog.Builder(this)
            .setTitle("${gloss.uppercase(Locale.ROOT)} draft")
            .setItems(arrayOf("Preview for signer review", "Record a replacement")) { _, action ->
                if (action == 0) {
                    lastRecognizedGloss = gloss
                    binding.hearingMessageInput.setText(gloss)
                    previewLastAvatarMotion()
                } else {
                    confirmCommissioningCapture(gloss)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmCommissioningCapture(gloss: String) {
        AlertDialog.Builder(this)
            .setTitle("Record ${gloss.uppercase(Locale.ROOT)}")
            .setMessage(
                "This creates an avatar-motion draft for '$gloss'. The person recording must know the intended sign. " +
                    "Nia will not use the draft in replies until a fluent signer reviews the rendered avatar."
            )
            .setPositiveButton("Start recording") { _, _ -> startRecording(gloss) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMotionBackupMenu() {
        AlertDialog.Builder(this)
            .setTitle("Approved motion backup")
            .setItems(arrayOf("Export approved motions", "Restore approved motions")) { _, action ->
                if (action == 0) confirmMotionExport() else confirmMotionImport()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmMotionExport() {
        val approved = avatarMotionStore.availableGlosses(validatedOnly = true).size
        if (approved == 0) {
            binding.statusText.text = "There are no signer-approved motions to back up yet"
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Export $approved approved motion(s)?")
            .setMessage(
                "The backup contains motion data plus signer names, sign languages, review times, and review notes. " +
                    "Store it securely and share it only with the reviewer's permission."
            )
            .setPositiveButton("Choose save location") { _, _ ->
                createMotionBackup.launch("project_nia_approved_avatar_motions.zip")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmMotionImport() {
        AlertDialog.Builder(this)
            .setTitle("Restore approved motions?")
            .setMessage(
                "Nia will verify every motion against its signer-review hash. Existing local motions will not be overwritten."
            )
            .setPositiveButton("Choose backup") { _, _ ->
                openMotionBackup.launch(arrayOf("application/zip", "application/octet-stream"))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun previewLastAvatarMotion() {
        val typedGloss = binding.hearingMessageInput.text.toString()
            .trim()
            .lowercase(Locale.ROOT)
            .takeIf { it.matches(Regex("[a-z0-9_]+")) }
        val gloss = lastRecognizedGloss ?: typedGloss
        val clip = gloss?.let(avatarMotionStore::load)
        if (clip == null) {
            showAvatarStage("No reviewable motion found. Record a sign or type one exact gloss first.")
            binding.avatarView.clearMotion()
            return
        }
        lastRecognizedGloss = gloss
        binding.validateMotionButton.visibility = if (clip.signerValidated) View.GONE else View.VISIBLE
        binding.avatarView.play(listOf(clip))
        val review = avatarMotionStore.loadReview(gloss)
        val status = if (clip.signerValidated && review != null) {
            "${review.signLanguage} reviewed by ${review.reviewerName}"
        } else {
            "draft - fluent signer review required"
        }
        showAvatarStage("${clip.gloss.uppercase(Locale.ROOT)} | $status")
    }

    private fun confirmSignerValidation() {
        val gloss = lastRecognizedGloss ?: return
        val reviewerInput = EditText(this).apply {
            hint = "Fluent signer's name"
            contentDescription = hint
        }
        val languageInput = EditText(this).apply {
            hint = "Sign language, for example ASL or USL"
            contentDescription = hint
        }
        val notesInput = EditText(this).apply {
            hint = "Review notes (optional)"
            contentDescription = hint
            maxLines = 3
        }
        val padding = (20 * resources.displayMetrics.density).toInt()
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, 0, padding, 0)
            addView(reviewerInput)
            addView(languageInput)
            addView(notesInput)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Validate this sign motion?")
            .setMessage(
                "Only continue if a fluent signer has watched the full 3D motion and confirms " +
                    "that it accurately communicates '$gloss'. The review will be tied to this exact motion file."
            )
            .setView(form)
            .setPositiveButton("Signer confirms", null)
            .setNegativeButton("Keep as draft", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val reviewer = reviewerInput.text.toString().trim()
                val language = languageInput.text.toString().trim()
                when {
                    reviewer.length < 2 -> reviewerInput.error = "Enter the fluent signer's name"
                    language.length < 2 -> languageInput.error = "Enter the sign language reviewed"
                    avatarMotionStore.markSignerValidated(
                        gloss = gloss,
                        reviewerName = reviewer,
                        signLanguage = language,
                        notes = notesInput.text.toString(),
                    ) -> {
                        binding.statusText.text = "$gloss approved for $language replies by $reviewer"
                        refreshMotionLibraryStatus()
                        dialog.dismiss()
                        previewLastAvatarMotion()
                    }
                    else -> binding.statusText.text = "Could not save the signer review"
                }
            }
        }
        dialog.show()
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
        speechRecognizer?.destroy()
        speechRecognizer = null
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        frameExtractor.close()
        model.close()
        cameraExecutor.shutdown()
        networkExecutor.shutdown()
        super.onDestroy()
    }
}

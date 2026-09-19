package com.example.mushroomexposed

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Size
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.mushroomexposed.databinding.ActivityMainBinding
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Field mode: the live preview guides the user (frame, quality hints), a single
 * shutter press freezes the frame, analyses that one frame and leaves the result
 * on screen until the next press.
 */
class MainActivity : AppCompatActivity() {

    /** One line of assets/labels.txt: "Deutscher Name|essbar|giftig|unbekannt|Sci_Name". */
    private data class Species(
        val name: String,
        val verdict: String,
        val scientific: String,
    )

    private lateinit var binding: ActivityMainBinding
    private lateinit var previewView: PreviewView
    private var interpreter: Interpreter? = null
    private var labels: List<Species> = emptyList()
    private var lookalikes: Map<String, List<Lookalike>> = emptyMap()
    /** Einmal aus den Labels berechnet, nicht pro Inferenz. */
    private var riskyGenera: Set<String> = emptySet()
    private var inputW = 224
    private var inputH = 224
    private var torchOn = false
    private var frozen: Bitmap? = null
    private val fieldMode = FieldModeMachine()

    private val inferenceExecutor = Executors.newSingleThreadExecutor()
    private val qualityExecutor = Executors.newSingleThreadExecutor()
    private val history by lazy { HistoryStore(File(filesDir, "history")) }
    private val clock = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.GERMANY)

    private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    private val CAMERA_REQUEST_CODE = 10

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        previewView = binding.previewView

        loadModel()
        wireControls()

        if (allPermissionsGranted()) startCamera() else requestPermissions()
    }

    private fun loadModel() {
        try {
            interpreter = Interpreter(loadModelFile())
            labels = loadLabels()
            lookalikes = LookalikeData.load(assets, labels.associate { it.scientific to it.name })
            riskyGenera = ToxicGenus.riskyGenera(labels.associate { it.scientific to it.verdict })
            val shape = interpreter!!.getInputTensor(0).shape()
            inputH = shape[1]
            inputW = shape[2]
            val modelClasses = interpreter!!.getOutputTensor(0).shape().lastOrNull()
                ?: throw IllegalStateException("Model output tensor has no class dimension.")
            ModelContract.requireMatchingClassCount(modelClasses, labels.size)
            binding.resultHeadline.text = getString(R.string.ready, labels.size)

            val curated = lookalikes.values.sumOf { it.size }
            if (curated == 0) {
                binding.warningText.text = getString(R.string.no_lookalikes)
            } else {
                binding.warningText.text = getString(R.string.lookalike_count, curated)
            }
        } catch (e: Exception) {
            binding.resultHeadline.text = getString(R.string.model_failed, e.message)
            e.printStackTrace()
        }
    }

    private fun wireControls() {
        binding.shutterButton.setOnClickListener { onShutter() }
        binding.torchButton.setOnClickListener { toggleTorch() }
        binding.historyButton.setOnClickListener { showHistory() }
        binding.closeHistoryButton.setOnClickListener {
            binding.historyPanel.visibility = View.GONE
        }
        binding.clearHistoryButton.setOnClickListener {
            history.clear()
            renderHistory()
        }
        previewView.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                focusAt(view, event.x, event.y)
            }
            true
        }
    }

    /** Route the shutter press through the field-mode state machine. */
    private fun onShutter() {
        when (fieldMode.onPrimaryAction()) {
            FieldModeAction.CAPTURE -> captureAndAnalyse()
            FieldModeAction.RETURN_TO_LIVE -> showLiveMode()
            FieldModeAction.IGNORE -> Unit
        }
    }

    private fun captureAndAnalyse() {
        if (interpreter == null) {
            fieldMode.onCaptureUnavailable()
            toast(getString(R.string.model_missing))
            return
        }
        val bitmap = previewView.bitmap
        if (bitmap == null) {
            fieldMode.onCaptureUnavailable()
            toast(getString(R.string.frame_missing))
            return
        }
        showAnalysingMode(bitmap)
        analyse(bitmap)
    }

    private fun showAnalysingMode(bitmap: Bitmap) {
        frozen = bitmap
        binding.frozenImage.setImageBitmap(bitmap)
        binding.frozenImage.visibility = View.VISIBLE
        binding.targetFrame.visibility = View.GONE
        binding.qualityHint.text = ""
        binding.qualityHint.visibility = View.GONE
        binding.resultHeadline.setText(R.string.analysing)
        binding.resultHeadline.setTextColor(0xFFFFFFFF.toInt())
        binding.resultSubline.visibility = View.GONE
        binding.resultTops.visibility = View.GONE
        binding.warningText.visibility = View.GONE
        binding.emergencyText.visibility = View.GONE
        binding.shutterButton.setText(R.string.analysing)
        binding.shutterButton.isEnabled = false
    }

    private fun analyse(bitmap: Bitmap) {
        val interpreter = interpreter ?: return
        val crop = centreSquare(bitmap)
        inferenceExecutor.execute {
            try {
                val scaled = Bitmap.createScaledBitmap(crop, inputW, inputH, true)
                val pixels = IntArray(inputW * inputH)
                scaled.getPixels(pixels, 0, inputW, 0, 0, inputW, inputH)
                val buffer = ByteBuffer.allocateDirect(inputW * inputH * 3 * 4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                for (p in pixels) {
                    buffer.putFloat(((p shr 16) and 0xFF) / 255f)
                    buffer.putFloat(((p shr 8) and 0xFF) / 255f)
                    buffer.putFloat((p and 0xFF) / 255f)
                }
                buffer.rewind()

                val output = Array(1) { FloatArray(labels.size.coerceAtLeast(1)) }
                interpreter.run(buffer, output)
                val probs = softmax(output[0])
                val top = probs.indices.sortedByDescending { probs[it] }.take(3)

                val ranked = top.mapNotNull { index ->
                    labels.getOrNull(index)?.let { species ->
                        RankedSpecies(species.name, species.scientific, species.verdict, probs[index])
                    }
                }
                val bestIndex = top.firstOrNull()
                val best = bestIndex?.let { labels.getOrNull(it) }
                val bestProbability = bestIndex?.let { probs[it] } ?: 0f
                val hint = best?.let { lookalikes[it.scientific]?.firstOrNull() }
                // Liegt eine giftige Art in der kurzen Trefferliste, wird eine
                // "essbar"-Freigabe zurueckgestuft. Messbefund 19.09.2026: das
                // Modell gibt giftige Arten als essbar frei, die Konfidenz
                // verraet das nicht -- die giftige Alternative in den Top-3 schon.
                val toxicAlternative = ranked.firstOrNull { it.verdict == "giftig" }
                // Zweites, breiteres Signal: die Gattung. Die schlimmsten
                // Fehlfreigaben fuehren Knollenblaetterpilze in den Top-3, aber
                // als *essbare* (Amanita_ceciliae) -- das Verdict sieht das
                // nicht, die Gattung schon. Nur Hinweis, keine Blockade: als
                // Blockade gemessen kostet die Regel die Haelfte der Freigaben.
                val toxicGenus = ToxicGenus.firstRisky(
                    ranked.map { it.scientific },
                    riskyGenera,
                )
                val decision = VerdictPolicy.decide(
                    best?.verdict,
                    bestProbability,
                    hint,
                    toxicAlternative?.germanName,
                    toxicGenus,
                )
                val view = ResultFormatter.format(ranked, decision)

                runOnUiThread {
                    fieldMode.onAnalysisFinished()
                    render(view)
                    recordHistory(best, bestProbability, decision, hint != null)
                    showFrozenControls()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    fieldMode.onAnalysisFinished()
                    binding.resultHeadline.text = getString(R.string.inference_failed, e.message)
                    showFrozenControls()
                }
            }
        }
    }

    private fun render(view: ResultView) {
        binding.resultHeadline.text = view.headline
        binding.resultHeadline.setTextColor(
            when (view.tone) {
                VerdictTone.DANGER -> 0xFFFF5252.toInt()
                VerdictTone.SAFE -> 0xFF69F0AE.toInt()
                VerdictTone.CAUTION -> 0xFFFFD54F.toInt()
            }
        )
        binding.resultSubline.text = view.subline
        binding.resultSubline.visibility = if (view.subline.isBlank()) View.GONE else View.VISIBLE
        binding.resultTops.text = view.topLines.joinToString("\n")
        binding.resultTops.visibility = if (view.topLines.isEmpty()) View.GONE else View.VISIBLE
        binding.warningText.text = view.warning
        binding.warningText.visibility = if (view.warning.isBlank()) View.GONE else View.VISIBLE
        binding.emergencyText.text = view.emergency.orEmpty()
        binding.emergencyText.visibility = if (view.emergency == null) View.GONE else View.VISIBLE
        binding.qualityHint.text = ""
    }

    private fun showFrozenControls() {
        binding.shutterButton.setText(R.string.new_capture)
        binding.shutterButton.isEnabled = true
    }

    private fun showLiveMode() {
        frozen = null
        binding.frozenImage.setImageDrawable(null)
        binding.frozenImage.visibility = View.GONE
        binding.targetFrame.visibility = View.VISIBLE
        binding.qualityHint.visibility = View.GONE
        binding.qualityHint.text = ""
        binding.shutterButton.isEnabled = true
        binding.shutterButton.setText(R.string.shutter)
        binding.resultHeadline.text = getString(R.string.ready, labels.size)
        binding.resultHeadline.setTextColor(0xFFFFFFFF.toInt())
        binding.resultSubline.visibility = View.GONE
        binding.resultTops.visibility = View.GONE
        binding.warningText.visibility = View.GONE
        binding.emergencyText.visibility = View.GONE
    }

    private fun recordHistory(
        best: Species?,
        probability: Float,
        decision: VerdictDecision,
        hasLookalike: Boolean,
    ) {
        val species = best ?: return
        history.append(
            HistoryEntry(
                timestamp = clock.format(Date()),
                scientific = species.scientific,
                german = species.name,
                confidence = probability,
                verdict = decision.tone.name.lowercase(Locale.GERMANY),
                lookalike = hasLookalike,
            )
        )
    }

    private fun showHistory() {
        renderHistory()
        binding.historyPanel.visibility = View.VISIBLE
    }

    private fun renderHistory() {
        val list = binding.historyList
        list.removeAllViews()
        val entries = history.readNewestFirst()
        if (entries.isEmpty()) {
            list.addView(label(getString(R.string.history_empty)))
            return
        }
        val toneMark = mapOf(
            "danger" to "☠",
            "caution" to "!",
            "safe" to "✓",
        )
        for (entry in entries) {
            val mark = toneMark[entry.verdict] ?: "·"
            val percent = ResultFormatter.percent(entry.confidence)
            val warning = if (entry.lookalike) " ⚠" else ""
            list.addView(label("$mark  ${entry.timestamp}  ${entry.german} — $percent$warning"))
        }
    }

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(0xFFFFFFFF.toInt())
        textSize = 14f
        setPadding(0, 12, 0, 12)
    }

    private fun centreSquare(bitmap: Bitmap): Bitmap {
        val side = minOf(bitmap.width, bitmap.height)
        return Bitmap.createBitmap(
            bitmap,
            (bitmap.width - side) / 2,
            (bitmap.height - side) / 2,
            side,
            side,
        )
    }

    private fun toggleTorch() {
        val camera = camera ?: return
        if (!camera.cameraInfo.hasFlashUnit()) {
            toast(getString(R.string.no_torch))
            return
        }
        torchOn = !torchOn
        camera.cameraControl.enableTorch(torchOn)
    }

    private fun focusAt(view: View, x: Float, y: Float) {
        val camera = camera ?: return
        val action = FocusMeteringAction.Builder(
            previewView.meteringPointFactory.createPoint(x, y)
        ).build()
        camera.cameraControl.startFocusAndMetering(action)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun loadLabels(): List<Species> =
        assets.open("labels.txt").bufferedReader().readLines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val parts = line.split("|")
                Species(parts[0].trim(), parts.getOrElse(1) { "unbekannt" }.trim().lowercase(), parts.getOrElse(2) { "" }.trim())
            }

    private fun allPermissionsGranted(): Boolean = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        requestPermissions(REQUIRED_PERMISSIONS, CAMERA_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != CAMERA_REQUEST_CODE) return
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            toast(getString(R.string.permission_denied))
            finish()
        }
    }

    private var camera: androidx.camera.core.Camera? = null

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(qualityExecutor) { proxy -> analyseQuality(proxy) }
                }
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this as LifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
                binding.torchButton.isEnabled = camera?.cameraInfo?.hasFlashUnit() == true
            } catch (exc: Exception) {
                binding.resultHeadline.text = getString(R.string.camera_failed, exc.message)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Live quality pass: no model, just enough to tell the user to move closer,
     * add light or hold still. Never touches the frozen result.
     */
    private fun analyseQuality(proxy: ImageProxy) {
        try {
            if (!fieldMode.acceptsQualityUpdates) return
            val bitmap = proxyToBitmap(proxy) ?: return
            val scaled = Bitmap.createScaledBitmap(bitmap, 224, 224, true)
            val pixels = IntArray(224 * 224)
            scaled.getPixels(pixels, 0, 224, 0, 0, 224, 224)
            val gray = IntArray(pixels.size) { i ->
                val p = pixels[i]
                (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
            }
            val quality = FrameQualityAnalyzer.analyze(gray, 224, 224)
            val hint = FrameQualityPolicy.hint(quality)
            runOnUiThread {
                if (!fieldMode.acceptsQualityUpdates) return@runOnUiThread
                binding.qualityHint.visibility = View.VISIBLE
                binding.qualityHint.text = when (hint) {
                    QualityHint.DARK -> getString(R.string.hint_dark)
                    QualityHint.BRIGHT -> getString(R.string.hint_bright)
                    QualityHint.BLURRY -> getString(R.string.hint_blurry)
                    QualityHint.OK -> getString(R.string.hint_ok)
                }
                binding.targetFrame.alpha = if (hint == QualityHint.OK) 1f else 0.55f
            }
        } catch (e: Exception) {
            // A dropped quality frame must never disturb the frozen result.
        } finally {
            proxy.close()
        }
    }

    /** RGBA_8888 ImageProxy -> Bitmap, honouring a row stride wider than width*4. */
    private fun proxyToBitmap(proxy: ImageProxy): Bitmap? {
        val plane = proxy.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val w = proxy.width
        val h = proxy.height
        val padding = rowStride - w * pixelStride
        return if (padding == 0) {
            buffer.rewind()
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(buffer) }
        } else {
            val dense = ByteBuffer.allocate(w * h * pixelStride)
            for (row in 0 until h) {
                buffer.position(row * rowStride)
                for (col in 0 until w) dense.putInt(buffer.int)
            }
            dense.position(0)
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(dense) }
        }
    }

    private fun softmax(logits: FloatArray): FloatArray {
        val max = logits.maxOrNull() ?: 0f
        var sum = 0.0
        val out = FloatArray(logits.size)
        for (i in logits.indices) {
            out[i] = Math.exp((logits[i] - max).toDouble()).toFloat()
            sum += out[i]
        }
        for (i in out.indices) out[i] = (out[i] / sum).toFloat()
        return out
    }

    private fun loadModelFile(): MappedByteBuffer {
        val descriptor = assets.openFd("model.tflite")
        val stream = FileInputStream(descriptor.fileDescriptor)
        return stream.channel.map(
            FileChannel.MapMode.READ_ONLY,
            descriptor.startOffset,
            descriptor.declaredLength,
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        inferenceExecutor.shutdown()
        qualityExecutor.shutdown()
        interpreter?.close()
    }
}

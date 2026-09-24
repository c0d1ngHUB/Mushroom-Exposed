package com.example.mushroomexposed

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Size
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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

    /**
     * Sammelt die drei belegten Ansichten waehrend des Gedrueckthaltens.
     *
     * Die Akzeptanzschwelle gehoert zur evaluierten Modellkonfiguration
     * (Spec 2026-09-24, „Die konkreten Grenzen gehören zur evaluierten
     * Modellkonfiguration, nicht in den UI-Code“) und kommt deshalb aus
     * `viewpoint.json`. Sie ist **null**, solange das Asset fehlt oder
     * unvollstaendig ist — dann wird kein Frame akzeptiert und der
     * Sammelvorgang kann nie abschliessen. Der fruehere Platzhalter 0.05 steht
     * hier bewusst nicht mehr: er war unkalibriert, und gemessen liess er
     * `stipe_ring` 1,6 % seiner belegten Frames erkennen (25.09.2026).
     */
    private var viewAcceptance: ViewAcceptance? = null
    private var viewAccumulator: ViewAccumulator? = null

    /**
     * Der Konsens wird bei jedem Start eines Sammelvorgangs neu gebaut:
     * Vektoren aus einem vorherigen Vorgang duerfen kein Ergebnis tragen.
     *
     * Nullable, weil die Artenzahl erst nach `loadModel()` feststeht und ein
     * `SpeciesConsensus(0)` ungueltig waere.
     */
    private var consensus: SpeciesConsensus? = null
    private lateinit var segmenter: ViewpointSegmenter
    private val viewFrameCounter = java.util.concurrent.atomic.AtomicLong(0L)

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
            loadViewpointModel()
            binding.resultHeadline.text = getString(R.string.status_ready, labels.size)

            val curated = lookalikes.values.sumOf { it.size }
            binding.statusDetail.text = if (curated == 0) {
                getString(R.string.lookalikes_missing)
            } else {
                getString(R.string.lookalikes_loaded, curated)
            }
        } catch (e: Exception) {
            binding.resultHeadline.text = getString(R.string.model_failed, e.message)
            binding.statusDetail.visibility = View.GONE
            ViewStyling.fillOf(this, binding.statusPill, color(R.color.verdict_danger))
            e.printStackTrace()
        }
    }

    /**
     * Beide Touch-Listener rufen `performClick()` selbst auf, sobald ein Klick
     * erkannt ist (Sucher beim Tippen, Ausloeser beim Loslassen). Lint kann das
     * nicht durch das Lambda hindurch sehen und meldet die fehlende
     * `performClick`-Ueberschreibung; die eigentliche Zusicherung — "ein Klick
     * loest auch die Klickhandlung aus" — ist erfuellt.
     *
     * Eine echte Ueberschreibung waere hier nicht moeglich: beide Ziele sind
     * einfache Framework-Views, keine eigenen Klassen. Die verbleibende
     * Einschraenkung ist echt und dokumentiert: wer ausschliesslich per
     * Screenreader bedient, kann nicht halten und damit nicht sammeln.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun wireControls() {
        // Hold-to-scan: der Finger sammelt die drei Ansichten, ein Klick nicht
        // mehr. ACTION_DOWN startet, ACTION_UP bricht ab. Waehrend der Analyse
        // wird das Loslassen bewusst ignoriert (FieldModeMachine).
        binding.shutterButton.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> onScanStart()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    onScanStop()
                    // Ein Haltering ist auch ein Bedienelement: Touch mit
                    // Vorlesehilfe und Screenreader erwarten einen Klick, und
                    // Lint verlangt ihn an genau dieser Stelle.
                    if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                }
            }
            // Nur die drei Zustandswechsel beanspruchen: alles andere soll
            // weiterlaufen, damit der Kreis optisch auf den Druck reagiert.
            event.actionMasked == MotionEvent.ACTION_DOWN ||
                event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
        }
        binding.newChip.setOnClickListener { onReturnToLive() }
        binding.torchButton.setOnClickListener { toggleTorch() }
        binding.historyButton.setOnClickListener { showHistory() }
        binding.historyChip.setOnClickListener { showHistory() }
        binding.closeHistoryButton.setOnClickListener {
            binding.historyPanel.visibility = View.GONE
        }
        binding.clearHistoryButton.setOnClickListener {
            confirmClearHistory()
        }
        binding.callPoisonButton.setOnClickListener {
            dial(ResultFormatter.POISON_CONTROL_NUMBER)
        }
        binding.callEmergencyButton.setOnClickListener {
            dial(ResultFormatter.EMERGENCY_NUMBER)
        }
        previewView.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                focusAt(view, event.x, event.y)
                view.performClick()
            }
            true
        }
    }

    /** Notruf-Ziel waehlen. Die Nummer kommt aus ResultFormatter, nicht aus dem UI. */
    private fun dial(number: String) {
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${number.replace(" ", "")}"))
        if (intent.resolveActivity(packageManager) == null) {
            toast(getString(R.string.dial_failed))
            return
        }
        startActivity(intent)
    }


    /**
     * Loslassen des Sammelrings. Waehrend des Sammelns bricht das ab — ohne
     * eingefrorenes Bild, ohne Verlaufseintrag, ohne Datei. Waehrend der
     * Analyse ignoriert die Maschine das Loslassen.
     */
    private fun onScanStop() {
        when (fieldMode.onPrimaryUp()) {
            FieldModeAction.STOP_SCAN -> cancelScan()
            FieldModeAction.IGNORE -> Unit
            else -> Unit
        }
    }

    private fun onScanStart() {
        // Der Sammelmodus verlangt **beides**: das Segmentierer-Asset und die
        // kalibrierten Grenzen aus `viewpoint.json`. Fehlt eines von beiden,
        // gaebe es keinen Nachweis und damit keinen Abschluss — dann bleibt es
        // beim Einzelbild statt bei einer Schleife, die nie fertig wird.
        val viewsReady = segmenter.available && viewAccumulator != null
        when (fieldMode.onPrimaryDown(viewsAvailable = viewsReady)) {
            FieldModeAction.CAPTURE -> {
                captureSingleFrame()
                return
            }
            FieldModeAction.START_SCAN -> Unit
            else -> return
        }
        if (interpreter == null) {
            fieldMode.onCaptureUnavailable()
            toast(getString(R.string.model_missing))
            return
        }
        viewAccumulator?.cancel()
        consensus = if (labels.isEmpty()) null else SpeciesConsensus(labels.size)
        showScanningMode()
    }

    /**
     * Rueckfall ohne Segmentierer: ein Frame, eine Analyse, ein Ergebnis.
     *
     * Das ist der Pfad vor dem Mehransichten-Prototyp. Er wird nicht still
     * genommen: die Ansichtszeilen koennen ohne Segmentierer nicht gruen werden,
     * der Artenkonsens entsteht aus genau einer Ansicht, und der Nutzer sieht
     * denselben Hinweis wie bisher. Was entfaellt, ist nur das Warten auf
     * Evidenz, die es nicht geben kann.
     */
    private fun captureSingleFrame() {
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
        analyseSingleFrame(bitmap)
    }

    /** Ein Frame durch das Artenmodell, direkt in die bestehende Anzeige. */
    private fun analyseSingleFrame(bitmap: Bitmap) {
        inferenceExecutor.execute {
            try {
                finishAnalysis(speciesProbabilities(bitmap))
            } catch (e: Exception) {
                runOnUiThread { onAnalysisFailed(e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    /**
     * Abbruch: alle temporaeren Ansichtsreferenzen verwerfen. Kein Bild, kein
     * Verlaufseintrag — der Abbruch darf nichts hinterlassen.
     */
    private fun cancelScan() {
        viewAccumulator?.cancel()
        consensus = if (labels.isEmpty()) null else SpeciesConsensus(labels.size)
        renderViewRows(ViewProgress(emptySet(), ViewStep.CAP, false))
        if (fieldMode.state == FieldMode.LIVE) showLiveMode()
    }

    /**
     * Der einzige Weg aus einem eingefrorenen Ergebnis zurueck. Loescht das
     * eingefrorene Bild sofort, damit kein Ergebnis einer alten Aufnahme unter
     * einem neuen Sucher stehen bleibt.
     */
    private fun onReturnToLive() {
        if (fieldMode.onReturnToLive() != FieldModeAction.RETURN_TO_LIVE) return
        viewAccumulator?.cancel()
        consensus = if (labels.isEmpty()) null else SpeciesConsensus(labels.size)
        renderViewRows(ViewProgress(emptySet(), ViewStep.CAP, false))
        showLiveMode()
    }

    /** Der Sammelzustand: Ansichtszeilen sichtbar, noch kein eingefrorenes Bild. */
    private fun showScanningMode() {
        binding.targetFrame.visibility = View.VISIBLE
        binding.resultSheet.visibility = View.GONE
        binding.frozenChips.visibility = View.GONE
        binding.controls.visibility = View.VISIBLE
        binding.qualityHint.visibility = View.VISIBLE
        binding.qualityHint.text = getString(R.string.view_scan_hint)
        renderViewRows(ViewProgress(emptySet(), ViewStep.CAP, false))
    }

    /**
     * Zeichnet die drei Ansichtszeilen. Gefuellt plus „Erfasst“ heisst
     * ausschliesslich: diese Bildansicht wurde ausreichend erfasst. Es ist
     * keine Bestimmung und keine Verzehrfreigabe.
     */
    private fun renderViewRows(progress: ViewProgress) {
        val rows = listOf(
            ViewStep.CAP to binding.viewCap,
            ViewStep.UNDERSIDE to binding.viewUnderside,
            ViewStep.STIPE_RING to binding.viewStipeRing,
        )
        for ((step, row) in rows) {
            val captured = step in progress.captured
            row.text = if (captured) {
                "${rowLabel(step)} — ${getString(R.string.view_captured)}"
            } else {
                rowLabel(step)
            }
            row.setTextColor(if (captured) color(R.color.ink) else color(R.color.ink3))
            row.setBackgroundResource(
                if (captured) R.drawable.bg_view_row_captured else R.drawable.bg_view_row,
            )
        }
    }

    private fun rowLabel(step: ViewStep): String = when (step) {
        ViewStep.CAP -> getString(R.string.view_show_cap)
        ViewStep.UNDERSIDE -> getString(R.string.view_show_underside)
        ViewStep.STIPE_RING -> getString(R.string.view_show_stipe_ring)
    }

    /**
     * Alle drei Ansichten liegen vor. Ab hier wird der eingefrorene Frame
     * gezeigt und gerechnet; der Konsens laeuft ueber die drei gesammelten
     * Wahrscheinlichkeitsvektoren statt ueber ein Einzelbild.
     *
     * Aufgerufen aus `sampleViews` auf dem Quality-Executor, aber nur innerhalb
     * eines `runOnUiThread`-Blocks — die Sichtbarkeitswechsel gehoeren auf den
     * UI-Thread.
     */
    private fun completeScan() {
        fieldMode.onViewsComplete()
        val bitmap = frozen ?: previewView.bitmap
        if (bitmap == null) {
            fieldMode.onCaptureUnavailable()
            viewAccumulator?.cancel()
            toast(getString(R.string.frame_missing))
            showLiveMode()
            return
        }
        showAnalysingMode(bitmap)
        analyseConsensus()
    }

    private fun showAnalysingMode(bitmap: Bitmap) {
        frozen = bitmap
        binding.frozenImage.setImageBitmap(bitmap)
        binding.frozenImage.visibility = View.VISIBLE
        binding.targetFrame.visibility = View.GONE
        binding.qualityHint.text = ""
        binding.qualityHint.visibility = View.GONE
        binding.resultSheet.visibility = View.GONE
        binding.frozenChips.visibility = View.GONE
        binding.statusPill.visibility = View.VISIBLE
        ViewStyling.fillOf(this, binding.statusPill, color(R.color.scrim))
        binding.resultHeadline.setText(R.string.analysing)
        binding.statusDetail.visibility = View.GONE
        binding.shutterButton.isEnabled = false
        binding.shutterButton.alpha = 0.55f
    }

    /**
     * Mehransichten-Konsens statt Einzelbild.
     *
     * Jede der drei belegten Ansichten laeuft einmal durch das Artenmodell; die
     * Wahrscheinlichkeitsvektoren werden geometrisch gemittelt. Erst danach
     * entscheidet die bestehende `VerdictPolicy` — der Sicherheitsschutz bleibt
     * der letzte Entscheider, nicht der Konsens.
     */
    private fun analyseConsensus() {
        val probabilities = consensus?.result()
        inferenceExecutor.execute {
            try {
                if (probabilities == null) {
                    runOnUiThread { onAnalysisFailed(getString(R.string.viewpoint_missing)) }
                    return@execute
                }
                finishAnalysis(probabilities)
            } catch (e: Exception) {
                runOnUiThread { onAnalysisFailed(e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    /** Ein Frame durch das Artenmodell: weiche Wahrscheinlichkeiten ueber alle Klassen. */
    private fun speciesProbabilities(bitmap: Bitmap): FloatArray {
        val interpreter = interpreter ?: throw IllegalStateException("no interpreter")
        val crop = centreSquare(bitmap)
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
        return softmax(output[0])
    }

    /** Aus den Konsens-Wahrscheinlichkeiten die Anzeige bauen. */
    private fun finishAnalysis(probs: FloatArray) {
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
        // "essbar"-Freigabe zurueckgestuft. Messbefund 19.09.2026: das Modell
        // gibt giftige Arten als essbar frei, die Konfidenz verraet das nicht --
        // die giftige Alternative in den Top-3 schon.
        val toxicAlternative = ranked.firstOrNull { it.verdict == "giftig" }
        // Zweites, breiteres Signal: die Gattung. Die schlimmsten Fehlfreigaben
        // fuehren Knollenblaetterpilze in die Top-3, aber als *essbare*
        // (Amanita_ceciliae) -- das Verdict sieht das nicht, die Gattung schon.
        // Nur Hinweis, keine Blockade: als Blockade gemessen kostet die Regel
        // die Haelfte der Freigaben.
        val toxicGenus = ToxicGenus.firstRisky(ranked.map { it.scientific }, riskyGenera)
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
    }

    private fun onAnalysisFailed(message: String) {
        fieldMode.onAnalysisFinished()
        binding.resultHeadline.text = getString(R.string.inference_failed, message)
        binding.statusDetail.visibility = View.GONE
        ViewStyling.fillOf(this, binding.statusPill, color(R.color.verdict_danger))
        binding.resultSheet.visibility = View.GONE
        binding.frozenChips.visibility = View.VISIBLE
        showFrozenControls()
    }

    /** Ergebnis-Sheet im Wald-Look: Marke, Artname, Warnkarte, Top-3, Notfall. */
    private fun render(view: ResultView) {
        val palette = when (view.tone) {
            VerdictTone.DANGER -> VerdictColors(
                mark = color(R.color.verdict_danger),
                tint = color(R.color.verdict_danger_tint),
                ink = color(R.color.verdict_danger_ink),
            )
            VerdictTone.CAUTION -> VerdictColors(
                mark = color(R.color.verdict_caution),
                tint = color(R.color.verdict_caution_tint),
                ink = color(R.color.verdict_caution_ink),
            )
            VerdictTone.SAFE -> VerdictColors(
                // Eine essbare Einschaetzung ist keine Freigabe. Neutrale Farbe
                // statt Gruen: ein giftiges Verwechslungsrisiko darf nie in
                // Freigabefarbe erscheinen.
                mark = color(R.color.verdict_neutral),
                tint = color(R.color.verdict_neutral_tint),
                ink = color(R.color.verdict_neutral_ink),
            )
        }

        // Statuspille: Ampelmarke plus Kurztext, damit das Urteil auch oben sichtbar ist.
        binding.statusPill.visibility = View.VISIBLE
        ViewStyling.fillOf(this, binding.statusPill, palette.mark)
        binding.resultHeadline.text = view.headline
        binding.statusDetail.visibility = View.GONE

        // Sheet.
        binding.resultSheet.visibility = View.VISIBLE
        binding.resultBadge.text = when (view.tone) {
            VerdictTone.DANGER -> getString(R.string.badge_danger)
            VerdictTone.CAUTION -> getString(R.string.badge_caution)
            VerdictTone.SAFE -> getString(R.string.badge_safe)
        }
        ViewStyling.fillOf(this, binding.resultBadge, palette.mark)
        binding.resultName.text = view.name
        binding.resultSubline.text = view.subline
        binding.resultSubline.visibility = if (view.subline.isBlank()) View.GONE else View.VISIBLE
        // Der Artname in der Unterzeile ist ein Fachname und wird kursiv gesetzt.
        binding.resultSubline.typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.SERIF,
            android.graphics.Typeface.ITALIC,
        )

        binding.warningText.text = view.warning
        binding.warningText.visibility = if (view.warning.isBlank()) View.GONE else View.VISIBLE
        if (view.warning.isNotBlank()) {
            ViewStyling.fillOf(this, binding.warningText, palette.tint)
            binding.warningText.setTextColor(palette.ink)
            ViewStyling.strokeOf(this, binding.warningText, palette.mark)
        }

        renderTops(view, palette)

        // Notfallblock nur, wenn das Urteil nicht sicher ist.
        val showEmergency = view.emergency != null
        binding.emergencyBlock.visibility = if (showEmergency) View.VISIBLE else View.GONE
        if (showEmergency) {
            binding.callPoisonNumber.text = ResultFormatter.POISON_CONTROL_NUMBER
            binding.callEmergencyNumber.text = ResultFormatter.EMERGENCY_NUMBER
            binding.callPoisonButton.contentDescription =
                getString(R.string.cd_call, ResultFormatter.POISON_CONTROL_NUMBER)
            binding.callEmergencyButton.contentDescription =
                getString(R.string.cd_call, ResultFormatter.EMERGENCY_NUMBER)
            binding.callPoisonButton.background =
                ViewStyling.rounded(this, 14, color(R.color.call_poison))
            binding.callEmergencyButton.background =
                ViewStyling.rounded(this, 14, color(R.color.call_emergency))
        }

        // Notfall-Wortlaut fuer Vorlesehilfen: derselbe gepinnte Satz wie im Test.
        binding.emergencyBlock.contentDescription =
            if (showEmergency) ResultFormatter.EMERGENCY_TEXT else null

        binding.qualityHint.text = ""
        binding.qualityHint.visibility = View.GONE
    }

    /** Top-3 als eigene Zeilen statt einer Textwand; giftige Raenge rot. */
    private fun renderTops(view: ResultView, palette: VerdictColors) {
        val list = binding.resultTops
        list.removeAllViews()
        list.visibility = if (view.tops.isEmpty()) View.GONE else View.VISIBLE
        if (view.tops.isEmpty()) return

        for (row in view.tops) {
            val rowView = layoutInflater.inflate(R.layout.row_top_hit, list, false)
            val rank = rowView.findViewById<TextView>(R.id.topRank)
            val label = rowView.findViewById<TextView>(R.id.topLabel)
            val mark = rowView.findViewById<TextView>(R.id.topMark)

            rank.text = "${row.rank}"
            label.text = row.label
            mark.text = row.mark
            mark.setTextColor(if (row.toxic) color(R.color.verdict_danger) else palette.ink)
            mark.visibility = if (row.mark.isBlank()) View.GONE else View.VISIBLE
            list.addView(rowView)
        }
    }

    private fun showFrozenControls() {
        // Das Ergebnis-Sheet wurde nur im Erfolgsfall bereits von render()
        // eingeblendet. Bei einem Analysefehler muss es verborgen bleiben.
        binding.controls.visibility = View.GONE
        binding.frozenChips.visibility = View.VISIBLE
        binding.shutterButton.isEnabled = true
        binding.shutterButton.alpha = 1f
    }

    private fun showLiveMode() {
        frozen = null
        binding.frozenImage.setImageDrawable(null)
        binding.frozenImage.visibility = View.GONE
        binding.targetFrame.visibility = View.VISIBLE
        binding.qualityHint.visibility = View.GONE
        binding.qualityHint.text = ""
        binding.resultSheet.visibility = View.GONE
        binding.frozenChips.visibility = View.GONE
        binding.controls.visibility = View.VISIBLE
        binding.shutterButton.isEnabled = true
        binding.shutterButton.alpha = 1f
        binding.statusPill.visibility = View.VISIBLE
        ViewStyling.fillOf(this, binding.statusPill, color(R.color.scrim))
        binding.resultHeadline.text = getString(R.string.status_ready, labels.size)
        binding.statusDetail.visibility = View.VISIBLE

        val curated = lookalikes.values.sumOf { it.size }
        binding.statusDetail.text = if (curated == 0) {
            getString(R.string.lookalikes_missing)
        } else {
            getString(R.string.lookalikes_loaded, curated)
        }
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

    /**
     * Loeschen ist unwiderruflich und wird deshalb erst nach Rueckfrage
     * ausgefuehrt. Der Knopf ist bei leerem Verlauf bereits deaktiviert; die
     * Rueckfrage deckt den Fall ab, dass der Verlauf waehrend der Anzeige
     * gefuellt wurde.
     */
    private fun confirmClearHistory() {
        AlertDialog.Builder(this)
            .setTitle(R.string.history_clear_confirm_title)
            .setMessage(R.string.history_clear_confirm_message)
            .setNegativeButton(R.string.history_cancel, null)
            .setPositiveButton(R.string.history_clear_confirm_ok) { _, _ ->
                history.clear()
                renderHistory()
            }
            .show()
    }

    private fun renderHistory() {
        val list = binding.historyList
        list.removeAllViews()
        val entries = history.readNewestFirst()

        // Ein roter Loeschknopf ohne Eintraege ist eine Einladung ins Leere.
        binding.clearHistoryButton.isEnabled = entries.isNotEmpty()
        binding.clearHistoryButton.alpha = if (entries.isNotEmpty()) 1f else 0.5f

        if (entries.isEmpty()) {
            list.addView(label(getString(R.string.history_empty)))
            return
        }
        // Eine Einschaetzung bekommt kein Haken-Symbol, nur eine giftige Warnung
        // eines. Die Ampel sitzt in der Schriftfarbe, nicht in einem Zeichen.
        for (entry in entries) {
            val row = ResultFormatter.historyRow(entry)
            val tone = when (entry.verdict) {
                "danger" -> VerdictTone.DANGER
                "caution" -> VerdictTone.CAUTION
                "safe" -> VerdictTone.SAFE
                else -> null
            }
            list.addView(historyEntryView(row, tone))
        }
    }

    /**
     * Eine Verlaufszeile: Art als Ueberschrift, Urteil und lokales Datum als
     * ruhige zweite Zeile. Die Verwechslungswarnung wird benannt, nicht mit
     * einem zweiten Zeichen angedeutet.
     */
    private fun historyEntryView(
        row: ResultFormatter.HistoryRow,
        tone: VerdictTone?,
    ): android.view.View {
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 14, 0, 14)
        }
        val species = TextView(this).apply {
            text = row.species
            textSize = 15f
            setTextColor(color(R.color.ink))
            // Der Artname wird kursiv gesetzt — er ist ein Fachname, keine
            // Ueberschrift (Review: "Cantharellus cibarius in italics").
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.SERIF,
                android.graphics.Typeface.ITALIC,
            )
        }
        val detail = TextView(this).apply {
            text = row.verdict
            textSize = 12f
            setTextColor(
                when (tone) {
                    VerdictTone.DANGER -> color(R.color.verdict_danger)
                    VerdictTone.CAUTION -> color(R.color.verdict_caution)
                    // Neutral, nicht gruen: die Zeile behauptet keine Freigabe.
                    VerdictTone.SAFE -> color(R.color.verdict_neutral_ink)
                    null -> color(R.color.ink2)
                }
            )
        }
        val meta = TextView(this).apply {
            val parts = listOf(row.timestamp, row.note).filter { it.isNotBlank() }
            text = parts.joinToString(" · ")
            textSize = 11f
            setTextColor(color(R.color.ink3))
        }
        block.addView(species)
        block.addView(detail)
        if (meta.text.isNotBlank()) block.addView(meta)
        return block
    }

    private fun label(text: String, tone: VerdictTone? = null): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setPadding(0, 12, 0, 12)
        setTextColor(
            when (tone) {
                VerdictTone.DANGER -> color(R.color.verdict_danger)
                VerdictTone.CAUTION -> color(R.color.verdict_caution)
                // Neutral, nicht gruen: die Zeile behauptet keine Freigabe.
                VerdictTone.SAFE -> color(R.color.verdict_neutral_ink)
                null -> color(R.color.ink)
            }
        )
    }

    /** Farbe aus den Wald-Tokens. */
    private fun color(resId: Int): Int = ViewStyling.colorOf(this, resId)

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
     * Live quality pass plus, while the ring is held, the viewpoint sampling.
     *
     * No video is retained: every sampled frame is reduced to a mask coverage,
     * a sharpness and — once for each of the three views — one probability
     * vector, then released. The frozen Bitmap is only set at the end.
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

            // Ansichten werden nur gesammelt, solange der Ring gedrueckt ist.
            if (fieldMode.state == FieldMode.SCANNING) sampleViews(bitmap)

            runOnUiThread {
                if (!fieldMode.acceptsQualityUpdates) return@runOnUiThread
                binding.qualityHint.visibility = View.VISIBLE
                // Waehrend des Sammelns bleibt die Sammelanweisung stehen: sie
                // ist die Handlungsanweisung, die der Nutzer gerade befolgt.
                if (fieldMode.state != FieldMode.SCANNING) {
                    binding.qualityHint.text = when (hint) {
                        QualityHint.DARK -> getString(R.string.hint_dark)
                        QualityHint.BRIGHT -> getString(R.string.hint_bright)
                        QualityHint.BLURRY -> getString(R.string.hint_blurry)
                        QualityHint.OK -> getString(R.string.hint_ok)
                    }
                }
                binding.targetFrame.alpha = if (hint == QualityHint.OK) 1f else 0.55f
            }
        } catch (e: Exception) {
            // A dropped quality frame must never disturb the frozen result.
        } finally {
            proxy.close()
        }
    }

    /**
     * Ein Sampledurchlauf: Segmentierer-Evidenz, und fuer jede neu belegte
     * Ansicht genau ein Wahrscheinlichkeitsvektor des Artenmodells.
     *
     * Laeuft auf dem Quality-Executor, greift aber nur auf den Accumulator zu,
     * der selbst nicht threadsicher sein muss, weil dieser Executor ein
     * einzelner Thread ist.
     */
    private fun sampleViews(bitmap: Bitmap) {
        val accumulator = viewAccumulator ?: return
        val frameId = viewFrameCounter.incrementAndGet()
        val evidence = segmenter.evidenceFor(bitmap, frameId)
        if (evidence.isEmpty()) return
        for (item in evidence) {
            val progress = accumulator.accept(item)
            // Nur wenn diese Ansicht neu oder besser belegt wurde, lohnt der
            // Modellaufruf: der Konsens braucht je Ansicht genau einen Vektor.
            val isSelected = accumulator.best(item.step)?.frameId == item.frameId
            if (isSelected) {
                try {
                    consensus?.add(item.step, speciesProbabilities(bitmap))
                } catch (e: Exception) {
                    // Eine fehlgeschlagene Artbestimmung darf das Sammeln nicht
                    // vergiften; der Konsens bleibt dann unvollstaendig und es
                    // gibt kein Ergebnis.
                }
            }
            runOnUiThread { renderViewRows(progress) }
            if (progress.complete) {
                runOnUiThread {
                    if (fieldMode.state == FieldMode.SCANNING) {
                        frozen = bitmap
                        completeScan()
                    }
                }
                return
            }
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

    /**
     * Laedt den Ansichts-Segmentierer, falls das Asset ausgeliefert ist.
     *
     * Fail closed statt Absturz: fehlt `viewpoint.tflite` oder passt sein
     * Kanalvertrag nicht, bleibt `segmenter.available` false. Die App laeuft
     * dann weiter, kann aber keine Ansicht belegen und zeigt kein Ergebnis —
     * genau das ist gewollt (Spec 2026-09-24: „Der Flow scheitert geschlossen,
     * falls viewpoint.tflite, sein Vertrag oder der Artenkonsens fehlt“).
     *
     * Ein fehlendes Prototyp-Asset darf die bestehende Einzelbild-Installation
     * nicht lahmlegen, deshalb wird hier nichts geworfen.
     */
    private fun loadViewpointModel() {
        segmenter = try {
            val descriptor = assets.openFd(VIEWPOINT_ASSET)
            val stream = FileInputStream(descriptor.fileDescriptor)
            val mapped = stream.channel.map(
                FileChannel.MapMode.READ_ONLY,
                descriptor.startOffset,
                descriptor.declaredLength,
            )
            val viewpoint = Interpreter(mapped)
            val channels = viewpoint.getOutputTensor(0).shape().lastOrNull() ?: 0
            val vShape = viewpoint.getInputTensor(0).shape()
            // Der Konstruktor prueft den Kanalvertrag selbst und schliesst
            // geschlossen, wenn das Asset nicht passt.
            ViewpointSegmenter(viewpoint, vShape[2], vShape[1], channels)
        } catch (e: Exception) {
            ViewpointSegmenter(null, 0, 0, 0)
        }

        // Die kalibrierten Grenzen kommen aus dem Vertrag. Ohne sie gaebe es
        // keine Akzeptanz und damit keinen Sammelvorgang — auch dann nicht,
        // wenn das Modell geladen ist. Beides muss zusammenpassen.
        viewAcceptance = try {
            ViewpointConfig.parseOrNull(
                assets.open(ViewpointConfig.ASSET).bufferedReader().use { it.readText() },
            )
        } catch (e: Exception) {
            null
        }
        viewAccumulator = viewAcceptance?.let(::ViewAccumulator)
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

    /**
     * Kein Ergebnis und kein Sammelvorgang darf einen Hintergrundwechsel
     * ueberleben: alle temporaeren Ansichtsreferenzen und der Konsens werden
     * hier verworfen.
     */
    override fun onStop() {
        super.onStop()
        viewAccumulator?.cancel()
        consensus = if (labels.isEmpty()) null else SpeciesConsensus(labels.size)
    }

    override fun onDestroy() {
        super.onDestroy()
        viewAccumulator?.cancel()
        inferenceExecutor.shutdown()
        qualityExecutor.shutdown()
        interpreter?.close()
    }

    private companion object {
        /** Asset des Mehransichten-Prototyps; nicht Teil der Einzelbild-Auslieferung. */
        const val VIEWPOINT_ASSET = "viewpoint.tflite"
    }
}
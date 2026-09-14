package com.example.mushroomexposed

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Size
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.mushroomexposed.databinding.ActivityMainBinding
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var previewView: PreviewView
    private lateinit var resultText: android.widget.TextView
    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()
    private var inputW = 224
    private var inputH = 224
    private val inferenceExecutor = Executors.newSingleThreadExecutor()
    private val CAMERA_REQUEST_CODE = 10
    private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        previewView = binding.previewView
        resultText = binding.resultText

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions()
        }

        // Load TensorFlow Lite model + labels from assets
        try {
            interpreter = Interpreter(loadModelFile())
            labels = loadLabels()
            val inShape = interpreter!!.getInputTensor(0).shape() // [1, H, W, 3] (NHWC)
            inputH = inShape[1]
            inputW = inShape[2]
            resultText.text = "Model ready — ${labels.size} Arten\n(${inputW}×${inputH})"
        } catch (e: Exception) {
            resultText.text = "Failed to load model: ${e.message}"
            e.printStackTrace()
        }
    }

    private fun loadLabels(): List<String> {
        return try {
            assets.open("labels.txt").bufferedReader().readLines().filter { it.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun allPermissionsGranted(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(
                baseContext, it
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        requestPermissions(REQUIRED_PERMISSIONS, CAMERA_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_REQUEST_CODE) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(
                    this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
                        analyzeImage(imageProxy)
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this as LifecycleOwner, cameraSelector, preview, imageAnalyzer
                )
            } catch (exc: Exception) {
                resultText.text = "Camera bind failed: ${exc.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** RGBA_8888 ImageProxy -> Bitmap, honoring the row stride (may exceed width*4). */
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        val plane = imageProxy.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val w = imageProxy.width
        val h = imageProxy.height
        val rowPadding = rowStride - w * pixelStride
        val bitmap = if (rowPadding == 0) {
            buffer.rewind()
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                copyPixelsFromBuffer(buffer)
            }
        } else {
            // Copy row by row to strip the padding
            val dense = java.nio.ByteBuffer.allocate(w * h * pixelStride)
            buffer.position(0)
            for (row in 0 until h) {
                buffer.position(row * rowStride)
                for (col in 0 until w) {
                    dense.putInt(buffer.int)
                }
            }
            dense.position(0)
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                copyPixelsFromBuffer(dense)
            }
        }
        return bitmap
    }

    private fun analyzeImage(imageProxy: ImageProxy) {
        // Model failed to load (e.g. placeholder asset) — nothing to infer with.
        val interpreter = interpreter
        if (interpreter == null) {
            imageProxy.close()
            return
        }

        try {
            val bitmap = imageProxyToBitmap(imageProxy)
                ?: throw IllegalStateException("no RGBA plane")
            val scaled = Bitmap.createScaledBitmap(bitmap, inputW, inputH, true)

            val nPixels = inputW * inputH
            val pixels = IntArray(nPixels)
            scaled.getPixels(pixels, 0, inputW, 0, 0, inputW, inputH)
            val inputBuffer = java.nio.ByteBuffer
                .allocateDirect(1 * nPixels * 3 * 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (p in pixels) {
                inputBuffer.putFloat(((p shr 16) and 0xFF) / 255f)  // R
                inputBuffer.putFloat(((p shr 8) and 0xFF) / 255f)   // G
                inputBuffer.putFloat((p and 0xFF) / 255f)           // B
            }
            inputBuffer.rewind()

            val nClasses = if (labels.isNotEmpty()) labels.size else 12
            val outputArray = Array(1) { FloatArray(nClasses) }

            inferenceExecutor.execute {
                try {
                    interpreter.run(inputBuffer, outputArray)
                    val probs = softmax(outputArray[0])
                    val maxIndex = probs.indices.maxByOrNull { probs[it] } ?: 0
                    val confidence = probs[maxIndex]
                    val species = labels.getOrNull(maxIndex) ?: "Klasse $maxIndex"
                    val verdict = when {
                        species.endsWith("_edible") -> "essbar ✓"
                        species.endsWith("_poisonous") -> "giftig ⚠"
                        else -> "unbekannt"
                    }
                    val display = species.substringBefore('_').replace('_', ' ')
                    val second = probs.withIndex()
                        .filter { it.index != maxIndex }
                        .maxByOrNull { it.value }
                    val secondText = if (second != null) {
                        val s2 = labels.getOrNull(second.index) ?: "Klasse ${second.index}"
                        "${s2.substringBefore('_').replace('_', ' ')} ${(second.value * 100).toInt()} %"
                    } else ""
                    runOnUiThread {
                        resultText.text =
                            "$display — $verdict\n${(confidence * 100).toInt()} % · " +
                                    "2. Treffer: $secondText"
                    }
                } catch (e: Exception) {
                    runOnUiThread { resultText.text = "Inference error: ${e.message}" }
                }
            }
        } catch (e: Exception) {
            runOnUiThread { resultText.text = "Frame error: ${e.message}" }
        } finally {
            imageProxy.close()
        }
    }

    private fun softmax(logits: FloatArray): FloatArray {
        val maxLogit = logits.max()
        var sumExp = 0.0
        val out = FloatArray(logits.size)
        for (i in logits.indices) {
            out[i] = Math.exp((logits[i] - maxLogit).toDouble()).toFloat()
            sumExp += out[i]
        }
        for (i in out.indices) out[i] = (out[i] / sumExp).toFloat()
        return out
    }

    private fun loadModelFile(): MappedByteBuffer {
        val fileDescriptor = assets.openFd("model.tflite")
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
package com.example.anyshare.qr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.anyshare.databinding.ActivityQrScannerBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Fullscreen camera activity for scanning QR codes using CameraX and ZXing.
 */
class QrScannerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PAYLOAD_JSON = "EXTRA_PAYLOAD_JSON"
        const val EXTRA_MANUAL_PIN = "EXTRA_MANUAL_PIN"
    }

    private lateinit var binding: ActivityQrScannerBinding
    private lateinit var cameraExecutor: ExecutorService
    private var camera: Camera? = null
    private var isTorchOn = false
    private var analyzer: QrImageAnalyzer? = null

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission is required to scan QR codes", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        binding = ActivityQrScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        setupListeners()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnToggleTorch.setOnClickListener {
            toggleTorch()
        }

        binding.btnEnterPin.setOnClickListener {
            showManualPinDialog()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            analyzer = QrImageAnalyzer { scannedText ->
                runOnUiThread {
                    handleScannedResult(scannedText)
                }
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, analyzer!!)
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
            } catch (exc: Exception) {
                Toast.makeText(this, "Failed to start camera: ${exc.message}", Toast.LENGTH_SHORT).show()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleScannedResult(scannedText: String) {
        val payload = QrConnectionPayload.fromJson(scannedText)
        if (payload != null) {
            Toast.makeText(this, "Connected with ${payload.deviceName}!", Toast.LENGTH_SHORT).show()
            val resultIntent = Intent().apply {
                putExtra(EXTRA_PAYLOAD_JSON, scannedText)
            }
            setResult(RESULT_OK, resultIntent)
            finish()
        } else {
            Toast.makeText(this, "Invalid Anyshare QR Code. Scanning again...", Toast.LENGTH_SHORT).show()
            analyzer?.resumeScanning()
        }
    }

    private fun toggleTorch() {
        camera?.let {
            if (it.cameraInfo.hasFlashUnit()) {
                isTorchOn = !isTorchOn
                it.cameraControl.enableTorch(isTorchOn)
                binding.btnToggleTorch.alpha = if (isTorchOn) 1.0f else 0.6f
            } else {
                Toast.makeText(this, "Flashlight unavailable", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showManualPinDialog() {
        val input = EditText(this).apply {
            hint = "e.g. 4821"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            maxLines = 1
            textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER
            textSize = 24f
        }

        AlertDialog.Builder(this)
            .setTitle("Enter 4-Digit PIN")
            .setMessage("Enter the pairing PIN displayed on the sender's device:")
            .setView(input)
            .setPositiveButton("Connect") { _, _ ->
                val pin = input.text.toString().trim()
                if (pin.length >= 4) {
                    val resultIntent = Intent().apply {
                        putExtra(EXTRA_MANUAL_PIN, pin)
                    }
                    setResult(RESULT_OK, resultIntent)
                    finish()
                } else {
                    Toast.makeText(this, "Please enter a valid 4-digit PIN", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun finish() {
        super.finish()
        com.example.anyshare.utils.ActivityTransitionHelper.applySlideCloseTransition(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

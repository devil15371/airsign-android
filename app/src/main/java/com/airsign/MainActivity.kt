package com.airsign

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.airsign.signal.DTW
import com.airsign.signal.DrawingCanvas
import com.airsign.signal.PUF
import com.airsign.utils.StorageHelper
import java.io.File

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var btnPermissions: Button
    private lateinit var btnReset: Button
    private lateinit var tvProgress: TextView
    private lateinit var tvCanvasInstructions: TextView
    private lateinit var drawingCanvas: DrawingCanvas

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    // Buffer lists for dynamic draw capture
    private val rawSensorBufferX = ArrayList<Double>()
    private val rawSensorBufferY = ArrayList<Double>()
    private val rawSensorBufferZ = ArrayList<Double>()
    private val motionIntentBuffer = ArrayList<List<Float>>()

    private var isRecording = false
    private var enrollmentRunIndex = 0
    private val enrollmentRuns = ArrayList<List<List<Float>>>()
    private val enrollmentPufSamplesX = ArrayList<DoubleArray>()
    private val enrollmentPufSamplesY = ArrayList<DoubleArray>()
    private val enrollmentPufSamplesZ = ArrayList<DoubleArray>()

    private val OVERLAY_PERMISSION_REQ_CODE = 5372

    override fun onCreate(savedInstanceState: Bundle?) {
        // Register crash handler to intercept and save any launch failures
        val crashFile = File(getExternalFilesDir(null), "crash.txt")
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            try {
                val sw = java.io.StringWriter()
                throwable.printStackTrace(java.io.PrintWriter(sw))
                crashFile.writeText(sw.toString())
            } catch (e: Exception) {
                // Fail-safe
            }
            android.os.Process.killProcess(android.os.Process.myPid())
            java.lang.System.exit(10)
        }

        super.onCreate(savedInstanceState)

        // Show crash alert if the app previously failed to start
        if (crashFile.exists()) {
            val crashLog = try { crashFile.readText() } catch (e: Exception) { "Failed to read crash log" }
            try { crashFile.delete() } catch (e: Exception) {}
            
            AlertDialog.Builder(this)
                .setTitle("Launch Crash Log")
                .setMessage(crashLog)
                .setPositiveButton("OK", null)
                .show()
        }

        setContentView(R.layout.activity_main)

        // Initialize UI Elements
        btnPermissions = findViewById(R.id.btnPermissions)
        btnReset = findViewById(R.id.btnReset)
        tvProgress = findViewById(R.id.tvProgress)
        tvCanvasInstructions = findViewById(R.id.tvCanvasInstructions)
        drawingCanvas = findViewById(R.id.drawingCanvas)

        // Initialize Sensors
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        // Click handlers
        btnPermissions.setOnClickListener {
            checkAndRequestOverlayPermission()
        }

        btnReset.setOnClickListener {
            StorageHelper.clearEnrollment(this)
            stopLockScreenService()
            resetEnrollmentState()
            Toast.makeText(this, "Credentials reset completely.", Toast.LENGTH_SHORT).show()
        }

        // Setup Drawing Canvas Callbacks
        drawingCanvas.setDrawingListener(object : DrawingCanvas.DrawingListener {
            override fun onDrawStart() {
                tvCanvasInstructions.visibility = View.GONE
                clearSensorBuffers()
                isRecording = true
            }

            override fun onDrawing(x: Float, y: Float, speedX: Float, speedY: Float) {
                // Map finger coordinates to clean motion intent force vectors
                val forceX = speedX * 0.05f
                val forceY = -speedY * 0.05f
                motionIntentBuffer.add(listOf(forceX, forceY, 0f))
            }

            override fun onDrawEnd(points: List<android.graphics.PointF>) {
                isRecording = false
                processEnrollmentAttempt()
            }
        })

        updateUIState()
    }

    override fun onResume() {
        super.onResume()
        // Register sensor listener
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
        updatePermissionButtonState()
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    // SensorEventListener interfaces
    override fun onSensorChanged(event: SensorEvent) {
        if (isRecording && event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            // Collect raw high-frequency micro-vibrations for the Silicon PUF
            rawSensorBufferX.add(event.values[0].toDouble())
            rawSensorBufferY.add(event.values[1].toDouble())
            rawSensorBufferZ.add(event.values[2].toDouble())
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun clearSensorBuffers() {
        rawSensorBufferX.clear()
        rawSensorBufferY.clear()
        rawSensorBufferZ.clear()
        motionIntentBuffer.clear()
    }

    private fun checkAndRequestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, OVERLAY_PERMISSION_REQ_CODE)
            } else {
                Toast.makeText(this, "Permission already granted.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQ_CODE) {
            updatePermissionButtonState()
        }
    }

    private fun updatePermissionButtonState() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(this)) {
                btnPermissions.text = "Overlay Permission: GRANTED"
                btnPermissions.isEnabled = false
                btnPermissions.backgroundTintList = ColorStateList.valueOf(android.graphics.Color.parseColor("#4caf50"))
            } else {
                btnPermissions.text = "Grant Overlay Permission"
                btnPermissions.isEnabled = true
                btnPermissions.backgroundTintList = ColorStateList.valueOf(android.graphics.Color.parseColor("#a12cff"))
            }
        }
    }

    private fun updateUIState() {
        if (StorageHelper.isEnrolled(this)) {
            val pufKey = StorageHelper.getEnrolledPUF(this) ?: "Uncalibrated"
            tvProgress.text = "Status: SECURED\nPUF: 0x${pufKey.take(16)}..."
            tvProgress.setTextColor(ColorStateList.valueOf(android.graphics.Color.GREEN))
            drawingCanvas.isEnabled = false
            tvCanvasInstructions.text = "Setup Completed\nDevice Protected"
            startLockScreenService()
        } else {
            resetEnrollmentState()
        }
    }

    private fun resetEnrollmentState() {
        enrollmentRunIndex = 0
        enrollmentRuns.clear()
        enrollmentPufSamplesX.clear()
        enrollmentPufSamplesY.clear()
        enrollmentPufSamplesZ.clear()
        
        tvProgress.text = "Step: 1 / 3"
        tvProgress.setTextColor(ColorStateList.valueOf(android.graphics.Color.CYAN))
        drawingCanvas.isEnabled = true
        tvCanvasInstructions.text = "Draw Gesture Here"
        drawingCanvas.clearCanvas()
    }

    private fun processEnrollmentAttempt() {
        if (motionIntentBuffer.size < 15) {
            Toast.makeText(this, "Gesture too short. Try again.", Toast.LENGTH_SHORT).show()
            drawingCanvas.clearCanvas()
            tvCanvasInstructions.visibility = View.VISIBLE
            return
        }

        // Standardize gesture length
        val normalized = DTW.normalizeSignal3D(motionIntentBuffer)
        val resampled = DTW.resampleSignal3D(normalized, 64)
        
        enrollmentRuns.add(resampled)
        
        // Save raw double arrays for PUF extraction
        enrollmentPufSamplesX.add(rawSensorBufferX.toDoubleArray())
        enrollmentPufSamplesY.add(rawSensorBufferY.toDoubleArray())
        enrollmentPufSamplesZ.add(rawSensorBufferZ.toDoubleArray())

        enrollmentRunIndex++
        drawingCanvas.clearCanvas()
        tvCanvasInstructions.visibility = View.VISIBLE

        if (enrollmentRunIndex < 3) {
            tvProgress.text = "Step: ${enrollmentRunIndex + 1} / 3"
        } else {
            // All 3 training runs collected, compile average template
            compileAndSaveEnrollment()
        }
    }

    private fun compileAndSaveEnrollment() {
        // Average coordinates across runs to form master template
        val size = 64
        val masterTemplate = ArrayList<List<Float>>()
        for (i in 0 until size) {
            var sumX = 0f
            var sumY = 0f
            var sumZ = 0f
            for (run in enrollmentRuns) {
                sumX += run[i][0]
                sumY += run[i][1]
                sumZ += run[i][2]
            }
            masterTemplate.add(listOf(sumX / 3f, sumY / 3f, sumZ / 3f))
        }
        
        val finalTemplate = DTW.normalizeSignal3D(masterTemplate)

        // Extract silicon PUF from first run buffers
        val pufKey = PUF.extractPUF(
            enrollmentPufSamplesX[0],
            enrollmentPufSamplesY[0],
            enrollmentPufSamplesZ[0]
        )

        // Save
        StorageHelper.saveEnrollment(this, finalTemplate, pufKey, 0.22f)
        
        Toast.makeText(this, "Enrollment completed!", Toast.LENGTH_LONG).show()
        updateUIState()
    }

    private fun startLockScreenService() {
        val serviceIntent = Intent(this, LockScreenService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun stopLockScreenService() {
        val serviceIntent = Intent(this, LockScreenService::class.java)
        stopService(serviceIntent)
    }
}

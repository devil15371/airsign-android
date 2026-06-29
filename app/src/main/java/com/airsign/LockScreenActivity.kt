package com.airsign

import android.app.KeyguardManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.airsign.signal.DTW
import com.airsign.signal.DrawingCanvas
import com.airsign.signal.PUF
import com.airsign.utils.StorageHelper

class LockScreenActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var tvStatusReport: TextView
    private lateinit var tvLockInstructions: TextView
    private lateinit var drawingCanvasLock: DrawingCanvas
    private lateinit var lockScreenRoot: View

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    // Capture buffers
    private val rawSensorBufferX = ArrayList<Double>()
    private val rawSensorBufferY = ArrayList<Double>()
    private val rawSensorBufferZ = ArrayList<Double>()
    private val motionIntentBuffer = ArrayList<List<Float>>()

    private var isRecording = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Apply flags to overlay the keyguard/lockscreen
        setupLockScreenFlags()

        setContentView(R.layout.activity_lock_screen)

        // Initialize UI Elements
        tvStatusReport = findViewById(R.id.tvStatusReport)
        tvLockInstructions = findViewById(R.id.tvLockInstructions)
        drawingCanvasLock = findViewById(R.id.drawingCanvasLock)
        lockScreenRoot = findViewById(R.id.lockScreenRoot)

        // Initialize Sensors
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        // Setup Drawing Canvas
        drawingCanvasLock.setDrawingListener(object : DrawingCanvas.DrawingListener {
            override fun onDrawStart() {
                tvLockInstructions.visibility = View.GONE
                tvStatusReport.text = "Recording Signature..."
                tvStatusReport.setTextColor(resources.getColor(android.R.color.holo_blue_light, theme))
                
                rawSensorBufferX.clear()
                rawSensorBufferY.clear()
                rawSensorBufferZ.clear()
                motionIntentBuffer.clear()
                isRecording = true
            }

            override fun onDrawing(x: Float, y: Float, speedX: Float, speedY: Float) {
                val forceX = speedX * 0.05f
                val forceY = -speedY * 0.05f
                motionIntentBuffer.add(listOf(forceX, forceY, 0f))
            }

            override fun onDrawEnd(points: List<android.graphics.PointF>) {
                isRecording = false
                verifyAttempt()
            }
        })
    }

    private fun setupLockScreenFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        
        // Keep screen on while locked screen is active
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // Dismiss keyguard manager
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            keyguardManager.requestDismissKeyguard(this, null)
        }
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
        tvStatusReport.text = "Ready for Verification"
        tvStatusReport.setTextColor(resources.getColor(android.R.color.holo_cyan_light, theme))
        drawingCanvasLock.clearCanvas()
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (isRecording && event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            rawSensorBufferX.add(event.values[0].toDouble())
            rawSensorBufferY.add(event.values[1].toDouble())
            rawSensorBufferZ.add(event.values[2].toDouble())
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // Disable standard system back press to prevent bypass
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Do nothing. Force user to authenticate via gesture
    }

    private fun verifyAttempt() {
        val enrolledGesture = StorageHelper.getEnrolledGesture(this)
        val enrolledPUF = StorageHelper.getEnrolledPUF(this)

        if (enrolledGesture == null || enrolledPUF == null) {
            Toast.makeText(this, "Device not enrolled. Opening configuration.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (motionIntentBuffer.size < 15) {
            tvStatusReport.text = "Gesture Too Quick! Draw complete path."
            tvStatusReport.setTextColor(resources.getColor(android.R.color.holo_orange_dark, theme))
            drawingCanvasLock.clearCanvas()
            tvLockInstructions.visibility = View.VISIBLE
            return
        }

        // 1. Process attempt gesture (low-pass smooth + scale normalize + resample)
        val normalized = DTW.normalizeSignal3D(motionIntentBuffer)
        val resampled = DTW.resampleSignal3D(normalized, 64)

        // 2. Perform DTW match check
        val gestureThresh = StorageHelper.getGestureThreshold(this)
        val gestureDist = DTW.dtwDistance(resampled, enrolledGesture)
        val gestureMatch = gestureDist <= gestureThresh

        // 3. Extract and compare Silicon PUF fingerprint
        val attemptPUF = PUF.extractPUF(
            rawSensorBufferX.toDoubleArray(),
            rawSensorBufferY.toDoubleArray(),
            rawSensorBufferZ.toDoubleArray()
        )
        val pufDist = PUF.hammingDistanceHex(attemptPUF, enrolledPUF)
        val pufMatch = pufDist <= 32 // Hamming bit mismatch threshold

        if (gestureMatch && pufMatch) {
            // Dual-factor authentication match! Dismiss and unlock phone
            tvStatusReport.text = "AUTHENTICATED"
            tvStatusReport.setTextColor(resources.getColor(android.R.color.holo_green_light, theme))
            
            Toast.makeText(this, "Welcome Back!", Toast.LENGTH_SHORT).show()
            finish() // Closes and unlocks
        } else {
            // Rejection alert
            drawingCanvasLock.clearCanvas()
            tvLockInstructions.visibility = View.VISIBLE
            
            if (!gestureMatch && !pufMatch) {
                tvStatusReport.text = "REJECTED: Gesture & Hardware Mismatch!"
            } else if (!gestureMatch) {
                tvStatusReport.text = "REJECTED: Incorrect Pattern Rhythm!"
            } else {
                tvStatusReport.text = "🔒 SPOOF DETECTED: Hardware Signature Mismatch!"
            }
            tvStatusReport.setTextColor(resources.getColor(android.R.color.holo_red_dark, theme))
        }
    }
}

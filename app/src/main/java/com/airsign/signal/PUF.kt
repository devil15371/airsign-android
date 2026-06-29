package com.airsign.signal

import java.security.MessageDigest
import kotlin.math.*

object PUF {

    /**
     * Extracts a 256-bit hexadecimal Silicon PUF key from the raw sensor stream
     */
    fun extractPUF(
        rawX: DoubleArray,
        rawY: DoubleArray,
        rawZ: DoubleArray,
        sampleRate: Double = 60.0
    ): String {
        if (rawX.size < 10) return "0000000000000000000000000000000000000000000000000000000000000000"

        // 1. DC bias offsets (DC Components)
        val biasX = SignalProcessor.mean(rawX)
        val biasY = SignalProcessor.mean(rawY)
        val biasZ = SignalProcessor.mean(rawZ)

        // 2. High-pass filter to isolate manufacturing micro-vibrations (> 8Hz)
        val hpFilterX = SignalProcessor.ButterworthFilter("highpass", 8.0, sampleRate)
        val hpFilterY = SignalProcessor.ButterworthFilter("highpass", 8.0, sampleRate)
        val hpFilterZ = SignalProcessor.ButterworthFilter("highpass", 8.0, sampleRate)

        val noiseX = DoubleArray(rawX.size) { hpFilterX.filter(rawX[it]) }
        val noiseY = DoubleArray(rawY.size) { hpFilterY.filter(rawY[it]) }
        val noiseZ = DoubleArray(rawZ.size) { hpFilterZ.filter(rawZ[it]) }

        // 3. FFT on noise profiles
        val fftX = SignalProcessor.fft(noiseX)
        val fftY = SignalProcessor.fft(noiseY)
        val fftZ = SignalProcessor.fft(noiseZ)

        // 4. Extract spectral and coupling features
        val rmsX = SignalProcessor.rms(noiseX)
        val rmsY = SignalProcessor.rms(noiseY)
        val rmsZ = SignalProcessor.rms(noiseZ)

        val centroidX = calculateSpectralCentroid(fftX, sampleRate)
        val centroidY = calculateSpectralCentroid(fftY, sampleRate)
        val centroidZ = calculateSpectralCentroid(fftZ, sampleRate)

        val flatnessX = calculateSpectralFlatness(fftX)
        val flatnessY = calculateSpectralFlatness(fftY)
        val flatnessZ = calculateSpectralFlatness(fftZ)

        val corrXY = SignalProcessor.pearson(noiseX, noiseY)
        val corrYZ = SignalProcessor.pearson(noiseY, noiseZ)
        val corrZX = SignalProcessor.pearson(noiseZ, noiseX)

        // 5. Build 64-dimensional hardware feature vector
        val featureVector = ArrayList<Float>()
        
        // Add scaled bias components
        featureVector.add((biasX * 100).toFloat())
        featureVector.add((biasY * 100).toFloat())
        featureVector.add((biasZ * 100).toFloat())

        // Add noise RMS amplitudes
        featureVector.add((rmsX * 200).toFloat())
        featureVector.add((rmsY * 200).toFloat())
        featureVector.add((rmsZ * 200).toFloat())

        // Add spectral centroids
        featureVector.add((centroidX / 10).toFloat())
        featureVector.add((centroidY / 10).toFloat())
        featureVector.add((centroidZ / 10).toFloat())

        // Add spectral flatness measurements
        featureVector.add((flatnessX * 50).toFloat())
        featureVector.add((flatnessY * 50).toFloat())
        featureVector.add((flatnessZ * 50).toFloat())

        // Add axis cross-talk couplings
        featureVector.add((corrXY * 100).toFloat())
        featureVector.add((corrYZ * 100).toFloat())
        featureVector.add((corrZX * 100).toFloat())

        // Pad to exactly 64 values
        while (featureVector.size < 64) {
            val idx = featureVector.size
            val paddedVal = (featureVector[idx % 10] * featureVector[(idx + 3) % 15]) % 1f
            featureVector.add(paddedVal)
        }

        // 6. Hash to 256-bit hex string
        val vectorString = featureVector.joinToString(",") { String.format("%.6f", it) }
        return sha256(vectorString)
    }

    private fun calculateSpectralCentroid(fftMags: DoubleArray, sampleRate: Double): Double {
        var num = 0.0
        var den = 0.0
        val binWidth = sampleRate / (fftMags.size * 2)

        for (i in fftMags.indices) {
            val freq = i * binWidth
            num += freq * fftMags[i]
            den += fftMags[i]
        }
        return if (den == 0.0) 0.0 else num / den
    }

    private fun calculateSpectralFlatness(fftMags: DoubleArray): Double {
        if (fftMags.isEmpty()) return 0.0
        var sum = 0.0
        var logSum = 0.0
        val len = fftMags.size

        for (i in fftMags.indices) {
            val v = max(fftMags[i], 1e-7) // avoid log(0)
            sum += v
            logSum += log(v, Math.E)
        }

        val geomMean = exp(logSum / len)
        val arithMean = sum / len
        return if (arithMean == 0.0) 0.0 else geomMean / arithMean
    }

    private fun sha256(input: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
            hash.joinToString("") { String.format("%02x", it) }
        } catch (e: Exception) {
            "0000000000000000000000000000000000000000000000000000000000000000"
        }
    }

    fun hammingDistanceHex(hex1: String, hex2: String): Int {
        if (hex1.length != hex2.length) return hex1.length * 4
        var dist = 0
        for (i in hex1.indices) {
            val v1 = hex1[i].toString().toInt(16)
            val v2 = hex2[i].toString().toInt(16)
            var xorVal = v1 xor v2
            while (xorVal > 0) {
                if (xorVal and 1 == 1) dist++
                xorVal = xorVal shr 1
            }
        }
        return dist
    }
}

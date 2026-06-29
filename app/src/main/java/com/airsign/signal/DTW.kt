package com.airsign.signal

import kotlin.math.*

object DTW {

    fun euclideanDistance3D(p1: List<Float>, p2: List<Float>): Float {
        val dx = p1[0] - p2[0]
        val dy = p1[1] - p2[1]
        val dz = p1[2] - p2[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * Standardizes a 3D signal to have zero mean and unit variance.
     */
    fun normalizeSignal3D(signal: List<List<Float>>): List<List<Float>> {
        val len = signal.size
        if (len == 0) return emptyList()

        // Mean
        var meanX = 0f
        var meanY = 0f
        var meanZ = 0f
        for (i in 0 until len) {
            meanX += signal[i][0]
            meanY += signal[i][1]
            meanZ += signal[i][2]
        }
        meanX /= len
        meanY /= len
        meanZ /= len

        // Variance
        var varX = 0f
        var varY = 0f
        var varZ = 0f
        for (i in 0 until len) {
            val dx = signal[i][0] - meanX
            val dy = signal[i][1] - meanY
            val dz = signal[i][2] - meanZ
            varX += dx * dx
            varY += dy * dy
            varZ += dz * dz
        }

        val stdX = if (varX > 0f) sqrt(varX / len) else 1f
        val stdY = if (varY > 0f) sqrt(varY / len) else 1f
        val stdZ = if (varZ > 0f) sqrt(varZ / len) else 1f

        val normalized = ArrayList<List<Float>>(len)
        for (i in 0 until len) {
            normalized.add(
                listOf(
                    (signal[i][0] - meanX) / stdX,
                    (signal[i][1] - meanY) / stdY,
                    (signal[i][2] - meanZ) / stdZ
                )
            )
        }
        return normalized
    }

    /**
     * Resamples a 3D signal to a fixed target length.
     */
    fun resampleSignal3D(signal: List<List<Float>>, targetLength: Int = 64): List<List<Float>> {
        val currentLength = signal.size
        if (currentLength == 0) return emptyList()
        if (currentLength == targetLength) return signal

        val resampled = ArrayList<List<Float>>(targetLength)
        for (i in 0 until targetLength) {
            val t = if (targetLength > 1) i.toFloat() / (targetLength - 1) else 0f
            val indexF = t * (currentLength - 1)
            val indexLow = floor(indexF).toInt()
            val indexHigh = ceil(indexF).toInt()
            val weight = indexF - indexLow

            val pLow = signal[indexLow]
            val pHigh = signal[indexHigh]

            val pInterp = listOf(
                pLow[0] * (1f - weight) + pHigh[0] * weight,
                pLow[1] * (1f - weight) + pHigh[1] * weight,
                pLow[2] * (1f - weight) + pHigh[2] * weight
            )
            resampled.add(pInterp)
        }
        return resampled
    }

    /**
     * Computes Dynamic Time Warping (DTW) distance with Sakoe-Chiba window constraint.
     */
    fun dtwDistance(s1: List<List<Float>>, s2: List<List<Float>>, windowPercentage: Float = 0.25f): Float {
        val n = s1.size
        val m = s2.size

        val w = max(ceil(max(n, m) * windowPercentage).toInt(), abs(n - m))

        val dtw = Array(n + 1) { FloatArray(m + 1) { Float.POSITIVE_INFINITY } }
        dtw[0][0] = 0f

        for (i in 1..n) {
            val jStart = max(1, i - w)
            val jEnd = min(m, i + w)
            for (j in jStart..jEnd) {
                val cost = euclideanDistance3D(s1[i - 1], s2[j - 1])
                dtw[i][j] = cost + min(
                    dtw[i - 1][j],
                    min(
                        dtw[i][j - 1],
                        dtw[i - 1][j - 1]
                    )
                )
            }
        }
        return dtw[n][m] / (n + m)
    }
}

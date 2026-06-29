package com.airsign.signal

import kotlin.math.*

class SignalProcessor {

    /**
     * 2nd Order Butterworth IIR Filter
     */
    class ButterworthFilter(type: String, cutoffFreq: Double, sampleRate: Double) {
        private var b0 = 0.0
        private var b1 = 0.0
        private var b2 = 0.0
        private var a1 = 0.0
        private var a2 = 0.0

        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        init {
            val w0 = 2.0 * Math.PI * cutoffFreq / sampleRate
            val cosW0 = cos(w0)
            val sinW0 = sin(w0)
            val alpha = sinW0 / sqrt(2.0) // Q = 0.707

            val rawB0: Double
            val rawB1: Double
            val rawB2: Double
            val rawA0: Double
            val rawA1: Double
            val rawA2: Double

            if (type == "highpass") {
                rawB0 = (1.0 + cosW0) / 2.0
                rawB1 = -(1.0 + cosW0)
                rawB2 = (1.0 + cosW0) / 2.0
                rawA0 = 1.0 + alpha
                rawA1 = -2.0 * cosW0
                rawA2 = 1.0 - alpha
            } else { // lowpass
                rawB0 = (1.0 - cosW0) / 2.0
                rawB1 = 1.0 - cosW0
                rawB2 = (1.0 - cosW0) / 2.0
                rawA0 = 1.0 + alpha
                rawA1 = -2.0 * cosW0
                rawA2 = 1.0 - alpha
            }

            b0 = rawB0 / rawA0
            b1 = rawB1 / rawA0
            b2 = rawB2 / rawA0
            a1 = rawA1 / rawA0
            a2 = rawA2 / rawA0
        }

        fun filter(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            return y
        }
    }

    companion object {
        /**
         * Cooley-Tukey Radix-2 FFT
         */
        fun fft(input: DoubleArray): DoubleArray {
            var n = input.size
            var size = n
            
            // Check if size is a power of 2
            val m = log2(n.toDouble())
            if (m % 1.0 != 0.0) {
                val nextPower = 2.0.pow(ceil(m)).toInt()
                val padded = DoubleArray(nextPower)
                System.arraycopy(input, 0, padded, 0, n)
                n = nextPower
                size = nextPower
            }

            val real = DoubleArray(size) { if (it < input.size) input[it] else 0.0 }
            val imag = DoubleArray(size)

            // Bit reversal
            var limit = 1
            var bit = size shr 1
            while (limit < size) {
                for (i in 0 until limit) {
                    if ((i and bit) == 0) {
                        val idx1 = i
                        val idx2 = i + limit

                        val tempR = real[idx2]
                        real[idx2] = real[idx1]
                        real[idx1] = tempR

                        val tempI = imag[idx2]
                        imag[idx2] = imag[idx1]
                        imag[idx1] = tempI
                    }
                }
                limit = limit shl 1
                bit = bit shr 1
            }

            // Decimation in time recursion
            var len = 2
            while (len <= size) {
                val wAngle = -2.0 * Math.PI / len
                val wRStep = cos(wAngle)
                val wIStep = sin(wAngle)

                for (i in 0 until size step len) {
                    var wR = 1.0
                    var wI = 0.0
                    val halfLen = len shr 1

                    for (j in 0 until halfLen) {
                        val uIdx = i + j
                        val vIdx = i + j + halfLen

                        val targetR = real[vIdx] * wR - imag[vIdx] * wI
                        val targetI = real[vIdx] * wI + imag[vIdx] * wR

                        real[vIdx] = real[uIdx] - targetR
                        imag[vIdx] = imag[uIdx] - targetI
                        real[uIdx] += targetR
                        imag[uIdx] += targetI

                        val nextWR = wR * wRStep - wI * wIStep
                        wI = wR * wIStep + wI * wRStep
                        wR = nextWR
                    }
                }
                len = len shl 1
            }

            // Magnitude spectrum for the first half bins
            val halfSize = size shr 1
            val magnitudes = DoubleArray(halfSize)
            for (i in 0 until halfSize) {
                magnitudes[i] = sqrt(real[i] * real[i] + imag[i] * imag[i])
            }
            return magnitudes
        }

        fun mean(arr: DoubleArray): Double {
            return if (arr.isEmpty()) 0.0 else arr.sum() / arr.size
        }

        fun rms(arr: DoubleArray): Double {
            if (arr.isEmpty()) return 0.0
            val sumSq = arr.fold(0.0) { sum, v -> sum + v * v }
            return sqrt(sumSq / arr.size)
        }

        fun pearson(a: DoubleArray, b: DoubleArray): Double {
            if (a.size != b.size || a.isEmpty()) return 0.0
            val meanA = mean(a)
            val meanB = mean(b)
            var num = 0.0
            var denA = 0.0
            var denB = 0.0

            for (i in a.indices) {
                val da = a[i] - meanA
                val db = b[i] - meanB
                num += da * db
                denA += da * da
                denB += db * db
            }
            val den = sqrt(denA * denB)
            return if (den == 0.0) 0.0 else num / den
        }
    }
}

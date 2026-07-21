package com.classic.camera

object ToneCurveEngine {
    init {
        System.loadLibrary("tonecurve")
    }

    fun generateLUT(points: FloatArray, lutSize: Int): FloatArray {
        return nativeGenerateLUT(points, lutSize)
    }

    fun evalAt(points: FloatArray, inputs: FloatArray): FloatArray {
        return nativeEvalAt(points, inputs)
    }

    private external fun nativeGenerateLUT(points: FloatArray, lutSize: Int): FloatArray
    private external fun nativeEvalAt(points: FloatArray, inputs: FloatArray): FloatArray
}

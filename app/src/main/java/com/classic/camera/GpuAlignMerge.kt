package com.classic.camera

import android.opengl.GLES30
import android.opengl.GLES31
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * GPU 加速多帧对齐融合管线（OpenGL ES 3.1+）。
 * 支持运行时 2~MAX_FRAMES 帧，实际帧数由 process() 的 frames.size 决定。
 * 所有方法必须在 GL 线程调用。
 */
class GpuAlignMerge {

    companion object {
        private const val TAG = "GpuAlignMerge"
        private const val GL_R16UI = 0x8234
        private const val GL_R32UI = 0x8236
        private const val GL_RED_INTEGER = 0x8D94
        private const val T_SIZE_2 = 16
        private const val MAX_FRAMES = 8
        // 金字塔层数：L0=2x, L1=4x, L2=8x, L3=16x, L4=32x（相对全分辨率）
        private const val NUM_LEVELS = 5
        private const val SBO_WEIGHT_BINDING = 5
    }

    private var initialized = false
    private var supported = false

    private var progCSBoxDown2 = 0
    private var progCSGaussDown2 = 0
    private var progAlign = 0
    private var progWeights = 0
    private var progMerge = 0

    // 传感器的白电平（用于金字塔归一化）。外部通过 process() 传入。
    private var sensorWhiteLevel: Float = 65535.0f

    private val frameTex = IntArray(MAX_FRAMES)
    private val pyrTex = IntArray(MAX_FRAMES * NUM_LEVELS)
    private val pyrTexW = IntArray(MAX_FRAMES * NUM_LEVELS)
    private val pyrTexH = IntArray(MAX_FRAMES * NUM_LEVELS)
    private var fbo = 0
    private var weightSSBO = 0
    private var level0SSBO = 0
    private var level1SSBO = 0
    private var level2SSBO = 0
    private var level3SSBO = 0
    private var level4SSBO = 0
    private var zeroSSBO = 0
    private var outSSBO = 0

    // 上传用复用 buffer
    private var reuseByteBuf: java.nio.ByteBuffer? = null

    // ===================== 初始化 =====================

    fun checkSupport(): Boolean {
        val ver = GLES30.glGetString(GLES30.GL_VERSION) ?: ""
        val major = IntArray(1)
        val minor = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAJOR_VERSION, major, 0)
        GLES30.glGetIntegerv(GLES30.GL_MINOR_VERSION, minor, 0)
        val es31 = (major[0] > 3) || (major[0] == 3 && minor[0] >= 1)
        // 检查 GL_EXT_gpu_shader5 扩展（sampler 数组动态索引需要）
        val extStr = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: ""
        val hasExt = extStr.contains("GL_EXT_gpu_shader5")
        supported = es31 && hasExt
        android.util.Log.d(TAG, "GL_VERSION=$ver (major=${major[0]} minor=${minor[0]}), compute=$es31, gpu_shader5=$hasExt, supported=$supported")
        return supported
    }

    fun init() {
        // 先无条件释放：若上一轮 GL 上下文已被销毁，frameTex/pyrTex/outSSBO 等都是
        // 失效名字；如果 checkSupport() 在这种瞬态下误判返回 false，又没有 release，
        // initialized 会保留 true 导致 process() 沿用失效名字 → texelFetch 全 0 → 黑屏。
        // 即使 checkSupport 失败此处的释放也保证 initialized=false、资源名清零。
        releaseGL()
        if (!checkSupport()) {
            Log.w(TAG, "ES 3.1 not supported, GPU fallback")
            return
        }
        initGL()
    }

    fun initGL() {
        releaseGL()
        try {
            progAlign = createComputeProgram(CS_ALIGN)
            progWeights = createComputeProgram(CS_WEIGHTS)
            progMerge = createComputeProgram(CS_MERGE)
            progCSBoxDown2 = createComputeProgram(CS_BOX_DOWN2)
            progCSGaussDown2 = createComputeProgram(CS_GAUSS_DOWN2)
            GLES30.glGenTextures(MAX_FRAMES, frameTex, 0)
            for (i in 0 until MAX_FRAMES) {
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frameTex[i])
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            }
            GLES30.glGenTextures(MAX_FRAMES * NUM_LEVELS, pyrTex, 0)
            val fboArr = IntArray(1); GLES30.glGenFramebuffers(1, fboArr, 0); fbo = fboArr[0]
            val outBuf = IntArray(1)
            GLES30.glGenBuffers(1, outBuf, 0)
            outSSBO = outBuf[0]
            initialized = true
            Log.d(TAG, "GPU pipeline ready")
        } catch (e: Exception) {
            Log.e(TAG, "initGL failed", e)
            releaseGL()
            supported = false
        }
    }

    fun releaseGL() {
        if (frameTex[0] != 0) { GLES30.glDeleteTextures(MAX_FRAMES, frameTex, 0); frameTex.fill(0) }
        if (pyrTex[0] != 0) { GLES30.glDeleteTextures(MAX_FRAMES * NUM_LEVELS, pyrTex, 0); pyrTex.fill(0) }
        pyrTexW.fill(0); pyrTexH.fill(0)
        if (fbo != 0) { GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0); fbo = 0 }
        if (level0SSBO != 0) { GLES31.glDeleteBuffers(1, intArrayOf(level0SSBO), 0); level0SSBO = 0 }
        if (level1SSBO != 0) { GLES31.glDeleteBuffers(1, intArrayOf(level1SSBO), 0); level1SSBO = 0 }
        if (level2SSBO != 0) { GLES31.glDeleteBuffers(1, intArrayOf(level2SSBO), 0); level2SSBO = 0 }
        if (level3SSBO != 0) { GLES31.glDeleteBuffers(1, intArrayOf(level3SSBO), 0); level3SSBO = 0 }
        if (level4SSBO != 0) { GLES31.glDeleteBuffers(1, intArrayOf(level4SSBO), 0); level4SSBO = 0 }
        if (zeroSSBO != 0) { GLES31.glDeleteBuffers(1, intArrayOf(zeroSSBO), 0); zeroSSBO = 0 }
        if (outSSBO != 0) { GLES31.glDeleteBuffers(1, intArrayOf(outSSBO), 0); outSSBO = 0 }
        if (weightSSBO != 0) { GLES31.glDeleteBuffers(1, intArrayOf(weightSSBO), 0); weightSSBO = 0 }
        if (progCSBoxDown2 != 0) { GLES30.glDeleteProgram(progCSBoxDown2); progCSBoxDown2 = 0 }
        if (progCSGaussDown2 != 0) { GLES30.glDeleteProgram(progCSGaussDown2); progCSGaussDown2 = 0 }
        if (progAlign != 0) { GLES31.glDeleteProgram(progAlign); progAlign = 0 }
        if (progWeights != 0) { GLES31.glDeleteProgram(progWeights); progWeights = 0 }
        if (progMerge != 0) { GLES30.glDeleteProgram(progMerge); progMerge = 0 }
        reuseByteBuf = null
        initialized = false
    }

    fun isSupported(): Boolean = supported && initialized

    fun getFrameTexId(index: Int): Int {
        return frameTex[index]
    }

    /**
     * 对齐 + 融合多帧为单帧 ShortArray。
     * 返回 null 表示失败（调用者应回退 CPU）。
     * @param whiteLevel 传感器的白电平（如 1023 或 4095），用于金字塔归一化。
     * frames.size 不得超过 MAX_FRAMES(8)。
     */
    fun process(frames: Array<ShortArray>, w: Int, h: Int, numTx: Int, numTy: Int, whiteLevel: Float = 65535.0f): ShortArray {
        if (!initialized) {
            Log.w(TAG, "process: not initialized, calling init() now")
            init()
        }
        if (!initialized) throw IllegalStateException("GpuAlignMerge init() failed")
        sensorWhiteLevel = whiteLevel
        val nf = minOf(frames.size, MAX_FRAMES)
        try {
            uploadFrames(frames, w, h, nf)

            buildPyramids(w, h, nf)
            alignAllFrames(w, h, nf, numTx, numTy)
            computeWeights(nf, numTx, numTy)
            return merge(w, h, nf, numTx, numTy)
        } finally {
            // 1. 强制解绑所有 Image Textures
            GLES31.glBindImageTexture(0, 0, 0, false, 0, GLES31.GL_READ_ONLY, GLES30.GL_R32F)
            GLES31.glBindImageTexture(8, 0, 0, false, 0, GLES31.GL_READ_ONLY, GLES30.GL_RGBA8)

            // 2. 解绑 SSBO 和其他所有 Buffer
            GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 2, 0)
            GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 3, 0)
            GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 4, 0)
            GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, SBO_WEIGHT_BINDING, 0)
            GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
            GLES30.glBindBuffer(GLES30.GL_UNIFORM_BUFFER, 0)
            GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, 0)

            // 3. 解绑所有被占用的纹理单元 (0 到 4)
            for (i in 0..4) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + i)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            }
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)

            // 4. 解绑 FBO 及其挂载点
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

            // 5. 清空 Program
            GLES31.glUseProgram(0)

            // 6. 循环清除所有可能残留的 GL 错误
            while (GLES30.glGetError() != GLES30.GL_NO_ERROR) { }
        }
    }

    // ===================== 实现 =====================

    private fun uploadFrames(frames: Array<ShortArray>, w: Int, h: Int, nf: Int) {
        val count = w * h
        val byteCount = count * 2
        val bb = reuseByteBuf?.let { if (it.capacity() >= byteCount) { it.clear(); it.limit(byteCount); it } else null }
            ?: ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder()).also { reuseByteBuf = it }
        for (i in 0 until nf) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frameTex[i])
            bb.clear().limit(byteCount)
            bb.asShortBuffer().put(frames[i], 0, count)
            bb.position(0)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GL_R16UI, w, h, 0,
                GL_RED_INTEGER, GLES30.GL_UNSIGNED_SHORT, bb)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        }
    }

    private fun buildPyramids(w: Int, h: Int, nf: Int) {
        // 每级 2x 下采样：L0=2x, L1=4x, L2=8x, L3=16x, L4=32x（相对全分辨率）
        val lw = IntArray(NUM_LEVELS)
        val lh = IntArray(NUM_LEVELS)
        lw[0] = (w + 1) / 2; lh[0] = (h + 1) / 2
        for (l in 1 until NUM_LEVELS) {
            lw[l] = (lw[l - 1] + 1) / 2
            lh[l] = (lh[l - 1] + 1) / 2
        }

        for (f in 0 until nf) {
            for (l in 0 until NUM_LEVELS) {
                val idx = pt(f, l)
                initTex(pyrTex[idx], lw[l], lh[l], idx)
            }
        }

        val ntx = IntArray(NUM_LEVELS)
        val nty = IntArray(NUM_LEVELS)
        for (l in 0 until NUM_LEVELS) {
            ntx[l] = (lw[l] + 15) / 16
            nty[l] = (lh[l] + 15) / 16
        }

        val uBoxSrcLoc = GLES31.glGetUniformLocation(progCSBoxDown2, "uSrc")
        val uBoxSizeLoc = GLES31.glGetUniformLocation(progCSBoxDown2, "uSrcSize")
        val uBoxWlLoc = GLES31.glGetUniformLocation(progCSBoxDown2, "uWhiteLevel")
        val uGaussSrcLoc = GLES31.glGetUniformLocation(progCSGaussDown2, "uSrc")
        val uGaussSizeLoc = GLES31.glGetUniformLocation(progCSGaussDown2, "uSrcSize")

        for (f in 0 until nf) {
            // L0：全分辨率 RAW → 2x2 box
            GLES31.glUseProgram(progCSBoxDown2)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frameTex[f])
            GLES31.glUniform1i(uBoxSrcLoc, 0)
            GLES31.glUniform2i(uBoxSizeLoc, w, h)
            GLES31.glUniform1f(uBoxWlLoc, sensorWhiteLevel)
            GLES31.glBindImageTexture(0, pyrTex[pt(f, 0)], 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA8)
            GLES31.glDispatchCompute(ntx[0], nty[0], 1)
            GLES31.glMemoryBarrier(GLES31.GL_TEXTURE_FETCH_BARRIER_BIT or GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)

            // L1..L4：逐级 2x 高斯下采样
            GLES31.glUseProgram(progCSGaussDown2)
            GLES31.glUniform1i(uGaussSrcLoc, 0)
            for (l in 1 until NUM_LEVELS) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, pyrTex[pt(f, l - 1)])
                GLES31.glUniform2i(uGaussSizeLoc, lw[l - 1], lh[l - 1])
                GLES31.glBindImageTexture(0, pyrTex[pt(f, l)], 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA8)
                GLES31.glDispatchCompute(ntx[l], nty[l], 1)
                GLES31.glMemoryBarrier(GLES31.GL_TEXTURE_FETCH_BARRIER_BIT or GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)
            }
        }
    }

    private fun alignAllFrames(w: Int, h: Int, nf: Int, numTx: Int, numTy: Int) {
        val lw = IntArray(NUM_LEVELS)
        val lh = IntArray(NUM_LEVELS)
        lw[0] = (w + 1) / 2; lh[0] = (h + 1) / 2
        for (l in 1 until NUM_LEVELS) {
            lw[l] = (lw[l - 1] + 1) / 2
            lh[l] = (lh[l - 1] + 1) / 2
        }

        val gx = IntArray(NUM_LEVELS)
        val gy = IntArray(NUM_LEVELS)
        for (l in 0 until NUM_LEVELS) {
            gx[l] = (lw[l] + T_SIZE_2 - 1) / T_SIZE_2
            gy[l] = (lh[l] + T_SIZE_2 - 1) / T_SIZE_2
        }
        // L0 由调用方按 32px 全分辨率 tile 给出，保持与 merge/weights 一致
        gx[0] = numTx; gy[0] = numTy

        ensureSSBO(::level0SSBO, (nf - 1) * gx[0] * gy[0] * 2 * 4)
        ensureSSBO(::level1SSBO, gx[1] * gy[1] * 2 * 4)
        ensureSSBO(::level2SSBO, gx[2] * gy[2] * 2 * 4)
        ensureSSBO(::level3SSBO, gx[3] * gy[3] * 2 * 4)
        ensureSSBO(::level4SSBO, gx[4] * gy[4] * 2 * 4)

        if (zeroSSBO == 0) {
            val ids = IntArray(1); GLES31.glGenBuffers(1, ids, 0); zeroSSBO = ids[0]
        }
        val zeroSize = gx[NUM_LEVELS - 1] * gy[NUM_LEVELS - 1] * 2 * 4
        val zeroArr = ByteArray(zeroSize)
        val zeroBuf = ByteBuffer.allocateDirect(zeroSize).order(ByteOrder.nativeOrder()).put(zeroArr)
        zeroBuf.position(0)
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, zeroSSBO)
        GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, zeroSize, zeroBuf, GLES31.GL_STATIC_DRAW)

        val uRefLoc = GLES31.glGetUniformLocation(progAlign, "uRef")
        val uAltLoc = GLES31.glGetUniformLocation(progAlign, "uAlt")
        val uGridSizeLoc = GLES31.glGetUniformLocation(progAlign, "uGridSize")
        val uPrevGridLoc = GLES31.glGetUniformLocation(progAlign, "uPrevGrid")
        val uScaleLoc = GLES31.glGetUniformLocation(progAlign, "uScale")
        val uTileStepLoc = GLES31.glGetUniformLocation(progAlign, "uTileStep")
        val uMinMaxLoc = GLES31.glGetUniformLocation(progAlign, "uMinMax")
        val uFrameBaseLoc = GLES31.glGetUniformLocation(progAlign, "uFrameBase")

        // 每层的搜索中心限幅（单位：该层像素）。L4 从零开始。
        val minMax = arrayOf(
            intArrayOf(-84, 63),  // L0: 2x
            intArrayOf(-40, 30),  // L1: 4x
            intArrayOf(-20, 15),  // L2: 8x
            intArrayOf(-8, 8),    // L3: 16x
            intArrayOf(0, 0),     // L4: 32x
        )
        val ssbo = intArrayOf(level0SSBO, level1SSBO, level2SSBO, level3SSBO, level4SSBO)

        // 每副帧独立做 5 层对齐（L4 → L0）
        for (altF in 1 until nf) {
            GLES31.glUseProgram(progAlign)
            for (l in NUM_LEVELS - 1 downTo 0) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, pyrTex[pt(0, l)])
                GLES30.glUniform1i(uRefLoc, 0)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, pyrTex[pt(altF, l)])
                GLES30.glUniform1i(uAltLoc, 1)

                // 最粗层用零缓冲作为 prev；其余层用上一层的偏移
                val prevBuf = if (l == NUM_LEVELS - 1) zeroSSBO else ssbo[l + 1]
                val prevGx = if (l == NUM_LEVELS - 1) gx[l] else gx[l + 1]
                val prevGy = if (l == NUM_LEVELS - 1) gy[l] else gy[l + 1]

                GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 2, prevBuf)
                GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 3, ssbo[l])
                GLES31.glUniform2i(uGridSizeLoc, gx[l], gy[l])
                GLES31.glUniform2i(uPrevGridLoc, prevGx, prevGy)
                GLES31.glUniform1i(uScaleLoc, 2)
                GLES31.glUniform1i(uTileStepLoc, 2)
                GLES31.glUniform2i(uMinMaxLoc, minMax[l][0], minMax[l][1])
                GLES31.glUniform1i(uFrameBaseLoc, if (l == 0) (altF - 1) * gx[0] * gy[0] * 2 else 0)
                GLES31.glDispatchCompute(gx[l], gy[l], 1)
                GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
            }
        }
    }

    private fun computeWeights(nf: Int, numTx: Int, numTy: Int) {
        // 确保权重 SSBO 始终存在（merge 也需要它）
        val wgtSize = numTx * numTy * MAX_FRAMES * 4
        ensureSSBO(::weightSSBO, wgtSize)
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, SBO_WEIGHT_BINDING, weightSSBO)
        if (nf <= 1) return

        GLES31.glUseProgram(progWeights)
        // 所有副帧的偏移 SSBO 绑定到 slot 2
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 2, level0SSBO)

        GLES31.glUniform2i(GLES31.glGetUniformLocation(progWeights, "uGridSize"), numTx, numTy)
        GLES31.glUniform1i(GLES31.glGetUniformLocation(progWeights, "uNumFrames"), nf)
        GLES31.glUniform1i(GLES31.glGetUniformLocation(progWeights, "uMaxFrames"), MAX_FRAMES)
        GLES31.glUniform1i(GLES31.glGetUniformLocation(progWeights, "uFrameBase"), 0)
        GLES31.glUniform1f(GLES31.glGetUniformLocation(progWeights, "uWhiteLevel"), sensorWhiteLevel)

        // 绑定帧的 half-res 纹理到 sampler 数组
        for (i in 0 until nf) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + i)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, pyrTex[pt(i, 0)])
        }
        val uHalfLoc = GLES31.glGetUniformLocation(progWeights, "uHalf")
        if (uHalfLoc >= 0) {
            val units = IntArray(MAX_FRAMES) { it }
            GLES31.glUniform1iv(uHalfLoc, MAX_FRAMES, units, 0)
        }

        GLES31.glDispatchCompute(numTx, numTy, 1)
        GLES31.glMemoryBarrier(GLES31.GL_TEXTURE_FETCH_BARRIER_BIT or GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
    }

    private fun merge(w: Int, h: Int, nf: Int, numTx: Int, numTy: Int): ShortArray {
        GLES31.glUseProgram(progMerge)

        // 绑定原始帧纹理到 sampler 数组 uTex
        for (i in 0 until nf) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + i)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frameTex[i])
        }
        val uTexLoc = GLES30.glGetUniformLocation(progMerge, "uTex")
        if (uTexLoc >= 0) {
            val units = IntArray(MAX_FRAMES) { it }
            GLES30.glUniform1iv(uTexLoc, MAX_FRAMES, units, 0)
        }

        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 3, level0SSBO)
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, SBO_WEIGHT_BINDING, weightSSBO)

        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 4, outSSBO)
        GLES30.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, w * h * 4, null, GLES30.GL_DYNAMIC_READ)

        GLES31.glUniform2i(GLES31.glGetUniformLocation(progMerge, "uImgSize"), w, h)
        GLES31.glUniform2i(GLES31.glGetUniformLocation(progMerge, "uGridSize"), numTx, numTy)
        GLES31.glUniform1i(GLES31.glGetUniformLocation(progMerge, "uNumFrames"), nf)
        GLES31.glUniform1i(GLES31.glGetUniformLocation(progMerge, "uMaxFrames"), MAX_FRAMES)

        val numGroupsX = (w + 15) / 16
        val numGroupsY = (h + 15) / 16
        GLES31.glDispatchCompute(numGroupsX, numGroupsY, 1)
        GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)

        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, outSSBO)
        val buf = GLES30.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, w * h * 4, GLES30.GL_MAP_READ_BIT) as java.nio.ByteBuffer
        buf.order(ByteOrder.nativeOrder())

        val intBuf = buf.asIntBuffer()
        val result = ShortArray(w * h)
        for (i in 0 until w * h) {
            result[i] = intBuf.get(i).toShort()
        }
        GLES30.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0)

        return result
    }

    // ===================== 工具 =====================

    private fun pt(f: Int, l: Int) = f * NUM_LEVELS + l

    private fun initTex(texId: Int, tw: Int, th: Int, idx: Int) {
        if (pyrTexW[idx] == tw && pyrTexH[idx] == th) return
        if (pyrTexW[idx] != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(texId), 0)
            val newId = IntArray(1)
            GLES30.glGenTextures(1, newId, 0)
            pyrTex[idx] = newId[0]
        }
        val actualId = pyrTex[idx]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, actualId)
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, tw, th)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        pyrTexW[idx] = tw
        pyrTexH[idx] = th
    }

    private fun ensureSSBO(idRef: kotlin.reflect.KMutableProperty0<Int>, minBytes: Int) {
        if (idRef.get() == 0) {
            val ids = IntArray(1)
            GLES31.glGenBuffers(1, ids, 0)
            idRef.set(ids[0])
        }
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, idRef.get())
        GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, minBytes, null, GLES31.GL_DYNAMIC_COPY)
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0)
    }

    // ===================== 释放 =====================

    fun release() {
        releaseGL()
    }

    // ===================== Shaders =====================

    private val CS_BOX_DOWN2 = """
        #version 310 es
        precision highp image2D;
        layout(local_size_x=16,local_size_y=16) in;
        uniform highp usampler2D uSrc;
        uniform ivec2 uSrcSize;
        uniform float uWhiteLevel;
        layout(rgba8, binding=0) writeonly uniform highp image2D uDst;
        void main() {
            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
            ivec2 base = p * 2;
            float sum = 0.0;
            int cnt = 0;
            float wl = max(uWhiteLevel, 1.0);
            for (int dy = 0; dy < 2; dy++) {
                for (int dx = 0; dx < 2; dx++) {
                    ivec2 srcPos = base + ivec2(dx, dy);
                    if (srcPos.x < uSrcSize.x && srcPos.y < uSrcSize.y) {
                        sum += float(texelFetch(uSrc, srcPos, 0).r) / wl;
                        cnt++;
                    }
                }
            }
            float val = (cnt > 0) ? sum / float(cnt) : 0.0;
            imageStore(uDst, p, vec4(val, 0.0, 0.0, 1.0));
        }
    """.trimIndent()

    private val CS_GAUSS_DOWN2 = """
        #version 310 es
        precision highp sampler2D; precision highp image2D;
        layout(local_size_x=16,local_size_y=16) in;
        uniform sampler2D uSrc; uniform ivec2 uSrcSize;
        layout(rgba8,binding=0) writeonly uniform highp image2D uDst;
        void main() {
            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
            ivec2 cp = p * 2;
            if (cp.x >= uSrcSize.x || cp.y >= uSrcSize.y) { return; }
            const float k[5] = float[](1.0,4.0,6.0,4.0,1.0);
            float sum = 0.0; float tw = 0.0;
            for (int dy=-2; dy<=2; dy++) for (int dx=-2; dx<=2; dx++) {
                int sx = clamp(cp.x+dx, 0, uSrcSize.x-1);
                int sy = clamp(cp.y+dy, 0, uSrcSize.y-1);
                float w = k[dx+2] * k[dy+2];
                sum += texelFetch(uSrc, ivec2(sx,sy), 0).r * w; tw += w;
            }
            imageStore(uDst, p, vec4(sum / tw, 0.0, 0.0, 1.0));
        }
    """.trimIndent()

    private val CS_ALIGN = """
        #version 310 es
        precision highp sampler2D;
        layout(local_size_x = 16, local_size_y = 16) in;
        uniform sampler2D uRef;
        uniform sampler2D uAlt;
        layout(std430, binding = 2) buffer Prev { int prev[]; };
        layout(std430, binding = 3) buffer OutBuf { int outData[]; };
        uniform ivec2 uGridSize; uniform ivec2 uPrevGrid;
        uniform int uScale; uniform int uTileStep; uniform ivec2 uMinMax;
        uniform int uFrameBase;

        shared float sRef[256];
        shared float sDiff[256];
        shared float sCnt[256];
        void main() {
            uint tx = gl_WorkGroupID.x, ty = gl_WorkGroupID.y;
            uint lx = gl_LocalInvocationID.x, ly = gl_LocalInvocationID.y, tid = ly*16u+lx;
            ivec2 sz = textureSize(uRef, 0);
            int gx = int(tx*16u+lx);
            int gy = int(ty*16u+ly);
            bool valid = (gx < sz.x && gy < sz.y);
            gx = min(gx, sz.x-1);
            gy = min(gy, sz.y-1);
            sRef[tid] = texelFetch(uRef, ivec2(gx,gy), 0).r;
            memoryBarrierShared(); barrier();

            int pt = clamp(int(tx)/uTileStep, 0, uPrevGrid.x-1);
            int pp = clamp(int(ty)/uTileStep, 0, uPrevGrid.y-1);
            int bi = (pp*uPrevGrid.x+pt)*2;
            int bx = int(clamp(float(prev[bi]*uScale), float(uMinMax.x), float(uMinMax.y)));
            int by = int(clamp(float(prev[bi+1]*uScale), float(uMinMax.x), float(uMinMax.y)));

            float best = 1e10; int bdx=0,bdy=0;
            for (int dy=-4; dy<=4; dy++) for (int dx=-4; dx<=4; dx++) {
                int ox = bx+dx, oy = by+dy;
                int ax = gx+ox, ay = gy+oy;
                bool inBound = (ax >= 0 && ay >= 0 && ax < sz.x && ay < sz.y);
                int cax = clamp(ax,0,sz.x-1), cay = clamp(ay,0,sz.y-1);
                float av = texelFetch(uAlt,ivec2(cax,cay),0).r;
                // 仅对参考与副帧均有效的像素累计差异；越界像素既不计差异也不计数量，
                // 避免 clamp 产生的虚假边缘差异，同时不偏向把内容移出画面的偏移。
                float contrib = (valid && inBound) ? 1.0 : 0.0;
                float diff = (valid && inBound) ? abs(sRef[tid] - av) : 0.0;
                // 中心偏移微惩罚：平坦区平局时偏向零偏移
                diff += (valid && inBound) ? 1e-4 * (float(abs(ox)) + float(abs(oy))) : 0.0;
                sDiff[tid] = diff;
                sCnt[tid] = contrib;
                memoryBarrierShared(); barrier();
                for (int s=128; s>0; s>>=1) {
                    if (tid < uint(s)) {
                        sDiff[tid] += sDiff[tid+uint(s)];
                        sCnt[tid] += sCnt[tid+uint(s)];
                    }
                    memoryBarrierShared(); barrier();
                }
                if (tid==0u && sCnt[0] > 0.0 && sDiff[0] / sCnt[0] < best) {
                    best = sDiff[0] / sCnt[0]; bdx=ox; bdy=oy;
                }
                barrier();
            }
            if (tid==0u) {
                int idx = uFrameBase + (int(ty) * uGridSize.x + int(tx)) * 2;
                outData[idx]=bdx; outData[idx+1]=bdy;
            }
        }
    """.trimIndent()

    private val CS_WEIGHTS = """
        #version 310 es
        #extension GL_EXT_gpu_shader5 : require
        precision highp sampler2D;
        precision highp image2D;
        layout(local_size_x = 16, local_size_y = 16) in;
        const int _MAX_FRAMES_ = $MAX_FRAMES;
        uniform sampler2D uHalf[_MAX_FRAMES_];
        uniform float uWhiteLevel;
        layout(std430, binding=2) buffer Off { int offs[]; };
        layout(std430, binding=$SBO_WEIGHT_BINDING) buffer WgtBuf { float wgtOut[]; };
        uniform ivec2 uGridSize; uniform int uNumFrames; uniform int uMaxFrames; uniform int uFrameBase;
        shared float sL1[256];
        void main() {
            uint tx=gl_WorkGroupID.x, ty=gl_WorkGroupID.y;
            uint tid=gl_LocalInvocationIndex; uint lx=gl_LocalInvocationID.x,ly=gl_LocalInvocationID.y;
            ivec2 sz=textureSize(uHalf[0],0);
            int orig_hx=int(tx*16u+lx); int orig_hy=int(ty*16u+ly);
            bool valid=(orig_hx<sz.x && orig_hy<sz.y);
            int hx=min(orig_hx,sz.x-1); int hy=min(orig_hy,sz.y-1);
            float rv=texelFetch(uHalf[0],ivec2(hx,hy),0).r;
            int base = (int(ty) * uGridSize.x + int(tx)) * uMaxFrames;
            if (tid==0u) { wgtOut[base] = 1.0; }
            int fi=uGridSize.x*uGridSize.y*2;
            int baseBi = uFrameBase + (int(ty) * uGridSize.x + int(tx)) * 2;
            float wl = max(uWhiteLevel, 1.0);
            for (int f=1; f<uNumFrames; f++) {
                int bi = baseBi + (f-1)*fi;
                int ox = offs[bi];
                int oy = offs[bi+1];
                int ahx=clamp(hx+ox,0,sz.x-1); int ahy=clamp(hy+oy,0,sz.y-1);
                float av = texelFetch(uHalf[f], ivec2(ahx, ahy), 0).r;
                sL1[tid] = valid ? abs(rv - av) : 0.0;
                memoryBarrierShared(); barrier();
                for (int s=128; s>0; s>>=1) {
                    if (tid < uint(s)) sL1[tid] += sL1[tid+uint(s)];
                    memoryBarrierShared(); barrier();
                }
                if (tid==0u) {
                    float avg=sL1[0]/256.0;
                    float nd=max(1.0, avg*wl/8.0-10.0/8.0);
                    float wgt = (nd <= (300.0-10.0)/8.0) ? min(1.0/nd,10.0) : 0.0;
                    // 边缘衰减：靠近图像边界的 tile 对齐不可靠，按距离降低副帧权重
                    float hcx = float(tx)*16.0 + 8.0;
                    float hcy = float(ty)*16.0 + 8.0;
                    float dx = min(hcx, float(sz.x) - hcx);
                    float dy = min(hcy, float(sz.y) - hcy);
                    float edgeD = min(dx, dy);
                    float edgeW = clamp(edgeD / 32.0, 0.0, 1.0);
                    wgtOut[base + f] = wgt * edgeW;
                }
                memoryBarrierShared(); barrier();
            }
        }
    """.trimIndent()

    private val CS_MERGE = """
        #version 310 es
        #extension GL_EXT_gpu_shader5 : require
        precision highp usampler2D; precision highp int;
        layout(local_size_x=16,local_size_y=16) in;
        const int _MAX_FRAMES_ = $MAX_FRAMES;
        uniform highp usampler2D uTex[_MAX_FRAMES_]; uniform ivec2 uImgSize; uniform ivec2 uGridSize;
        uniform int uNumFrames; uniform int uMaxFrames;
        layout(std430,binding=3) readonly buffer Off { int offs[]; };
        layout(std430,binding=$SBO_WEIGHT_BINDING) buffer WgtBuf { float wgtOut[]; };
        layout(std430,binding=4) writeonly buffer OutBuf { uint outData[]; };
        float hann(int i) {
            float a=3.14159265*(float(i)+0.5)/64.0;
            return 0.5-0.5*cos(2.0*a);
        }
        void main() {
            ivec2 p=ivec2(gl_GlobalInvocationID.xy);
            int x=p.x, y=p.y;
            if (x>=uImgSize.x||y>=uImgSize.y) return;
            int t0x=x/32-1, t1x=x/32, t0y=y/32-1, t1y=y/32;
            int c0x=clamp(t0x,0,uGridSize.x-1),c1x=clamp(t1x,0,uGridSize.x-1);
            int c0y=clamp(t0y,0,uGridSize.y-1),c1y=clamp(t1y,0,uGridSize.y-1);
            int p0x=(x%32)+32, p1x=x%32, p0y=(y%32)+32, p1y=y%32;
            float wx0=hann(p0x),wx1=hann(p1x),wy0=hann(p0y),wy1=hann(p1y);
            float res=0.0; int fi=uGridSize.x*uGridSize.y*2;
            for (int i=0;i<4;i++) {
                int tx=(i&1)==0?c0x:c1x, ty=i<2?c0y:c1y;
                float wx=(i&1)==0?wx0:wx1, wy=i<2?wy0:wy1;
                int bi=(ty*uGridSize.x+tx)*2;
                int base = (ty * uGridSize.x + tx) * uMaxFrames;
                float r0 = float(texelFetch(uTex[0], clamp(p, ivec2(0), uImgSize-1), 0).r) / 65535.0;
                float sum=r0; float tw=1.0;
                for (int f=1; f<uNumFrames; f++) {
                    float w = wgtOut[base + f];
                    if (w > 0.001) {
                        int ofsIdx = (f-1) * fi + bi;
                        int ax = offs[ofsIdx] * 2;
                        int ay = offs[ofsIdx + 1] * 2;
                        sum += float(texelFetch(uTex[f], clamp(p+ivec2(ax,ay), ivec2(0), uImgSize-1), 0).r) / 65535.0 * w;
                        tw += w;
                    }
                }
                res+=wx*wy*(sum/tw);
            }
            float n=wx0*wy0+wx1*wy0+wx0*wy1+wx1*wy1;
            float fv=clamp(res/n,0.0,1.0);
            outData[y * uImgSize.x + x] = uint(fv * 65535.0);
        }
    """.trimIndent()

    private fun compileShader(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(s)
            GLES30.glDeleteShader(s)
            throw RuntimeException("shader error: $log\n${src.take(200)}")
        }
        return s
    }

    private fun createComputeProgram(src: String): Int {
        val c = compileShader(GLES31.GL_COMPUTE_SHADER, src)
        val p = GLES31.glCreateProgram()
        GLES31.glAttachShader(p, c)
        GLES31.glLinkProgram(p)
        val ok = IntArray(1)
        GLES31.glGetProgramiv(p, GLES31.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("compute link: ${GLES31.glGetProgramInfoLog(p)}")
        GLES31.glDeleteShader(c)
        return p
    }
}

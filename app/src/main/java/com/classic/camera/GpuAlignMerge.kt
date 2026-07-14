package com.classic.camera

import android.opengl.GLES30
import android.opengl.GLES31
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * GPU 加速多帧对齐融合管线（OpenGL ES 3.1+）。
 *
 * 所有方法必须在 GL 线程调用。
 */
class GpuAlignMerge {

    companion object {
        private const val TAG = "GpuAlignMerge"
        private const val GL_R16UI = 0x8234
        private const val GL_R32UI = 0x8236
        private const val GL_RED_INTEGER = 0x8D94
        private const val T_SIZE_2 = 16
        private const val MAX_FRAMES = 4
    }

    private var initialized = false
    private var supported = false

    private var progCSBoxDown2 = 0
    private var progCSGaussDown4 = 0
    private var progAlign = 0
    private var progWeights = 0
    private var progMerge = 0

    private val frameTex = IntArray(MAX_FRAMES)
    private val pyrTex = IntArray(MAX_FRAMES * 3)
    private var fbo = 0
    private var weightTex = 0
    private var level0SSBO = 0
    private var level1SSBO = 0
    private var level2SSBO = 0
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
        supported = (major[0] > 3) || (major[0] == 3 && minor[0] >= 1)
        android.util.Log.d(TAG, "GL_VERSION=$ver (major=${major[0]} minor=${minor[0]}), compute=$supported")
        return supported
    }

    fun init() {
        if (!checkSupport()) {
            Log.w(TAG, "ES 3.1 not supported, GPU fallback")
            return
        }
        initialized = true
        initGL()
    }

    fun initGL() {
        releaseGL()
        try {
            progAlign = createComputeProgram(CS_ALIGN)
            progWeights = createComputeProgram(CS_WEIGHTS)
            progMerge = createComputeProgram(CS_MERGE)
            progCSBoxDown2 = createComputeProgram(CS_BOX_DOWN2)
            progCSGaussDown4 = createComputeProgram(CS_GAUSS_DOWN4)
            GLES30.glGenTextures(MAX_FRAMES, frameTex, 0)
            for (i in 0 until MAX_FRAMES) {
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frameTex[i])
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            }
            GLES30.glGenTextures(MAX_FRAMES * 3, pyrTex, 0)
            val fboArr = IntArray(1); GLES30.glGenFramebuffers(1, fboArr, 0); fbo = fboArr[0]
            val texArr = IntArray(1)
            GLES30.glGenTextures(1, texArr, 0); weightTex = texArr[0]
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
        if (pyrTex[0] != 0) { GLES30.glDeleteTextures(MAX_FRAMES * 3, pyrTex, 0); pyrTex.fill(0) }
        if (fbo != 0) { GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0); fbo = 0 }
        if (weightTex != 0) { GLES30.glDeleteTextures(1, intArrayOf(weightTex), 0); weightTex = 0 }
        if (level0SSBO != 0) { GLES31.glDeleteBuffers(1, intArrayOf(level0SSBO), 0); level0SSBO = 0 }
        if (level1SSBO != 0) { GLES31.glDeleteBuffers(1, intArrayOf(level1SSBO), 0); level1SSBO = 0 }
        if (level2SSBO != 0) { GLES31.glDeleteBuffers(1, intArrayOf(level2SSBO), 0); level2SSBO = 0 }
        if (zeroSSBO != 0) { GLES31.glDeleteBuffers(1, intArrayOf(zeroSSBO), 0); zeroSSBO = 0 }
        if (outSSBO != 0) { GLES31.glDeleteBuffers(1, intArrayOf(outSSBO), 0); outSSBO = 0 }
        if (progCSBoxDown2 != 0) { GLES30.glDeleteProgram(progCSBoxDown2); progCSBoxDown2 = 0 }
        if (progCSGaussDown4 != 0) { GLES30.glDeleteProgram(progCSGaussDown4); progCSGaussDown4 = 0 }
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
     */
    fun process(frames: Array<ShortArray>, w: Int, h: Int, numTx: Int, numTy: Int): ShortArray {
        if (!initialized) {
            Log.w(TAG, "process: not initialized, calling init() now")
            init()
        }
        if (!initialized) throw IllegalStateException("GpuAlignMerge init() failed")
        val nf = minOf(frames.size, MAX_FRAMES)
        try {
            uploadFrames(frames, w, h, nf)

            // ================= DEBUG STEP 6 START =================
            // 验证上传到 GPU 的 frameTex 是否正常
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, frameTex[0], 0)

            val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            if (status == GLES30.GL_FRAMEBUFFER_COMPLETE) {
                // 注意：因为 frameTex 是 GL_R16UI 格式，读取时要用 GL_RED_INTEGER 和 GL_UNSIGNED_SHORT
                val pixels = ShortArray(1)
                GLES30.glReadPixels(0, 0, 1, 1, GL_RED_INTEGER, GLES30.GL_UNSIGNED_SHORT, java.nio.ShortBuffer.wrap(pixels))
                Log.d(TAG, "FrameTex[0] Pixel(0,0) = ${pixels[0]}")

                // 检查是否有 GL 错误
                val err = GLES30.glGetError()
                if (err != GLES30.GL_NO_ERROR) {
                    Log.e(TAG, "GL Error after reading FrameTex: 0x${Integer.toHexString(err)}")
                }
            } else {
                Log.e(TAG, "FBO Incomplete for FrameTex: $status")
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            // ================= DEBUG STEP 6 END =================

            buildPyramids(w, h, nf)
            alignAllFrames(w, h, nf, numTx, numTy)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, weightTex)
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, numTx, numTy)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
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
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0) // 恢复默认纹理单元

            // 4. 解绑 FBO 及其挂载点
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

            // 5. 清空 Program
            GLES31.glUseProgram(0)

            // 6. 循环清除所有可能残留的 GL 错误，防止错误级联导致后续渲染死锁
            while (GLES30.glGetError() != GLES30.GL_NO_ERROR) {
                // 仅用于清除错误队列
            }
        }
    }

    // ===================== 实现 =====================

    private fun uploadFrames(frames: Array<ShortArray>, w: Int, h: Int, nf: Int) {
        // ================= DEBUG STEP 5 START =================
        // 检查第一帧的第一个像素和平均值
        var sum = 0L
        for (i in 0 until minOf(100, frames[0].size)) sum += frames[0][i].toLong()
        Log.d(TAG, "Input Check: FirstPixel=${frames[0][0]}, First100Avg=${sum / 100}")
        // ================= DEBUG STEP 5 END =================

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
        val pyrW0 = (w + 1) / 2; val pyrH0 = (h + 1) / 2
        val pyrW1 = (pyrW0 + 3) / 4; val pyrH1 = (pyrH0 + 3) / 4
        val pyrW2 = (pyrW1 + 3) / 4; val pyrH2 = (pyrH1 + 3) / 4

        for (f in 0 until nf) {
            initTex(pyrTex[pt(f, 0)], pyrW0, pyrH0)
            initTex(pyrTex[pt(f, 1)], pyrW1, pyrH1)
            initTex(pyrTex[pt(f, 2)], pyrW2, pyrH2)
        }
        val ntx0 = (pyrW0 + 15) / 16; val nty0 = (pyrH0 + 15) / 16
        val ntx1 = (pyrW1 + 15) / 16; val nty1 = (pyrH1 + 15) / 16
        val ntx2 = (pyrW2 + 15) / 16; val nty2 = (pyrH2 + 15) / 16
        for (f in 0 until nf) {
            GLES31.glUseProgram(progCSBoxDown2)
            checkGLError("UseProgram BoxDown2")

            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frameTex[f])

            GLES31.glUniform1i(GLES31.glGetUniformLocation(progCSBoxDown2, "uSrc"), 0)
            GLES31.glUniform2i(GLES31.glGetUniformLocation(progCSBoxDown2, "uSrcSize"), w, h)

            GLES31.glBindImageTexture(0, pyrTex[pt(f, 0)], 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA8)
            checkGLError("BindImageTexture BoxDown2")

            GLES31.glDispatchCompute(ntx0, nty0, 1)
            checkGLError("DispatchCompute BoxDown2")

            GLES31.glMemoryBarrier(GLES31.GL_TEXTURE_FETCH_BARRIER_BIT or GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)
            checkGLError("MemoryBarrier BoxDown2")

            GLES31.glUseProgram(progCSGaussDown4)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, pyrTex[pt(f, 0)])
            GLES31.glUniform1i(GLES31.glGetUniformLocation(progCSGaussDown4, "uSrc"), 0)
            GLES31.glUniform2i(GLES31.glGetUniformLocation(progCSGaussDown4, "uSrcSize"), pyrW0, pyrH0)
            GLES31.glBindImageTexture(0, pyrTex[pt(f, 1)], 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA8)
            GLES31.glDispatchCompute(ntx1, nty1, 1)
            GLES31.glMemoryBarrier(GLES31.GL_TEXTURE_FETCH_BARRIER_BIT or GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)

            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, pyrTex[pt(f, 1)])
            GLES31.glUniform2i(GLES31.glGetUniformLocation(progCSGaussDown4, "uSrcSize"), pyrW1, pyrH1)
            GLES31.glBindImageTexture(0, pyrTex[pt(f, 2)], 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA8)
            GLES31.glDispatchCompute(ntx2, nty2, 1)
            GLES31.glMemoryBarrier(GLES31.GL_TEXTURE_FETCH_BARRIER_BIT or GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)
        }

        // ================= DEBUG STEP 4 START =================
        // 检查金字塔 Level 0 的中心像素值
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, pyrTex[pt(0, 0)])

        // 使用 glGetTexImage 读取纹理 (注意：GLES30 没有 glGetTexImage，需要用 FBO 或读取 PBO，
        // 但最简单的 debug 方法是用 glGetTexLevelParameter 检查是否 complete，
        // 或者重新绑定回 FBO 读取一个像素。这里用简易方法：利用 ReadPixels)

        // 1. 绑定 FBO 并 Attach 纹理
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, pyrTex[pt(0, 0)], 0)

        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (status == GLES30.GL_FRAMEBUFFER_COMPLETE) {
            // 修改点：用 ByteArray 读取 GL_RGBA8 格式
            val pixels = ByteArray(4) // RGBA 需要 4 个字节
            GLES30.glReadPixels(0, 0, 1, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, java.nio.ByteBuffer.wrap(pixels))

            // 手动把第一个字节转为 0.0-1.0 的 float 显示
            val valFloat = (pixels[0].toInt() and 0xFF) / 255.0f
            Log.d(TAG, "Pyramid L0 Pixel(0,0) R=$valFloat")
        } else {
            Log.e(TAG, "Framebuffer Incomplete for Pyramid Debug: $status")
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        // ================= DEBUG STEP 4 END =================
    }

    private fun alignAllFrames(w: Int, h: Int, nf: Int, numTx: Int, numTy: Int) {
        val pyrW0 = (w + 1) / 2; val pyrH0 = (h + 1) / 2
        val pyrW1 = (pyrW0 + 3) / 4; val pyrH1 = (pyrH0 + 3) / 4
        val pyrW2 = (pyrW1 + 3) / 4; val pyrH2 = (pyrH1 + 3) / 4

        val nt2x = (pyrW2 + T_SIZE_2 - 1) / T_SIZE_2
        val nt2y = (pyrH2 + T_SIZE_2 - 1) / T_SIZE_2
        val nt1x = (pyrW1 + T_SIZE_2 - 1) / T_SIZE_2
        val nt1y = (pyrH1 + T_SIZE_2 - 1) / T_SIZE_2
        val nt0x = numTx; val nt0y = numTy

        ensureSSBO(::level0SSBO, (nf - 1) * nt0x * nt0y * 2 * 4)
        ensureSSBO(::level1SSBO, nt1x * nt1y * 2 * 4)
        ensureSSBO(::level2SSBO, nt2x * nt2y * 2 * 4)

        if (zeroSSBO == 0) {
            val ids = IntArray(1); GLES31.glGenBuffers(1, ids, 0); zeroSSBO = ids[0]
        }
        val zeroSize = nt2x * nt2y * 2 * 4
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
        val uMinMaxLoc = GLES31.glGetUniformLocation(progAlign, "uMinMax")
        val uFrameBaseLoc = GLES31.glGetUniformLocation(progAlign, "uFrameBase")

        // 每副帧独立做 3 层对齐
        for (altF in 1 until nf) {
            GLES31.glUseProgram(progAlign)

            // Layer 2 (coarsest)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, pyrTex[pt(0, 2)])
            GLES30.glUniform1i(uRefLoc, 0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, pyrTex[pt(altF, 2)])
            GLES30.glUniform1i(uAltLoc, 1)
            GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 2, zeroSSBO)
            GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 3, level2SSBO)
            GLES31.glUniform2i(uGridSizeLoc, nt2x, nt2y)
            GLES31.glUniform2i(uPrevGridLoc, nt2x, nt2y)
            GLES31.glUniform1i(uScaleLoc, 4)
            GLES31.glUniform2i(uMinMaxLoc, 0, 0)
            GLES31.glUniform1i(uFrameBaseLoc, 0)
            GLES31.glDispatchCompute(nt2x, nt2y, 1)
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)

            // Layer 1
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, pyrTex[pt(0, 1)])
            GLES30.glUniform1i(uRefLoc, 0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, pyrTex[pt(altF, 1)])
            GLES30.glUniform1i(uAltLoc, 1)
            GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 2, level2SSBO)
            GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 3, level1SSBO)
            GLES31.glUniform2i(uGridSizeLoc, nt1x, nt1y)
            GLES31.glUniform2i(uPrevGridLoc, nt2x, nt2y)
            GLES31.glUniform1i(uScaleLoc, 4)
            GLES31.glUniform2i(uMinMaxLoc, -20, 15)
            GLES31.glUniform1i(uFrameBaseLoc, 0)
            GLES31.glDispatchCompute(nt1x, nt1y, 1)
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)

            // Layer 0 (finest)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, pyrTex[pt(0, 0)])
            GLES30.glUniform1i(uRefLoc, 0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, pyrTex[pt(altF, 0)])
            GLES30.glUniform1i(uAltLoc, 1)
            GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 2, level1SSBO)
            GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 3, level0SSBO)
            GLES31.glUniform2i(uGridSizeLoc, nt0x, nt0y)
            GLES31.glUniform2i(uPrevGridLoc, nt1x, nt1y)
            GLES31.glUniform1i(uScaleLoc, 4)
            GLES31.glUniform2i(uMinMaxLoc, -84, 63)
            GLES31.glUniform1i(uFrameBaseLoc, (altF - 1) * nt0x * nt0y * 2)
            GLES31.glDispatchCompute(nt0x, nt0y, 1)
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
        }

        // ================= DEBUG STEP 1 START =================
        // 读回 level0SSBO 检查偏移量
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, level0SSBO)
        val ssboSize = (nf - 1) * nt0x * nt0y * 2 * 4
        val ssboBuffer = GLES31.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, ssboSize, GLES31.GL_MAP_READ_BIT) as java.nio.ByteBuffer
        ssboBuffer.order(ByteOrder.nativeOrder())
        val intBuf = ssboBuffer.asIntBuffer()

        var totalOffsetX = 0
        var totalOffsetY = 0
        var count = 0

        // 遍历所有副帧（Frame 1, 2, 3）
        for (f in 1 until nf) {
            val baseIndex = (f - 1) * nt0x * nt0y * 2
            // 仅打印第一个 Tile 的偏移量作为样本
            val offX = intBuf.get(baseIndex)
            val offY = intBuf.get(baseIndex + 1)
            Log.d(TAG, "Align Frame[$f] Sample Offset: dx=$offX, dy=$offY")

            // 统计平均偏移量绝对值
            for (i in 0 until nt0x * nt0y) {
                val idx = baseIndex + i * 2
                if (idx + 1 < intBuf.capacity()) {
                    totalOffsetX += kotlin.math.abs(intBuf.get(idx))
                    totalOffsetY += kotlin.math.abs(intBuf.get(idx + 1))
                    count++
                }
            }
        }
        GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
        if (count > 0) {
            Log.d(TAG, "Align Average Abs Offset: dx=${totalOffsetX / count}, dy=${totalOffsetY / count}")
        }
        // ================= DEBUG STEP 1 END =================

        GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0)
    }

    private fun computeWeights(nf: Int, numTx: Int, numTy: Int) {
        if (nf <= 1) return
        GLES31.glUseProgram(progWeights)
        // 所有副帧的偏移 SSBO 绑定到 slot 2
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 2, level0SSBO)
        GLES31.glUniform2i(
            GLES31.glGetUniformLocation(progWeights, "uGridSize"), numTx, numTy)

        // 绑定输入：ref + nf-1 副帧的 half res（分开的 sampler uniform）
        val samplerNames = arrayOf("uHalf0", "uHalf1", "uHalf2", "uHalf3")
        for (i in 0 until nf) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + i)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, pyrTex[pt(i, 0)])
            val loc = GLES31.glGetUniformLocation(progWeights, samplerNames[i])
            if (loc >= 0) GLES31.glUniform1i(loc, i)
        }
        // 输出权重纹理（仍用 imageStore）
        GLES31.glBindImageTexture(8, weightTex, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES30.GL_RGBA8)
        GLES31.glUniform1i(GLES31.glGetUniformLocation(progWeights, "uNumFrames"), nf)
        GLES31.glUniform1i(GLES31.glGetUniformLocation(progWeights, "uFrameBase"), 0)
        GLES31.glDispatchCompute(numTx, numTy, 1)
        GLES31.glMemoryBarrier(GLES31.GL_TEXTURE_FETCH_BARRIER_BIT)
    }

    private fun merge(w: Int, h: Int, nf: Int, numTx: Int, numTy: Int): ShortArray {
        GLES31.glUseProgram(progMerge)

        for (i in 0 until nf) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + i)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frameTex[i])
            val loc = GLES30.glGetUniformLocation(progMerge, "uTex[$i]")
            if (loc >= 0) GLES30.glUniform1i(loc, i)
        }

        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 3, level0SSBO)

        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 4, outSSBO)
        GLES30.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, w * h * 4, null, GLES30.GL_DYNAMIC_READ)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE4)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, weightTex)
        val wtLoc = GLES30.glGetUniformLocation(progMerge, "uWeightTex")
        if (wtLoc >= 0) GLES30.glUniform1i(wtLoc, 4)

        GLES31.glUniform2i(GLES31.glGetUniformLocation(progMerge, "uImgSize"), w, h)
        GLES31.glUniform2i(GLES31.glGetUniformLocation(progMerge, "uGridSize"), numTx, numTy)
        GLES31.glUniform1i(GLES31.glGetUniformLocation(progMerge, "uNumFrames"), nf)

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

    private fun pt(f: Int, l: Int) = f * 3 + l

    private fun initTex(tex: Int, tw: Int, th: Int) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)

        // 修改点：使用 glTexStorage2D 替代 glTexImage2D
        // 参数含义：目标、Mipmap层数(我们只用到0层，所以是1)、内部格式、宽度、高度
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, tw, th)

        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
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

    private fun checkGLError(tag: String) {
        val err = GLES30.glGetError()
        if (err != GLES30.GL_NO_ERROR) {
            Log.e(TAG, "GL Error at [$tag]: 0x${Integer.toHexString(err)}")
        } else {
            Log.d(TAG, "GL OK at [$tag]")
        }
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
        // 修改点：layout 改为 rgba8
        layout(rgba8, binding=0) writeonly uniform highp image2D uDst;
        void main() {
            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
            ivec2 base = p * 2;
            float sum = 0.0;
            int cnt = 0;
            for (int dy = 0; dy < 2; dy++) {
                for (int dx = 0; dx < 2; dx++) {
                    ivec2 srcPos = base + ivec2(dx, dy);
                    if (srcPos.x < uSrcSize.x && srcPos.y < uSrcSize.y) {
                        // 修改点：读取后除以 255.0，变成 0.0-1.0 的浮点数
                        sum += float(texelFetch(uSrc, srcPos, 0).r) / 255.0;
                        cnt++;
                    }
                }
            }
            float val = (cnt > 0) ? sum / float(cnt) : 0.0;
            imageStore(uDst, p, vec4(val, 0.0, 0.0, 1.0));
        }
    """.trimIndent()

    private val CS_GAUSS_DOWN4 = """
        #version 310 es
        precision highp sampler2D; precision highp image2D;
        layout(local_size_x=16,local_size_y=16) in;
        uniform sampler2D uSrc; uniform ivec2 uSrcSize;
        layout(rgba8,binding=0) writeonly uniform highp image2D uDst;
        void main() {
            ivec2 p = ivec2(gl_GlobalInvocationID.xy);
            ivec2 cp = p * 4;
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
        uniform int uScale; uniform ivec2 uMinMax;
        uniform int uFrameBase;

        shared float sRef[256];
        shared float sDiff[256];
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

            int pt = clamp(int(tx)/4, 0, uPrevGrid.x-1);
            int pp = clamp(int(ty)/4, 0, uPrevGrid.y-1);
            int bi = (pp*uPrevGrid.x+pt)*2;
            int bx = int(clamp(float(prev[bi]*uScale), float(uMinMax.x), float(uMinMax.y)));
            int by = int(clamp(float(prev[bi+1]*uScale), float(uMinMax.x), float(uMinMax.y)));

            float best = 1e10; int bdx=0,bdy=0;
            for (int dy=-4; dy<=4; dy++) for (int dx=-4; dx<=4; dx++) {
                int ox = bx+dx, oy = by+dy;
                int ax = clamp(gx+ox,0,sz.x-1), ay = clamp(gy+oy,0,sz.y-1);
                float av = texelFetch(uAlt,ivec2(ax,ay),0).r;
                sDiff[tid] = valid ? abs(sRef[tid] - av) : 0.0;
                memoryBarrierShared(); barrier();
                for (int s=128; s>0; s>>=1) {
                    if (tid < uint(s)) sDiff[tid] += sDiff[tid+uint(s)];
                    memoryBarrierShared(); barrier();
                }
                if (tid==0u && sDiff[0] < best) { best = sDiff[0]; bdx=ox; bdy=oy; }
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
        precision highp sampler2D;
        precision highp image2D;
        layout(local_size_x = 16, local_size_y = 16) in;
        uniform sampler2D uHalf0;
        uniform sampler2D uHalf1;
        uniform sampler2D uHalf2;
        uniform sampler2D uHalf3;
        layout(std430, binding=2) buffer Off { int offs[]; };
        layout(rgba8, binding=8) writeonly uniform image2D uWt;
        uniform ivec2 uGridSize; uniform int uNumFrames; uniform int uFrameBase;
        shared float sL1[256];
        void main() {
            uint tx=gl_WorkGroupID.x, ty=gl_WorkGroupID.y;
            uint tid=gl_LocalInvocationIndex; uint lx=gl_LocalInvocationID.x,ly=gl_LocalInvocationID.y;
            ivec2 sz=textureSize(uHalf0,0);
            int orig_hx=int(tx*16u+lx); int orig_hy=int(ty*16u+ly);
            bool valid=(orig_hx<sz.x && orig_hy<sz.y);
            int hx=min(orig_hx,sz.x-1); int hy=min(orig_hy,sz.y-1);
            float rv=texelFetch(uHalf0,ivec2(hx,hy),0).r;
            int fi=uGridSize.x*uGridSize.y*2;
            int baseBi = uFrameBase + (int(ty) * uGridSize.x + int(tx)) * 2;
            vec3 w=vec3(1.0);
            for (int f=1; f<uNumFrames; f++) {
                int bi = baseBi + (f-1)*fi;
                int ox = offs[bi];
                int oy = offs[bi+1];
                int ahx=clamp(hx+ox,0,sz.x-1); int ahy=clamp(hy+oy,0,sz.y-1);
                float av;
                if (f==1) av=texelFetch(uHalf1,ivec2(ahx,ahy),0).r;
                else if (f==2) av=texelFetch(uHalf2,ivec2(ahx,ahy),0).r;
                else av=texelFetch(uHalf3,ivec2(ahx,ahy),0).r;
                sL1[tid] = valid ? abs(rv - av) : 0.0;
                memoryBarrierShared(); barrier();
                for (int s=128; s>0; s>>=1) {
                    if (tid < uint(s)) sL1[tid] += sL1[tid+uint(s)];
                    memoryBarrierShared(); barrier();
                }
                if (tid==0u) {
                    float avg=sL1[0]/256.0;
                    float nd=max(1.0, avg*65535.0/8.0-10.0/8.0);
                    float wgt = (nd <= (300.0-10.0)/8.0) ? min(1.0/nd,10.0) : 0.0;
                    if (f==1) w.r=wgt; else if (f==2) w.g=wgt; else w.b=wgt;
                }
                memoryBarrierShared(); barrier();
            }
            if (tid==0u) imageStore(uWt,ivec2(int(tx),int(ty)),vec4(w,0.0));
        }
    """.trimIndent()

    private val CS_MERGE = """
        #version 310 es
        precision highp usampler2D; precision highp int;
        layout(local_size_x=16,local_size_y=16) in;
        uniform highp usampler2D uTex[4]; uniform sampler2D uWeightTex;
        uniform ivec2 uImgSize; uniform ivec2 uGridSize; uniform int uNumFrames;
        layout(std430,binding=3) readonly buffer Off { int offs[]; };
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
                int ox=(uNumFrames>1)?offs[0*fi+bi]*2:0, oy=(uNumFrames>1)?offs[0*fi+bi+1]*2:0;
                int ox2=(uNumFrames>2)?offs[1*fi+bi]*2:0, oy2=(uNumFrames>2)?offs[1*fi+bi+1]*2:0;
                int ox3=(uNumFrames>3)?offs[2*fi+bi]*2:0, oy3=(uNumFrames>3)?offs[2*fi+bi+1]*2:0;
                vec3 w=texelFetch(uWeightTex,ivec2(tx,ty),0).rgb;
                float r0 = float(texelFetch(uTex[0], clamp(p, ivec2(0), uImgSize-1), 0).r) / 65535.0;
                float sum=r0; float tw=1.0;
                if (uNumFrames>1&&w.r>0.001) {
                    sum+=float(texelFetch(uTex[1],clamp(p+ivec2(ox,oy),ivec2(0),uImgSize-1),0).r)/65535.0*w.r;
                    tw+=w.r;
                }
                if (uNumFrames>2&&w.g>0.001) {
                    sum+=float(texelFetch(uTex[2],clamp(p+ivec2(ox2,oy2),ivec2(0),uImgSize-1),0).r)/65535.0*w.g;
                    tw+=w.g;
                }
                if (uNumFrames>3&&w.b>0.001) {
                    sum+=float(texelFetch(uTex[3],clamp(p+ivec2(ox3,oy3),ivec2(0),uImgSize-1),0).r)/65535.0*w.b;
                    tw+=w.b;
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

package com.classic.camera

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max

class FilmicHrEngine {
    companion object {
        private const val GL_R16F = 0x822D
        private const val GL_RGBA16F = 0x881A
        private const val GL_HALF_FLOAT = 0x140B
        private const val HR_GAMMA = 0.5f
        private const val HR_GAMMA_COMP = 0.5f
        private const val HR_BETA = 1f
        private const val HR_BETA_COMP = 0f
        private const val HR_DELTA = 1f
        private const val HR_NOISE_LEVEL = 0.2f
        private const val HR_MAX_NUM_SCALES = 10
        private const val HR_BSPLINE_FSIZE = 5
        private const val HR_HIGH_QUALITY_ITERATIONS = 1
    }

    private var initialized = false

    private var maskProgram = 0
    private var inpaintNoiseProgram = 0
    private var initReconstructProgram = 0
    private var bsplineProgram = 0
    private var highFrequencyProgram = 0
    private var waveletsReconstructProgram = 0
    private var computeNormsProgram = 0
    private var computeRatiosProgram = 0
    private var restoreRatiosProgram = 0

    private var vs = 0

    private var width = 0
    private var height = 0

    private var maskTexId = 0
    private var maskFboId = 0
    private var workTexId = 0
    private var workFboId = 0
    private var tempTexId = 0
    private var tempFboId = 0
    private var lfEvenTexId = 0
    private var lfEvenFboId = 0
    private var lfOddTexId = 0
    private var lfOddFboId = 0
    private var hfTexId = 0
    private var hfFboId = 0
    private var hfRgbTexId = 0
    private var hfRgbFboId = 0
    private var normsTexId = 0
    private var normsFboId = 0
    private val reconTexIds = IntArray(2)
    private val reconFboIds = IntArray(2)

    private val quadVerts = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val quadUVs = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)

    fun init() {
        if (initialized) return
        vs = compileShader(GLES30.GL_VERTEX_SHADER, FilmicHrShaders.VS)
        if (vs == 0) { android.util.Log.e("FilmicHr", "VS compile failed"); return }
        maskProgram = createProgram(vs, FilmicHrShaders.MASK_FS)
        inpaintNoiseProgram = createProgram(vs, FilmicHrShaders.INPAINT_NOISE_FS)
        initReconstructProgram = createProgram(vs, FilmicHrShaders.INIT_RECONSTRUCT_FS)
        bsplineProgram = createProgram(vs, FilmicHrShaders.BSPLINE_FS)
        highFrequencyProgram = createProgram(vs, FilmicHrShaders.HIGH_FREQUENCY_FS)
        waveletsReconstructProgram = createProgram(vs, FilmicHrShaders.WAVELETS_RECONSTRUCT_FS)
        computeNormsProgram = createProgram(vs, FilmicHrShaders.COMPUTE_NORMS_FS)
        computeRatiosProgram = createProgram(vs, FilmicHrShaders.COMPUTE_RATIOS_FS)
        restoreRatiosProgram = createProgram(vs, FilmicHrShaders.RESTORE_RATIOS_FS)
        if (maskProgram == 0 || inpaintNoiseProgram == 0 || initReconstructProgram == 0 ||
            bsplineProgram == 0 || highFrequencyProgram == 0 || waveletsReconstructProgram == 0 ||
            computeNormsProgram == 0 || computeRatiosProgram == 0 || restoreRatiosProgram == 0
        ) {
            release()
            return
        }
        initialized = true
    }

    fun release() {
        val progs = intArrayOf(maskProgram, inpaintNoiseProgram, initReconstructProgram,
            bsplineProgram, highFrequencyProgram, waveletsReconstructProgram,
            computeNormsProgram, computeRatiosProgram, restoreRatiosProgram)
        for (p in progs) if (p != 0) GLES30.glDeleteProgram(p)
        if (vs != 0) GLES30.glDeleteShader(vs)
        maskProgram = 0; inpaintNoiseProgram = 0; initReconstructProgram = 0
        bsplineProgram = 0; highFrequencyProgram = 0; waveletsReconstructProgram = 0
        computeNormsProgram = 0; computeRatiosProgram = 0; restoreRatiosProgram = 0
        vs = 0
        releaseFbos()
        initialized = false
    }

    private fun releaseFbos() {
        deleteTexFbo(maskTexId, maskFboId); maskTexId = 0; maskFboId = 0
        deleteTexFbo(workTexId, workFboId); workTexId = 0; workFboId = 0
        deleteTexFbo(tempTexId, tempFboId); tempTexId = 0; tempFboId = 0
        deleteTexFbo(lfEvenTexId, lfEvenFboId); lfEvenTexId = 0; lfEvenFboId = 0
        deleteTexFbo(lfOddTexId, lfOddFboId); lfOddTexId = 0; lfOddFboId = 0
        deleteTexFbo(hfTexId, hfFboId); hfTexId = 0; hfFboId = 0
        deleteTexFbo(hfRgbTexId, hfRgbFboId); hfRgbTexId = 0; hfRgbFboId = 0
        deleteTexFbo(normsTexId, normsFboId); normsTexId = 0; normsFboId = 0
        for (i in 0..1) { deleteTexFbo(reconTexIds[i], reconFboIds[i]); reconTexIds[i] = 0; reconFboIds[i] = 0 }
        width = 0; height = 0
    }

    /** 执行高光重建，inputTexId 是线性 RGB 纹理 (RGBA8/RGBA16F) */
    fun render(inputTexId: Int, w: Int, h: Int): Int {
        if (!initialized || w <= 0 || h <= 0) return inputTexId
        ensureFbos(w, h)

        // classic_camera 数据已归一化到 [0,1]（RGBA8），用固定阈值标记近白像素
        val reconstructThreshold = 0.9f
        val reconstructFeather = 12f
        val normalize = reconstructFeather / reconstructThreshold

        // 1. Mask
        renderPass(maskProgram, maskFboId, w, h, "mask") { prog ->
            bindTex(prog, "uInputTexture", 0, inputTexId)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uExposureGain"), 1f)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uNormalize"), normalize)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uFeathering"), reconstructFeather)
        }

        // 2. Inpaint noise
        renderPass(inpaintNoiseProgram, workFboId, w, h, "inpaint") { prog ->
            bindTex(prog, "uInputTexture", 0, inputTexId)
            bindTex(prog, "uMaskTexture", 1, maskTexId)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uExposureGain"), 1f)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uNoiseLevel"), HR_NOISE_LEVEL)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uThreshold"), reconstructThreshold)
        }

        // 3. Wavelets reconstruction
        var reconTexId = reconstructWavelets(workTexId, w, h, FilmicHrShaders.RECONSTRUCT_RGB)

        // 4. Iterative refinement
        repeat(HR_HIGH_QUALITY_ITERATIONS) {
            renderPass(computeNormsProgram, normsFboId, w, h, "computeNorms") { prog ->
                bindTex(prog, "uInputTexture", 0, reconTexId)
            }
            renderPass(computeRatiosProgram, workFboId, w, h, "computeRatios") { prog ->
                bindTex(prog, "uInputTexture", 0, reconTexId)
                bindTex(prog, "uNormsTexture", 1, normsTexId)
            }
            val ratiosTexId = reconstructWavelets(workTexId, w, h, FilmicHrShaders.RECONSTRUCT_RATIOS)
            reconTexId = restoreRatios(ratiosTexId, w, h)
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return reconTexId
    }

    private fun reconstructWavelets(inputTexId: Int, w: Int, h: Int, variant: Int): Int {
        val scales = scaleCount(w, h)

        renderPass(initReconstructProgram, reconFboIds[0], w, h, "initReconstruct") { prog ->
            bindTex(prog, "uInputTexture", 0, inputTexId)
            bindTex(prog, "uMaskTexture", 1, maskTexId)
        }

        var readIdx = 0
        var prevLfTexId = 0
        for (scale in 0 until scales) {
            val detailTexId = if (scale == 0) inputTexId else prevLfTexId
            val (lfTexId, lfFboId) = if (scale % 2 == 0) lfOddTexId to lfOddFboId else lfEvenTexId to lfEvenFboId
            val mult = 1 shl scale

            bsplineBlur(detailTexId, lfFboId, w, h, mult, "lf_$scale")

            renderPass(highFrequencyProgram, hfFboId, w, h, "hf_$scale") { prog ->
                bindTex(prog, "uDetailTexture", 0, detailTexId)
                bindTex(prog, "uLowFrequencyTexture", 1, lfTexId)
            }

            bsplineBlur(hfTexId, hfRgbFboId, w, h, 1, "hfRgb_$scale")

            val writeIdx = 1 - readIdx
            renderPass(waveletsReconstructProgram, reconFboIds[writeIdx], w, h, "wavelets_$scale") { prog ->
                bindTex(prog, "uHighFrequencyTexture", 0, hfRgbTexId)
                bindTex(prog, "uLowFrequencyTexture", 1, lfTexId)
                bindTex(prog, "uTextureTexture", 2, hfTexId)
                bindTex(prog, "uMaskTexture", 3, maskTexId)
                bindTex(prog, "uReconstructedTexture", 4, reconTexIds[readIdx])
                GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uGamma"), HR_GAMMA)
                GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uGammaComp"), HR_GAMMA_COMP)
                GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uBeta"), HR_BETA)
                GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uBetaComp"), HR_BETA_COMP)
                GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uDelta"), HR_DELTA)
                GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, "uScaleIndex"), scale)
                GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, "uScaleCount"), scales)
                GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, "uVariant"), variant)
            }

            readIdx = writeIdx
            prevLfTexId = lfTexId
        }

        return reconTexIds[readIdx]
    }

    private fun bsplineBlur(inputTexId: Int, outputFboId: Int, w: Int, h: Int, mult: Int, label: String) {
        renderPass(bsplineProgram, tempFboId, w, h, "${label}_v") { prog ->
            bindTex(prog, "uInputTexture", 0, inputTexId)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, "uWidth"), w)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, "uHeight"), h)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, "uMult"), mult)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, "uDirection"), 0)
        }
        renderPass(bsplineProgram, outputFboId, w, h, "${label}_h") { prog ->
            bindTex(prog, "uInputTexture", 0, tempTexId)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, "uWidth"), w)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, "uHeight"), h)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, "uMult"), mult)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, "uDirection"), 1)
        }
    }

    private fun restoreRatios(ratiosTexId: Int, w: Int, h: Int): Int {
        val outIdx = if (ratiosTexId == reconTexIds[0]) 1 else 0
        renderPass(restoreRatiosProgram, reconFboIds[outIdx], w, h, "restoreRatios") { prog ->
            bindTex(prog, "uRatiosTexture", 0, ratiosTexId)
            bindTex(prog, "uNormsTexture", 1, normsTexId)
        }
        return reconTexIds[outIdx]
    }

    // ===== FBO 管理 =====

    private fun ensureFbos(w: Int, h: Int) {
        if (this.width == w && this.height == h && maskTexId != 0 && workTexId != 0 && reconFboIds[0] != 0) return
        releaseFbos()
        this.width = w; this.height = h
        val pair = createTexFbo(w, h, GL_R16F)
        maskTexId = pair.first; maskFboId = pair.second
        val pairW = createTexFbo(w, h, GL_RGBA16F)
        workTexId = pairW.first; workFboId = pairW.second
        val pairT = createTexFbo(w, h, GL_RGBA16F)
        tempTexId = pairT.first; tempFboId = pairT.second
        val pairE = createTexFbo(w, h, GL_RGBA16F)
        lfEvenTexId = pairE.first; lfEvenFboId = pairE.second
        val pairO = createTexFbo(w, h, GL_RGBA16F)
        lfOddTexId = pairO.first; lfOddFboId = pairO.second
        val pairH = createTexFbo(w, h, GL_RGBA16F)
        hfTexId = pairH.first; hfFboId = pairH.second
        val pairR = createTexFbo(w, h, GL_RGBA16F)
        hfRgbTexId = pairR.first; hfRgbFboId = pairR.second
        val pairN = createTexFbo(w, h, GL_R16F)
        normsTexId = pairN.first; normsFboId = pairN.second
        for (i in 0..1) {
            val p = createTexFbo(w, h, GL_RGBA16F)
            reconTexIds[i] = p.first; reconFboIds[i] = p.second
        }
    }

    private fun createTexFbo(w: Int, h: Int, internalFormat: Int): Pair<Int, Int> {
        val isRgba = internalFormat == GL_RGBA16F
        val format = if (isRgba) GLES30.GL_RGBA else GLES30.GL_RED
        val texs = IntArray(1)
        GLES30.glGenTextures(1, texs, 0)
        val texId = texs[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, internalFormat, w, h, 0, format, GL_HALF_FLOAT, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)

        val fbos = IntArray(1)
        GLES30.glGenFramebuffers(1, fbos, 0)
        val fboId = fbos[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texId, 0)
        if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            throw RuntimeException("HR FBO incomplete: fmt=$internalFormat ${w}x$h")
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return texId to fboId
    }

    // ===== 渲染工具 =====

    private fun renderPass(program: Int, fboId: Int, w: Int, h: Int, label: String, bindUniforms: (Int) -> Unit) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(program)
        bindUniforms(program)
        drawQuad(program)
    }

    private fun bindTex(program: Int, name: String, unit: Int, texId: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, name), unit)
    }

    private fun drawQuad(program: Int) {
        val aPos = GLES30.glGetAttribLocation(program, "aPos")
        val aTex = GLES30.glGetAttribLocation(program, "aTexCoord")
        if (aPos >= 0) {
            GLES30.glEnableVertexAttribArray(aPos)
            GLES30.glVertexAttribPointer(aPos, 2, GLES30.GL_FLOAT, false, 0, floatBuf(quadVerts))
        }
        if (aTex >= 0) {
            GLES30.glEnableVertexAttribArray(aTex)
            GLES30.glVertexAttribPointer(aTex, 2, GLES30.GL_FLOAT, false, 0, floatBuf(quadUVs))
        }
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        if (aPos >= 0) GLES30.glDisableVertexAttribArray(aPos)
        if (aTex >= 0) GLES30.glDisableVertexAttribArray(aTex)
    }

    private fun scaleCount(w: Int, h: Int): Int {
        val size = max(w, h).coerceAtLeast(1).toDouble()
        val filterSize = HR_BSPLINE_FSIZE.toDouble()
        val argument = (2.0 * size / ((filterSize - 1.0) * filterSize)) - 1.0
        val scales = floor(ln(max(argument, 1.0)) / ln(2.0)).toInt()
        return scales.coerceIn(1, HR_MAX_NUM_SCALES)
    }

    private fun compileShader(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(s)
            android.util.Log.e("FilmicHr", "shader compile fail: $log")
            GLES30.glDeleteShader(s); return 0
        }
        return s
    }

    private fun createProgram(vs: Int, fsSrc: String): Int {
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fsSrc)
        if (fs == 0) return 0
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, vs); GLES30.glAttachShader(p, fs)
        GLES30.glLinkProgram(p)
        val link = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, link, 0)
        if (link[0] != GLES30.GL_TRUE) {
            val log = GLES30.glGetProgramInfoLog(p)
            android.util.Log.e("FilmicHr", "link fail: $log")
            GLES30.glDeleteProgram(p); GLES30.glDeleteShader(fs); return 0
        }
        GLES30.glDeleteShader(fs)
        return p
    }

    private fun deleteTexFbo(texId: Int, fboId: Int) {
        if (texId != 0) GLES30.glDeleteTextures(1, intArrayOf(texId), 0)
        if (fboId != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
    }

    private fun floatBuf(a: FloatArray): FloatBuffer {
        val bb = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        fb.put(a); fb.position(0)
        return fb
    }
}

package com.classic.camera

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * RAW Bayer → RGB 的 OpenGL ES 3.0 管线（第四版）。
 *
 * 支持 4 种去马赛克算法：
 *   0 = 双线性（原版）
 *   1 = Hamilton-Adams 边缘自适应
 *   2 = Malvar-He-Cutler (MHC) 固定 5×5 滤波器
 *   3 = 迭代优化式（MHC + 色差双边滤波正则化）
 *
 * 核心处理（色彩/影调，预览与成片共享同一份）：
 *   减黑电平 → 白归一化 → 白平衡 gains → Demosaic → CCM → Gamma → S-curve
 */
class RawPipeline : GLSurfaceView.Renderer {

    companion object {
        const val DEMOSAIC_BILINEAR = 0
        const val DEMOSAIC_HA = 1
        const val DEMOSAIC_MHC = 2
        const val DEMOSAIC_ITERATIVE = 3

        private const val GL_R16 = 0x822A
    }

    // ---- 去马赛克模式 ----
    var demosaicMode = DEMOSAIC_BILINEAR

    // ---- 供外部更新参数 ----
    var rawWidth: Int = 0
        private set
    var rawHeight: Int = 0
        private set

    @Volatile var rawShorts: ShortArray? = null
    @Volatile var rawW: Int = 0
    @Volatile var rawH: Int = 0
    var blackLevelR = 0.0f
    var blackLevelG = 0.0f
    var blackLevelB = 0.0f
    var whiteLevel = 1023f
    var wbR = 1f; var wbG = 1f; var wbB = 1f
    var ccm = FloatArray(9) { if (it % 4 == 0) 1f else 0f }
    var orientation: Int = 90
    var mirror: Boolean = false
    var cfaType = 2

    private var rawDirectBuf: java.nio.ByteBuffer? = null
    private var texAllocated = false
    private var texW = 0
    private var texH = 0

    // ---- 着色器程序 ----
    private var progBilinear = 0
    private var progHA = 0
    private var progMHC = 0
    private var progRefine = 0   // 迭代模式的第二轮（正则化）
    private var progMHC_Linear = 0  // MHC 线性输出变体（迭代模式 Pass 1）
    private var activeProg = 0

    // ---- Uniform 缓存（每程序独立） ----
    private class ProgUniforms(val id: Int) {
        var aPos = 0; var aTexCoord = 0
        var uRawTex = 0; var uRawSize = 0
        var uBlackLevel = 0; var uWhiteLevel = 0
        var uWBGain = 0; var uCCM = 0
        var uAspectScale = 0; var uCFAType = 0
        var uOrientation = 0; var uMirror = 0
        // refine pass2 专用
        var uRGBATex = 0; var uTexSize = 0

        fun lookupBayer() {
            aPos = GLES30.glGetAttribLocation(id, "aPos")
            aTexCoord = GLES30.glGetAttribLocation(id, "aTexCoord")
            uRawTex = GLES30.glGetUniformLocation(id, "uRawTex")
            uRawSize = GLES30.glGetUniformLocation(id, "uRawSize")
            uBlackLevel = GLES30.glGetUniformLocation(id, "uBlackLevel")
            uWhiteLevel = GLES30.glGetUniformLocation(id, "uWhiteLevel")
            uWBGain = GLES30.glGetUniformLocation(id, "uWBGain")
            uCCM = GLES30.glGetUniformLocation(id, "uCCM")
            uAspectScale = GLES30.glGetUniformLocation(id, "uAspectScale")
            uCFAType = GLES30.glGetUniformLocation(id, "uCFAType")
            uOrientation = GLES30.glGetUniformLocation(id, "uOrientation")
            uMirror = GLES30.glGetUniformLocation(id, "uMirror")
        }

        fun lookupRefine() {
            aPos = GLES30.glGetAttribLocation(id, "aPos")
            aTexCoord = GLES30.glGetAttribLocation(id, "aTexCoord")
            uRGBATex = GLES30.glGetUniformLocation(id, "uRGBATex")
            uTexSize = GLES30.glGetUniformLocation(id, "uTexSize")
            uAspectScale = GLES30.glGetUniformLocation(id, "uAspectScale")
            uOrientation = GLES30.glGetUniformLocation(id, "uOrientation")
            uMirror = GLES30.glGetUniformLocation(id, "uMirror")
            uCCM = GLES30.glGetUniformLocation(id, "uCCM")
        }
    }

    private lateinit var uniBilinear: ProgUniforms
    private lateinit var uniHA: ProgUniforms
    private lateinit var uniMHC: ProgUniforms
    private lateinit var uniRefine: ProgUniforms
    private lateinit var uniMHC_Linear: ProgUniforms

    private var texId = 0
    private var drawCount = 0
    private var lastDrawLog = 0L

    // ---- 离屏渲染 ----
    private var captureFbo = 0
    private var captureTex = 0
    private var captureTexW = 0
    private var captureTexH = 0
    private var lastViewportW = 0
    private var lastViewportH = 0

    // ---- 迭代模式中间 FBO ----
    private var iterFbo = 0
    private var iterTex = 0
    private var iterTexW = 0
    private var iterTexH = 0

    private val quadVerts = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val quadUVs = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)

    fun setCCM(m: FloatArray) {
        if (m.size < 9) return
        var allZero = true
        for (i in 0 until 9) { if (m[i] != 0f) { allZero = false; break } }
        if (allZero) {
            android.util.Log.w("ClassicCamera", "CCM all zeros, keeping identity")
            return
        }
        ccm = mergeForwardMatrixToSRGB(m)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        android.util.Log.d("ClassicCamera", "GL onSurfaceCreated")
        captureFbo = 0; captureTex = 0; captureTexW = 0; captureTexH = 0
        iterFbo = 0; iterTex = 0; iterTexW = 0; iterTexH = 0
        texAllocated = false; texW = 0; texH = 0
        activeProg = 0

        // 编译所有着色器程序
        progBilinear = createProgram(VS, FS_BILINEAR)
        progHA = createProgram(VS, FS_HA)
        progMHC = createProgram(VS, FS_MHC)
        progRefine = createProgram(VS, FS_REFINE)
        progMHC_Linear = createProgram(VS, FS_MHC_LINEAR)

        uniBilinear = ProgUniforms(progBilinear).also { it.lookupBayer() }
        uniHA = ProgUniforms(progHA).also { it.lookupBayer() }
        uniMHC = ProgUniforms(progMHC).also { it.lookupBayer() }
        uniRefine = ProgUniforms(progRefine).also { it.lookupRefine() }
        uniMHC_Linear = ProgUniforms(progMHC_Linear).also { it.lookupBayer() }

        android.util.Log.d("ClassicCamera", "GL programs: bilinear=$progBilinear ha=$progHA mhc=$progMHC refine=$progRefine mhc_linear=$progMHC_Linear")

        val texs = IntArray(1)
        GLES30.glGenTextures(1, texs, 0)
        texId = texs[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        lastViewportW = width
        lastViewportH = height
        GLES30.glViewport(0, 0, width, height)
    }

    private fun ensureIterFbo(w: Int, h: Int) {
        if (iterTex != 0 && iterTexW == w && iterTexH == h) return
        if (iterTex != 0) { GLES30.glDeleteTextures(1, intArrayOf(iterTex), 0); iterTex = 0 }
        if (iterFbo != 0) { GLES30.glDeleteFramebuffers(1, intArrayOf(iterFbo), 0); iterFbo = 0 }
        iterTexW = 0; iterTexH = 0

        val texs = IntArray(1)
        GLES30.glGenTextures(1, texs, 0)
        iterTex = texs[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, iterTex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        val fbos = IntArray(1)
        GLES30.glGenFramebuffers(1, fbos, 0)
        iterFbo = fbos[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, iterFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, iterTex, 0)
        if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            android.util.Log.e("ClassicCamera", "iterFbo incomplete")
        }
        iterTexW = w; iterTexH = h
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /** 上传 RAW 数据到 GL_R16 纹理，返回是否成功。 */
    private fun uploadRaw(data: ShortArray): Boolean {
        if (rawW <= 0 || rawH <= 0) return false
        val shortCount = rawW * rawH
        if (data.size != shortCount) {
            android.util.Log.w("ClassicCamera", "uploadRaw size mismatch: data=${data.size} expected=$shortCount")
            return false
        }
        val needed = shortCount * 2
        val bb = if (rawDirectBuf == null || rawDirectBuf!!.capacity() < needed)
            ByteBuffer.allocateDirect(needed).order(ByteOrder.nativeOrder()).also { rawDirectBuf = it }
        else rawDirectBuf!!
        bb.clear()
        bb.asShortBuffer().put(data)
        bb.position(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
        if (texAllocated && texW == rawW && texH == rawH) {
            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, rawW, rawH, GLES30.GL_RED, GLES30.GL_UNSIGNED_SHORT, bb)
        } else {
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GL_R16, rawW, rawH, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_SHORT, bb)
            texAllocated = true; texW = rawW; texH = rawH
        }
        return true
    }

    /** 设置 Bayer shader 的公共 uniform。 */
    private fun setBayerUniforms(u: ProgUniforms, aspectX: Float, aspectY: Float) {
        val inv65535 = 1.0f / 65535.0f
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
        GLES30.glUniform1i(u.uRawTex, 0)
        if (u.uRawSize >= 0) GLES30.glUniform2f(u.uRawSize, rawW.toFloat(), rawH.toFloat())
        GLES30.glUniform3f(u.uBlackLevel, blackLevelR * inv65535, blackLevelG * inv65535, blackLevelB * inv65535)
        GLES30.glUniform1f(u.uWhiteLevel, whiteLevel * inv65535)
        GLES30.glUniform3f(u.uWBGain, wbR, wbG, wbB)
        GLES30.glUniformMatrix3fv(u.uCCM, 1, false, ccm, 0)
        GLES30.glUniform1i(u.uCFAType, cfaType)
        GLES30.glUniform1i(u.uOrientation, orientation)
        GLES30.glUniform1i(u.uMirror, if (mirror) 1 else 0)
        GLES30.glUniform2f(u.uAspectScale, aspectX, aspectY)
    }

    /** 画全屏四边形。 */
    private fun drawQuad(u: ProgUniforms) {
        u.aPos.also {
            GLES30.glEnableVertexAttribArray(it)
            GLES30.glVertexAttribPointer(it, 2, GLES30.GL_FLOAT, false, 0, floatBuf(quadVerts))
        }
        u.aTexCoord.also {
            GLES30.glEnableVertexAttribArray(it)
            GLES30.glVertexAttribPointer(it, 2, GLES30.GL_FLOAT, false, 0, floatBuf(quadUVs))
        }
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun useProgram(prog: Int) {
        if (activeProg != prog) {
            GLES30.glUseProgram(prog)
            activeProg = prog
        }
    }

    /** 计算宽高比缩放值。 */
    private fun aspectScale(): Pair<Float, Float> {
        val rotated = (orientation == 90 || orientation == 270)
        val imageAspect = if (rotated) rawH.toFloat() / rawW.toFloat() else rawW.toFloat() / rawH.toFloat()
        val viewAspect = lastViewportW.toFloat() / lastViewportH.toFloat()
        return if (imageAspect > viewAspect) Pair(1f, viewAspect / imageAspect)
        else Pair(imageAspect / viewAspect, 1f)
    }

    // ====================== 预览渲染 ======================

    override fun onDrawFrame(gl: GL10?) {
        drawCount++
        val now = System.nanoTime()
        if (now - lastDrawLog > 1_000_000_000L) {
            android.util.Log.d("ClassicCamera",
                "GL draw rate=${drawCount}/s mode=$demosaicMode rawIsNull=${rawShorts==null} w=$rawW h=$rawH")
            drawCount = 0; lastDrawLog = now
        }

        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        val data = rawShorts ?: run { drawTestPattern(); return }
        if (rawW <= 0 || rawH <= 0) return
        if (!uploadRaw(data)) return

        // 预览固定双线性（快速），设置中选择的算法只影响拍照 JPG
        drawBayer(progBilinear, uniBilinear)
    }

    private fun drawBayer(prog: Int, u: ProgUniforms) {
        useProgram(prog)
        val (ax, ay) = aspectScale()
        setBayerUniforms(u, ax, ay)
        drawQuad(u)
    }

    /** 迭代模式：两遍渲染。 */
    private fun drawIterative() {
        val (ax, ay) = aspectScale()
        val rotated = (orientation == 90 || orientation == 270)
        val fboW = if (rotated) rawH else rawW
        val fboH = if (rotated) rawW else rawH
        ensureIterFbo(lastViewportW, lastViewportH)

        // Pass 1：MHC 线性 demosaic → iterFbo（无 CCM/gamma，输出线性 RGB）
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, iterFbo)
        GLES30.glViewport(0, 0, iterTexW, iterTexH)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        useProgram(progMHC_Linear)
        setBayerUniforms(uniMHC_Linear, 1f, 1f)
        drawQuad(uniMHC_Linear)

        // Pass 2：色差正则化 → 屏幕
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, lastViewportW, lastViewportH)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        useProgram(progRefine)

        // refine shader 用 iterTex 作为输入
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, iterTex)
        GLES30.glUniform1i(uniRefine.uRGBATex, 0)
        GLES30.glUniform2f(uniRefine.uTexSize, iterTexW.toFloat(), iterTexH.toFloat())
        GLES30.glUniform1i(uniRefine.uOrientation, 0)
        GLES30.glUniform1i(uniRefine.uMirror, 0)
        GLES30.glUniform2f(uniRefine.uAspectScale, ax, ay)
        GLES30.glUniformMatrix3fv(uniRefine.uCCM, 1, false, ccm, 0)
        drawQuad(uniRefine)
    }

    /** 无数据时画测试图案。 */
    private fun drawTestPattern() {
        useProgram(progBilinear)
        GLES30.glUniform3f(uniBilinear.uBlackLevel, 0f, 0f, 0f)
        GLES30.glUniform1f(uniBilinear.uWhiteLevel, 1f)
        GLES30.glUniform3f(uniBilinear.uWBGain, 1f, 1f, 1f)
        val ident = floatArrayOf(1f,0f,0f, 0f,1f,0f, 0f,0f,1f)
        GLES30.glUniformMatrix3fv(uniBilinear.uCCM, 1, false, ident, 0)
        GLES30.glUniform1i(uniBilinear.uCFAType, cfaType)
        GLES30.glUniform1i(uniBilinear.uOrientation, orientation)
        GLES30.glUniform1i(uniBilinear.uMirror, if (mirror) 1 else 0)
        GLES30.glUniform2f(uniBilinear.uAspectScale, 1f, 1f)
        val testBuf = ByteBuffer.allocateDirect(4 * 2).order(ByteOrder.nativeOrder())
        testBuf.putShort(floatToHalf(0.5f).toShort())
        testBuf.putShort(floatToHalf(0.0f).toShort())
        testBuf.putShort(floatToHalf(0.0f).toShort())
        testBuf.putShort(floatToHalf(1.0f).toShort())
        testBuf.rewind()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R16F, 2, 2, 0, GLES30.GL_RED, GLES30.GL_HALF_FLOAT, testBuf)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
        GLES30.glUniform1i(uniBilinear.uRawTex, 0)
        if (uniBilinear.uRawSize >= 0) GLES30.glUniform2f(uniBilinear.uRawSize, 2f, 2f)
        drawQuad(uniBilinear)
    }

    // ====================== 拍照离屏渲染 ======================

    fun renderCaptureToBitmap(
        bayer: ShortArray, w: Int, h: Int,
        blackLevelR: Float, blackLevelG: Float, blackLevelB: Float,
        whiteLevel: Float,
        wbR: Float, wbG: Float, wbB: Float,
        ccmColumnMajor: FloatArray,
        cfaType: Int = 2
    ): Bitmap? {
        if (w <= 0 || h <= 0 || bayer.size < w * h) return null

        // 存当前参数供渲染使用
        this.blackLevelR = blackLevelR; this.blackLevelG = blackLevelG; this.blackLevelB = blackLevelB
        this.whiteLevel = whiteLevel
        this.wbR = wbR; this.wbG = wbG; this.wbB = wbB
        this.ccm = ccmColumnMajor
        this.cfaType = cfaType
        rawW = w; rawH = h

        val rotated = (orientation == 90 || orientation == 270)
        val outW = if (rotated) h else w
        val outH = if (rotated) w else h

        try {
            ensureCaptureFbo(outW, outH)
        } catch (e: Exception) {
            android.util.Log.e("ClassicCamera", "ensureCaptureFbo failed: ${e.message}", e)
            return null
        }

        // 上传 RAW
        rawW = w; rawH = h
        if (!uploadRaw(bayer)) return null

        if (demosaicMode == DEMOSAIC_ITERATIVE) {
            return renderCaptureIterative(outW, outH)
        }

        // 单次渲染
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, captureFbo)
        GLES30.glViewport(0, 0, captureTexW, captureTexH)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        val prog = when (demosaicMode) {
            DEMOSAIC_HA -> progHA; DEMOSAIC_MHC -> progMHC
            else -> progBilinear
        }
        val u = when (demosaicMode) {
            DEMOSAIC_HA -> uniHA; DEMOSAIC_MHC -> uniMHC
            else -> uniBilinear
        }
        useProgram(prog)
        setBayerUniforms(u, 1f, 1f)
        drawQuad(u)

        return readCaptureBitmap(outW, outH)
    }

    /** 迭代模式的拍照渲染：两遍。 */
    private fun renderCaptureIterative(outW: Int, outH: Int): Bitmap? {
        // Pass 1：MHC 线性 demosaic → iterFbo（无 CCM/gamma，输出线性 RGB）
        ensureIterFbo(outW, outH)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, iterFbo)
        GLES30.glViewport(0, 0, outW, outH)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        useProgram(progMHC_Linear)
        setBayerUniforms(uniMHC_Linear, 1f, 1f)
        drawQuad(uniMHC_Linear)

        // Pass 2：正则化 → captureFbo
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, captureFbo)
        GLES30.glViewport(0, 0, outW, outH)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        useProgram(progRefine)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, iterTex)
        GLES30.glUniform1i(uniRefine.uRGBATex, 0)
        GLES30.glUniform2f(uniRefine.uTexSize, outW.toFloat(), outH.toFloat())
        GLES30.glUniform1i(uniRefine.uOrientation, 0)
        GLES30.glUniform1i(uniRefine.uMirror, 0)
        GLES30.glUniform2f(uniRefine.uAspectScale, 1f, 1f)
        GLES30.glUniformMatrix3fv(uniRefine.uCCM, 1, false, ccm, 0)
        drawQuad(uniRefine)

        return readCaptureBitmap(outW, outH)
    }

    private fun readCaptureBitmap(outW: Int, outH: Int): Bitmap? {
        try {
            val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val stripHeight = 512
            val stripBytes = ByteBuffer.allocateDirect(outW * stripHeight * 4).order(ByteOrder.nativeOrder())
            for (y in 0 until outH step stripHeight) {
                val curH = minOf(stripHeight, outH - y)
                stripBytes.clear()
                stripBytes.limit(outW * curH * 4)
                GLES30.glReadPixels(0, y, outW, curH, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, stripBytes)
                stripBytes.position(0)
                val argbStrip = IntArray(outW * curH)
                for (sy in 0 until curH) {
                    for (sx in 0 until outW) {
                        val r = stripBytes.get().toInt() and 0xFF
                        val g = stripBytes.get().toInt() and 0xFF
                        val b = stripBytes.get().toInt() and 0xFF
                        val a = stripBytes.get().toInt() and 0xFF
                        val destY = curH - 1 - sy
                        argbStrip[destY * outW + sx] = (a shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
                bitmap.setPixels(argbStrip, 0, outW, 0, outH - y - curH, outW, curH)
            }
            return bitmap
        } catch (e: Exception) {
            android.util.Log.e("ClassicCamera", "readCaptureBitmap error: ${e.message}", e)
            return null
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            if (lastViewportW > 0 && lastViewportH > 0) {
                GLES30.glViewport(0, 0, lastViewportW, lastViewportH)
            }
        }
    }

    // ====================== 资源管理 ======================

    private fun checkGlError(op: String) {
        var error: Int
        while (GLES30.glGetError().also { error = it } != GLES30.GL_NO_ERROR) {
            android.util.Log.e("ClassicCamera", "GL error after $op: $error")
        }
    }

    private fun ensureCaptureFbo(w: Int, h: Int) {
        if (captureTex != 0 && captureTexW == w && captureTexH == h) return
        if (captureTex != 0) { GLES30.glDeleteTextures(1, intArrayOf(captureTex), 0); captureTex = 0 }
        if (captureFbo != 0) { GLES30.glDeleteFramebuffers(1, intArrayOf(captureFbo), 0); captureFbo = 0 }
        captureTexW = 0; captureTexH = 0

        val texs = IntArray(1)
        GLES30.glGenTextures(1, texs, 0)
        captureTex = texs[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, captureTex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)

        val fbos = IntArray(1)
        GLES30.glGenFramebuffers(1, fbos, 0)
        captureFbo = fbos[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, captureFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, captureTex, 0)
        if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            throw RuntimeException("Capture FBO incomplete")
        }
        captureTexW = w; captureTexH = h
    }

    fun release() {
        if (captureTex != 0) { GLES30.glDeleteTextures(1, intArrayOf(captureTex), 0); captureTex = 0 }
        if (captureFbo != 0) { GLES30.glDeleteFramebuffers(1, intArrayOf(captureFbo), 0); captureFbo = 0 }
        if (iterTex != 0) { GLES30.glDeleteTextures(1, intArrayOf(iterTex), 0); iterTex = 0 }
        if (iterFbo != 0) { GLES30.glDeleteFramebuffers(1, intArrayOf(iterFbo), 0); iterFbo = 0 }
        if (texId != 0) { GLES30.glDeleteTextures(1, intArrayOf(texId), 0); texId = 0 }
        if (progBilinear != 0) { GLES30.glDeleteProgram(progBilinear); progBilinear = 0 }
        if (progHA != 0) { GLES30.glDeleteProgram(progHA); progHA = 0 }
        if (progMHC != 0) { GLES30.glDeleteProgram(progMHC); progMHC = 0 }
        if (progRefine != 0) { GLES30.glDeleteProgram(progRefine); progRefine = 0 }
        if (progMHC_Linear != 0) { GLES30.glDeleteProgram(progMHC_Linear); progMHC_Linear = 0 }
    }

    // ====================== GL 工具 ======================

    private fun floatToHalf(f: Float): Int {
        val bits = java.lang.Float.floatToIntBits(f)
        val sign = (bits ushr 16) and 0x8000
        var mant = bits and 0x7fffff
        var exp = ((bits ushr 23) and 0xff) - 127 + 15
        if (exp >= 31) return sign or 0x7c00
        if (exp <= 0) {
            mant = mant or 0x800000
            var shift = 14 - exp
            val half = (mant shr shift).toInt()
            return sign or (half and 0x7fff)
        }
        return sign or (exp shl 10) or (mant shr 13)
    }

    private fun floatBuf(a: FloatArray): FloatBuffer {
        val bb = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        fb.put(a); fb.position(0)
        return fb
    }

    private fun createProgram(vsSrc: String, fsSrc: String): Int {
        val vs = compile(GLES30.GL_VERTEX_SHADER, vsSrc)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fsSrc)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, vs); GLES30.glAttachShader(p, fs)
        GLES30.glLinkProgram(p)
        val link = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, link, 0)
        if (link[0] != GLES30.GL_TRUE) {
            throw RuntimeException("link fail: ${GLES30.glGetProgramInfoLog(p)}")
        }
        GLES30.glDeleteShader(vs); GLES30.glDeleteShader(fs)
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] != GLES30.GL_TRUE) {
            throw RuntimeException("compile fail: ${GLES30.glGetShaderInfoLog(s)}\n$src")
        }
        return s
    }

    // ====================== 着色器 ======================

    private val VS = """
        #version 300 es
        precision highp float;
        layout(location=0) in vec2 aPos;
        layout(location=1) in vec2 aTexCoord;
        uniform vec2 uAspectScale;
        uniform int uOrientation;
        uniform bool uMirror;
        out vec2 vUV;
        void main(){
            vec2 uv = aTexCoord;
            if (uMirror) uv.x = 1.0 - uv.x;
            if (uOrientation == 0) {
                vUV = vec2(uv.x, 1.0 - uv.y);
            } else if (uOrientation == 90) {
                vUV = vec2(uv.y, 1.0 - uv.x);
            } else if (uOrientation == 180) {
                vUV = vec2(1.0 - uv.x, uv.y);
            } else {
                vUV = vec2(1.0 - uv.y, uv.x);
            }
            gl_Position = vec4(aPos * uAspectScale, 0.0, 1.0);
        }
    """.trimIndent()

    // ===== 0. 双线性（原版） =====

    private val FS_BILINEAR = """
        #version 300 es
        precision highp float;
        precision highp sampler2D;
        uniform sampler2D uRawTex;
        uniform vec2 uRawSize;
        uniform vec3 uBlackLevel;
        uniform float uWhiteLevel;
        uniform vec3 uWBGain;
        uniform mat3 uCCM;
        uniform int uCFAType;
        in vec2 vUV;
        out vec4 frag;

        int cfaCh(int type, int x, int y) {
            int r = y & 1; int c = x & 1;
            if (type == 0) return (r == 0 && c == 0) ? 0 : ((r == 1 && c == 1) ? 2 : 1);
            if (type == 1) return (r == 0 && c == 1) ? 0 : ((r == 1 && c == 0) ? 2 : 1);
            if (type == 2) return (r == 1 && c == 0) ? 0 : ((r == 0 && c == 1) ? 2 : 1);
            return (r == 1 && c == 1) ? 0 : ((r == 0 && c == 0) ? 2 : 1);
        }
        float fix(float raw, int ch) {
            float bl = (ch == 0) ? uBlackLevel.r : ((ch == 1) ? uBlackLevel.g : uBlackLevel.b);
            float wb = (ch == 0) ? uWBGain.r  : ((ch == 1) ? uWBGain.g  : uWBGain.b);
            return max((raw - bl) / (uWhiteLevel - bl), 0.0) * wb;
        }
        void main(){
            vec2 sz = vec2(textureSize(uRawTex, 0));
            vec2 grid = vec2(1.0) / sz;
            float px = vUV.x * sz.x; float py = vUV.y * sz.y;
            int ix = int(floor(px)); int iy = int(floor(py));
            bool evenRow = (iy & 1) == 0; bool evenCol = (ix & 1) == 0;

            float raw_c0 = texture(uRawTex, vUV).r;
            float raw_l  = texture(uRawTex, vUV + vec2(-grid.x, 0.0)).r;
            float raw_r  = texture(uRawTex, vUV + vec2( grid.x, 0.0)).r;
            float raw_u  = texture(uRawTex, vUV + vec2(0.0, -grid.y)).r;
            float raw_d  = texture(uRawTex, vUV + vec2(0.0,  grid.y)).r;
            float raw_ul = texture(uRawTex, vUV + vec2(-grid.x, -grid.y)).r;
            float raw_ur = texture(uRawTex, vUV + vec2( grid.x, -grid.y)).r;
            float raw_dl = texture(uRawTex, vUV + vec2(-grid.x,  grid.y)).r;
            float raw_dr = texture(uRawTex, vUV + vec2( grid.x,  grid.y)).r;

            float c0 = fix(raw_c0, cfaCh(uCFAType, ix,   iy));
            float l  = fix(raw_l,  cfaCh(uCFAType, ix-1, iy));
            float r  = fix(raw_r,  cfaCh(uCFAType, ix+1, iy));
            float u  = fix(raw_u,  cfaCh(uCFAType, ix,   iy-1));
            float d  = fix(raw_d,  cfaCh(uCFAType, ix,   iy+1));
            float ul = fix(raw_ul, cfaCh(uCFAType, ix-1, iy-1));
            float ur = fix(raw_ur, cfaCh(uCFAType, ix+1, iy-1));
            float dl = fix(raw_dl, cfaCh(uCFAType, ix-1, iy+1));
            float dr = fix(raw_dr, cfaCh(uCFAType, ix+1, iy+1));

            float avgCross = 0.25 * (l + r + u + d);
            float avgDiag  = 0.25 * (ul + ur + dl + dr);

            float R, G, B;
            if (uCFAType == 0) {
                if (evenRow && evenCol) { R = c0; G = avgCross; B = avgDiag; }
                else if (evenRow && !evenCol) { G = c0; R = 0.5*(l+r); B = 0.5*(u+d); }
                else if (!evenRow && evenCol) { G = c0; B = 0.5*(l+r); R = 0.5*(u+d); }
                else { B = c0; G = avgCross; R = avgDiag; }
            } else if (uCFAType == 1) {
                if (evenRow && evenCol) { G = c0; R = 0.5*(l+r); B = 0.5*(u+d); }
                else if (evenRow && !evenCol) { R = c0; G = avgCross; B = avgDiag; }
                else if (!evenRow && evenCol) { B = c0; G = avgCross; R = avgDiag; }
                else { G = c0; B = 0.5*(l+r); R = 0.5*(u+d); }
            } else if (uCFAType == 2) {
                if (evenRow && evenCol) { G = c0; B = 0.5*(l+r); R = 0.5*(u+d); }
                else if (evenRow && !evenCol) { B = c0; G = avgCross; R = avgDiag; }
                else if (!evenRow && evenCol) { R = c0; G = avgCross; B = avgDiag; }
                else { G = c0; R = 0.5*(l+r); B = 0.5*(u+d); }
            } else {
                if (evenRow && evenCol) { B = c0; G = avgCross; R = avgDiag; }
                else if (evenRow && !evenCol) { G = c0; B = 0.5*(l+r); R = 0.5*(u+d); }
                else if (!evenRow && evenCol) { G = c0; R = 0.5*(l+r); B = 0.5*(u+d); }
                else { R = c0; G = avgCross; B = avgDiag; }
            }

            vec3 rgb = vec3(R, G, B);
            rgb = uCCM * rgb;
            rgb = clamp(rgb, 0.0, 1.0);
            rgb = pow(rgb, vec3(1.0/2.2));
            rgb = mix(rgb, smoothstep(0.0, 1.0, rgb), 0.20);
            frag = vec4(rgb, 1.0);
        }
    """.trimIndent()

    // ===== 1. Hamilton-Adams 边缘自适应 =====

    private val FS_HA = """
        #version 300 es
        precision highp float;
        precision highp sampler2D;
        uniform sampler2D uRawTex;
        uniform vec2 uRawSize;
        uniform vec3 uBlackLevel;
        uniform float uWhiteLevel;
        uniform vec3 uWBGain;
        uniform mat3 uCCM;
        uniform int uCFAType;
        in vec2 vUV;
        out vec4 frag;

        int cfaCh(int type, int x, int y) {
            int r = y & 1; int c = x & 1;
            if (type == 0) return (r == 0 && c == 0) ? 0 : ((r == 1 && c == 1) ? 2 : 1);
            if (type == 1) return (r == 0 && c == 1) ? 0 : ((r == 1 && c == 0) ? 2 : 1);
            if (type == 2) return (r == 1 && c == 0) ? 0 : ((r == 0 && c == 1) ? 2 : 1);
            return (r == 1 && c == 1) ? 0 : ((r == 0 && c == 0) ? 2 : 1);
        }
        float fix(float raw, int ch) {
            float bl = (ch == 0) ? uBlackLevel.r : ((ch == 1) ? uBlackLevel.g : uBlackLevel.b);
            float wb = (ch == 0) ? uWBGain.r  : ((ch == 1) ? uWBGain.g  : uWBGain.b);
            return max((raw - bl) / (uWhiteLevel - bl), 0.0) * wb;
        }
        void main(){
            vec2 sz = vec2(textureSize(uRawTex, 0));
            vec2 g = vec2(1.0) / sz;
            float px = vUV.x * sz.x; float py = vUV.y * sz.y;
            int ix = int(floor(px)); int iy = int(floor(py));
            int ch = cfaCh(uCFAType, ix, iy);

            // ── 5x5 邻域采样 ──
            float raw_c  = texture(uRawTex, vUV).r;
            float raw_l  = texture(uRawTex, vUV + vec2(-g.x, 0.0)).r;
            float raw_r  = texture(uRawTex, vUV + vec2( g.x, 0.0)).r;
            float raw_u  = texture(uRawTex, vUV + vec2(0.0, -g.y)).r;
            float raw_d  = texture(uRawTex, vUV + vec2(0.0,  g.y)).r;
            float raw_ul = texture(uRawTex, vUV + vec2(-g.x, -g.y)).r;
            float raw_ur = texture(uRawTex, vUV + vec2( g.x, -g.y)).r;
            float raw_dl = texture(uRawTex, vUV + vec2(-g.x,  g.y)).r;
            float raw_dr = texture(uRawTex, vUV + vec2( g.x,  g.y)).r;
            float raw_l2  = texture(uRawTex, vUV + vec2(-2.0*g.x, 0.0)).r;
            float raw_r2  = texture(uRawTex, vUV + vec2( 2.0*g.x, 0.0)).r;
            float raw_u2  = texture(uRawTex, vUV + vec2(0.0, -2.0*g.y)).r;
            float raw_d2  = texture(uRawTex, vUV + vec2(0.0,  2.0*g.y)).r;

            // 对角的 ±2 位置（R↔B 对角线插值用）
            float raw_ul2 = texture(uRawTex, vUV + vec2(-2.0*g.x, -2.0*g.y)).r;
            float raw_ur2 = texture(uRawTex, vUV + vec2( 2.0*g.x, -2.0*g.y)).r;
            float raw_dl2 = texture(uRawTex, vUV + vec2(-2.0*g.x,  2.0*g.y)).r;
            float raw_dr2 = texture(uRawTex, vUV + vec2( 2.0*g.x,  2.0*g.y)).r;

            // 全部 fix
            float c0  = fix(raw_c,  ch);
            float l   = fix(raw_l,  cfaCh(uCFAType, ix-1, iy));
            float r   = fix(raw_r,  cfaCh(uCFAType, ix+1, iy));
            float u   = fix(raw_u,  cfaCh(uCFAType, ix,   iy-1));
            float d   = fix(raw_d,  cfaCh(uCFAType, ix,   iy+1));
            float ul_ = fix(raw_ul, cfaCh(uCFAType, ix-1, iy-1));
            float ur_ = fix(raw_ur, cfaCh(uCFAType, ix+1, iy-1));
            float dl_ = fix(raw_dl, cfaCh(uCFAType, ix-1, iy+1));
            float dr_ = fix(raw_dr, cfaCh(uCFAType, ix+1, iy+1));
            // ±2 轴向（用于拉普拉斯校正，通道与中心相同）
            float l2  = fix(raw_l2,  cfaCh(uCFAType, ix-2, iy));
            float r2  = fix(raw_r2,  cfaCh(uCFAType, ix+2, iy));
            float u2  = fix(raw_u2,  cfaCh(uCFAType, ix,   iy-2));
            float d2  = fix(raw_d2,  cfaCh(uCFAType, ix,   iy+2));
            // ±2 对角（用于对角线插值）
            float ul2_ = fix(raw_ul2, cfaCh(uCFAType, ix-2, iy-2));
            float ur2_ = fix(raw_ur2, cfaCh(uCFAType, ix+2, iy-2));
            float dl2_ = fix(raw_dl2, cfaCh(uCFAType, ix-2, iy+2));
            float dr2_ = fix(raw_dr2, cfaCh(uCFAType, ix+2, iy+2));

            float R, G, B;

            if (ch == 0) {
                // ── R 像素 ──
                R = c0;
                // G 插值：边缘导向 + 拉普拉斯校正
                float dH = abs(l - r) + abs(2.0*c0 - l2 - r2);
                float dV = abs(u - d) + abs(2.0*c0 - u2 - d2);
                float GH = (l + r)*0.5 + (2.0*c0 - l2 - r2)*0.25;
                float GV = (u + d)*0.5 + (2.0*c0 - u2 - d2)*0.25;
                G = (dH < dV) ? GH : ((dV < dH) ? GV : (GH + GV)*0.5);

                // B 插值：对角线梯度
                float dN = abs(ul_ - dr_) + abs(2.0*c0 - ul2_ - dr2_);
                float dP = abs(ur_ - dl_) + abs(2.0*c0 - ur2_ - dl2_);
                float BN = (ul_ + dr_)*0.5 + (2.0*c0 - ul2_ - dr2_)*0.25;
                float BP = (ur_ + dl_)*0.5 + (2.0*c0 - ur2_ - dl2_)*0.25;
                B = (dN < dP) ? BN : ((dP < dN) ? BP : (BN + BP)*0.5);

            } else if (ch == 2) {
                // ── B 像素 ──
                B = c0;
                // G 插值
                float dH = abs(l - r) + abs(2.0*c0 - l2 - r2);
                float dV = abs(u - d) + abs(2.0*c0 - u2 - d2);
                float GH = (l + r)*0.5 + (2.0*c0 - l2 - r2)*0.25;
                float GV = (u + d)*0.5 + (2.0*c0 - u2 - d2)*0.25;
                G = (dH < dV) ? GH : ((dV < dH) ? GV : (GH + GV)*0.5);

                // R 插值：对角线梯度（R 在对角位置）
                float dN = abs(ul_ - dr_) + abs(2.0*c0 - ul2_ - dr2_);
                float dP = abs(ur_ - dl_) + abs(2.0*c0 - ur2_ - dl2_);
                float RN = (ul_ + dr_)*0.5 + (2.0*c0 - ul2_ - dr2_)*0.25;
                float RP = (ur_ + dl_)*0.5 + (2.0*c0 - ur2_ - dl2_)*0.25;
                R = (dN < dP) ? RN : ((dP < dN) ? RP : (RN + RP)*0.5);

            } else {
                // ── G 像素 ──
                G = c0;
                // 标准 HA 色差法 + 拉普拉斯校正
                // G 像素的 R 和 B 各在单一方向（水平或垂直），非对角线
                int chL = cfaCh(uCFAType, ix-1, iy);
                int chR = cfaCh(uCFAType, ix+1, iy);

                // R 插值：用同色方向邻居 + 同向 G 拉普拉斯校正（±2 G 像素）
                if (chL == 0 || chR == 0) {
                    R = (l + r)*0.5 + (2.0*G - l2 - r2)*0.25;
                } else {
                    R = (u + d)*0.5 + (2.0*G - u2 - d2)*0.25;
                }
                // B 在另一方向
                if (chL == 2 || chR == 2) {
                    B = (l + r)*0.5 + (2.0*G - l2 - r2)*0.25;
                } else {
                    B = (u + d)*0.5 + (2.0*G - u2 - d2)*0.25;
                }
            }

            vec3 rgb = vec3(R, G, B);
            rgb = uCCM * rgb;
            rgb = clamp(rgb, 0.0, 1.0);
            rgb = pow(rgb, vec3(1.0/2.2));
            rgb = mix(rgb, smoothstep(0.0, 1.0, rgb), 0.20);
            frag = vec4(rgb, 1.0);
        }
    """.trimIndent()

    // ===== 2. Malvar-He-Cutler 固定 5×5 滤波器 =====

    /** MHC 线性输出变体（无 CCM/gamma/S-curve），供迭代模式中间 Pass 使用。 */
    private val FS_MHC_LINEAR = """
        #version 300 es
        precision highp float;
        precision highp sampler2D;
        uniform sampler2D uRawTex;
        uniform vec2 uRawSize;
        uniform vec3 uBlackLevel;
        uniform float uWhiteLevel;
        uniform vec3 uWBGain;
        uniform int uCFAType;
        in vec2 vUV;
        out vec4 frag;

        int cfaCh(int type, int x, int y) {
            int r = y & 1; int c = x & 1;
            if (type == 0) return (r == 0 && c == 0) ? 0 : ((r == 1 && c == 1) ? 2 : 1);
            if (type == 1) return (r == 0 && c == 1) ? 0 : ((r == 1 && c == 0) ? 2 : 1);
            if (type == 2) return (r == 1 && c == 0) ? 0 : ((r == 0 && c == 1) ? 2 : 1);
            return (r == 1 && c == 1) ? 0 : ((r == 0 && c == 0) ? 2 : 1);
        }
        float fix(float raw, int ch) {
            float bl = (ch == 0) ? uBlackLevel.r : ((ch == 1) ? uBlackLevel.g : uBlackLevel.b);
            float wb = (ch == 0) ? uWBGain.r  : ((ch == 1) ? uWBGain.g  : uWBGain.b);
            return max((raw - bl) / (uWhiteLevel - bl), 0.0) * wb;
        }
        void main(){
            vec2 sz = vec2(textureSize(uRawTex, 0));
            vec2 g = vec2(1.0) / sz;
            float px = vUV.x * sz.x; float py = vUV.y * sz.y;
            int ix = int(floor(px)); int iy = int(floor(py));
            int ch = cfaCh(uCFAType, ix, iy);

            // ── 5x5 全部采样 ──
            float s[25];
            int idx = 0;
            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -2; dx <= 2; dx++) {
                    float raw = texture(uRawTex, vUV + vec2(float(dx)*g.x, float(dy)*g.y)).r;
                    s[idx] = fix(raw, cfaCh(uCFAType, ix+dx, iy+dy));
                    idx++;
                }
            }

            // MHC 固定 5x5 滤波器：G 通道
            float G = (-s[2] + 2.0*s[7] - s[10] + 2.0*s[11] + 4.0*s[12]
                      + 2.0*s[13] - s[14] + 2.0*s[17] - s[22]) / 8.0;

            float R = 0.0, B = 0.0;

            if (ch == 0) {
                R = s[12];
                float bg_sum = 0.0; int bg_n = 0;
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        int idx2 = (dy+2)*5 + (dx+2);
                        int dch = cfaCh(uCFAType, ix+dx, iy+dy);
                        if (dch == 2) {
                            float g_n = 0.0; int g_n_cnt = 0;
                            if (dx-1 >= -2) { g_n += s[(dy+2)*5 + (dx-1+2)]; g_n_cnt++; }
                            if (dx+1 <= 2)  { g_n += s[(dy+2)*5 + (dx+1+2)]; g_n_cnt++; }
                            if (dy-1 >= -2) { g_n += s[(dy-1+2)*5 + (dx+2)]; g_n_cnt++; }
                            if (dy+1 <= 2)  { g_n += s[(dy+1+2)*5 + (dx+2)]; g_n_cnt++; }
                            g_n /= float(g_n_cnt);
                            bg_sum += s[idx2] - g_n; bg_n++;
                        }
                    }
                }
                B = G + (bg_n > 0 ? bg_sum / float(bg_n) : 0.0);
            } else if (ch == 2) {
                B = s[12];
                float rg_sum = 0.0; int rg_n = 0;
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        int idx2 = (dy+2)*5 + (dx+2);
                        int dch = cfaCh(uCFAType, ix+dx, iy+dy);
                        if (dch == 0) {
                            float g_n = 0.0; int g_n_cnt = 0;
                            if (dx-1 >= -2) { g_n += s[(dy+2)*5 + (dx-1+2)]; g_n_cnt++; }
                            if (dx+1 <= 2)  { g_n += s[(dy+2)*5 + (dx+1+2)]; g_n_cnt++; }
                            if (dy-1 >= -2) { g_n += s[(dy-1+2)*5 + (dx+2)]; g_n_cnt++; }
                            if (dy+1 <= 2)  { g_n += s[(dy+1+2)*5 + (dx+2)]; g_n_cnt++; }
                            g_n /= float(g_n_cnt);
                            rg_sum += s[idx2] - g_n; rg_n++;
                        }
                    }
                }
                R = G + (rg_n > 0 ? rg_sum / float(rg_n) : 0.0);
            } else {
                G = s[12];
                float rg_sum = 0.0; int rg_n = 0;
                float bg_sum = 0.0; int bg_n = 0;
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        int idx2 = (dy+2)*5 + (dx+2);
                        int dch = cfaCh(uCFAType, ix+dx, iy+dy);
                        if (dch == 0) {
                            float g_n = 0.0; int g_n_cnt = 0;
                            if (dx-1 >= -2) { g_n += s[(dy+2)*5 + (dx-1+2)]; g_n_cnt++; }
                            if (dx+1 <= 2)  { g_n += s[(dy+2)*5 + (dx+1+2)]; g_n_cnt++; }
                            if (dy-1 >= -2) { g_n += s[(dy-1+2)*5 + (dx+2)]; g_n_cnt++; }
                            if (dy+1 <= 2)  { g_n += s[(dy+1+2)*5 + (dx+2)]; g_n_cnt++; }
                            g_n /= float(g_n_cnt);
                            rg_sum += s[idx2] - g_n; rg_n++;
                        }
                        if (dch == 2) {
                            float g_n = 0.0; int g_n_cnt = 0;
                            if (dx-1 >= -2) { g_n += s[(dy+2)*5 + (dx-1+2)]; g_n_cnt++; }
                            if (dx+1 <= 2)  { g_n += s[(dy+2)*5 + (dx+1+2)]; g_n_cnt++; }
                            if (dy-1 >= -2) { g_n += s[(dy-1+2)*5 + (dx+2)]; g_n_cnt++; }
                            if (dy+1 <= 2)  { g_n += s[(dy+1+2)*5 + (dx+2)]; g_n_cnt++; }
                            g_n /= float(g_n_cnt);
                            bg_sum += s[idx2] - g_n; bg_n++;
                        }
                    }
                }
                R = G + (rg_n > 0 ? rg_sum / float(rg_n) : 0.0);
                B = G + (bg_n > 0 ? bg_sum / float(bg_n) : 0.0);
            }

            frag = vec4(R, G, B, 1.0);
        }
    """.trimIndent()

    private val FS_MHC = """
        #version 300 es
        precision highp float;
        precision highp sampler2D;
        uniform sampler2D uRawTex;
        uniform vec2 uRawSize;
        uniform vec3 uBlackLevel;
        uniform float uWhiteLevel;
        uniform vec3 uWBGain;
        uniform mat3 uCCM;
        uniform int uCFAType;
        in vec2 vUV;
        out vec4 frag;

        int cfaCh(int type, int x, int y) {
            int r = y & 1; int c = x & 1;
            if (type == 0) return (r == 0 && c == 0) ? 0 : ((r == 1 && c == 1) ? 2 : 1);
            if (type == 1) return (r == 0 && c == 1) ? 0 : ((r == 1 && c == 0) ? 2 : 1);
            if (type == 2) return (r == 1 && c == 0) ? 0 : ((r == 0 && c == 1) ? 2 : 1);
            return (r == 1 && c == 1) ? 0 : ((r == 0 && c == 0) ? 2 : 1);
        }
        float fix(float raw, int ch) {
            float bl = (ch == 0) ? uBlackLevel.r : ((ch == 1) ? uBlackLevel.g : uBlackLevel.b);
            float wb = (ch == 0) ? uWBGain.r  : ((ch == 1) ? uWBGain.g  : uWBGain.b);
            return max((raw - bl) / (uWhiteLevel - bl), 0.0) * wb;
        }
        void main(){
            vec2 sz = vec2(textureSize(uRawTex, 0));
            vec2 g = vec2(1.0) / sz;
            float px = vUV.x * sz.x; float py = vUV.y * sz.y;
            int ix = int(floor(px)); int iy = int(floor(py));
            int ch = cfaCh(uCFAType, ix, iy);

            // ── 5x5 全部采样 ──
            float s[25];
            int idx = 0;
            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -2; dx <= 2; dx++) {
                    float raw = texture(uRawTex, vUV + vec2(float(dx)*g.x, float(dy)*g.y)).r;
                    s[idx] = fix(raw, cfaCh(uCFAType, ix+dx, iy+dy));
                    idx++;
                }
            }

            // MHC 固定 5x5 滤波器：G 通道
            // 非零系数位置：dy=-2,dx=0→-1; dy=-1,dx=0→2; dy=0,dx=-2→-1;
            // dy=0,dx=-1→2; dy=0,dx=0→4; dy=0,dx=1→2; dy=0,dx=2→-1;
            // dy=1,dx=0→2; dy=2,dx=0→-1
            float G = (-s[2] + 2.0*s[7] - s[10] + 2.0*s[11] + 4.0*s[12]
                      + 2.0*s[13] - s[14] + 2.0*s[17] - s[22]) / 8.0;

            float R = 0.0, B = 0.0;

            if (ch == 0) {
                R = s[12];
                float bg_sum = 0.0; int bg_n = 0;
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        int idx2 = (dy+2)*5 + (dx+2);
                        int dch = cfaCh(uCFAType, ix+dx, iy+dy);
                        if (dch == 2) {
                            float g_n = 0.0; int g_n_cnt = 0;
                            if (dx-1 >= -2) { g_n += s[(dy+2)*5 + (dx-1+2)]; g_n_cnt++; }
                            if (dx+1 <= 2)  { g_n += s[(dy+2)*5 + (dx+1+2)]; g_n_cnt++; }
                            if (dy-1 >= -2) { g_n += s[(dy-1+2)*5 + (dx+2)]; g_n_cnt++; }
                            if (dy+1 <= 2)  { g_n += s[(dy+1+2)*5 + (dx+2)]; g_n_cnt++; }
                            g_n /= float(g_n_cnt);
                            bg_sum += s[idx2] - g_n; bg_n++;
                        }
                    }
                }
                B = G + (bg_n > 0 ? bg_sum / float(bg_n) : 0.0);
            } else if (ch == 2) {
                B = s[12];
                float rg_sum = 0.0; int rg_n = 0;
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        int idx2 = (dy+2)*5 + (dx+2);
                        int dch = cfaCh(uCFAType, ix+dx, iy+dy);
                        if (dch == 0) {
                            float g_n = 0.0; int g_n_cnt = 0;
                            if (dx-1 >= -2) { g_n += s[(dy+2)*5 + (dx-1+2)]; g_n_cnt++; }
                            if (dx+1 <= 2)  { g_n += s[(dy+2)*5 + (dx+1+2)]; g_n_cnt++; }
                            if (dy-1 >= -2) { g_n += s[(dy-1+2)*5 + (dx+2)]; g_n_cnt++; }
                            if (dy+1 <= 2)  { g_n += s[(dy+1+2)*5 + (dx+2)]; g_n_cnt++; }
                            g_n /= float(g_n_cnt);
                            rg_sum += s[idx2] - g_n; rg_n++;
                        }
                    }
                }
                R = G + (rg_n > 0 ? rg_sum / float(rg_n) : 0.0);
            } else {
                G = s[12];
                float rg_sum = 0.0; int rg_n = 0;
                float bg_sum = 0.0; int bg_n = 0;
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        int idx2 = (dy+2)*5 + (dx+2);
                        int dch = cfaCh(uCFAType, ix+dx, iy+dy);
                        if (dch == 0) {
                            float g_n = 0.0; int g_n_cnt = 0;
                            if (dx-1 >= -2) { g_n += s[(dy+2)*5 + (dx-1+2)]; g_n_cnt++; }
                            if (dx+1 <= 2)  { g_n += s[(dy+2)*5 + (dx+1+2)]; g_n_cnt++; }
                            if (dy-1 >= -2) { g_n += s[(dy-1+2)*5 + (dx+2)]; g_n_cnt++; }
                            if (dy+1 <= 2)  { g_n += s[(dy+1+2)*5 + (dx+2)]; g_n_cnt++; }
                            g_n /= float(g_n_cnt);
                            rg_sum += s[idx2] - g_n; rg_n++;
                        }
                        if (dch == 2) {
                            float g_n = 0.0; int g_n_cnt = 0;
                            if (dx-1 >= -2) { g_n += s[(dy+2)*5 + (dx-1+2)]; g_n_cnt++; }
                            if (dx+1 <= 2)  { g_n += s[(dy+2)*5 + (dx+1+2)]; g_n_cnt++; }
                            if (dy-1 >= -2) { g_n += s[(dy-1+2)*5 + (dx+2)]; g_n_cnt++; }
                            if (dy+1 <= 2)  { g_n += s[(dy+1+2)*5 + (dx+2)]; g_n_cnt++; }
                            g_n /= float(g_n_cnt);
                            bg_sum += s[idx2] - g_n; bg_n++;
                        }
                    }
                }
                R = G + (rg_n > 0 ? rg_sum / float(rg_n) : 0.0);
                B = G + (bg_n > 0 ? bg_sum / float(bg_n) : 0.0);
            }

            vec3 rgb = vec3(R, G, B);
            rgb = uCCM * rgb;
            rgb = clamp(rgb, 0.0, 1.0);
            rgb = pow(rgb, vec3(1.0/2.2));
            rgb = mix(rgb, smoothstep(0.0, 1.0, rgb), 0.20);
            frag = vec4(rgb, 1.0);
        }
    """.trimIndent()

    // ===== 3. 迭代正则化（第二轮：色差双边滤波） =====

    private val FS_REFINE = """
        #version 300 es
        precision highp float;
        precision highp sampler2D;
        uniform sampler2D uRGBATex;
        uniform vec2 uTexSize;
        uniform mat3 uCCM;
        uniform int uOrientation;
        uniform bool uMirror;
        uniform vec2 uAspectScale;
        in vec2 vUV;
        out vec4 frag;

        void main() {
            vec2 g = vec2(1.0) / uTexSize;
            vec3 center = texture(uRGBATex, vUV).rgb;
            float R = center.r, G = center.g, B = center.b;

            // 色差双边滤波（5x5 窗口）
            float sigS = 2.0;
            float sigR = 0.08;
            float sumW = 0.0;
            float sumCr = 0.0;
            float sumCb = 0.0;

            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -2; dx <= 2; dx++) {
                    vec3 s = texture(uRGBATex, vUV + vec2(float(dx)*g.x, float(dy)*g.y)).rgb;
                    float wS = exp(-float(dx*dx + dy*dy) / (2.0*sigS*sigS));
                    float lumDiff = dot(s - center, vec3(0.3, 0.6, 0.1));
                    float wR = exp(-(lumDiff * lumDiff) / (2.0 * sigR * sigR));
                    float w = wS * wR;
                    sumW += w;
                    sumCr += w * (s.r - s.g);
                    sumCb += w * (s.b - s.g);
                }
            }

            float blend = 0.4;
            float refinedCr = sumCr / sumW;
            float refinedCb = sumCb / sumW;
            float origCr = R - G;
            float origCb = B - G;

            R = G + mix(origCr, refinedCr, blend);
            B = G + mix(origCb, refinedCb, blend);

            vec3 rgb = vec3(R, G, B);
            rgb = uCCM * rgb;
            rgb = clamp(rgb, 0.0, 1.0);
            rgb = pow(rgb, vec3(1.0/2.2));
            rgb = mix(rgb, smoothstep(0.0, 1.0, rgb), 0.20);
            frag = vec4(rgb, 1.0);
        }
    """.trimIndent()
}

package com.classic.camera

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class RawPipeline : GLSurfaceView.Renderer {

    companion object {
        private const val GL_R16 = 0x822A
    }

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
    private var activeProg = 0

    // ---- Uniform 缓存（每程序独立） ----
    private class ProgUniforms(val id: Int) {
        var aPos = 0; var aTexCoord = 0
        var uRawTex = 0; var uRawSize = 0
        var uBlackLevel = 0; var uWhiteLevel = 0
        var uWBGain = 0; var uCCM = 0
        var uAspectScale = 0; var uCFAOffset = 0
        var uOrientation = 0; var uMirror = 0
        // LUT
        var uLutTex = 0; var uLutSizeLoc = 0; var uEnableLut = 0

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
            uCFAOffset = GLES30.glGetUniformLocation(id, "uCFAOffset")
            uOrientation = GLES30.glGetUniformLocation(id, "uOrientation")
            uMirror = GLES30.glGetUniformLocation(id, "uMirror")
            lookupLut()
        }

        fun lookupLut() {
            uLutTex = GLES30.glGetUniformLocation(id, "uLutTexture")
            uLutSizeLoc = GLES30.glGetUniformLocation(id, "uLutSize")
            uEnableLut = GLES30.glGetUniformLocation(id, "uEnableLut")
        }
    }

    private lateinit var uniBilinear: ProgUniforms

    private var texId = 0
    private var drawCount = 0
    private var lastDrawLog = 0L

    // ---- LUT 滤镜 ----
    @Volatile var lutFloatArray: FloatArray? = null
    private var lutTextureId = 0
    private var lutEnabled = false

    // ---- 离屏渲染 ----
    private var captureFbo = 0
    private var captureTex = 0
    private var captureTexW = 0
    private var captureTexH = 0
    private var lastViewportW = 0
    private var lastViewportH = 0

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
        texAllocated = false; texW = 0; texH = 0
        activeProg = 0

        progBilinear = createProgram(VS, buildBayerShader(BILINEAR_BODY))
        uniBilinear = ProgUniforms(progBilinear).also { it.lookupBayer() }

        android.util.Log.d("ClassicCamera", "GL programs: bilinear=$progBilinear")

        val texs = IntArray(1)
        GLES30.glGenTextures(1, texs, 0)
        texId = texs[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        // LUT 纹理
        val lutTexs = IntArray(1)
        GLES30.glGenTextures(1, lutTexs, 0)
        lutTextureId = lutTexs[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lutTextureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        lutEnabled = false
        // 表面重建后恢复 LUT 数据
        val existingLut = lutFloatArray
        if (existingLut != null) {
            val bitmap = LutUtils.createLutBitmap(existingLut)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
            bitmap.recycle()
            lutEnabled = true
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        lastViewportW = width
        lastViewportH = height
        GLES30.glViewport(0, 0, width, height)
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
        val cfaoX = if (cfaType == 1 || cfaType == 3) 1f else 0f
        val cfaoY = if (cfaType == 2 || cfaType == 3) 1f else 0f
        GLES30.glUniform2f(u.uCFAOffset, cfaoX, cfaoY)
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
                "GL draw rate=${drawCount}/s rawIsNull=${rawShorts==null} w=$rawW h=$rawH")
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

    /** 绑定 LUT 纹理并设置 uniform。在 useProgram + setBayerUniforms 之后调用。 */
    private fun bindLut(u: ProgUniforms) {
        if (lutTextureId == 0) return
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lutTextureId)
        GLES30.glUniform1i(u.uLutTex, 1)
        GLES30.glUniform1f(u.uLutSizeLoc, 33.0f)
        GLES30.glUniform1i(u.uEnableLut, if (lutEnabled && lutFloatArray != null) 1 else 0)
    }

    private fun drawBayer(prog: Int, u: ProgUniforms) {
        useProgram(prog)
        val (ax, ay) = aspectScale()
        setBayerUniforms(u, ax, ay)
        bindLut(u)
        drawQuad(u)
    }

    /** 无数据时画测试图案。 */
    private fun drawTestPattern() {
        useProgram(progBilinear)
        GLES30.glUniform3f(uniBilinear.uBlackLevel, 0f, 0f, 0f)
        GLES30.glUniform1f(uniBilinear.uWhiteLevel, 1f)
        GLES30.glUniform3f(uniBilinear.uWBGain, 1f, 1f, 1f)
        val ident = floatArrayOf(1f,0f,0f, 0f,1f,0f, 0f,0f,1f)
        GLES30.glUniformMatrix3fv(uniBilinear.uCCM, 1, false, ident, 0)
        GLES30.glUniform2f(uniBilinear.uCFAOffset, 0f, 0f)
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

        // 固定使用双线性去马赛克
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, captureFbo)
        GLES30.glViewport(0, 0, captureTexW, captureTexH)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        useProgram(progBilinear)
        setBayerUniforms(uniBilinear, 1f, 1f)
        bindLut(uniBilinear)
        drawQuad(uniBilinear)

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

    /** 设置 LUT 数据（GL 线程中调用）。null = 关闭滤镜。 */
    fun setLut(data: FloatArray?) {
        lutFloatArray = data
        lutEnabled = data != null
        if (data != null && lutTextureId != 0) {
            val bitmap = LutUtils.createLutBitmap(data)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lutTextureId)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
            bitmap.recycle()
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
        if (texId != 0) { GLES30.glDeleteTextures(1, intArrayOf(texId), 0); texId = 0 }
        if (lutTextureId != 0) { GLES30.glDeleteTextures(1, intArrayOf(lutTextureId), 0); lutTextureId = 0 }
        if (progBilinear != 0) { GLES30.glDeleteProgram(progBilinear); progBilinear = 0 }
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

    // ===== 共享 Shader 构建 =====

    /** 构建 Bayer → RGB Fragment Shader。demosaicBody 需产生 float R,G,B 变量。 */
    private fun buildBayerShader(demosaicBody: String): String {
        return SHADER_HEADER + demosaicBody.replace("vUV", "uv") + TONEMAP_OUTPUT
    }

    private val SHADER_HEADER = """
#version 300 es
precision highp float;
precision highp sampler2D;
uniform sampler2D uRawTex;
uniform vec2 uRawSize;
uniform vec3 uBlackLevel;
uniform float uWhiteLevel;
uniform vec3 uWBGain;
uniform mat3 uCCM;
uniform vec2 uCFAOffset;
uniform sampler2D uLutTexture;
uniform float uLutSize;
uniform bool uEnableLut;
in vec2 vUV;
out vec4 frag;

int ch(int x, int y) {
    return ((y & 1) == 0) ? (((x & 1) == 0) ? 0 : 1) : (((x & 1) == 0) ? 1 : 2);
}

float fix(float raw, int ch) {
    float bl = (ch == 0) ? uBlackLevel.r : ((ch == 1) ? uBlackLevel.g : uBlackLevel.b);
    float wb = (ch == 0) ? uWBGain.r  : ((ch == 1) ? uWBGain.g  : uWBGain.b);
    return max((raw - bl) / (uWhiteLevel - bl), 0.0) * wb;
}

vec3 applyLUT(vec3 color) {
    vec3 lutCoord = color * (uLutSize - 1.0);
    float blueSlice = floor(lutCoord.b);
    float blueOffset = lutCoord.b - blueSlice;
    float rCoord1 = (lutCoord.r + blueSlice * uLutSize + 0.5) / (uLutSize * uLutSize);
    float rCoord2 = (lutCoord.r + min(blueSlice + 1.0, uLutSize - 1.0) * uLutSize + 0.5) / (uLutSize * uLutSize);
    float gCoord = (lutCoord.g + 0.5) / uLutSize;
    vec3 lutColor1 = texture(uLutTexture, vec2(rCoord1, gCoord)).rgb;
    vec3 lutColor2 = texture(uLutTexture, vec2(rCoord2, gCoord)).rgb;
    return mix(lutColor1, lutColor2, blueOffset);
}

vec3 applyLutWithProtection(vec3 originalRgb) {
    vec3 filteredRgb = applyLUT(originalRgb);
    float luminance = dot(originalRgb, vec3(0.299, 0.587, 0.114));
    float shadowStrength = mix(0.3, 1.0, smoothstep(0.0, 0.15, luminance));
    float highlightStrength = mix(1.0, 0.4, smoothstep(0.85, 1.0, luminance));
    return mix(originalRgb, filteredRgb, shadowStrength * highlightStrength);
}

void main() {
    vec2 sz = vec2(textureSize(uRawTex, 0));
    vec2 grid = vec2(1.0) / sz;
    vec2 uv = vUV + uCFAOffset * grid;
    float px = uv.x * sz.x; float py = uv.y * sz.y;
    int ix = int(floor(px)); int iy = int(floor(py));
    int bx = int(floor(vUV.x * sz.x)); int by = int(floor(vUV.y * sz.y));
""".trimIndent()

    private val TONEMAP_OUTPUT = """
    vec3 rgb = vec3(R, G, B);
    rgb = uCCM * rgb;
    rgb = clamp(rgb, 0.0, 1.0);
    rgb = mix(12.92 * rgb, 1.055 * pow(rgb, vec3(1.0/2.4)) - 0.055, step(vec3(0.0031308), rgb));
    if (uEnableLut) { rgb = applyLutWithProtection(rgb); }
    frag = vec4(rgb, 1.0);
}
""".trimIndent()

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

    // ===== Malvar-He-Cutler 5x5 卷积去马赛克 =====

    private val BILINEAR_BODY = """
    float s[25];
    int idx = 0;
    for (int dy = -2; dy <= 2; dy++) {
        for (int dx = -2; dx <= 2; dx++) {
            float raw = texture(uRawTex, vUV + vec2(float(dx)*grid.x, float(dy)*grid.y)).r;
            s[idx] = fix(raw, ch(bx+dx, by+dy));
            idx++;
        }
    }

    float d0 = (-s[2] + 2.0*s[7] - s[10] + 2.0*s[11] + 4.0*s[12] + 2.0*s[13] - s[14] + 2.0*s[17] - s[22]) / 8.0;
    float d1 = (s[2] - 2.0*s[6] - 2.0*s[8] - 2.0*s[10] + 8.0*s[11] + 10.0*s[12] + 8.0*s[13] - 2.0*s[14] - 2.0*s[16] - 2.0*s[18] + s[22]) / 16.0;
    float d2 = (-2.0*s[2] - 2.0*s[6] + 8.0*s[7] - 2.0*s[8] + s[10] + 10.0*s[12] + s[14] - 2.0*s[16] + 8.0*s[17] - 2.0*s[18] - 2.0*s[22]) / 16.0;
    float d3 = (-3.0*s[2] + 4.0*s[6] + 4.0*s[8] - 3.0*s[10] + 12.0*s[12] - 3.0*s[14] + 4.0*s[16] + 4.0*s[18] - 3.0*s[22]) / 16.0;

    bool R_row = (by & 1) == 0;
    bool B_row = !R_row;
    bool R_col = (bx & 1) == 0;
    bool B_col = !R_col;

    float R, G, B;
    if (R_row && R_col) { R = s[12]; G = d0; B = d3; }
    else if (R_row && B_col) { R = d1; G = s[12]; B = d2; }
    else if (B_row && R_col) { R = d2; G = s[12]; B = d1; }
    else { R = d3; G = d0; B = s[12]; }
""".trimIndent()
}

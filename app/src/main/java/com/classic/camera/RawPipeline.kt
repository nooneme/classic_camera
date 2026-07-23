package com.classic.camera

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLES31
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
        private const val GL_R16UI = 0x8234
        private const val GL_RGBA16F = 0x881A
        private const val GL_HALF_FLOAT = 0x140B
        private const val GL_R8 = 0x8229
        private const val RCD_WG = 16
        private const val SHADER_STORAGE_BARRIER = 0x00000002
    }



    // ---- 供外部更新参数 ----
    var rawWidth: Int = 0
        private set
    var rawHeight: Int = 0
        private set

    @Volatile var rawShorts: ShortArray? = null
    /** ByteBuffer 路径（预览用，跳过 ShortArray 中间分配）。position=0, limit=w*h*2。 */
    @Volatile var rawBuffer: java.nio.ByteBuffer? = null
    @Volatile var rawW: Int = 0
    @Volatile var rawH: Int = 0
    var blackLevelR = 0.0f
    var blackLevelG = 0.0f
    var blackLevelB = 0.0f
    var whiteLevel = 1023f
    @Volatile var blackLevelOffset = 0f
    @Volatile var whiteLevelOffset = 0f
    var wbR = 1f; var wbG = 1f; var wbB = 1f
    var ccm = FloatArray(9) { if (it % 4 == 0) 1f else 0f }
    var orientation: Int = 90
    var mirror: Boolean = false
    var cfaType = 2
    @Volatile var toneMapD = 0.59f
    @Volatile var toneMapE = 0.14f

    private var rawDirectBuf: java.nio.ByteBuffer? = null
    private var texAllocated = false
    private var texW = 0
    private var texH = 0

    // ---- 着色器程序 ----
    private var progBilinear = 0
    // ---- Uniform 缓存（每程序独立） ----
    private class ProgUniforms(val id: Int) {
        var aPos = 0; var aTexCoord = 0
        var uRawTex = 0; var uRawSize = 0
        var uBlackLevel = 0; var uWhiteLevel = 0
        var uWBGain = 0; var uCCM = 0
        var uAspectScale = 0; var uCFAOffset = 0
        var uOrientation = 0; var uMirror = 0
        // tone mapping
        var uToneMapD = 0; var uToneMapE = 0
        // LUT
        var uLutTex = 0; var uLutSizeLoc = 0; var uEnableLut = 0; var uLutIntensity = 0
        // tone curve
        var uToneCurveLUT = 0
        // LSC
        var uLscGainTex = 0; var uLscGridSize = 0; var uEnableLsc = 0

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
            uLscGainTex = GLES30.glGetUniformLocation(id, "uLscGainTex")
            uLscGridSize = GLES30.glGetUniformLocation(id, "uLscGridSize")
            uEnableLsc = GLES30.glGetUniformLocation(id, "uEnableLsc")
            uToneMapD = GLES30.glGetUniformLocation(id, "uToneMapD")
            uToneMapE = GLES30.glGetUniformLocation(id, "uToneMapE")
            uToneCurveLUT = GLES30.glGetUniformLocation(id, "uToneCurveLUT")
            lookupLut()
        }

        fun lookupLut() {
            uLutTex = GLES30.glGetUniformLocation(id, "uLutTexture")
            uLutSizeLoc = GLES30.glGetUniformLocation(id, "uLutSize")
            uEnableLut = GLES30.glGetUniformLocation(id, "uEnableLut")
            uLutIntensity = GLES30.glGetUniformLocation(id, "uLutIntensity")
        }
    }

    private lateinit var uniBilinear: ProgUniforms

    private var texId = 0

    // ---- LUT 滤镜 ----
    @Volatile var lutFloatArray: FloatArray? = null
    private var lutTextureId = 0
    private var lutEnabled = false
    @Volatile var lutIntensity: Float = 1f

    // ---- 色调曲线 ----
    @Volatile var toneCurvePoints: FloatArray? = null
    private var toneCurveTexId = 0
    @Volatile var toneCurveDirty = false

    // ---- LSC 镜头阴影校正 ----
    @Volatile var lscGainMap: FloatArray? = null
    @Volatile var lscGridCols: Int = 0
    @Volatile var lscGridRows: Int = 0
    private var lscTexId = 0
    private var lastGainMapRef: FloatArray? = null

    // ---- RCD 计算管线 ----
    private var rcdInitialized = false
    private var rcdEnabled = false
    private var rcdPopulateProg = 0
    private var rcdStep1Prog = 0
    private var rcdStep2Prog = 0
    private var rcdStep3Prog = 0
    private var rcdStep40Prog = 0
    private var rcdStep41Prog = 0
    private var rcdStep42Prog = 0
    private var rcdStep43Prog = 0
    private var rcdWriteProg = 0
    private val rcdSsbo = IntArray(9)
    private var rcdRawTexId = 0
    private var rcdOutTexId = 0
    private var rcdBufW = 0
    private var rcdBufH = 0

    // ---- RCD 后处理 Fragment Shader ----
    private var progRcd = 0
    private lateinit var uniRcd: RcdUniforms
    private class RcdUniforms(val id: Int) {
        var aPos = 0; var aTexCoord = 0
        var uInputTex = 0
        var uCCM = 0
        var uAspectScale = 0; var uOrientation = 0; var uMirror = 0
        var uToneMapD = 0; var uToneMapE = 0
        var uToneCurveLUT = 0
        var uLutTex = 0; var uLutSizeLoc = 0; var uEnableLut = 0; var uLutIntensity = 0
        var uLscGainTex = 0; var uLscGridSize = 0; var uEnableLsc = 0
        fun lookup() {
            aPos = GLES30.glGetAttribLocation(id, "aPos")
            aTexCoord = GLES30.glGetAttribLocation(id, "aTexCoord")
            uInputTex = GLES30.glGetUniformLocation(id, "uInputTex")
            uCCM = GLES30.glGetUniformLocation(id, "uCCM")
            uAspectScale = GLES30.glGetUniformLocation(id, "uAspectScale")
            uOrientation = GLES30.glGetUniformLocation(id, "uOrientation")
            uMirror = GLES30.glGetUniformLocation(id, "uMirror")
            uToneMapD = GLES30.glGetUniformLocation(id, "uToneMapD")
            uToneMapE = GLES30.glGetUniformLocation(id, "uToneMapE")
            uToneCurveLUT = GLES30.glGetUniformLocation(id, "uToneCurveLUT")
            uLutTex = GLES30.glGetUniformLocation(id, "uLutTexture")
            uLutSizeLoc = GLES30.glGetUniformLocation(id, "uLutSize")
            uEnableLut = GLES30.glGetUniformLocation(id, "uEnableLut")
            uLutIntensity = GLES30.glGetUniformLocation(id, "uLutIntensity")
            uLscGainTex = GLES30.glGetUniformLocation(id, "uLscGainTex")
            uLscGridSize = GLES30.glGetUniformLocation(id, "uLscGridSize")
            uEnableLsc = GLES30.glGetUniformLocation(id, "uEnableLsc")
        }
    }

    // ---- GL 初始化回调（GPU 多帧融合等需 context 就绪后初始化） ----
    var onGluReady: (() -> Unit)? = null

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
        captureFbo = 0; captureTex = 0; captureTexW = 0; captureTexH = 0
        texAllocated = false; texW = 0; texH = 0
        rcdInitialized = false; rcdEnabled = false
        rcdSsbo.fill(0); rcdBufW = 0; rcdBufH = 0

        progBilinear = createProgram(VS, buildBayerShader(BILINEAR_BODY))
        uniBilinear = ProgUniforms(progBilinear).also { it.lookupBayer() }

        val texs = IntArray(1)
        GLES30.glGenTextures(1, texs, 0)
        texId = texs[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        // LUT 3D 纹理
        val lutTexs = IntArray(1)
        GLES30.glGenTextures(1, lutTexs, 0)
        lutTextureId = lutTexs[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTextureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        lutEnabled = false
        if (lutFloatArray != null) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTextureId)
            GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGBA8, 33, 33, 33, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, lutToBuffer3D(lutFloatArray!!))
            while (GLES30.glGetError() != GLES30.GL_NO_ERROR) {}
            lutEnabled = true
        }

        // LSC gain map 纹理
        val lscTexs = IntArray(1)
        GLES30.glGenTextures(1, lscTexs, 0)
        lscTexId = lscTexs[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lscTexId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        lastGainMapRef = null

        // 色调曲线 LUT 纹理 (256x1, R8)
        val tcTexs = IntArray(1)
        GLES30.glGenTextures(1, tcTexs, 0)
        toneCurveTexId = tcTexs[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, toneCurveTexId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        uploadIdentityToneCurve()

        // RCD 计算管线初始化
        initRcd()

        // RCD 后处理 Fragment Shader
        progRcd = createProgram(VS, RCD_FRAGMENT_SHADER)
        uniRcd = RcdUniforms(progRcd).also { it.lookup() }

        // GPU 多帧管线初始化（context 已就绪）
        onGluReady?.invoke()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        lastViewportW = width
        lastViewportH = height
        GLES30.glViewport(0, 0, width, height)
    }

    /** 上传 DirectByteBuffer 到 GL_R16 纹理（预览路径，ByteBuffer 已是 Direct 且 position=0）。 */
    private fun uploadRawBuffer(buf: java.nio.ByteBuffer): Boolean {
        if (rawW <= 0 || rawH <= 0) return false
        if (buf.remaining() < rawW * rawH * 2) return false
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
        if (texAllocated && texW == rawW && texH == rawH) {
            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, rawW, rawH, GLES30.GL_RED, GLES30.GL_UNSIGNED_SHORT, buf)
        } else {
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GL_R16, rawW, rawH, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_SHORT, buf)
            texAllocated = true; texW = rawW; texH = rawH
        }
        return true
    }

    /** 上传 ShortArray 到 GL_R16 纹理（拍照路径，内部通过 rawDirectBuf 中转）。 */
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
        else { rawDirectBuf!!.clear(); rawDirectBuf!!.limit(needed); rawDirectBuf!! }
        bb.asShortBuffer().put(data)
        bb.position(0)
        return uploadRawBuffer(bb)
    }

    /** 设置 Bayer shader 的公共 uniform。 */
    private fun setBayerUniforms(u: ProgUniforms, aspectX: Float, aspectY: Float) {
        val inv65535 = 1.0f / 65535.0f
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
        GLES30.glUniform1i(u.uRawTex, 0)
        if (u.uRawSize >= 0) GLES30.glUniform2f(u.uRawSize, rawW.toFloat(), rawH.toFloat())
        GLES30.glUniform3f(u.uBlackLevel, (blackLevelR + blackLevelOffset) * inv65535, (blackLevelG + blackLevelOffset) * inv65535, (blackLevelB + blackLevelOffset) * inv65535)
        GLES30.glUniform1f(u.uWhiteLevel, (whiteLevel + whiteLevelOffset) * inv65535)
        GLES30.glUniform3f(u.uWBGain, wbR, wbG, wbB)
        GLES30.glUniformMatrix3fv(u.uCCM, 1, false, ccm, 0)
        val cfaoX = if (cfaType == 1 || cfaType == 3) 1f else 0f
        val cfaoY = if (cfaType == 2 || cfaType == 3) 1f else 0f
        GLES30.glUniform2f(u.uCFAOffset, cfaoX, cfaoY)
        GLES30.glUniform1i(u.uOrientation, orientation)
        GLES30.glUniform1i(u.uMirror, if (mirror) 1 else 0)
        GLES30.glUniform1f(u.uToneMapD, toneMapD)
        GLES30.glUniform1f(u.uToneMapE, toneMapE)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, toneCurveTexId)
        GLES30.glUniform1i(u.uToneCurveLUT, 3)
        GLES30.glUniform2f(u.uAspectScale, aspectX, aspectY)
    }

    // ====================== RCD 计算管线 ======================

    private fun initRcd() {
        if (rcdInitialized) return
        try {
            rcdPopulateProg = compileCompute(RcdShaders.POPULATE)
            rcdStep1Prog = compileCompute(RcdShaders.STEP_1)
            rcdStep2Prog = compileCompute(RcdShaders.STEP_2)
            rcdStep3Prog = compileCompute(RcdShaders.STEP_3)
            rcdStep40Prog = compileCompute(RcdShaders.STEP_4_0)
            rcdStep41Prog = compileCompute(RcdShaders.STEP_4_1)
            rcdStep42Prog = compileCompute(RcdShaders.STEP_4_2)
            rcdStep43Prog = compileCompute(RcdShaders.STEP_4_3)
            rcdWriteProg = compileCompute(RcdShaders.WRITE_OUTPUT)

            GLES31.glGenBuffers(9, rcdSsbo, 0)

            // RCD RAW 输入纹理 (R16UI)
            val rcdTexs = IntArray(1)
            GLES31.glGenTextures(1, rcdTexs, 0)
            rcdRawTexId = rcdTexs[0]

            // RCD 输出纹理 (RGBA16F)
            val outTexs = IntArray(1)
            GLES31.glGenTextures(1, outTexs, 0)
            rcdOutTexId = outTexs[0]

            rcdEnabled = true
        } catch (e: Exception) {
            android.util.Log.w("ClassicCamera", "RCD init failed, falling back to MHC", e)
            rcdEnabled = false
        }
        rcdInitialized = true
    }

    private fun ensureRcdBuffers(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        if (rcdSsbo[0] == 0) GLES31.glGenBuffers(9, rcdSsbo, 0)
        val fullBytes = w * h * 4
        val halfBytes = w * h * 2
        val sizes = intArrayOf(fullBytes, fullBytes, fullBytes, fullBytes, fullBytes, halfBytes, halfBytes, halfBytes, halfBytes)
        for (i in 0..8) {
            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, rcdSsbo[i])
            GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, sizes[i], null, GLES31.GL_DYNAMIC_DRAW)
        }
        if (rcdBufW != w || rcdBufH != h) {
            if (rcdRawTexId != 0) { GLES31.glDeleteTextures(1, intArrayOf(rcdRawTexId), 0) }
            if (rcdOutTexId != 0) { GLES31.glDeleteTextures(1, intArrayOf(rcdOutTexId), 0) }
            val rawTex = IntArray(1); GLES31.glGenTextures(1, rawTex, 0); rcdRawTexId = rawTex[0]
            val outTex = IntArray(1); GLES31.glGenTextures(1, outTex, 0); rcdOutTexId = outTex[0]
            GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, rcdRawTexId)
            GLES31.glTexStorage2D(GLES31.GL_TEXTURE_2D, 1, GL_R16UI, w, h)
            GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MIN_FILTER, GLES31.GL_NEAREST)
            GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MAG_FILTER, GLES31.GL_NEAREST)
            GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_S, GLES31.GL_CLAMP_TO_EDGE)
            GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_T, GLES31.GL_CLAMP_TO_EDGE)
            GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, rcdOutTexId)
            GLES31.glTexStorage2D(GLES31.GL_TEXTURE_2D, 1, GL_RGBA16F, w, h)
            GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MIN_FILTER, GLES31.GL_LINEAR)
            GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MAG_FILTER, GLES31.GL_LINEAR)
            GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_S, GLES31.GL_CLAMP_TO_EDGE)
            GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_T, GLES31.GL_CLAMP_TO_EDGE)
            rcdBufW = w; rcdBufH = h
        }
    }

    private fun uploadRcdRaw(buf: java.nio.Buffer, w: Int, h: Int) {
        if (rcdRawTexId == 0) return
        buf.position(0)
        val shortCount = buf.remaining() / 2
        if (shortCount < w * h) return
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, rcdRawTexId)
        GLES31.glTexSubImage2D(GLES31.GL_TEXTURE_2D, 0, 0, 0, w, h, GLES31.GL_RED_INTEGER, GLES31.GL_UNSIGNED_SHORT, buf)
    }

    private fun uploadRcdRawShort(data: ShortArray, w: Int, h: Int) {
        if (rcdRawTexId == 0 || data.size < w * h) return
        val bb = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.nativeOrder())
        bb.asShortBuffer().put(data)
        bb.position(0)
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, rcdRawTexId)
        GLES31.glTexSubImage2D(GLES31.GL_TEXTURE_2D, 0, 0, 0, w, h, GLES31.GL_RED_INTEGER, GLES31.GL_UNSIGNED_SHORT, bb)
    }

    private fun drawRcdToFbo() {
        if (rcdOutTexId == 0 || captureFbo == 0) return
        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, captureFbo)
        GLES31.glViewport(0, 0, captureTexW, captureTexH)
        GLES31.glClearColor(0f, 0f, 0f, 1f)
        GLES31.glClear(GLES31.GL_COLOR_BUFFER_BIT)
        GLES31.glUseProgram(progRcd)
        GLES31.glUniform2f(uniRcd.uAspectScale, 1f, 1f)
        GLES31.glUniform1i(uniRcd.uOrientation, orientation)
        GLES31.glUniform1i(uniRcd.uMirror, if (mirror) 1 else 0)
        GLES31.glActiveTexture(GLES31.GL_TEXTURE0)
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, rcdOutTexId)
        GLES31.glUniform1i(uniRcd.uInputTex, 0)
        GLES31.glUniformMatrix3fv(uniRcd.uCCM, 1, false, ccm, 0)
        GLES31.glUniform1f(uniRcd.uToneMapD, toneMapD)
        GLES31.glUniform1f(uniRcd.uToneMapE, toneMapE)
        GLES31.glActiveTexture(GLES31.GL_TEXTURE3)
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, toneCurveTexId)
        GLES31.glUniform1i(uniRcd.uToneCurveLUT, 3)
        GLES31.glActiveTexture(GLES31.GL_TEXTURE1)
        GLES31.glBindTexture(GLES31.GL_TEXTURE_3D, lutTextureId)
        GLES31.glUniform1i(uniRcd.uLutTex, 1)
        GLES31.glUniform1f(uniRcd.uLutSizeLoc, 33.0f)
        GLES31.glUniform1i(uniRcd.uEnableLut, if (lutEnabled && lutFloatArray != null) 1 else 0)
        GLES31.glUniform1f(uniRcd.uLutIntensity, lutIntensity)
        GLES31.glActiveTexture(GLES31.GL_TEXTURE2)
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, lscTexId)
        GLES31.glUniform1i(uniRcd.uLscGainTex, 2)
        GLES31.glUniform2f(uniRcd.uLscGridSize, lscGridCols.toFloat(), lscGridRows.toFloat())
        GLES31.glUniform1i(uniRcd.uEnableLsc, if (lscGainMap != null) 1 else 0)
        drawQuadRaw(uniRcd.aPos, uniRcd.aTexCoord)
    }

    private fun dispatchRcd(w: Int, h: Int, cfa: Int, black: FloatArray, white: Float, wbGains: FloatArray) {
        if (!rcdEnabled) return
        ensureRcdBuffers(w, h)

        val gx = (w + RCD_WG - 1) / RCD_WG
        val gy = (h + RCD_WG - 1) / RCD_WG
        val gx2 = ((w / 2) + RCD_WG - 1) / RCD_WG

        val black4 = if (black.size >= 4) floatArrayOf(black[0], black[1], black[2], black[3])
            else floatArrayOf(black[0], black[0], black[0], black[0])
        val wb4 = if (wbGains.size >= 4) floatArrayOf(wbGains[0], wbGains[1], wbGains[2], wbGains[3])
            else floatArrayOf(wbGains[0], 1f, 1f, wbGains[0])

        // POPULATE
        GLES31.glUseProgram(rcdPopulateProg)
        GLES31.glActiveTexture(GLES31.GL_TEXTURE0)
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, rcdRawTexId)
        GLES31.glUniform1i(GLES31.glGetUniformLocation(rcdPopulateProg, "uRawTexture"), 0)
        for (i in 0..3) GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, i, rcdSsbo[i])
        GLES31.glUniform2i(GLES31.glGetUniformLocation(rcdPopulateProg, "uImageSize"), w, h)
        GLES31.glUniform1i(GLES31.glGetUniformLocation(rcdPopulateProg, "uCfaPattern"), cfa)
        GLES31.glUniform4f(GLES31.glGetUniformLocation(rcdPopulateProg, "uBlackLevel"), black4[0], black4[1], black4[2], black4[3])
        GLES31.glUniform1f(GLES31.glGetUniformLocation(rcdPopulateProg, "uWhiteLevel"), white)
        GLES31.glUniform4f(GLES31.glGetUniformLocation(rcdPopulateProg, "uWhiteBalanceGains"), wb4[0], wb4[1], wb4[2], wb4[3])
        GLES31.glDispatchCompute(gx, gy, 1)
        GLES31.glMemoryBarrier(SHADER_STORAGE_BARRIER)

        // STEP 1
        GLES31.glUseProgram(rcdStep1Prog)
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, rcdSsbo[0])
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 4, rcdSsbo[4])
        GLES31.glUniform2i(GLES31.glGetUniformLocation(rcdStep1Prog, "uImageSize"), w, h)
        GLES31.glDispatchCompute(gx, gy, 1)
        GLES31.glMemoryBarrier(SHADER_STORAGE_BARRIER)

        // STEP 2
        GLES31.glUseProgram(rcdStep2Prog)
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, rcdSsbo[0])
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 5, rcdSsbo[5])
        GLES31.glUniform2i(GLES31.glGetUniformLocation(rcdStep2Prog, "uImageSize"), w, h)
        GLES31.glUniform1i(GLES31.glGetUniformLocation(rcdStep2Prog, "uCfaPattern"), cfa)
        GLES31.glDispatchCompute(gx2, gy, 1)
        GLES31.glMemoryBarrier(SHADER_STORAGE_BARRIER)

        // STEP 3
        GLES31.glUseProgram(rcdStep3Prog)
        for (i in intArrayOf(0, 2, 4, 5)) GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, i, rcdSsbo[i])
        GLES31.glUniform2i(GLES31.glGetUniformLocation(rcdStep3Prog, "uImageSize"), w, h)
        GLES31.glUniform1i(GLES31.glGetUniformLocation(rcdStep3Prog, "uCfaPattern"), cfa)
        GLES31.glDispatchCompute(gx2, gy, 1)
        GLES31.glMemoryBarrier(SHADER_STORAGE_BARRIER)

        // STEP 4_0
        GLES31.glUseProgram(rcdStep40Prog)
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, rcdSsbo[0])
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 6, rcdSsbo[6])
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 7, rcdSsbo[7])
        GLES31.glUniform2i(GLES31.glGetUniformLocation(rcdStep40Prog, "uImageSize"), w, h)
        GLES31.glDispatchCompute(gx2, gy, 1)
        GLES31.glMemoryBarrier(SHADER_STORAGE_BARRIER)

        // STEP 4_1
        GLES31.glUseProgram(rcdStep41Prog)
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 6, rcdSsbo[6])
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 7, rcdSsbo[7])
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 5, rcdSsbo[5])
        GLES31.glUniform2i(GLES31.glGetUniformLocation(rcdStep41Prog, "uImageSize"), w, h)
        GLES31.glUniform1i(GLES31.glGetUniformLocation(rcdStep41Prog, "uCfaPattern"), cfa)
        GLES31.glDispatchCompute(gx2, gy, 1)
        GLES31.glMemoryBarrier(SHADER_STORAGE_BARRIER)

        // STEP 4_2
        GLES31.glUseProgram(rcdStep42Prog)
        for (i in 0..3) GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, i, rcdSsbo[i])
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 4, rcdSsbo[5])
        GLES31.glUniform2i(GLES31.glGetUniformLocation(rcdStep42Prog, "uImageSize"), w, h)
        GLES31.glUniform1i(GLES31.glGetUniformLocation(rcdStep42Prog, "uCfaPattern"), cfa)
        GLES31.glDispatchCompute(gx2, gy, 1)
        GLES31.glMemoryBarrier(SHADER_STORAGE_BARRIER)

        // STEP 4_3
        GLES31.glUseProgram(rcdStep43Prog)
        for (i in 0..3) GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, i, rcdSsbo[i])
        GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 4, rcdSsbo[4])
        GLES31.glUniform2i(GLES31.glGetUniformLocation(rcdStep43Prog, "uImageSize"), w, h)
        GLES31.glUniform1i(GLES31.glGetUniformLocation(rcdStep43Prog, "uCfaPattern"), cfa)
        GLES31.glDispatchCompute(gx2, gy, 1)
        GLES31.glMemoryBarrier(SHADER_STORAGE_BARRIER)

        // WRITE_OUTPUT
        GLES31.glUseProgram(rcdWriteProg)
        for (i in 0..3) GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, i, rcdSsbo[i])
        GLES31.glBindImageTexture(0, rcdOutTexId, 0, false, 0, GLES31.GL_WRITE_ONLY, GL_RGBA16F)
        GLES31.glUniform2i(GLES31.glGetUniformLocation(rcdWriteProg, "uImageSize"), w, h)
        GLES31.glUniform1i(GLES31.glGetUniformLocation(rcdWriteProg, "uCfaPattern"), cfa)
        GLES31.glUniform3f(GLES31.glGetUniformLocation(rcdWriteProg, "uCalculationGains"), 1f, 1f, 1f)
        GLES31.glDispatchCompute(gx, gy, 1)
        GLES31.glMemoryBarrier(SHADER_STORAGE_BARRIER or GLES31.GL_TEXTURE_FETCH_BARRIER_BIT)
    }

    /** 用 RCD 输出纹理绘制（替代 BILINEAR_BODY 路径）。 */
    private fun drawRcd(u: RcdUniforms) {
        if (rcdOutTexId == 0) return
        GLES31.glUseProgram(u.id)
        val (ax, ay) = aspectScale()
        GLES31.glUniform2f(u.uAspectScale, ax, ay)
        GLES31.glUniform1i(u.uOrientation, orientation)
        GLES31.glUniform1i(u.uMirror, if (mirror) 1 else 0)

        GLES31.glActiveTexture(GLES31.GL_TEXTURE0)
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, rcdOutTexId)
        GLES31.glUniform1i(u.uInputTex, 0)

        GLES31.glUniformMatrix3fv(u.uCCM, 1, false, ccm, 0)
        GLES31.glUniform1f(u.uToneMapD, toneMapD)
        GLES31.glUniform1f(u.uToneMapE, toneMapE)

        GLES31.glActiveTexture(GLES31.GL_TEXTURE3)
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, toneCurveTexId)
        GLES31.glUniform1i(u.uToneCurveLUT, 3)

        GLES31.glActiveTexture(GLES31.GL_TEXTURE1)
        GLES31.glBindTexture(GLES31.GL_TEXTURE_3D, lutTextureId)
        GLES31.glUniform1i(u.uLutTex, 1)
        GLES31.glUniform1f(u.uLutSizeLoc, 33.0f)
        GLES31.glUniform1i(u.uEnableLut, if (lutEnabled && lutFloatArray != null) 1 else 0)
        GLES31.glUniform1f(u.uLutIntensity, lutIntensity)

        GLES31.glActiveTexture(GLES31.GL_TEXTURE2)
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, lscTexId)
        GLES31.glUniform1i(u.uLscGainTex, 2)
        GLES31.glUniform2f(u.uLscGridSize, lscGridCols.toFloat(), lscGridRows.toFloat())
        GLES31.glUniform1i(u.uEnableLsc, if (lscGainMap != null) 1 else 0)

        drawQuadRaw(u.aPos, u.aTexCoord)
    }

    private fun drawQuadRaw(aPos: Int, aTex: Int) {
        GLES31.glEnableVertexAttribArray(aPos)
        GLES31.glVertexAttribPointer(aPos, 2, GLES31.GL_FLOAT, false, 0, floatBuf(quadVerts))
        GLES31.glEnableVertexAttribArray(aTex)
        GLES31.glVertexAttribPointer(aTex, 2, GLES31.GL_FLOAT, false, 0, floatBuf(quadUVs))
        GLES31.glDrawArrays(GLES31.GL_TRIANGLE_STRIP, 0, 4)
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
        GLES30.glUseProgram(prog)
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
        if (lastViewportW > 0 && lastViewportH > 0) {
            GLES30.glViewport(0, 0, lastViewportW, lastViewportH)
        }

        while (GLES30.glGetError() != GLES30.GL_NO_ERROR) {}

        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        if (rawW <= 0 || rawH <= 0) { drawTestPattern(); return }

        val buf = rawBuffer
        if (buf != null) {
            buf.position(0)
            if (!uploadRawBuffer(buf)) { drawTestPattern(); return }
        } else {
            val data = rawShorts ?: run { drawTestPattern(); return }
            if (!uploadRaw(data)) { drawTestPattern(); return }
        }

        // 预览用 MHC（快），RCD 仅用于拍照出图
        drawBayer(progBilinear, uniBilinear)
    }

    /** 绑定 LUT 纹理并设置 uniform。在 useProgram + setBayerUniforms 之后调用。 */
    private fun bindLut(u: ProgUniforms) {
        if (lutTextureId == 0) return
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTextureId)
        GLES30.glUniform1i(u.uLutTex, 1)
        GLES30.glUniform1f(u.uLutSizeLoc, 33.0f)
        GLES30.glUniform1i(u.uEnableLut, if (lutEnabled && lutFloatArray != null) 1 else 0)
        GLES30.glUniform1f(u.uLutIntensity, lutIntensity)
    }

    /** 绑定 LSC gain map 纹理并设置 uniform。 */
    private fun bindLsc(u: ProgUniforms) {
        if (lscTexId == 0) return
        val gains = lscGainMap
        val cols = lscGridCols
        val rows = lscGridRows
        if (gains != null && cols > 0 && rows > 0) {
            if (lastGainMapRef !== gains) {
                val pixelCount = cols * rows * 4
                val halfData = java.nio.ByteBuffer.allocateDirect(pixelCount * 2).order(java.nio.ByteOrder.nativeOrder())
                val halfBuf = halfData.asShortBuffer()
                for (i in 0 until pixelCount) {
                    halfBuf.put(floatToHalf(gains[i]).toShort())
                }
                halfBuf.rewind()
                halfData.rewind()
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lscTexId)
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GL_RGBA16F, cols, rows, 0, GLES30.GL_RGBA, GL_HALF_FLOAT, halfData)
                lastGainMapRef = gains
            }
            GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lscTexId)
            GLES30.glUniform1i(u.uLscGainTex, 2)
            GLES30.glUniform2f(u.uLscGridSize, cols.toFloat(), rows.toFloat())
            GLES30.glUniform1i(u.uEnableLsc, 1)
        } else {
            GLES30.glUniform1i(u.uEnableLsc, 0)
        }
    }

    private fun drawBayer(prog: Int, u: ProgUniforms) {
        useProgram(prog)
        val (ax, ay) = aspectScale()
        setBayerUniforms(u, ax, ay)
        bindLut(u)
        bindLsc(u)
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
        GLES30.glUniform1i(uniBilinear.uEnableLsc, 0)
        GLES30.glUniform1f(uniBilinear.uToneMapD, 0.59f)
        GLES30.glUniform1f(uniBilinear.uToneMapE, 0.14f)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, toneCurveTexId)
        GLES30.glUniform1i(uniBilinear.uToneCurveLUT, 3)
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

        this.blackLevelR = blackLevelR; this.blackLevelG = blackLevelG; this.blackLevelB = blackLevelB
        this.whiteLevel = whiteLevel
        this.wbR = wbR; this.wbG = wbG; this.wbB = wbB
        this.ccm = ccmColumnMajor
        this.cfaType = cfaType
        rawW = w; rawH = h

        val rotated = (orientation == 90 || orientation == 270)
        val outW = if (rotated) h else w
        val outH = if (rotated) w else h

        try { ensureCaptureFbo(outW, outH) } catch (e: Exception) { return null }

        // 上传 RAW
        val shortBuf = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.nativeOrder())
        shortBuf.asShortBuffer().put(bayer)
        shortBuf.position(0)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, captureFbo)
        GLES30.glViewport(0, 0, captureTexW, captureTexH)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        if (rcdEnabled && rcdOutTexId != 0) {
            ensureRcdBuffers(w, h)
            GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, rcdRawTexId)
            GLES31.glTexSubImage2D(GLES31.GL_TEXTURE_2D, 0, 0, 0, w, h, GLES31.GL_RED_INTEGER, GLES31.GL_UNSIGNED_SHORT, shortBuf)
            dispatchRcd(w, h, cfaType, floatArrayOf(blackLevelR, blackLevelG, blackLevelG, blackLevelB),
                whiteLevel, floatArrayOf(wbR, wbG, wbG, wbB))
            drawRcdToFbo()
        } else {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GL_R16, w, h, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_SHORT, shortBuf)
            useProgram(progBilinear)
            setBayerUniforms(uniBilinear, 1f, 1f)
            bindLut(uniBilinear)
            bindLsc(uniBilinear)
            drawQuad(uniBilinear)
        }

        return readCaptureBitmap(outW, outH)
    }

    private fun readCaptureBitmap(outW: Int, outH: Int): Bitmap? {
        try {
            val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val stripHeight = 512
            val stripBytes = ByteBuffer.allocateDirect(outW * stripHeight * 4).order(ByteOrder.nativeOrder())
            val tReadStart = System.nanoTime()
            var totalReadTime = 0L
            var totalConvertTime = 0L
            for (y in 0 until outH step stripHeight) {
                val curH = minOf(stripHeight, outH - y)
                stripBytes.clear()
                stripBytes.limit(outW * curH * 4)
                val tGlRead = System.nanoTime()
                GLES30.glReadPixels(0, y, outW, curH, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, stripBytes)
                totalReadTime += System.nanoTime() - tGlRead
                stripBytes.position(0)
                val tConvert = System.nanoTime()
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
                totalConvertTime += System.nanoTime() - tConvert
                bitmap.setPixels(argbStrip, 0, outW, 0, outH - y - curH, outW, curH)
            }
            android.util.Log.d("ClassicCamera", String.format("readCaptureBitmap: glReadPixels=%.1fms convert+setPixels=%.1fms total=%.1fms (strips=%d, stripH=%d)",
                totalReadTime / 1_000_000.0,
                totalConvertTime / 1_000_000.0,
                (System.nanoTime() - tReadStart) / 1_000_000.0,
                (outH + stripHeight - 1) / stripHeight,
                stripHeight))
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
            while (GLES30.glGetError() != GLES30.GL_NO_ERROR) {}
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTextureId)
            GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGBA8, 33, 33, 33, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, lutToBuffer3D(data))
            while (GLES30.glGetError() != GLES30.GL_NO_ERROR) {}
        }
    }

    private fun lutToBuffer(lut: FloatArray): java.nio.ByteBuffer {
        val w = 1089; val h = 33
        val buf = java.nio.ByteBuffer.allocateDirect(w * h * 4).order(java.nio.ByteOrder.nativeOrder())
        for (y in 0 until h) {
            for (x in 0 until w) {
                val bSlice = x / 33; val rIndex = x % 33; val gIndex = y
                val idx = (bSlice * 1089 + gIndex * 33 + rIndex) * 3
                buf.put((lut[idx] * 255f).toInt().coerceIn(0, 255).toByte())
                buf.put((lut[idx + 1] * 255f).toInt().coerceIn(0, 255).toByte())
                buf.put((lut[idx + 2] * 255f).toInt().coerceIn(0, 255).toByte())
                buf.put(255.toByte())
            }
        }
        buf.rewind()
        return buf
    }

    private fun lutToBuffer3D(lut: FloatArray): java.nio.ByteBuffer {
        val size = 33
        val buf = java.nio.ByteBuffer.allocateDirect(size * size * size * 4).order(java.nio.ByteOrder.nativeOrder())
        for (b in 0 until size) {
            for (g in 0 until size) {
                for (r in 0 until size) {
                    val idx = (b * size * size + g * size + r) * 3
                    buf.put((lut[idx] * 255f).toInt().coerceIn(0, 255).toByte())
                    buf.put((lut[idx + 1] * 255f).toInt().coerceIn(0, 255).toByte())
                    buf.put((lut[idx + 2] * 255f).toInt().coerceIn(0, 255).toByte())
                    buf.put(255.toByte())
                }
            }
        }
        buf.rewind()
        return buf
    }

    // ====================== 色调曲线 ======================

    private val TONE_CURVE_LUT_SIZE = 256

    /** 上传 y=x 恒等曲线到纹理。 */
    private fun uploadIdentityToneCurve() {
        val buf = java.nio.ByteBuffer.allocateDirect(TONE_CURVE_LUT_SIZE).order(java.nio.ByteOrder.nativeOrder())
        for (i in 0 until TONE_CURVE_LUT_SIZE) {
            buf.put((i * 255 / (TONE_CURVE_LUT_SIZE - 1)).toByte())
        }
        buf.rewind()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, toneCurveTexId)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GL_R8, TONE_CURVE_LUT_SIZE, 1, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, buf)
    }

    /** 从 float[256] LUT 更新纹理。 */
    private fun uploadToneCurveLUT(lut: FloatArray) {
        val buf = java.nio.ByteBuffer.allocateDirect(TONE_CURVE_LUT_SIZE).order(java.nio.ByteOrder.nativeOrder())
        for (i in 0 until TONE_CURVE_LUT_SIZE.coerceAtMost(lut.size)) {
            buf.put((lut[i].coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255).toByte())
        }
        buf.rewind()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, toneCurveTexId)
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, TONE_CURVE_LUT_SIZE, 1, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, buf)
    }

    /** 设置色调曲线（GL 线程中调用）。null = 重置为恒等曲线。 */
    fun setToneCurve(points: FloatArray?) {
        toneCurvePoints = points
        if (points == null) {
            uploadIdentityToneCurve()
        } else {
            val lut = ToneCurveEngine.generateLUT(points, TONE_CURVE_LUT_SIZE)
            uploadToneCurveLUT(lut)
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
        if (lscTexId != 0) { GLES30.glDeleteTextures(1, intArrayOf(lscTexId), 0); lscTexId = 0 }
        if (toneCurveTexId != 0) { GLES30.glDeleteTextures(1, intArrayOf(toneCurveTexId), 0); toneCurveTexId = 0 }
        if (progBilinear != 0) { GLES30.glDeleteProgram(progBilinear); progBilinear = 0 }
        if (rcdRawTexId != 0) { GLES30.glDeleteTextures(1, intArrayOf(rcdRawTexId), 0); rcdRawTexId = 0 }
        if (rcdOutTexId != 0) { GLES30.glDeleteTextures(1, intArrayOf(rcdOutTexId), 0); rcdOutTexId = 0 }
        if (rcdSsbo[0] != 0) { GLES31.glDeleteBuffers(9, rcdSsbo, 0); rcdSsbo.fill(0) }
        if (progRcd != 0) { GLES30.glDeleteProgram(progRcd); progRcd = 0 }
        val progs = intArrayOf(rcdPopulateProg, rcdStep1Prog, rcdStep2Prog, rcdStep3Prog, rcdStep40Prog, rcdStep41Prog, rcdStep42Prog, rcdStep43Prog, rcdWriteProg)
        for (p in progs) if (p != 0) GLES30.glDeleteProgram(p)
        rcdPopulateProg = 0; rcdStep1Prog = 0; rcdStep2Prog = 0; rcdStep3Prog = 0
        rcdStep40Prog = 0; rcdStep41Prog = 0; rcdStep42Prog = 0; rcdStep43Prog = 0; rcdWriteProg = 0
        rcdInitialized = false; rcdEnabled = false
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

    private fun compileCompute(src: String): Int {
        val s = GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER)
        GLES31.glShaderSource(s, src)
        GLES31.glCompileShader(s)
        val ok = IntArray(1)
        GLES31.glGetShaderiv(s, GLES31.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] != GLES31.GL_TRUE) {
            throw RuntimeException("compute compile fail: ${GLES31.glGetShaderInfoLog(s)}")
        }
        val p = GLES31.glCreateProgram()
        GLES31.glAttachShader(p, s)
        GLES31.glLinkProgram(p)
        val link = IntArray(1)
        GLES31.glGetProgramiv(p, GLES31.GL_LINK_STATUS, link, 0)
        if (link[0] != GLES31.GL_TRUE) {
            throw RuntimeException("compute link fail: ${GLES31.glGetProgramInfoLog(p)}")
        }
        GLES31.glDeleteShader(s)
        return p
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
uniform highp sampler3D uLutTexture;
uniform float uLutSize;
uniform bool uEnableLut;
uniform float uLutIntensity;
uniform sampler2D uLscGainTex;
uniform vec2 uLscGridSize;
uniform bool uEnableLsc;
uniform float uToneMapD;
uniform float uToneMapE;
uniform sampler2D uToneCurveLUT;
in vec2 vUV;
out vec4 frag;

int ch(int x, int y) {
    return ((y & 1) == 0) ? (((x & 1) == 0) ? 0 : 1) : (((x & 1) == 0) ? 1 : 2);
}

float getLscGain(int channel, vec2 uv) {
    if (!uEnableLsc) return 1.0;
    vec2 texCoord = vec2(
        (uv.x * (uLscGridSize.x - 1.0) + 0.5) / uLscGridSize.x,
        (uv.y * (uLscGridSize.y - 1.0) + 0.5) / uLscGridSize.y
    );
    vec4 gain = texture(uLscGainTex, texCoord);
    return (channel == 0) ? gain.r : ((channel == 1) ? gain.g : gain.b);
}

float fix(float raw, int ch, vec2 lscUV) {
    float bl = (ch == 0) ? uBlackLevel.r : ((ch == 1) ? uBlackLevel.g : uBlackLevel.b);
    float val = max(raw - bl, 0.0);
    val *= getLscGain(ch, lscUV);
    float wb = (ch == 0) ? uWBGain.r  : ((ch == 1) ? uWBGain.g  : uWBGain.b);
    return val / (uWhiteLevel - bl) * wb;
}

vec3 applyLUT(vec3 color) {
    vec3 coord = (color * (uLutSize - 1.0) + 0.5) / uLutSize;
    return mix(color, texture(uLutTexture, coord).rgb, uLutIntensity);
}

vec3 applyToneCurve(vec3 color) {
    float luminance = dot(color, vec3(0.2126, 0.7152, 0.0722));
    float mapped = texture(uToneCurveLUT, vec2(luminance, 0.5)).r;
    return color * (mapped / max(luminance, 0.001));
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
    rgb = max(rgb, 0.0);
    const float a = 2.51, b = 0.03, c = 2.43;
    rgb = rgb * (a * rgb + b) / (rgb * (c * rgb + uToneMapD) + uToneMapE);
    rgb = applyToneCurve(rgb);
    rgb = mix(12.92 * rgb, 1.055 * pow(rgb, vec3(1.0/2.4)) - 0.055, step(vec3(0.0031308), rgb));
    if (uEnableLut) { rgb = applyLUT(rgb); }
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

    // ===== RCD 后处理 Fragment Shader =====
    //
    // 从 RCD 输出纹理采样 RGB → WB → CCM → 色调映射 → sRGB Gamma → LUT

    private val RCD_FRAGMENT_SHADER = """
        #version 300 es
        precision highp float;
        precision highp sampler3D;
        uniform sampler2D uInputTex;
        uniform mat3 uCCM;
        uniform float uToneMapD;
        uniform float uToneMapE;
        uniform sampler2D uToneCurveLUT;
        uniform highp sampler3D uLutTexture;
        uniform float uLutSize;
        uniform bool uEnableLut;
        uniform float uLutIntensity;
        uniform sampler2D uLscGainTex;
        uniform vec2 uLscGridSize;
        uniform bool uEnableLsc;
        in vec2 vUV;
        out vec4 frag;

        vec3 applyLUT(vec3 color) {
            vec3 coord = (color * (uLutSize - 1.0) + 0.5) / uLutSize;
            return mix(color, texture(uLutTexture, coord).rgb, uLutIntensity);
        }

        vec3 applyToneCurve(vec3 color) {
            float l = dot(color, vec3(0.2126, 0.7152, 0.0722));
            float m = texture(uToneCurveLUT, vec2(l, 0.5)).r;
            return color * (m / max(l, 0.001));
        }

        void main() {
            vec3 rgb = texture(uInputTex, vUV).rgb;
            if (uEnableLsc) {
                vec2 tc = vec2((vUV.x * (uLscGridSize.x - 1.0) + 0.5) / uLscGridSize.x,
                               (vUV.y * (uLscGridSize.y - 1.0) + 0.5) / uLscGridSize.y);
                vec4 gain = texture(uLscGainTex, tc);
                rgb *= vec3(gain.r, gain.g, gain.b);
            }
            rgb = uCCM * rgb;
            rgb = max(rgb, 0.0);
            const float a = 2.51, b = 0.03, c = 2.43;
            rgb = rgb * (a * rgb + b) / (rgb * (c * rgb + uToneMapD) + uToneMapE);
            rgb = applyToneCurve(rgb);
            rgb = mix(12.92 * rgb, 1.055 * pow(rgb, vec3(1.0/2.4)) - 0.055, step(vec3(0.0031308), rgb));
            if (uEnableLut) rgb = applyLUT(rgb);
            frag = vec4(rgb, 1.0);
        }
    """.trimIndent()

    // ===== Malvar-He-Cutler 5x5 卷积去马赛克 =====

    private val BILINEAR_BODY = """
    float s[25];
    int idx = 0;
    for (int dy = -2; dy <= 2; dy++) {
        for (int dx = -2; dx <= 2; dx++) {
            vec2 sampleUV = vUV + vec2(float(dx)*grid.x, float(dy)*grid.y);
            float raw = texture(uRawTex, sampleUV).r;
            s[idx] = fix(raw, ch(bx+dx, by+dy), sampleUV);
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

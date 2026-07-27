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
        private const val GL_R16F = 0x822D
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

    // ---- 黑电平扣除中间 Pass ----
    private var progBlSub = 0
    private var blFbo = 0
    private var blTexId = 0
    private var blTexW = 0
    private var blTexH = 0

    // ---- LSC 增益中间 Pass ----
    private var progLsc = 0
    private var progLscRgb = 0
    private var lscBufFbo = 0
    private var lscBufTexId = 0
    private var lscBufW = 0
    private var lscBufH = 0

    // ---- CFA 重排中间 Pass ----
    private var progCfaReorder = 0
    private var cfaBufFbo = 0
    private var cfaBufTexId = 0
    private var cfaBufW = 0
    private var cfaBufH = 0

    // ---- WB 归一化中间 Pass ----
    private var progWb = 0
    private var wbBufFbo = 0
    private var wbBufTexId = 0
    private var wbBufW = 0
    private var wbBufH = 0

    // ---- MHC 去马赛克中间 Pass ----
    private var progMhcDemosaic = 0
    private var mhcBufFbo = 0
    private var mhcBufTexId = 0
    private var mhcBufW = 0
    private var mhcBufH = 0

    // ---- 后处理（CCM/色调映射/曲线/Gamma/LUT）----
    private var progPostProcess = 0
    private var progCcm = 0
    private var ccmBufFbo = 0
    private var ccmBufTexId = 0
    private var ccmBufW = 0
    private var ccmBufH = 0
    private var progToneMap = 0
    private var toneMapBufFbo = 0
    private var toneMapBufTexId = 0
    private var toneMapBufW = 0
    private var toneMapBufH = 0
    private var progToneCurve = 0
    private var curveBufFbo = 0
    private var curveBufTexId = 0
    private var curveBufW = 0
    private var curveBufH = 0
    private var progGamma = 0
    private var gammaBufFbo = 0
    private var gammaBufTexId = 0
    private var gammaBufW = 0
    private var gammaBufH = 0

    @Volatile var toneMapD = 0.59f
    @Volatile var toneMapE = 0.14f
    @Volatile var highlightReconstructionEnabled = false

    private var rawDirectBuf: java.nio.ByteBuffer? = null
    private var texAllocated = false
    private var texW = 0
    private var texH = 0

    // ---- 着色器程序 ----
    private var progBlit = 0

    // ---- 高光重建引擎 ----
    private val hrEngine = FilmicHrEngine()
    // 降采样/上采样缓冲（预览用，高光重建前降采样到 1/2 尺寸）
    private var dsBufFbo = 0; private var dsBufTexId = 0
    private var dsBufW = 0; private var dsBufH = 0
    private var usBufFbo = 0; private var usBufTexId = 0
    private var usBufW = 0; private var usBufH = 0

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

    // ---- Bayer 降采样预览 Pass ----
    @Volatile var bayerDsScale = 2
    private var progBayerDs = 0
    private var bayerDsBufFbo = 0
    private var bayerDsBufTexId = 0
    private var bayerDsBufW = 0
    private var bayerDsBufH = 0

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

    // ---- 黑电平扣除 Pass Uniforms ----
    private class BlSubUniforms(val id: Int) {
        var aPos = 0; var aTexCoord = 0
        var uRawTex = 0
        var uBlackLevel = 0
        var uAspectScale = 0; var uOrientation = 0; var uMirror = 0

        fun lookup() {
            aPos = GLES30.glGetAttribLocation(id, "aPos")
            aTexCoord = GLES30.glGetAttribLocation(id, "aTexCoord")
            uRawTex = GLES30.glGetUniformLocation(id, "uRawTex")
            uBlackLevel = GLES30.glGetUniformLocation(id, "uBlackLevel")
            uAspectScale = GLES30.glGetUniformLocation(id, "uAspectScale")
            uOrientation = GLES30.glGetUniformLocation(id, "uOrientation")
            uMirror = GLES30.glGetUniformLocation(id, "uMirror")
        }
    }

    private lateinit var uniBlSub: BlSubUniforms

    // ---- Bayer 降采样 Pass Uniforms ----
    private class BayerDsUniforms(val id: Int) {
        var aPos = 0; var aTexCoord = 0
        var uRawTex = 0; var uCfaPattern = 0
        var uAspectScale = 0; var uOrientation = 0; var uMirror = 0

        fun lookup() {
            aPos = GLES30.glGetAttribLocation(id, "aPos")
            aTexCoord = GLES30.glGetAttribLocation(id, "aTexCoord")
            uRawTex = GLES30.glGetUniformLocation(id, "uRawTex")
            uCfaPattern = GLES30.glGetUniformLocation(id, "uCfaPattern")
            uAspectScale = GLES30.glGetUniformLocation(id, "uAspectScale")
            uOrientation = GLES30.glGetUniformLocation(id, "uOrientation")
            uMirror = GLES30.glGetUniformLocation(id, "uMirror")
        }
    }

    private lateinit var uniBayerDs: BayerDsUniforms

    // ---- LSC 增益 Pass Uniforms ----
    private class LscUniforms(val id: Int) {
        var aPos = 0; var aTexCoord = 0
        var uInputTex = 0
        var uLscGainTex = 0; var uLscGridSize = 0; var uEnableLsc = 0
        var uAspectScale = 0; var uOrientation = 0; var uMirror = 0

        fun lookup() {
            aPos = GLES30.glGetAttribLocation(id, "aPos")
            aTexCoord = GLES30.glGetAttribLocation(id, "aTexCoord")
            uInputTex = GLES30.glGetUniformLocation(id, "uInputTex")
            uLscGainTex = GLES30.glGetUniformLocation(id, "uLscGainTex")
            uLscGridSize = GLES30.glGetUniformLocation(id, "uLscGridSize")
            uEnableLsc = GLES30.glGetUniformLocation(id, "uEnableLsc")
            uAspectScale = GLES30.glGetUniformLocation(id, "uAspectScale")
            uOrientation = GLES30.glGetUniformLocation(id, "uOrientation")
            uMirror = GLES30.glGetUniformLocation(id, "uMirror")
        }
    }

    private lateinit var uniLsc: LscUniforms

    // ---- LSC 增益 Pass Uniforms（RGB 版本，RCD 路径）----
    private class LscRgbUniforms(val id: Int) {
        var aPos = 0; var aTexCoord = 0
        var uInputTex = 0
        var uLscGainTex = 0; var uLscGridSize = 0; var uEnableLsc = 0
        var uAspectScale = 0; var uOrientation = 0; var uMirror = 0

        fun lookup() {
            aPos = GLES30.glGetAttribLocation(id, "aPos")
            aTexCoord = GLES30.glGetAttribLocation(id, "aTexCoord")
            uInputTex = GLES30.glGetUniformLocation(id, "uInputTex")
            uLscGainTex = GLES30.glGetUniformLocation(id, "uLscGainTex")
            uLscGridSize = GLES30.glGetUniformLocation(id, "uLscGridSize")
            uEnableLsc = GLES30.glGetUniformLocation(id, "uEnableLsc")
            uAspectScale = GLES30.glGetUniformLocation(id, "uAspectScale")
            uOrientation = GLES30.glGetUniformLocation(id, "uOrientation")
            uMirror = GLES30.glGetUniformLocation(id, "uMirror")
        }
    }

    private lateinit var uniLscRgb: LscRgbUniforms

    // ---- CFA 重排 Pass Uniforms ----
    private class CfaUniforms(val id: Int) {
        var aPos = 0; var aTexCoord = 0
        var uInputTex = 0; var uCfaPattern = 0
        var uAspectScale = 0; var uOrientation = 0; var uMirror = 0

        fun lookup() {
            aPos = GLES30.glGetAttribLocation(id, "aPos")
            aTexCoord = GLES30.glGetAttribLocation(id, "aTexCoord")
            uInputTex = GLES30.glGetUniformLocation(id, "uInputTex")
            uCfaPattern = GLES30.glGetUniformLocation(id, "uCfaPattern")
            uAspectScale = GLES30.glGetUniformLocation(id, "uAspectScale")
            uOrientation = GLES30.glGetUniformLocation(id, "uOrientation")
            uMirror = GLES30.glGetUniformLocation(id, "uMirror")
        }
    }

    private lateinit var uniCfa: CfaUniforms

    // ---- WB 归一化 Pass Uniforms ----
    private class WbUniforms(val id: Int) {
        var aPos = 0; var aTexCoord = 0
        var uInputTex = 0
        var uBlackLevel = 0; var uWhiteLevel = 0; var uWBGain = 0
        var uAspectScale = 0; var uOrientation = 0; var uMirror = 0

        fun lookup() {
            aPos = GLES30.glGetAttribLocation(id, "aPos")
            aTexCoord = GLES30.glGetAttribLocation(id, "aTexCoord")
            uInputTex = GLES30.glGetUniformLocation(id, "uInputTex")
            uBlackLevel = GLES30.glGetUniformLocation(id, "uBlackLevel")
            uWhiteLevel = GLES30.glGetUniformLocation(id, "uWhiteLevel")
            uWBGain = GLES30.glGetUniformLocation(id, "uWBGain")
            uAspectScale = GLES30.glGetUniformLocation(id, "uAspectScale")
            uOrientation = GLES30.glGetUniformLocation(id, "uOrientation")
            uMirror = GLES30.glGetUniformLocation(id, "uMirror")
        }
    }

    private lateinit var uniWb: WbUniforms

    // ---- MHC 去马赛克 Pass Uniforms ----
    private class MhcDemosaicUniforms(val id: Int) {
        var aPos = 0; var aTexCoord = 0
        var uInputTex = 0
        var uAspectScale = 0; var uOrientation = 0; var uMirror = 0

        fun lookup() {
            aPos = GLES30.glGetAttribLocation(id, "aPos")
            aTexCoord = GLES30.glGetAttribLocation(id, "aTexCoord")
            uInputTex = GLES30.glGetUniformLocation(id, "uInputTex")
            uAspectScale = GLES30.glGetUniformLocation(id, "uAspectScale")
            uOrientation = GLES30.glGetUniformLocation(id, "uOrientation")
            uMirror = GLES30.glGetUniformLocation(id, "uMirror")
        }
    }

    private lateinit var uniMhc: MhcDemosaicUniforms

    // ---- CCM 色彩校正 Pass Uniforms ----
    private class CcmUniforms(val id: Int) {
        var aPos = 0; var aTexCoord = 0
        var uInputTex = 0; var uCCM = 0
        var uAspectScale = 0; var uOrientation = 0; var uMirror = 0

        fun lookup() {
            aPos = GLES30.glGetAttribLocation(id, "aPos")
            aTexCoord = GLES30.glGetAttribLocation(id, "aTexCoord")
            uInputTex = GLES30.glGetUniformLocation(id, "uInputTex")
            uCCM = GLES30.glGetUniformLocation(id, "uCCM")
            uAspectScale = GLES30.glGetUniformLocation(id, "uAspectScale")
            uOrientation = GLES30.glGetUniformLocation(id, "uOrientation")
            uMirror = GLES30.glGetUniformLocation(id, "uMirror")
        }
    }

    private lateinit var uniCcm: CcmUniforms

    // ---- 色调曲线 Pass Uniforms ----
    private class ToneCurveUniforms(val id: Int) {
        var aPos = 0; var aTexCoord = 0
        var uInputTex = 0; var uToneCurveLUT = 0
        var uAspectScale = 0; var uOrientation = 0; var uMirror = 0

        fun lookup() {
            aPos = GLES30.glGetAttribLocation(id, "aPos")
            aTexCoord = GLES30.glGetAttribLocation(id, "aTexCoord")
            uInputTex = GLES30.glGetUniformLocation(id, "uInputTex")
            uToneCurveLUT = GLES30.glGetUniformLocation(id, "uToneCurveLUT")
            uAspectScale = GLES30.glGetUniformLocation(id, "uAspectScale")
            uOrientation = GLES30.glGetUniformLocation(id, "uOrientation")
            uMirror = GLES30.glGetUniformLocation(id, "uMirror")
        }
    }

    private lateinit var uniCurve: ToneCurveUniforms

    // ---- sRGB Gamma Pass Uniforms ----
    private class GammaUniforms(val id: Int) {
        var aPos = 0; var aTexCoord = 0
        var uInputTex = 0
        var uAspectScale = 0; var uOrientation = 0; var uMirror = 0

        fun lookup() {
            aPos = GLES30.glGetAttribLocation(id, "aPos")
            aTexCoord = GLES30.glGetAttribLocation(id, "aTexCoord")
            uInputTex = GLES30.glGetUniformLocation(id, "uInputTex")
            uAspectScale = GLES30.glGetUniformLocation(id, "uAspectScale")
            uOrientation = GLES30.glGetUniformLocation(id, "uOrientation")
            uMirror = GLES30.glGetUniformLocation(id, "uMirror")
        }
    }

    private lateinit var uniGamma: GammaUniforms

    // ---- 后处理 Pass Uniforms ----
    private class PostProcessUniforms(val id: Int) {
        var aPos = 0; var aTexCoord = 0
        var uInputTex = 0
        var uLutTex = 0; var uLutSizeLoc = 0; var uEnableLut = 0; var uLutIntensity = 0
        var uAspectScale = 0; var uOrientation = 0; var uMirror = 0

        fun lookup() {
            aPos = GLES30.glGetAttribLocation(id, "aPos")
            aTexCoord = GLES30.glGetAttribLocation(id, "aTexCoord")
            uInputTex = GLES30.glGetUniformLocation(id, "uInputTex")
            uLutTex = GLES30.glGetUniformLocation(id, "uLutTexture")
            uLutSizeLoc = GLES30.glGetUniformLocation(id, "uLutSize")
            uEnableLut = GLES30.glGetUniformLocation(id, "uEnableLut")
            uLutIntensity = GLES30.glGetUniformLocation(id, "uLutIntensity")
            uAspectScale = GLES30.glGetUniformLocation(id, "uAspectScale")
            uOrientation = GLES30.glGetUniformLocation(id, "uOrientation")
            uMirror = GLES30.glGetUniformLocation(id, "uMirror")
        }
    }

    private lateinit var uniPost: PostProcessUniforms

    // ---- 色调映射 Pass Uniforms ----
    private class ToneMapUniforms(val id: Int) {
        var aPos = 0; var aTexCoord = 0
        var uInputTex = 0; var uToneMapD = 0; var uToneMapE = 0
        var uAspectScale = 0; var uOrientation = 0; var uMirror = 0

        fun lookup() {
            aPos = GLES30.glGetAttribLocation(id, "aPos")
            aTexCoord = GLES30.glGetAttribLocation(id, "aTexCoord")
            uInputTex = GLES30.glGetUniformLocation(id, "uInputTex")
            uToneMapD = GLES30.glGetUniformLocation(id, "uToneMapD")
            uToneMapE = GLES30.glGetUniformLocation(id, "uToneMapE")
            uAspectScale = GLES30.glGetUniformLocation(id, "uAspectScale")
            uOrientation = GLES30.glGetUniformLocation(id, "uOrientation")
            uMirror = GLES30.glGetUniformLocation(id, "uMirror")
        }
    }

    private lateinit var uniToneMap: ToneMapUniforms

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
        blFbo = 0; blTexId = 0; blTexW = 0; blTexH = 0
        lscBufFbo = 0; lscBufTexId = 0; lscBufW = 0; lscBufH = 0
        wbBufFbo = 0; wbBufTexId = 0; wbBufW = 0; wbBufH = 0
        cfaBufFbo = 0; cfaBufTexId = 0; cfaBufW = 0; cfaBufH = 0
        mhcBufFbo = 0; mhcBufTexId = 0; mhcBufW = 0; mhcBufH = 0
        ccmBufFbo = 0; ccmBufTexId = 0; ccmBufW = 0; ccmBufH = 0
        toneMapBufFbo = 0; toneMapBufTexId = 0; toneMapBufW = 0; toneMapBufH = 0
        curveBufFbo = 0; curveBufTexId = 0; curveBufW = 0; curveBufH = 0
        gammaBufFbo = 0; gammaBufTexId = 0; gammaBufW = 0; gammaBufH = 0
        dsBufFbo = 0; dsBufTexId = 0; dsBufW = 0; dsBufH = 0
        usBufFbo = 0; usBufTexId = 0; usBufW = 0; usBufH = 0
        bayerDsBufFbo = 0; bayerDsBufTexId = 0; bayerDsBufW = 0; bayerDsBufH = 0

        progBlit = createProgram(BLIT_VS, BLIT_FS)

        progBlSub = createProgram(VS, BLACK_LEVEL_FS)
        uniBlSub = BlSubUniforms(progBlSub).also { it.lookup() }

        progLsc = createProgram(VS, LSC_FS)
        uniLsc = LscUniforms(progLsc).also { it.lookup() }

        progLscRgb = createProgram(VS, LSC_RGB_FS)
        uniLscRgb = LscRgbUniforms(progLscRgb).also { it.lookup() }

        progBayerDs = createProgram(VS, BAYER_DS_FS)
        uniBayerDs = BayerDsUniforms(progBayerDs).also { it.lookup() }

        progCfaReorder = createProgram(VS, CFA_REORDER_FS)
        uniCfa = CfaUniforms(progCfaReorder).also { it.lookup() }

        progWb = createProgram(VS, WB_NORM_FS)
        uniWb = WbUniforms(progWb).also { it.lookup() }

        progMhcDemosaic = createProgram(VS, MHC_DEMOSAIC_FS)
        uniMhc = MhcDemosaicUniforms(progMhcDemosaic).also { it.lookup() }

        progCcm = createProgram(VS, CCM_FS)
        uniCcm = CcmUniforms(progCcm).also { it.lookup() }

        progPostProcess = createProgram(VS, POST_PROCESS_FS)
        uniPost = PostProcessUniforms(progPostProcess).also { it.lookup() }

        progToneMap = createProgram(VS, TONE_MAP_FS)
        uniToneMap = ToneMapUniforms(progToneMap).also { it.lookup() }

        progToneCurve = createProgram(VS, TONE_CURVE_FS)
        uniCurve = ToneCurveUniforms(progToneCurve).also { it.lookup() }

        progGamma = createProgram(VS, GAMMA_FS)
        uniGamma = GammaUniforms(progGamma).also { it.lookup() }

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

        // 高光重建引擎初始化（先 release 确保 GL 上下文重建后能重新初始化）
        hrEngine.release()
        hrEngine.init()

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

    /** 用 RCD 输出纹理绘制。 */
    private fun drawQuadRaw(aPos: Int, aTex: Int) {
        GLES31.glEnableVertexAttribArray(aPos)
        GLES31.glVertexAttribPointer(aPos, 2, GLES31.GL_FLOAT, false, 0, floatBuf(quadVerts))
        GLES31.glEnableVertexAttribArray(aTex)
        GLES31.glVertexAttribPointer(aTex, 2, GLES31.GL_FLOAT, false, 0, floatBuf(quadUVs))
        GLES31.glDrawArrays(GLES31.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun drawQuadBlit() {
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, floatBuf(quadVerts))
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 0, floatBuf(quadUVs))
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
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

        if (rawW <= 0 || rawH <= 0) return

        val buf = rawBuffer
        if (buf != null) {
            buf.position(0)
            if (!uploadRawBuffer(buf)) return
        } else {
            val data = rawShorts ?: return
            if (!uploadRaw(data)) return
        }

        // ---- Bayer 降采样预览 Pass ----
        val dsW = (rawW / bayerDsScale).coerceAtLeast(2)
        val dsH = (rawH / bayerDsScale).coerceAtLeast(2)
        val inv65535 = 1.0f / 65535.0f

        ensureBayerDsFbo(dsW, dsH)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bayerDsBufFbo)
        GLES30.glViewport(0, 0, dsW, dsH)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        GLES30.glUseProgram(progBayerDs)
        GLES30.glUniform2f(uniBayerDs.uAspectScale, 1f, 1f)
        GLES30.glUniform1i(uniBayerDs.uOrientation, 0)
        GLES30.glUniform1i(uniBayerDs.uMirror, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
        GLES30.glUniform1i(uniBayerDs.uRawTex, 0)
        GLES30.glUniform1i(uniBayerDs.uCfaPattern, cfaType)
        drawQuadRaw(uniBayerDs.aPos, uniBayerDs.aTexCoord)

        // 后续 Pass 全部用降采样尺寸
        val w = dsW; val h = dsH

        // ---- Pass 0: CFA 重排（降采样→降采样）----
        val pipelineInputTexId: Int
        if (cfaType != 0) {
            ensureCfaBufFbo(w, h)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, cfaBufFbo)
            GLES30.glViewport(0, 0, w, h)
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

            GLES30.glUseProgram(progCfaReorder)
            GLES30.glUniform2f(uniCfa.uAspectScale, 1f, 1f)
            GLES30.glUniform1i(uniCfa.uOrientation, 0)
            GLES30.glUniform1i(uniCfa.uMirror, 0)

            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bayerDsBufTexId)
            GLES30.glUniform1i(uniCfa.uInputTex, 0)
            GLES30.glUniform1i(uniCfa.uCfaPattern, cfaType)

            drawQuadRaw(uniCfa.aPos, uniCfa.aTexCoord)
            pipelineInputTexId = cfaBufTexId
        } else {
            pipelineInputTexId = bayerDsBufTexId
        }

        // ---- Pass 1: 黑电平扣除（全分辨率 1:1，无旋转/镜像）----
        ensureBlFbo(w, h)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, blFbo)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        GLES30.glUseProgram(progBlSub)
        GLES30.glUniform2f(uniBlSub.uAspectScale, 1f, 1f)
        GLES30.glUniform1i(uniBlSub.uOrientation, 0)
        GLES30.glUniform1i(uniBlSub.uMirror, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, pipelineInputTexId)
        GLES30.glUniform1i(uniBlSub.uRawTex, 0)

        GLES30.glUniform3f(uniBlSub.uBlackLevel,
            (blackLevelR + blackLevelOffset) * inv65535,
            (blackLevelG + blackLevelOffset) * inv65535,
            (blackLevelB + blackLevelOffset) * inv65535)

        drawQuadRaw(uniBlSub.aPos, uniBlSub.aTexCoord)

        // ---- Pass 2: LSC 增益（全分辨率 1:1，无旋转/镜像）----
        ensureLscBufFbo(w, h)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, lscBufFbo)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        GLES30.glUseProgram(progLsc)
        GLES30.glUniform2f(uniLsc.uAspectScale, 1f, 1f)
        GLES30.glUniform1i(uniLsc.uOrientation, 0)
        GLES30.glUniform1i(uniLsc.uMirror, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, blTexId)
        GLES30.glUniform1i(uniLsc.uInputTex, 0)

        val gains = lscGainMap
        val cols = lscGridCols
        val rows = lscGridRows
        if (gains != null && cols > 0 && rows > 0 && this.lscTexId != 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
            if (lastGainMapRef !== gains) {
                val pixelCount = cols * rows * 4
                val halfData = java.nio.ByteBuffer.allocateDirect(pixelCount * 2).order(java.nio.ByteOrder.nativeOrder())
                val halfBuf = halfData.asShortBuffer()
                for (i in 0 until pixelCount) {
                    halfBuf.put(floatToHalf(gains[i]).toShort())
                }
                halfBuf.rewind()
                halfData.rewind()
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, this.lscTexId)
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GL_RGBA16F, cols, rows, 0, GLES30.GL_RGBA, GL_HALF_FLOAT, halfData)
                lastGainMapRef = gains
            } else {
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, this.lscTexId)
            }
            GLES30.glUniform1i(uniLsc.uLscGainTex, 2)
            GLES30.glUniform2f(uniLsc.uLscGridSize, cols.toFloat(), rows.toFloat())
            GLES30.glUniform1i(uniLsc.uEnableLsc, 1)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        } else {
            GLES30.glUniform1i(uniLsc.uEnableLsc, 0)
        }

        drawQuadRaw(uniLsc.aPos, uniLsc.aTexCoord)

        // ---- Pass 3: WB 归一化（全分辨率 1:1，无旋转/镜像）----
        ensureWbBufFbo(w, h)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, wbBufFbo)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        GLES30.glUseProgram(progWb)
        GLES30.glUniform2f(uniWb.uAspectScale, 1f, 1f)
        GLES30.glUniform1i(uniWb.uOrientation, 0)
        GLES30.glUniform1i(uniWb.uMirror, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lscBufTexId)
        GLES30.glUniform1i(uniWb.uInputTex, 0)

        GLES30.glUniform3f(uniWb.uBlackLevel,
            (blackLevelR + blackLevelOffset) * inv65535,
            (blackLevelG + blackLevelOffset) * inv65535,
            (blackLevelB + blackLevelOffset) * inv65535)
        GLES30.glUniform1f(uniWb.uWhiteLevel, (whiteLevel + whiteLevelOffset) * inv65535)
        GLES30.glUniform3f(uniWb.uWBGain, wbR, wbG, wbB)

        drawQuadRaw(uniWb.aPos, uniWb.aTexCoord)

        // ---- Pass 4: MHC 5×5 去马赛克（全分辨率 → RGBA8）----
        ensureMhcBufFbo(w, h)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, mhcBufFbo)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        GLES30.glUseProgram(progMhcDemosaic)
        GLES30.glUniform2f(uniMhc.uAspectScale, 1f, 1f)
        GLES30.glUniform1i(uniMhc.uOrientation, 0)
        GLES30.glUniform1i(uniMhc.uMirror, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, wbBufTexId)
        GLES30.glUniform1i(uniMhc.uInputTex, 0)

        drawQuadRaw(uniMhc.aPos, uniMhc.aTexCoord)

        // ---- Pass 5: CCM 色彩校正（全分辨率 → RGBA8）----
        ensureCcmBufFbo(w, h)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, ccmBufFbo)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        GLES30.glUseProgram(progCcm)
        GLES30.glUniform2f(uniCcm.uAspectScale, 1f, 1f)
        GLES30.glUniform1i(uniCcm.uOrientation, 0)
        GLES30.glUniform1i(uniCcm.uMirror, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mhcBufTexId)
        GLES30.glUniform1i(uniCcm.uInputTex, 0)
        GLES30.glUniformMatrix3fv(uniCcm.uCCM, 1, false, ccm, 0)

        drawQuadRaw(uniCcm.aPos, uniCcm.aTexCoord)

        // ---- Pass 5.5: 跳过降采样/高光重建/上采样（预览省电）----
        val toneMapInputTexId = ccmBufTexId

        // ---- Pass 6: Reinhard 色调映射（全分辨率 → RGBA8）----
        ensureToneMapBufFbo(w, h)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, toneMapBufFbo)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        GLES30.glUseProgram(progToneMap)
        GLES30.glUniform2f(uniToneMap.uAspectScale, 1f, 1f)
        GLES30.glUniform1i(uniToneMap.uOrientation, 0)
        GLES30.glUniform1i(uniToneMap.uMirror, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, toneMapInputTexId)
        GLES30.glUniform1i(uniToneMap.uInputTex, 0)
        GLES30.glUniform1f(uniToneMap.uToneMapD, toneMapD)
        GLES30.glUniform1f(uniToneMap.uToneMapE, toneMapE)

        drawQuadRaw(uniToneMap.aPos, uniToneMap.aTexCoord)

        // ---- Pass 7: 色调曲线（全分辨率 → RGBA8）----
        ensureCurveBufFbo(w, h)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, curveBufFbo)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        GLES30.glUseProgram(progToneCurve)
        GLES30.glUniform2f(uniCurve.uAspectScale, 1f, 1f)
        GLES30.glUniform1i(uniCurve.uOrientation, 0)
        GLES30.glUniform1i(uniCurve.uMirror, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, toneMapBufTexId)
        GLES30.glUniform1i(uniCurve.uInputTex, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, toneCurveTexId)
        GLES30.glUniform1i(uniCurve.uToneCurveLUT, 3)

        drawQuadRaw(uniCurve.aPos, uniCurve.aTexCoord)

        // ---- Pass 8: sRGB Gamma（全分辨率 → RGBA8）----
        ensureGammaBufFbo(w, h)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, gammaBufFbo)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        GLES30.glUseProgram(progGamma)
        GLES30.glUniform2f(uniGamma.uAspectScale, 1f, 1f)
        GLES30.glUniform1i(uniGamma.uOrientation, 0)
        GLES30.glUniform1i(uniGamma.uMirror, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, curveBufTexId)
        GLES30.glUniform1i(uniGamma.uInputTex, 0)

        drawQuadRaw(uniGamma.aPos, uniGamma.aTexCoord)

        // ---- Pass 9: LUT 滤镜 → 屏幕 ----
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        if (lastViewportW > 0 && lastViewportH > 0) {
            GLES30.glViewport(0, 0, lastViewportW, lastViewportH)
        }

        GLES30.glUseProgram(progPostProcess)
        val (ppAx, ppAy) = aspectScale()
        GLES30.glUniform2f(uniPost.uAspectScale, ppAx, ppAy)
        GLES30.glUniform1i(uniPost.uOrientation, orientation)
        GLES30.glUniform1i(uniPost.uMirror, if (mirror) 1 else 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, gammaBufTexId)
        GLES30.glUniform1i(uniPost.uInputTex, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTextureId)
        GLES30.glUniform1i(uniPost.uLutTex, 1)
        GLES30.glUniform1f(uniPost.uLutSizeLoc, 33.0f)
        GLES30.glUniform1i(uniPost.uEnableLut, if (lutEnabled && lutFloatArray != null) 1 else 0)
        GLES30.glUniform1f(uniPost.uLutIntensity, lutIntensity)

        drawQuadRaw(uniPost.aPos, uniPost.aTexCoord)
    }

    // ====================== 黑电平扣除中间 FBO ======================

    private fun ensureBlFbo(w: Int, h: Int) {
        if (blTexId != 0 && blTexW == w && blTexH == h) return
        if (blTexId != 0) { GLES30.glDeleteTextures(1, intArrayOf(blTexId), 0); blTexId = 0 }
        if (blFbo != 0) { GLES30.glDeleteFramebuffers(1, intArrayOf(blFbo), 0); blFbo = 0 }
        blTexW = 0; blTexH = 0

        val texs = IntArray(1)
        GLES30.glGenTextures(1, texs, 0)
        blTexId = texs[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, blTexId)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GL_R16F, w, h, 0, GLES30.GL_RED, GLES30.GL_HALF_FLOAT, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        val fbos = IntArray(1)
        GLES30.glGenFramebuffers(1, fbos, 0)
        blFbo = fbos[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, blFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, blTexId, 0)
        if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            throw RuntimeException("BL FBO incomplete")
        }
        blTexW = w; blTexH = h
    }

    // ====================== Bayer 降采样中间 FBO ======================

    private fun ensureBayerDsFbo(w: Int, h: Int) {
        if (bayerDsBufTexId != 0 && bayerDsBufW == w && bayerDsBufH == h) return
        if (bayerDsBufTexId != 0) { GLES30.glDeleteTextures(1, intArrayOf(bayerDsBufTexId), 0); bayerDsBufTexId = 0 }
        if (bayerDsBufFbo != 0) { GLES30.glDeleteFramebuffers(1, intArrayOf(bayerDsBufFbo), 0); bayerDsBufFbo = 0 }
        bayerDsBufW = 0; bayerDsBufH = 0

        val texs = IntArray(1)
        GLES30.glGenTextures(1, texs, 0)
        bayerDsBufTexId = texs[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bayerDsBufTexId)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GL_R16F, w, h, 0, GLES30.GL_RED, GLES30.GL_HALF_FLOAT, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        val fbos = IntArray(1)
        GLES30.glGenFramebuffers(1, fbos, 0)
        bayerDsBufFbo = fbos[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bayerDsBufFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, bayerDsBufTexId, 0)
        if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            throw RuntimeException("Bayer DS FBO incomplete")
        }
        bayerDsBufW = w; bayerDsBufH = h
    }

    // ====================== LSC 增益中间 FBO ======================

    private fun ensureLscBufFbo(w: Int, h: Int) {
        if (lscBufTexId != 0 && lscBufW == w && lscBufH == h) return
        if (lscBufTexId != 0) { GLES30.glDeleteTextures(1, intArrayOf(lscBufTexId), 0); lscBufTexId = 0 }
        if (lscBufFbo != 0) { GLES30.glDeleteFramebuffers(1, intArrayOf(lscBufFbo), 0); lscBufFbo = 0 }
        lscBufW = 0; lscBufH = 0

        val texs = IntArray(1)
        GLES30.glGenTextures(1, texs, 0)
        lscBufTexId = texs[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lscBufTexId)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GL_R16F, w, h, 0, GLES30.GL_RED, GLES30.GL_HALF_FLOAT, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        val fbos = IntArray(1)
        GLES30.glGenFramebuffers(1, fbos, 0)
        lscBufFbo = fbos[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, lscBufFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, lscBufTexId, 0)
        if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            throw RuntimeException("LSC FBO incomplete")
        }
        lscBufW = w; lscBufH = h
    }

    // ====================== CFA 重排中间 FBO ======================

    private fun ensureCfaBufFbo(w: Int, h: Int) {
        if (cfaBufTexId != 0 && cfaBufW == w && cfaBufH == h) return
        if (cfaBufTexId != 0) { GLES30.glDeleteTextures(1, intArrayOf(cfaBufTexId), 0); cfaBufTexId = 0 }
        if (cfaBufFbo != 0) { GLES30.glDeleteFramebuffers(1, intArrayOf(cfaBufFbo), 0); cfaBufFbo = 0 }
        cfaBufW = 0; cfaBufH = 0

        val texs = IntArray(1)
        GLES30.glGenTextures(1, texs, 0)
        cfaBufTexId = texs[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, cfaBufTexId)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GL_R16F, w, h, 0, GLES30.GL_RED, GLES30.GL_HALF_FLOAT, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        val fbos = IntArray(1)
        GLES30.glGenFramebuffers(1, fbos, 0)
        cfaBufFbo = fbos[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, cfaBufFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, cfaBufTexId, 0)
        if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            throw RuntimeException("CFA FBO incomplete")
        }
        cfaBufW = w; cfaBufH = h
    }

    // ====================== WB 归一化中间 FBO ======================

    private fun ensureWbBufFbo(w: Int, h: Int) {
        if (wbBufTexId != 0 && wbBufW == w && wbBufH == h) return
        if (wbBufTexId != 0) { GLES30.glDeleteTextures(1, intArrayOf(wbBufTexId), 0); wbBufTexId = 0 }
        if (wbBufFbo != 0) { GLES30.glDeleteFramebuffers(1, intArrayOf(wbBufFbo), 0); wbBufFbo = 0 }
        wbBufW = 0; wbBufH = 0

        val texs = IntArray(1)
        GLES30.glGenTextures(1, texs, 0)
        wbBufTexId = texs[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, wbBufTexId)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GL_R16F, w, h, 0, GLES30.GL_RED, GLES30.GL_HALF_FLOAT, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        val fbos = IntArray(1)
        GLES30.glGenFramebuffers(1, fbos, 0)
        wbBufFbo = fbos[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, wbBufFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, wbBufTexId, 0)
        if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            throw RuntimeException("WB FBO incomplete")
        }
        wbBufW = w; wbBufH = h
    }

    // ====================== MHC 去马赛克中间 FBO ======================

    private fun ensureMhcBufFbo(w: Int, h: Int) {
        if (mhcBufTexId != 0 && mhcBufW == w && mhcBufH == h) return
        if (mhcBufTexId != 0) { GLES30.glDeleteTextures(1, intArrayOf(mhcBufTexId), 0); mhcBufTexId = 0 }
        if (mhcBufFbo != 0) { GLES30.glDeleteFramebuffers(1, intArrayOf(mhcBufFbo), 0); mhcBufFbo = 0 }
        mhcBufW = 0; mhcBufH = 0

        val texs = IntArray(1)
        GLES30.glGenTextures(1, texs, 0)
        mhcBufTexId = texs[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mhcBufTexId)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GL_RGBA16F, w, h, 0, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        val fbos = IntArray(1)
        GLES30.glGenFramebuffers(1, fbos, 0)
        mhcBufFbo = fbos[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, mhcBufFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, mhcBufTexId, 0)
        if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            throw RuntimeException("MHC FBO incomplete")
        }
        mhcBufW = w; mhcBufH = h
    }

    // ====================== CCM 中间 FBO ======================

    private fun ensureCcmBufFbo(w: Int, h: Int) {
        if (ccmBufTexId != 0 && ccmBufW == w && ccmBufH == h) return
        if (ccmBufTexId != 0) { GLES30.glDeleteTextures(1, intArrayOf(ccmBufTexId), 0); ccmBufTexId = 0 }
        if (ccmBufFbo != 0) { GLES30.glDeleteFramebuffers(1, intArrayOf(ccmBufFbo), 0); ccmBufFbo = 0 }
        ccmBufW = 0; ccmBufH = 0

        val texs = IntArray(1)
        GLES30.glGenTextures(1, texs, 0)
        ccmBufTexId = texs[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ccmBufTexId)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GL_RGBA16F, w, h, 0, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        val fbos = IntArray(1)
        GLES30.glGenFramebuffers(1, fbos, 0)
        ccmBufFbo = fbos[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, ccmBufFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, ccmBufTexId, 0)
        if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            throw RuntimeException("CCM FBO incomplete")
        }
        ccmBufW = w; ccmBufH = h
    }

    // ====================== 色调映射中间 FBO ======================

    private fun ensureToneMapBufFbo(w: Int, h: Int) {
        if (toneMapBufTexId != 0 && toneMapBufW == w && toneMapBufH == h) return
        if (toneMapBufTexId != 0) { GLES30.glDeleteTextures(1, intArrayOf(toneMapBufTexId), 0); toneMapBufTexId = 0 }
        if (toneMapBufFbo != 0) { GLES30.glDeleteFramebuffers(1, intArrayOf(toneMapBufFbo), 0); toneMapBufFbo = 0 }
        toneMapBufW = 0; toneMapBufH = 0

        val texs = IntArray(1)
        GLES30.glGenTextures(1, texs, 0)
        toneMapBufTexId = texs[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, toneMapBufTexId)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GL_RGBA16F, w, h, 0, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        val fbos = IntArray(1)
        GLES30.glGenFramebuffers(1, fbos, 0)
        toneMapBufFbo = fbos[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, toneMapBufFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, toneMapBufTexId, 0)
        if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            throw RuntimeException("ToneMap FBO incomplete")
        }
        toneMapBufW = w; toneMapBufH = h
    }

    // ====================== 色调曲线中间 FBO ======================

    private fun ensureCurveBufFbo(w: Int, h: Int) {
        if (curveBufTexId != 0 && curveBufW == w && curveBufH == h) return
        if (curveBufTexId != 0) { GLES30.glDeleteTextures(1, intArrayOf(curveBufTexId), 0); curveBufTexId = 0 }
        if (curveBufFbo != 0) { GLES30.glDeleteFramebuffers(1, intArrayOf(curveBufFbo), 0); curveBufFbo = 0 }
        curveBufW = 0; curveBufH = 0

        val texs = IntArray(1)
        GLES30.glGenTextures(1, texs, 0)
        curveBufTexId = texs[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, curveBufTexId)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GL_RGBA16F, w, h, 0, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        val fbos = IntArray(1)
        GLES30.glGenFramebuffers(1, fbos, 0)
        curveBufFbo = fbos[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, curveBufFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, curveBufTexId, 0)
        if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            throw RuntimeException("Curve FBO incomplete")
        }
        curveBufW = w; curveBufH = h
    }

    // ====================== 降采样 / 上采样 FBO ======================

    private fun ensureDsBufFbo(w: Int, h: Int) {
        if (dsBufTexId != 0 && dsBufW == w && dsBufH == h) return
        if (dsBufTexId != 0) { GLES30.glDeleteTextures(1, intArrayOf(dsBufTexId), 0); dsBufTexId = 0 }
        if (dsBufFbo != 0) { GLES30.glDeleteFramebuffers(1, intArrayOf(dsBufFbo), 0); dsBufFbo = 0 }
        dsBufW = w; dsBufH = h
        val texs = IntArray(1)
        GLES30.glGenTextures(1, texs, 0)
        dsBufTexId = texs[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, dsBufTexId)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GL_RGBA16F, w, h, 0, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        val fbos = IntArray(1)
        GLES30.glGenFramebuffers(1, fbos, 0)
        dsBufFbo = fbos[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, dsBufFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, dsBufTexId, 0)
        if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            throw RuntimeException("DS FBO incomplete")
        }
    }

    private fun ensureUsBufFbo(w: Int, h: Int) {
        if (usBufTexId != 0 && usBufW == w && usBufH == h) return
        if (usBufTexId != 0) { GLES30.glDeleteTextures(1, intArrayOf(usBufTexId), 0); usBufTexId = 0 }
        if (usBufFbo != 0) { GLES30.glDeleteFramebuffers(1, intArrayOf(usBufFbo), 0); usBufFbo = 0 }
        usBufW = w; usBufH = h
        val texs = IntArray(1)
        GLES30.glGenTextures(1, texs, 0)
        usBufTexId = texs[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, usBufTexId)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GL_RGBA16F, w, h, 0, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        val fbos = IntArray(1)
        GLES30.glGenFramebuffers(1, fbos, 0)
        usBufFbo = fbos[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, usBufFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, usBufTexId, 0)
        if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            throw RuntimeException("US FBO incomplete")
        }
    }

    // ====================== sRGB Gamma 中间 FBO ======================

    private fun ensureGammaBufFbo(w: Int, h: Int) {
        if (gammaBufTexId != 0 && gammaBufW == w && gammaBufH == h) return
        if (gammaBufTexId != 0) { GLES30.glDeleteTextures(1, intArrayOf(gammaBufTexId), 0); gammaBufTexId = 0 }
        if (gammaBufFbo != 0) { GLES30.glDeleteFramebuffers(1, intArrayOf(gammaBufFbo), 0); gammaBufFbo = 0 }
        gammaBufW = 0; gammaBufH = 0

        val texs = IntArray(1)
        GLES30.glGenTextures(1, texs, 0)
        gammaBufTexId = texs[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, gammaBufTexId)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GL_RGBA16F, w, h, 0, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        val fbos = IntArray(1)
        GLES30.glGenFramebuffers(1, fbos, 0)
        gammaBufFbo = fbos[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, gammaBufFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, gammaBufTexId, 0)
        if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            throw RuntimeException("Gamma FBO incomplete")
        }
        gammaBufW = w; gammaBufH = h
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

        if (!(rcdEnabled && rcdOutTexId != 0)) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            if (lastViewportW > 0 && lastViewportH > 0) {
                GLES30.glViewport(0, 0, lastViewportW, lastViewportH)
            }
            return null
        }

        ensureRcdBuffers(w, h)
        GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, rcdRawTexId)
        GLES31.glTexSubImage2D(GLES31.GL_TEXTURE_2D, 0, 0, 0, w, h, GLES31.GL_RED_INTEGER, GLES31.GL_UNSIGNED_SHORT, shortBuf)
        dispatchRcd(w, h, cfaType, floatArrayOf(blackLevelR, blackLevelG, blackLevelG, blackLevelB),
            whiteLevel, floatArrayOf(wbR, wbG, wbG, wbB))

        // ---- 模块化后处理链（复用预览的各 Pass）----

        // 确保所有中间 FBO 尺寸正确
        ensureCcmBufFbo(w, h)
        ensureToneMapBufFbo(w, h)
        ensureCurveBufFbo(w, h)
        ensureGammaBufFbo(w, h)

        // LSC_RGB: rcdOutTexId (RGBA16F) → ccmBufTexId
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, ccmBufFbo)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(progLscRgb)
        GLES30.glUniform2f(uniLscRgb.uAspectScale, 1f, 1f)
        GLES30.glUniform1i(uniLscRgb.uOrientation, 0)
        GLES30.glUniform1i(uniLscRgb.uMirror, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rcdOutTexId)
        GLES30.glUniform1i(uniLscRgb.uInputTex, 0)
        val gains = lscGainMap
        if (gains != null && lscGridCols > 0 && lscGridRows > 0 && this.lscTexId != 0) {
            if (lastGainMapRef !== gains) {
                val pixelCount = lscGridCols * lscGridRows * 4
                val halfData = java.nio.ByteBuffer.allocateDirect(pixelCount * 2).order(java.nio.ByteOrder.nativeOrder())
                val halfBuf = halfData.asShortBuffer()
                for (i in 0 until pixelCount) halfBuf.put(floatToHalf(gains[i]).toShort())
                halfBuf.rewind(); halfData.rewind()
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, this.lscTexId)
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GL_RGBA16F, lscGridCols, lscGridRows, 0, GLES30.GL_RGBA, GL_HALF_FLOAT, halfData)
                lastGainMapRef = gains
            }
            GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, this.lscTexId)
            GLES30.glUniform1i(uniLscRgb.uLscGainTex, 2)
            GLES30.glUniform2f(uniLscRgb.uLscGridSize, lscGridCols.toFloat(), lscGridRows.toFloat())
            GLES30.glUniform1i(uniLscRgb.uEnableLsc, 1)
        } else {
            GLES30.glUniform1i(uniLscRgb.uEnableLsc, 0)
        }
        drawQuadRaw(uniLscRgb.aPos, uniLscRgb.aTexCoord)

        // CCM: ccmBufTexId → toneMapBufTexId
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, toneMapBufFbo)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(progCcm)
        GLES30.glUniform2f(uniCcm.uAspectScale, 1f, 1f)
        GLES30.glUniform1i(uniCcm.uOrientation, 0)
        GLES30.glUniform1i(uniCcm.uMirror, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ccmBufTexId)
        GLES30.glUniform1i(uniCcm.uInputTex, 0)
        GLES30.glUniformMatrix3fv(uniCcm.uCCM, 1, false, ccm, 0)
        drawQuadRaw(uniCcm.aPos, uniCcm.aTexCoord)

        // 高光重建: toneMapBufTexId → hr result（可选）
        val captureToneMapInputTexId = if (highlightReconstructionEnabled) {
            hrEngine.render(toneMapBufTexId, w, h)
        } else {
            toneMapBufTexId
        }

        // 色调映射: captureToneMapInputTexId → curveBufTexId
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, curveBufFbo)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(progToneMap)
        GLES30.glUniform2f(uniToneMap.uAspectScale, 1f, 1f)
        GLES30.glUniform1i(uniToneMap.uOrientation, 0)
        GLES30.glUniform1i(uniToneMap.uMirror, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, captureToneMapInputTexId)
        GLES30.glUniform1i(uniToneMap.uInputTex, 0)
        GLES30.glUniform1f(uniToneMap.uToneMapD, toneMapD)
        GLES30.glUniform1f(uniToneMap.uToneMapE, toneMapE)
        drawQuadRaw(uniToneMap.aPos, uniToneMap.aTexCoord)

        // 色调曲线: curveBufTexId → gammaBufTexId
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, gammaBufFbo)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(progToneCurve)
        GLES30.glUniform2f(uniCurve.uAspectScale, 1f, 1f)
        GLES30.glUniform1i(uniCurve.uOrientation, 0)
        GLES30.glUniform1i(uniCurve.uMirror, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, curveBufTexId)
        GLES30.glUniform1i(uniCurve.uInputTex, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, toneCurveTexId)
        GLES30.glUniform1i(uniCurve.uToneCurveLUT, 3)
        drawQuadRaw(uniCurve.aPos, uniCurve.aTexCoord)

        // Gamma: gammaBufTexId → ccmBufTexId (复用)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, ccmBufFbo)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(progGamma)
        GLES30.glUniform2f(uniGamma.uAspectScale, 1f, 1f)
        GLES30.glUniform1i(uniGamma.uOrientation, 0)
        GLES30.glUniform1i(uniGamma.uMirror, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, gammaBufTexId)
        GLES30.glUniform1i(uniGamma.uInputTex, 0)
        drawQuadRaw(uniGamma.aPos, uniGamma.aTexCoord)

        // LUT: ccmBufTexId → captureFbo (带旋转)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, captureFbo)
        GLES30.glViewport(0, 0, captureTexW, captureTexH)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(progPostProcess)
        GLES30.glUniform2f(uniPost.uAspectScale, 1f, 1f)
        GLES30.glUniform1i(uniPost.uOrientation, orientation)
        GLES30.glUniform1i(uniPost.uMirror, if (mirror) 1 else 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ccmBufTexId)
        GLES30.glUniform1i(uniPost.uInputTex, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTextureId)
        GLES30.glUniform1i(uniPost.uLutTex, 1)
        GLES30.glUniform1f(uniPost.uLutSizeLoc, 33.0f)
        GLES30.glUniform1i(uniPost.uEnableLut, if (lutEnabled && lutFloatArray != null) 1 else 0)
        GLES30.glUniform1f(uniPost.uLutIntensity, lutIntensity)
        drawQuadRaw(uniPost.aPos, uniPost.aTexCoord)

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
        if (progBlit != 0) { GLES30.glDeleteProgram(progBlit); progBlit = 0 }
        if (dsBufTexId != 0) { GLES30.glDeleteTextures(1, intArrayOf(dsBufTexId), 0); dsBufTexId = 0 }
        if (dsBufFbo != 0) { GLES30.glDeleteFramebuffers(1, intArrayOf(dsBufFbo), 0); dsBufFbo = 0 }
        if (usBufTexId != 0) { GLES30.glDeleteTextures(1, intArrayOf(usBufTexId), 0); usBufTexId = 0 }
        if (usBufFbo != 0) { GLES30.glDeleteFramebuffers(1, intArrayOf(usBufFbo), 0); usBufFbo = 0 }
        if (rcdRawTexId != 0) { GLES30.glDeleteTextures(1, intArrayOf(rcdRawTexId), 0); rcdRawTexId = 0 }
        if (rcdOutTexId != 0) { GLES30.glDeleteTextures(1, intArrayOf(rcdOutTexId), 0); rcdOutTexId = 0 }
        if (rcdSsbo[0] != 0) { GLES31.glDeleteBuffers(9, rcdSsbo, 0); rcdSsbo.fill(0) }
        val progs = intArrayOf(rcdPopulateProg, rcdStep1Prog, rcdStep2Prog, rcdStep3Prog, rcdStep40Prog, rcdStep41Prog, rcdStep42Prog, rcdStep43Prog, rcdWriteProg)
        for (p in progs) if (p != 0) GLES30.glDeleteProgram(p)
        rcdPopulateProg = 0; rcdStep1Prog = 0; rcdStep2Prog = 0; rcdStep3Prog = 0
        rcdStep40Prog = 0; rcdStep41Prog = 0; rcdStep42Prog = 0; rcdStep43Prog = 0; rcdWriteProg = 0
        rcdInitialized = false; rcdEnabled = false
        if (blTexId != 0) { GLES30.glDeleteTextures(1, intArrayOf(blTexId), 0); blTexId = 0 }
        if (blFbo != 0) { GLES30.glDeleteFramebuffers(1, intArrayOf(blFbo), 0); blFbo = 0 }
        if (progBlSub != 0) { GLES30.glDeleteProgram(progBlSub); progBlSub = 0 }
        if (bayerDsBufTexId != 0) { GLES30.glDeleteTextures(1, intArrayOf(bayerDsBufTexId), 0); bayerDsBufTexId = 0 }
        if (bayerDsBufFbo != 0) { GLES30.glDeleteFramebuffers(1, intArrayOf(bayerDsBufFbo), 0); bayerDsBufFbo = 0 }
        if (progBayerDs != 0) { GLES30.glDeleteProgram(progBayerDs); progBayerDs = 0 }
        if (lscBufTexId != 0) { GLES30.glDeleteTextures(1, intArrayOf(lscBufTexId), 0); lscBufTexId = 0 }
        if (lscBufFbo != 0) { GLES30.glDeleteFramebuffers(1, intArrayOf(lscBufFbo), 0); lscBufFbo = 0 }
        if (progLsc != 0) { GLES30.glDeleteProgram(progLsc); progLsc = 0 }
        if (progLscRgb != 0) { GLES30.glDeleteProgram(progLscRgb); progLscRgb = 0 }
        if (wbBufTexId != 0) { GLES30.glDeleteTextures(1, intArrayOf(wbBufTexId), 0); wbBufTexId = 0 }
        if (wbBufFbo != 0) { GLES30.glDeleteFramebuffers(1, intArrayOf(wbBufFbo), 0); wbBufFbo = 0 }
        if (progWb != 0) { GLES30.glDeleteProgram(progWb); progWb = 0 }
        if (cfaBufTexId != 0) { GLES30.glDeleteTextures(1, intArrayOf(cfaBufTexId), 0); cfaBufTexId = 0 }
        if (cfaBufFbo != 0) { GLES30.glDeleteFramebuffers(1, intArrayOf(cfaBufFbo), 0); cfaBufFbo = 0 }
        if (progCfaReorder != 0) { GLES30.glDeleteProgram(progCfaReorder); progCfaReorder = 0 }
        if (mhcBufTexId != 0) { GLES30.glDeleteTextures(1, intArrayOf(mhcBufTexId), 0); mhcBufTexId = 0 }
        if (mhcBufFbo != 0) { GLES30.glDeleteFramebuffers(1, intArrayOf(mhcBufFbo), 0); mhcBufFbo = 0 }
        if (progMhcDemosaic != 0) { GLES30.glDeleteProgram(progMhcDemosaic); progMhcDemosaic = 0 }
        if (progPostProcess != 0) { GLES30.glDeleteProgram(progPostProcess); progPostProcess = 0 }
        if (ccmBufTexId != 0) { GLES30.glDeleteTextures(1, intArrayOf(ccmBufTexId), 0); ccmBufTexId = 0 }
        if (ccmBufFbo != 0) { GLES30.glDeleteFramebuffers(1, intArrayOf(ccmBufFbo), 0); ccmBufFbo = 0 }
        if (progCcm != 0) { GLES30.glDeleteProgram(progCcm); progCcm = 0 }
        if (toneMapBufTexId != 0) { GLES30.glDeleteTextures(1, intArrayOf(toneMapBufTexId), 0); toneMapBufTexId = 0 }
        if (toneMapBufFbo != 0) { GLES30.glDeleteFramebuffers(1, intArrayOf(toneMapBufFbo), 0); toneMapBufFbo = 0 }
        if (progToneMap != 0) { GLES30.glDeleteProgram(progToneMap); progToneMap = 0 }
        if (curveBufTexId != 0) { GLES30.glDeleteTextures(1, intArrayOf(curveBufTexId), 0); curveBufTexId = 0 }
        if (curveBufFbo != 0) { GLES30.glDeleteFramebuffers(1, intArrayOf(curveBufFbo), 0); curveBufFbo = 0 }
        if (progToneCurve != 0) { GLES30.glDeleteProgram(progToneCurve); progToneCurve = 0 }
        if (gammaBufTexId != 0) { GLES30.glDeleteTextures(1, intArrayOf(gammaBufTexId), 0); gammaBufTexId = 0 }
        if (gammaBufFbo != 0) { GLES30.glDeleteFramebuffers(1, intArrayOf(gammaBufFbo), 0); gammaBufFbo = 0 }
        if (progGamma != 0) { GLES30.glDeleteProgram(progGamma); progGamma = 0 }
        hrEngine.release()
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

    // ===== 黑电平扣除 Fragment Shader =====
    private val BLACK_LEVEL_FS = """
#version 300 es
precision highp float;
uniform highp sampler2D uRawTex;
uniform vec3 uBlackLevel;
in vec2 vUV;
out vec4 frag;

int ch(int x, int y) {
    return ((y & 1) == 0) ? (((x & 1) == 0) ? 0 : 1) : (((x & 1) == 0) ? 1 : 2);
}

void main() {
    ivec2 center = ivec2(floor(vUV * vec2(textureSize(uRawTex, 0))));
    int channel = ch(center.x, center.y);
    float bl = (channel == 0) ? uBlackLevel.r
              : ((channel == 1) ? uBlackLevel.g : uBlackLevel.b);
    float raw = texture(uRawTex, vUV).r;
    frag = vec4(max(raw - bl, 0.0), 0.0, 0.0, 1.0);
}
""".trimIndent()

    // ===== LSC 增益 Fragment Shader =====
    private val LSC_FS = """
#version 300 es
precision highp float;
uniform highp sampler2D uInputTex;
uniform highp sampler2D uLscGainTex;
uniform vec2 uLscGridSize;
uniform bool uEnableLsc;
in vec2 vUV;
out vec4 frag;

int ch(int x, int y) {
    return ((y & 1) == 0) ? (((x & 1) == 0) ? 0 : 1) : (((x & 1) == 0) ? 1 : 2);
}

void main() {
    float val = texture(uInputTex, vUV).r;
    if (uEnableLsc) {
        ivec2 center = ivec2(floor(vUV * vec2(textureSize(uInputTex, 0))));
        int channel = ch(center.x, center.y);
        vec2 tc = vec2(
            (vUV.x * (uLscGridSize.x - 1.0) + 0.5) / uLscGridSize.x,
            (vUV.y * (uLscGridSize.y - 1.0) + 0.5) / uLscGridSize.y
        );
        vec4 gain = texture(uLscGainTex, tc);
        val *= (channel == 0) ? gain.r : ((channel == 1) ? gain.g : gain.b);
    }
    frag = vec4(val, 0.0, 0.0, 1.0);
}
""".trimIndent()

    // ===== CFA 重排 Fragment Shader（任意排列 → RGGB）=====
    private val CFA_REORDER_FS = """
#version 300 es
precision highp float;
uniform highp sampler2D uInputTex;
uniform int uCfaPattern;
in vec2 vUV;
out vec4 frag;

void main() {
    ivec2 sz = textureSize(uInputTex, 0);
    ivec2 pos = ivec2(floor(vUV * vec2(sz)));
    int bx = pos.x / 2 * 2;
    int by = pos.y / 2 * 2;
    int rx = pos.x - bx;
    int ry = pos.y - by;

    float s00 = texelFetch(uInputTex, ivec2(bx, by), 0).r;
    float s10 = texelFetch(uInputTex, ivec2(bx + 1, by), 0).r;
    float s01 = texelFetch(uInputTex, ivec2(bx, by + 1), 0).r;
    float s11 = texelFetch(uInputTex, ivec2(bx + 1, by + 1), 0).r;

    float result;
    if (uCfaPattern == 0) {
        result = (rx == 0) ? ((ry == 0) ? s00 : s01) : ((ry == 0) ? s10 : s11);
    } else if (uCfaPattern == 1) {
        result = (rx == 0) ? ((ry == 0) ? s10 : s11) : ((ry == 0) ? s00 : s01);
    } else if (uCfaPattern == 2) {
        result = (rx == 0) ? ((ry == 0) ? s01 : s00) : ((ry == 0) ? s11 : s10);
    } else {
        result = (rx == 0) ? ((ry == 0) ? s11 : s10) : ((ry == 0) ? s01 : s00);
    }
    frag = vec4(result, 0.0, 0.0, 1.0);
}
""".trimIndent()

    // ===== WB 归一化 Fragment Shader =====
    private val WB_NORM_FS = """
#version 300 es
precision highp float;
uniform highp sampler2D uInputTex;
uniform vec3 uBlackLevel;
uniform float uWhiteLevel;
uniform vec3 uWBGain;
in vec2 vUV;
out vec4 frag;

int ch(int x, int y) {
    return ((y & 1) == 0) ? (((x & 1) == 0) ? 0 : 1) : (((x & 1) == 0) ? 1 : 2);
}

void main() {
    ivec2 center = ivec2(floor(vUV * vec2(textureSize(uInputTex, 0))));
    int channel = ch(center.x, center.y);
    float bl = (channel == 0) ? uBlackLevel.r : ((channel == 1) ? uBlackLevel.g : uBlackLevel.b);
    float wb = (channel == 0) ? uWBGain.r  : ((channel == 1) ? uWBGain.g  : uWBGain.b);
    float raw = texture(uInputTex, vUV).r;
    frag = vec4(raw / (uWhiteLevel - bl) * wb, 0.0, 0.0, 1.0);
}
""".trimIndent()

    private val BLIT_VS = """
        #version 300 es
        precision highp float;
        layout(location=0) in vec2 aPos;
        layout(location=1) in vec2 aTexCoord;
        out vec2 vTexCoord;
        void main() {
            gl_Position = vec4(aPos, 0.0, 1.0);
            vTexCoord = aTexCoord;
        }
    """.trimIndent()

    private val BLIT_FS = """
        #version 300 es
        precision highp float;
        in vec2 vTexCoord;
        out vec4 fragColor;
        uniform highp sampler2D uTexture;
        void main() {
            fragColor = texture(uTexture, vTexCoord);
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

    // ===== MHC 5×5 去马赛克独立 Pass（输入 RGGB 单通道 → 输出 RGB）=====
    private val MHC_DEMOSAIC_FS = """
#version 300 es
precision highp float;
uniform highp sampler2D uInputTex;
in vec2 vUV;
out vec4 frag;

void main() {
    vec2 sz = vec2(textureSize(uInputTex, 0));
    vec2 grid = vec2(1.0) / sz;
    ivec2 center = ivec2(floor(vUV * sz));
    int bx = center.x; int by = center.y;

    float s[25];
    int idx = 0;
    for (int dy = -2; dy <= 2; dy++) {
        for (int dx = -2; dx <= 2; dx++) {
            s[idx] = texture(uInputTex, vUV + vec2(float(dx)*grid.x, float(dy)*grid.y)).r;
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

    frag = vec4(R, G, B, 1.0);
}
""".trimIndent()

    // ===== CCM 色彩校正独立 Pass =====
    private val CCM_FS = """
#version 300 es
precision highp float;
uniform highp sampler2D uInputTex;
uniform mat3 uCCM;
in vec2 vUV;
out vec4 frag;

void main() {
    vec3 rgb = texture(uInputTex, vUV).rgb;
    frag = vec4(max(uCCM * rgb, 0.0), 1.0);
}
""".trimIndent()

    // ===== 色调映射独立 Pass（Reinhard）=====
    private val TONE_MAP_FS = """
#version 300 es
precision highp float;
uniform highp sampler2D uInputTex;
uniform float uToneMapD;
uniform float uToneMapE;
in vec2 vUV;
out vec4 frag;

void main() {
    vec3 rgb = texture(uInputTex, vUV).rgb;
    const float a = 2.51, b = 0.03, c = 2.43;
    frag = vec4(rgb * (a * rgb + b) / (rgb * (c * rgb + uToneMapD) + uToneMapE), 1.0);
}
""".trimIndent()

    // ===== 色调曲线独立 Pass =====
    private val TONE_CURVE_FS = """
#version 300 es
precision highp float;
uniform highp sampler2D uInputTex;
uniform highp sampler2D uToneCurveLUT;
in vec2 vUV;
out vec4 frag;

void main() {
    vec3 rgb = texture(uInputTex, vUV).rgb;
    float luminance = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
    float mapped = texture(uToneCurveLUT, vec2(luminance, 0.5)).r;
    frag = vec4(rgb * (mapped / max(luminance, 0.001)), 1.0);
}
""".trimIndent()

    // ===== sRGB Gamma 独立 Pass =====
    private val GAMMA_FS = """
#version 300 es
precision highp float;
uniform highp sampler2D uInputTex;
in vec2 vUV;
out vec4 frag;

void main() {
    vec3 rgb = texture(uInputTex, vUV).rgb;
    frag = vec4(mix(12.92 * rgb, 1.055 * pow(rgb, vec3(1.0 / 2.4)) - 0.055, step(vec3(0.0031308), rgb)), 1.0);
}
""".trimIndent()

    // ===== LUT 滤镜 Pass =====
    private val POST_PROCESS_FS = """
#version 300 es
precision highp float;
precision highp sampler3D;
uniform highp sampler2D uInputTex;
uniform highp sampler3D uLutTexture;
uniform float uLutSize;
uniform bool uEnableLut;
uniform float uLutIntensity;
in vec2 vUV;
out vec4 frag;

    vec3 applyLUT(vec3 color) {
        vec3 c = clamp(color, 0.0, 1.0);
        vec3 coord = (c * (uLutSize - 1.0) + 0.5) / uLutSize;
        return mix(color, texture(uLutTexture, coord).rgb, uLutIntensity);
    }

void main() {
    vec3 rgb = texture(uInputTex, vUV).rgb;
    if (uEnableLut) rgb = applyLUT(rgb);
    frag = vec4(rgb, 1.0);
}
""".trimIndent()

    // ===== LSC 增益（RGB 版本，用于 RCD 拍照路径）=====
    private val LSC_RGB_FS = """
#version 300 es
precision highp float;
uniform highp sampler2D uInputTex;
uniform highp sampler2D uLscGainTex;
uniform vec2 uLscGridSize;
uniform bool uEnableLsc;
in vec2 vUV;
out vec4 frag;

void main() {
    vec3 rgb = texture(uInputTex, vUV).rgb;
    if (uEnableLsc) {
        vec2 tc = vec2(
            (vUV.x * (uLscGridSize.x - 1.0) + 0.5) / uLscGridSize.x,
            (vUV.y * (uLscGridSize.y - 1.0) + 0.5) / uLscGridSize.y
        );
        vec4 gain = texture(uLscGainTex, tc);
        rgb *= vec3(gain.r, gain.g, gain.b);
    }
    frag = vec4(rgb, 1.0);
}
""".trimIndent()

    // ===== Bayer 降采样 Fragment Shader =====
    private val BAYER_DS_FS = """
#version 300 es
precision highp float;
uniform highp sampler2D uRawTex;
uniform int uCfaPattern;
in vec2 vUV;
out vec4 frag;

int ch(int x, int y) {
    if (uCfaPattern == 0) {
        return ((y & 1) == 0) ? (((x & 1) == 0) ? 0 : 1) : (((x & 1) == 0) ? 1 : 2);
    } else if (uCfaPattern == 1) {
        return ((y & 1) == 0) ? (((x & 1) == 0) ? 1 : 0) : (((x & 1) == 0) ? 2 : 1);
    } else if (uCfaPattern == 2) {
        return ((y & 1) == 0) ? (((x & 1) == 0) ? 1 : 2) : (((x & 1) == 0) ? 0 : 1);
    } else {
        return ((y & 1) == 0) ? (((x & 1) == 0) ? 2 : 1) : (((x & 1) == 0) ? 1 : 0);
    }
}

void main() {
    ivec2 inSz = textureSize(uRawTex, 0);
    ivec2 outPos = ivec2(floor(vUV * vec2(inSz) / 2.0));
    int baseX = outPos.x * 2;
    int baseY = outPos.y * 2;
    int targetCh = ch(outPos.x, outPos.y);

    float sum = 0.0;
    int count = 0;
    int c;

    c = ch(baseX, baseY); if (c == targetCh) { sum += texelFetch(uRawTex, ivec2(baseX, baseY), 0).r; count++; }
    c = ch(baseX + 1, baseY); if (c == targetCh) { sum += texelFetch(uRawTex, ivec2(baseX + 1, baseY), 0).r; count++; }
    c = ch(baseX, baseY + 1); if (c == targetCh) { sum += texelFetch(uRawTex, ivec2(baseX, baseY + 1), 0).r; count++; }
    c = ch(baseX + 1, baseY + 1); if (c == targetCh) { sum += texelFetch(uRawTex, ivec2(baseX + 1, baseY + 1), 0).r; count++; }

    float avg = count > 0 ? sum / float(count) : 0.0;
    frag = vec4(avg, 0.0, 0.0, 1.0);
}
""".trimIndent()
}

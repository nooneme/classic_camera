package com.classic.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.view.MotionEvent
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class VectorFieldRenderer : GLSurfaceView.Renderer {

    companion object {
        const val LUT_SIZE = 33
        const val STRIDE = 5
        var pendingLut: FloatArray? = null
    }

    private var dotCount = 0
    private var dotBuffer: FloatBuffer? = null
    private var lineCount = 0
    private var lineBuffer: FloatBuffer? = null
    private var axisBuffer: FloatBuffer? = null
    private var labelBuffer: FloatBuffer? = null
    private var wireBuffer: FloatBuffer? = null

    private var dotProgram = 0
    private var lineProgram = 0
    private var textProgram = 0
    private var wireProgram = 0
    private var labelTextures = IntArray(3)
    private var hasData = false
    private var hasLabels = false

    private val mvpMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val viewProj = FloatArray(16)

    private var rotationX = -20f
    private var rotationY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var maxOffset = 0f
    private var scale = 1f
    private var lastPinchDist = 0f

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.04f, 0.04f, 0.04f, 1f)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        val commonVs = """
            #version 300 es
            in vec3 aPosition;
            in vec3 aColor;
            uniform mat4 uMVPMatrix;
            out vec3 vColor;
            void main() {
                gl_Position = uMVPMatrix * vec4(aPosition, 1.0);
                vColor = aColor;
            }
        """.trimIndent()

        val lineFs = """
            #version 300 es
            precision mediump float;
            in vec3 vColor;
            out vec4 fragColor;
            void main() {
                fragColor = vec4(vColor, 1.0);
            }
        """.trimIndent()

        val dotVs = """
            #version 300 es
            in vec3 aPosition;
            in vec3 aColor;
            uniform mat4 uMVPMatrix;
            out vec3 vColor;
            void main() {
                gl_Position = uMVPMatrix * vec4(aPosition, 1.0);
                vColor = aColor;
                gl_PointSize = 14.0;
            }
        """.trimIndent()

        val dotFs = """
            #version 300 es
            precision mediump float;
            in vec3 vColor;
            out vec4 fragColor;
            void main() {
                vec2 coord = gl_PointCoord - vec2(0.5);
                float dist = length(coord);
                if (dist > 0.5) discard;
                fragColor = vec4(vColor, 1.0);
            }
        """.trimIndent()

        val textVs = """
            #version 300 es
            in vec3 aPosition;
            in vec2 aTexCoord;
            uniform mat4 uMVPMatrix;
            out vec2 vTexCoord;
            void main() {
                gl_Position = uMVPMatrix * vec4(aPosition, 1.0);
                vTexCoord = aTexCoord;
            }
        """.trimIndent()

        val textFs = """
            #version 300 es
            precision mediump float;
            in vec2 vTexCoord;
            uniform sampler2D uTexture;
            out vec4 fragColor;
            void main() {
                fragColor = texture(uTexture, vTexCoord);
            }
        """.trimIndent()

        val wireFs = """
            #version 300 es
            precision mediump float;
            out vec4 fragColor;
            void main() {
                fragColor = vec4(0.3, 0.3, 0.3, 0.4);
            }
        """.trimIndent()

        dotProgram = createProgram(dotVs, dotFs)
        lineProgram = createProgram(commonVs, lineFs)
        textProgram = createProgram(textVs, textFs)
        wireProgram = createProgram(commonVs, wireFs)

        buildFromPending()
        buildAxisBuffer()
        buildLabels()
        buildWireCube()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        val aspect = width.toFloat() / height.toFloat()
        Matrix.perspectiveM(projMatrix, 0, 45f, aspect, 1f, 1000f)
        Matrix.setLookAtM(viewMatrix, 0,
            0f, 0f, 450f,
            127.5f, 127.5f, 127.5f,
            0f, 1f, 0f)
    }

    fun setLutData(lut: FloatArray) {
        buildVectorBuffers(lut)
    }

    private fun buildFromPending() {
        val data = pendingLut ?: return
        buildVectorBuffers(data)
        pendingLut = null
    }

    private fun buildVectorBuffers(lut: FloatArray) {
        maxOffset = 0f

        for (bz in 0 until LUT_SIZE step STRIDE) {
            for (gy in 0 until LUT_SIZE step STRIDE) {
                for (rx in 0 until LUT_SIZE step STRIDE) {
                    val idx = (bz * LUT_SIZE * LUT_SIZE + gy * LUT_SIZE + rx) * 3
                    val inR = rx / (LUT_SIZE - 1).toFloat()
                    val inG = gy / (LUT_SIZE - 1).toFloat()
                    val inB = bz / (LUT_SIZE - 1).toFloat()
                    val outR = lut[idx]; val outG = lut[idx + 1]; val outB = lut[idx + 2]
                    val dr = (outR - inR) * 255f
                    val dg = (outG - inG) * 255f
                    val db = (outB - inB) * 255f
                    val mag = kotlin.math.sqrt((dr * dr + dg * dg + db * db).toDouble()).toFloat()
                    if (mag > maxOffset) maxOffset = mag
                }
            }
        }

        val vecScale = if (maxOffset > 0.001f) kotlin.math.min(80f / maxOffset, 3f) else 1f

        val dotVerts = mutableListOf<Float>()
        val lineVerts = mutableListOf<Float>()

        for (bz in 0 until LUT_SIZE step STRIDE) {
            for (gy in 0 until LUT_SIZE step STRIDE) {
                for (rx in 0 until LUT_SIZE step STRIDE) {
                    val inR = rx / (LUT_SIZE - 1).toFloat()
                    val inG = gy / (LUT_SIZE - 1).toFloat()
                    val inB = bz / (LUT_SIZE - 1).toFloat()
                    val posX = rx * 255f / (LUT_SIZE - 1)
                    val posY = gy * 255f / (LUT_SIZE - 1)
                    val posZ = bz * 255f / (LUT_SIZE - 1)

                    val idx = (bz * LUT_SIZE * LUT_SIZE + gy * LUT_SIZE + rx) * 3
                    val outR = lut[idx]; val outG = lut[idx + 1]; val outB = lut[idx + 2]

                    val dr = (outR - inR) * 255f * vecScale
                    val dg = (outG - inG) * 255f * vecScale
                    val db = (outB - inB) * 255f * vecScale

                    val mag = kotlin.math.sqrt((dr * dr + dg * dg + db * db).toDouble()).toFloat()
                    if (mag < 2f) continue

                    // 小圆点 (起点 = 输入色)
                    dotVerts.add(posX); dotVerts.add(posY); dotVerts.add(posZ)
                    dotVerts.add(inR); dotVerts.add(inG); dotVerts.add(inB)

                    // 线段 (起点→终点, 输入色→映射色)
                    lineVerts.add(posX); lineVerts.add(posY); lineVerts.add(posZ)
                    lineVerts.add(inR); lineVerts.add(inG); lineVerts.add(inB)
                    lineVerts.add(posX + dr); lineVerts.add(posY + dg); lineVerts.add(posZ + db)
                    lineVerts.add(outR); lineVerts.add(outG); lineVerts.add(outB)
                }
            }
        }

        dotCount = dotVerts.size / 6
        lineCount = lineVerts.size / 6

        if (dotCount > 0) {
            val bb = ByteBuffer.allocateDirect(dotVerts.size * 4).order(ByteOrder.nativeOrder())
            val fb = bb.asFloatBuffer()
            for (v in dotVerts) fb.put(v); fb.position(0)
            dotBuffer = fb
        }
        if (lineCount > 0) {
            val bb = ByteBuffer.allocateDirect(lineVerts.size * 4).order(ByteOrder.nativeOrder())
            val fb = bb.asFloatBuffer()
            for (v in lineVerts) fb.put(v); fb.position(0)
            lineBuffer = fb
        }
        hasData = dotCount > 0 || lineCount > 0
    }

    fun onTouchEvent(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x; lastTouchY = event.y
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    val dx = event.getX(0) - event.getX(1)
                    val dy = event.getY(0) - event.getY(1)
                    lastPinchDist = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    val dx = event.getX(0) - event.getX(1)
                    val dy = event.getY(0) - event.getY(1)
                    val dist = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                    if (lastPinchDist > 0f) {
                        val factor = dist / lastPinchDist
                        scale = (scale * factor).coerceIn(0.2f, 5f)
                    }
                    lastPinchDist = dist
                    lastTouchX = (event.getX(0) + event.getX(1)) / 2f
                    lastTouchY = (event.getY(0) + event.getY(1)) / 2f
                } else {
                    val dx = event.x - lastTouchX; val dy = event.y - lastTouchY
                    rotationY += dx * 0.3f; rotationX += dy * 0.3f
                    lastTouchX = event.x; lastTouchY = event.y
                }
            }
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        if (!hasData) return

        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, 127.5f, 127.5f, 127.5f)
        val s = 0.25f * scale
        Matrix.scaleM(modelMatrix, 0, s, s, s)
        Matrix.rotateM(modelMatrix, 0, rotationX, 1f, 0f, 0f)
        Matrix.rotateM(modelMatrix, 0, rotationY, 0f, 1f, 0f)
        Matrix.translateM(modelMatrix, 0, -127.5f, -127.5f, -127.5f)
        Matrix.multiplyMM(viewProj, 0, projMatrix, 0, viewMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, viewProj, 0, modelMatrix, 0)

        val stride = 6 * 4

        // 线框
        if (wireBuffer != null && wireProgram != 0) {
            GLES30.glUseProgram(wireProgram)
            val wMVP = GLES30.glGetUniformLocation(wireProgram, "uMVPMatrix")
            GLES30.glUniformMatrix4fv(wMVP, 1, false, mvpMatrix, 0)
            val p = GLES30.glGetAttribLocation(wireProgram, "aPosition")
            val c = GLES30.glGetAttribLocation(wireProgram, "aColor")
            GLES30.glEnableVertexAttribArray(p)
            wireBuffer!!.position(0)
            GLES30.glVertexAttribPointer(p, 3, GLES30.GL_FLOAT, false, stride, wireBuffer)
            GLES30.glEnableVertexAttribArray(c)
            wireBuffer!!.position(3)
            GLES30.glVertexAttribPointer(c, 3, GLES30.GL_FLOAT, false, stride, wireBuffer)
            GLES30.glDrawArrays(GLES30.GL_LINES, 0, 24)
            GLES30.glDisableVertexAttribArray(p); GLES30.glDisableVertexAttribArray(c)
        }

        // 坐标轴
        if (axisBuffer != null && lineProgram != 0) {
            GLES30.glUseProgram(lineProgram)
            val aMVP = GLES30.glGetUniformLocation(lineProgram, "uMVPMatrix")
            GLES30.glUniformMatrix4fv(aMVP, 1, false, mvpMatrix, 0)
            val p = GLES30.glGetAttribLocation(lineProgram, "aPosition")
            val c = GLES30.glGetAttribLocation(lineProgram, "aColor")
            GLES30.glEnableVertexAttribArray(p)
            axisBuffer!!.position(0)
            GLES30.glVertexAttribPointer(p, 3, GLES30.GL_FLOAT, false, stride, axisBuffer)
            GLES30.glEnableVertexAttribArray(c)
            axisBuffer!!.position(3)
            GLES30.glVertexAttribPointer(c, 3, GLES30.GL_FLOAT, false, stride, axisBuffer)
            GLES30.glDrawArrays(GLES30.GL_LINES, 0, 6)
            GLES30.glDisableVertexAttribArray(p); GLES30.glDisableVertexAttribArray(c)
        }

        // 标签
        if (hasLabels && textProgram != 0) {
            GLES30.glUseProgram(textProgram)
            val tMVP = GLES30.glGetUniformLocation(textProgram, "uMVPMatrix")
            GLES30.glUniformMatrix4fv(tMVP, 1, false, mvpMatrix, 0)
            val ts = 5 * 4
            val tp = GLES30.glGetAttribLocation(textProgram, "aPosition")
            val tt = GLES30.glGetAttribLocation(textProgram, "aTexCoord")
            val tu = GLES30.glGetUniformLocation(textProgram, "uTexture")
            for (i in 0..2) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + i)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, labelTextures[i])
                GLES30.glUniform1i(tu, i)
                GLES30.glEnableVertexAttribArray(tp)
                labelBuffer!!.position(i * 6 * 5)
                GLES30.glVertexAttribPointer(tp, 3, GLES30.GL_FLOAT, false, ts, labelBuffer)
                GLES30.glEnableVertexAttribArray(tt)
                labelBuffer!!.position(i * 6 * 5 + 3)
                GLES30.glVertexAttribPointer(tt, 2, GLES30.GL_FLOAT, false, ts, labelBuffer)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
            }
            GLES30.glDisableVertexAttribArray(tp); GLES30.glDisableVertexAttribArray(tt)
        }

        // 小圆点
        if (dotCount > 0 && dotBuffer != null && dotProgram != 0) {
            val db = dotBuffer!!
            GLES30.glUseProgram(dotProgram)
            val dMVP = GLES30.glGetUniformLocation(dotProgram, "uMVPMatrix")
            GLES30.glUniformMatrix4fv(dMVP, 1, false, mvpMatrix, 0)
            val dp = GLES30.glGetAttribLocation(dotProgram, "aPosition")
            val dc = GLES30.glGetAttribLocation(dotProgram, "aColor")
            GLES30.glEnableVertexAttribArray(dp)
            db.position(0)
            GLES30.glVertexAttribPointer(dp, 3, GLES30.GL_FLOAT, false, stride, db)
            GLES30.glEnableVertexAttribArray(dc)
            db.position(3)
            GLES30.glVertexAttribPointer(dc, 3, GLES30.GL_FLOAT, false, stride, db)
            GLES30.glDrawArrays(GLES30.GL_POINTS, 0, dotCount)
            GLES30.glDisableVertexAttribArray(dp); GLES30.glDisableVertexAttribArray(dc)
        }

        // 线段
        if (lineCount > 0 && lineBuffer != null && lineProgram != 0) {
            val lb = lineBuffer!!
            GLES30.glUseProgram(lineProgram)
            val lMVP = GLES30.glGetUniformLocation(lineProgram, "uMVPMatrix")
            GLES30.glUniformMatrix4fv(lMVP, 1, false, mvpMatrix, 0)
            val lp = GLES30.glGetAttribLocation(lineProgram, "aPosition")
            val lc = GLES30.glGetAttribLocation(lineProgram, "aColor")
            GLES30.glEnableVertexAttribArray(lp)
            lb.position(0)
            GLES30.glVertexAttribPointer(lp, 3, GLES30.GL_FLOAT, false, stride, lb)
            GLES30.glEnableVertexAttribArray(lc)
            lb.position(3)
            GLES30.glVertexAttribPointer(lc, 3, GLES30.GL_FLOAT, false, stride, lb)
            GLES30.glDrawArrays(GLES30.GL_LINES, 0, lineCount)
            GLES30.glDisableVertexAttribArray(lp); GLES30.glDisableVertexAttribArray(lc)
        }
    }

    private fun buildAxisBuffer() {
        val data = floatArrayOf(
            0f, 0f, 0f, 1f, 0f, 0f, 255f, 0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 0f, 1f, 0f, 0f, 255f, 0f, 0f, 1f, 0f,
            0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 255f, 0f, 0f, 1f,
        )
        val bb = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        fb.put(data); fb.position(0)
        axisBuffer = fb
    }

    private fun buildLabels() {
        GLES30.glGenTextures(3, labelTextures, 0)
        val labels = arrayOf("R", "G", "B")
        val colors = intArrayOf(0xFFFF4444.toInt(), 0xFF44FF44.toInt(), 0xFF4444FF.toInt())
        val texSize = 48
        for (i in 0..2) {
            val bmp = Bitmap.createBitmap(texSize, texSize, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
            val paint = Paint().apply {
                this.color = colors[i]; textSize = texSize * 0.75f
                textAlign = Paint.Align.CENTER; isFakeBoldText = true; isAntiAlias = true
            }
            val x = texSize / 2f
            val y = texSize / 2f - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText(labels[i], x, y, paint)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, labelTextures[i])
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
            bmp.recycle()
        }
        val s = 20f
        val lb = floatArrayOf(
            275f-s, -s, -s, 0f, 0f, 275f+s, -s, -s, 1f, 0f, 275f-s, s, -s, 0f, 1f,
            275f-s,  s, -s, 0f, 1f, 275f+s, -s, -s, 1f, 0f, 275f+s,  s, -s, 1f, 1f,
               -s, 275f-s, -s, 0f, 0f,     s, 275f-s, -s, 1f, 0f,   -s, 275f+s, -s, 0f, 1f,
               -s, 275f+s, -s, 0f, 1f,     s, 275f-s, -s, 1f, 0f,    s, 275f+s, -s, 1f, 1f,
               -s,   -s, 275f-s, 0f, 0f,    s,   -s, 275f-s, 1f, 0f,  -s,   s, 275f-s, 0f, 1f,
               -s,    s, 275f-s, 0f, 1f,    s,   -s, 275f-s, 1f, 0f,   s,    s, 275f-s, 1f, 1f,
        )
        val bb = ByteBuffer.allocateDirect(lb.size * 4).order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        fb.put(lb); fb.position(0)
        labelBuffer = fb
        hasLabels = true
    }

    private fun buildWireCube() {
        val c = 0f; val d = 255f
        val g = floatArrayOf(0.3f, 0.3f, 0.3f)
        val v = floatArrayOf(
            c,c,c,g[0],g[1],g[2], d,c,c,g[0],g[1],g[2], c,d,c,g[0],g[1],g[2], d,d,c,g[0],g[1],g[2],
            c,c,d,g[0],g[1],g[2], d,c,d,g[0],g[1],g[2], c,d,d,g[0],g[1],g[2], d,d,d,g[0],g[1],g[2],
            c,c,c,g[0],g[1],g[2], c,d,c,g[0],g[1],g[2], d,c,c,g[0],g[1],g[2], d,d,c,g[0],g[1],g[2],
            c,c,d,g[0],g[1],g[2], c,d,d,g[0],g[1],g[2], d,c,d,g[0],g[1],g[2], d,d,d,g[0],g[1],g[2],
            c,c,c,g[0],g[1],g[2], c,c,d,g[0],g[1],g[2], d,c,c,g[0],g[1],g[2], d,c,d,g[0],g[1],g[2],
            c,d,c,g[0],g[1],g[2], c,d,d,g[0],g[1],g[2], d,d,c,g[0],g[1],g[2], d,d,d,g[0],g[1],g[2],
        )
        val bb = ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        fb.put(v); fb.position(0)
        wireBuffer = fb
    }

    private fun createProgram(vsSrc: String, fsSrc: String): Int {
        val vs = compile(GLES30.GL_VERTEX_SHADER, vsSrc)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fsSrc)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, vs); GLES30.glAttachShader(p, fs)
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] != GLES30.GL_TRUE) throw RuntimeException("link: ${GLES30.glGetProgramInfoLog(p)}")
        GLES30.glDeleteShader(vs); GLES30.glDeleteShader(fs)
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] != GLES30.GL_TRUE) throw RuntimeException("compile: ${GLES30.glGetShaderInfoLog(s)}\n$src")
        return s
    }
}

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

class CoverageVisualizer : GLSurfaceView.Renderer {

    companion object {
        private const val LUT_SIZE = 33
        private const val TOUCH_SENSITIVITY = 0.3f

        var pendingData: BooleanArray? = null
    }

    private var pointCount = 0
    private var vertexBuffer: FloatBuffer? = null
    private var axisBuffer: FloatBuffer? = null
    private var labelBuffer: FloatBuffer? = null
    private var pointProgram = 0
    private var lineProgram = 0
    private var textProgram = 0
    private var aPositionLoc = 0
    private var aColorLoc = 0
    private var uMVPMatrixLoc = 0
    private var labelTextures = IntArray(3)
    private var aTexCoordLoc = 0
    private var uTexLoc = 0
    private var hasLabels = false

    private val mvpMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val viewProj = FloatArray(16)

    private var rotationX = -20f
    private var rotationY = 0f
    private var isTouching = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var hasData = false

    fun setCoverageData(covered: BooleanArray) {
        buildVertexBuffer(covered)
    }

    private fun buildFromPending() {
        val data = pendingData ?: return
        buildVertexBuffer(data)
        pendingData = null
    }

    private fun buildVertexBuffer(covered: BooleanArray) {
        val points = mutableListOf<Float>()
        val colors = mutableListOf<Float>()
        for (z in 0 until LUT_SIZE) {
            for (y in 0 until LUT_SIZE) {
                for (x in 0 until LUT_SIZE) {
                    val idx = x + y * LUT_SIZE + z * LUT_SIZE * LUT_SIZE
                    if (covered[idx]) {
                        val px = (x.toFloat() / (LUT_SIZE - 1)) * 255f
                        val py = (y.toFloat() / (LUT_SIZE - 1)) * 255f
                        val pz = (z.toFloat() / (LUT_SIZE - 1)) * 255f
                        points.add(px); points.add(py); points.add(pz)
                        colors.add(px / 255f); colors.add(py / 255f); colors.add(pz / 255f)
                    }
                }
            }
        }
        pointCount = points.size / 3
        val interleaved = FloatArray(pointCount * 6)
        for (i in 0 until pointCount) {
            interleaved[i * 6] = points[i * 3]
            interleaved[i * 6 + 1] = points[i * 3 + 1]
            interleaved[i * 6 + 2] = points[i * 3 + 2]
            interleaved[i * 6 + 3] = colors[i * 3]
            interleaved[i * 6 + 4] = colors[i * 3 + 1]
            interleaved[i * 6 + 5] = colors[i * 3 + 2]
        }
        val bb = ByteBuffer.allocateDirect(interleaved.size * 4).order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        fb.put(interleaved); fb.position(0)
        vertexBuffer = fb
        hasData = true
    }

    fun onTouchEvent(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isTouching = true
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                rotationY += dx * TOUCH_SENSITIVITY
                rotationX += dy * TOUCH_SENSITIVITY
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isTouching = false
            }
        }
    }

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
                gl_PointSize = 8.0;
            }
        """.trimIndent()

        val pointFs = """
            #version 300 es
            precision mediump float;
            in vec3 vColor;
            out vec4 fragColor;
            void main() {
                vec2 coord = gl_PointCoord - vec2(0.5);
                float dist = length(coord);
                if (dist > 0.5) discard;
                float alpha = smoothstep(0.5, 0.0, dist);
                fragColor = vec4(vColor, alpha * 0.85);
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

        pointProgram = createProgram(commonVs, pointFs)
        lineProgram = createProgram(commonVs, lineFs)
        textProgram = createProgram(textVs, textFs)
        aPositionLoc = GLES30.glGetAttribLocation(pointProgram, "aPosition")
        aColorLoc = GLES30.glGetAttribLocation(pointProgram, "aColor")
        uMVPMatrixLoc = GLES30.glGetUniformLocation(pointProgram, "uMVPMatrix")
        vertexBuffer = null
        hasData = false
        buildFromPending()
        buildAxisBuffer()
        buildLabels()
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

    private fun buildAxisBuffer() {
        val axisData = floatArrayOf(
            0f, 0f, 0f, 1f, 0f, 0f,
            255f, 0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 0f, 1f, 0f,
            0f, 255f, 0f, 0f, 1f, 0f,
            0f, 0f, 0f, 0f, 0f, 1f,
            0f, 0f, 255f, 0f, 0f, 1f,
        )
        val bb = ByteBuffer.allocateDirect(axisData.size * 4).order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        fb.put(axisData); fb.position(0)
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
                this.color = colors[i]
                textSize = texSize * 0.75f
                textAlign = Paint.Align.CENTER
                isFakeBoldText = true
                isAntiAlias = true
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
        // 三个标签的四边形（朝向相机方向的 billboard）
        val s: Float = 20f
        val labelVerts = floatArrayOf(
            // X 轴标签 "R" 在 (275, 0, 0)，面向 Z
            275f - s, -s, -s, 0f, 0f,
            275f + s, -s, -s, 1f, 0f,
            275f - s,  s, -s, 0f, 1f,
            275f - s,  s, -s, 0f, 1f,
            275f + s, -s, -s, 1f, 0f,
            275f + s,  s, -s, 1f, 1f,
            // Y 轴标签 "G" 在 (0, 275, 0)，面向 Z
            -s, 275f - s, -s, 0f, 0f,
             s, 275f - s, -s, 1f, 0f,
            -s, 275f + s, -s, 0f, 1f,
            -s, 275f + s, -s, 0f, 1f,
             s, 275f - s, -s, 1f, 0f,
             s, 275f + s, -s, 1f, 1f,
            // Z 轴标签 "B" 在 (0, 0, 275)，面向 X
            -s, -s, 275f - s, 0f, 0f,
             s, -s, 275f - s, 1f, 0f,
            -s,  s, 275f - s, 0f, 1f,
            -s,  s, 275f - s, 0f, 1f,
             s, -s, 275f - s, 1f, 0f,
             s,  s, 275f - s, 1f, 1f,
        )
        val bb = ByteBuffer.allocateDirect(labelVerts.size * 4).order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        fb.put(labelVerts); fb.position(0)
        labelBuffer = fb
        hasLabels = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        if (!hasData || vertexBuffer == null) return

        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, 127.5f, 127.5f, 127.5f)
        Matrix.scaleM(modelMatrix, 0, 0.25f, 0.25f, 0.25f)
        Matrix.rotateM(modelMatrix, 0, rotationX, 1f, 0f, 0f)
        Matrix.rotateM(modelMatrix, 0, rotationY, 0f, 1f, 0f)
        Matrix.translateM(modelMatrix, 0, -127.5f, -127.5f, -127.5f)

        Matrix.multiplyMM(viewProj, 0, projMatrix, 0, viewMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, viewProj, 0, modelMatrix, 0)

        val stride = 6 * 4

        // 画坐标轴
        val ab = axisBuffer
        if (ab != null && lineProgram != 0) {
            GLES30.glUseProgram(lineProgram)
            val lineMVP = GLES30.glGetUniformLocation(lineProgram, "uMVPMatrix")
            GLES30.glUniformMatrix4fv(lineMVP, 1, false, mvpMatrix, 0)
            val pl = GLES30.glGetAttribLocation(lineProgram, "aPosition")
            val cl = GLES30.glGetAttribLocation(lineProgram, "aColor")
            GLES30.glEnableVertexAttribArray(pl)
            ab.position(0)
            GLES30.glVertexAttribPointer(pl, 3, GLES30.GL_FLOAT, false, stride, ab)
            GLES30.glEnableVertexAttribArray(cl)
            ab.position(3)
            GLES30.glVertexAttribPointer(cl, 3, GLES30.GL_FLOAT, false, stride, ab)
            GLES30.glDrawArrays(GLES30.GL_LINES, 0, 6)
            GLES30.glDisableVertexAttribArray(pl)
            GLES30.glDisableVertexAttribArray(cl)
        }

        // 画文字标签
        if (hasLabels && textProgram != 0) {
            GLES30.glUseProgram(textProgram)
            val texMVP = GLES30.glGetUniformLocation(textProgram, "uMVPMatrix")
            GLES30.glUniformMatrix4fv(texMVP, 1, false, mvpMatrix, 0)
            val texStride = 5 * 4
            val posL = GLES30.glGetAttribLocation(textProgram, "aPosition")
            val tcL = GLES30.glGetAttribLocation(textProgram, "aTexCoord")
            val texU = GLES30.glGetUniformLocation(textProgram, "uTexture")
            val lb = labelBuffer!!
            for (i in 0..2) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + i)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, labelTextures[i])
                GLES30.glUniform1i(texU, i)
                GLES30.glEnableVertexAttribArray(posL)
                lb.position(i * 6 * 5)
                GLES30.glVertexAttribPointer(posL, 3, GLES30.GL_FLOAT, false, texStride, lb)
                GLES30.glEnableVertexAttribArray(tcL)
                lb.position(i * 6 * 5 + 3)
                GLES30.glVertexAttribPointer(tcL, 2, GLES30.GL_FLOAT, false, texStride, lb)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
            }
            GLES30.glDisableVertexAttribArray(posL)
            GLES30.glDisableVertexAttribArray(tcL)
        }

        // 画点云
        GLES30.glUseProgram(pointProgram)
        GLES30.glUniformMatrix4fv(uMVPMatrixLoc, 1, false, mvpMatrix, 0)

        val fb = vertexBuffer!!

        GLES30.glEnableVertexAttribArray(aPositionLoc)
        fb.position(0)
        GLES30.glVertexAttribPointer(aPositionLoc, 3, GLES30.GL_FLOAT, false, stride, fb)

        GLES30.glEnableVertexAttribArray(aColorLoc)
        fb.position(3)
        GLES30.glVertexAttribPointer(aColorLoc, 3, GLES30.GL_FLOAT, false, stride, fb)

        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, pointCount)

        GLES30.glDisableVertexAttribArray(aPositionLoc)
        GLES30.glDisableVertexAttribArray(aColorLoc)
    }

    private fun createProgram(vsSrc: String, fsSrc: String): Int {
        val vs = compile(GLES30.GL_VERTEX_SHADER, vsSrc)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fsSrc)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, vs); GLES30.glAttachShader(p, fs)
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] != GLES30.GL_TRUE) {
            throw RuntimeException("link: ${GLES30.glGetProgramInfoLog(p)}")
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
            throw RuntimeException("compile: ${GLES30.glGetShaderInfoLog(s)}\n$src")
        }
        return s
    }
}

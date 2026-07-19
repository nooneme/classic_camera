package com.classic.camera

import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity

class CoverageVisualizerActivity : AppCompatActivity() {

    private lateinit var glSurfaceView: GLSurfaceView
    private val visualizer = CoverageVisualizer()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_coverage_visualizer)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        glSurfaceView = findViewById(R.id.coverageGLView)

        glSurfaceView.setEGLContextClientVersion(2)
        glSurfaceView.setRenderer(visualizer)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        glSurfaceView.setOnTouchListener { _, event ->
            visualizer.onTouchEvent(event)
            true
        }

        findViewById<android.widget.TextView>(R.id.btnClose).setOnClickListener {
            finish()
        }
    }

    override fun onPause() {
        super.onPause()
        glSurfaceView.onPause()
    }

    override fun onResume() {
        super.onResume()
        glSurfaceView.onResume()
    }
}

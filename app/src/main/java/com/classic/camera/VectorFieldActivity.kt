package com.classic.camera

import android.opengl.GLSurfaceView
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class VectorFieldActivity : AppCompatActivity() {

    private lateinit var glSurfaceView: GLSurfaceView
    private val renderer = VectorFieldRenderer()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vector_field)

        glSurfaceView = findViewById(R.id.vectorGLView)

        glSurfaceView.setEGLContextClientVersion(2)
        glSurfaceView.setRenderer(renderer)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        glSurfaceView.setOnTouchListener { _, event ->
            renderer.onTouchEvent(event)
            true
        }

        val filterName = intent.getStringExtra("filter_name") ?: ""
        findViewById<TextView>(R.id.tvTitle).text = "向量场 — $filterName"

        findViewById<TextView>(R.id.btnClose).setOnClickListener {
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

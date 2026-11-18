package app.streambird.android.media

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One alpha-blended textured quad for the compact clock text (the same tiny shader and quad as the ad layer: premultiplied texture times a global alpha),
 * so the date and time can cross-fade. GL thread only; one program and one direct buffer for the life of the compositor, no per-frame allocation.
 */
class ClockQuadGl(private val outW: Int, private val outH: Int) {
    private var program = 0; private var aPos = 0; private var aTex = 0; private var uTex = 0; private var uAlpha = 0
    private val quad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f, -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f)); position(0)
    }

    /** Draws [tex] into the GL rectangle (x, y bottom-left origin) with opacity [alpha] (0..1). */
    fun draw(tex: Int, x: Int, y: Int, w: Int, h: Int, alpha: Float) {
        ensureProgram()
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA) // Android bitmaps are premultiplied
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glUniform1f(uAlpha, alpha)
        GLES20.glViewport(x, y, w, h)
        quad.position(0); GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad); GLES20.glEnableVertexAttribArray(aPos)
        quad.position(2); GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad); GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos); GLES20.glDisableVertexAttribArray(aTex)
        GLES20.glUseProgram(0)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glViewport(0, 0, outW, outH)
    }

    private fun ensureProgram() {
        if (program != 0) return
        fun sh(type: Int, src: String): Int = GLES20.glCreateShader(type).also { GLES20.glShaderSource(it, src); GLES20.glCompileShader(it) }
        val vs = sh(GLES20.GL_VERTEX_SHADER, "attribute vec2 aPos; attribute vec2 aTex; varying vec2 vTex; void main(){ gl_Position = vec4(aPos, 0.0, 1.0); vTex = aTex; }")
        val fs = sh(GLES20.GL_FRAGMENT_SHADER, "precision mediump float; varying vec2 vTex; uniform sampler2D uTex; uniform float uAlpha; void main(){ gl_FragColor = texture2D(uTex, vTex) * uAlpha; }")
        program = GLES20.glCreateProgram().also { GLES20.glAttachShader(it, vs); GLES20.glAttachShader(it, fs); GLES20.glLinkProgram(it) }
        val ok = IntArray(1); GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) { GLES20.glDeleteProgram(program); program = 0; throw IllegalStateException("clock shader did not link") }
        aPos = GLES20.glGetAttribLocation(program, "aPos"); aTex = GLES20.glGetAttribLocation(program, "aTex")
        uTex = GLES20.glGetUniformLocation(program, "uTex"); uAlpha = GLES20.glGetUniformLocation(program, "uAlpha")
    }

    fun release() {
        if (program != 0) GLES20.glDeleteProgram(program)
        program = 0
    }
}

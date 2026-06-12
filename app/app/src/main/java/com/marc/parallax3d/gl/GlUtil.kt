package com.marc.parallax3d.gl

import android.opengl.GLES30
import android.util.Log

object GlUtil {
    fun buildProgram(vertSrc: String, fragSrc: String): Int {
        val vs = compile(GLES30.GL_VERTEX_SHADER, vertSrc)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragSrc)
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vs)
        GLES30.glAttachShader(prog, fs)
        GLES30.glLinkProgram(prog)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "link failed: ${GLES30.glGetProgramInfoLog(prog)}" }
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        return prog
    }

    private fun compile(type: Int, src: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, src)
        GLES30.glCompileShader(shader)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { "compile failed: ${GLES30.glGetShaderInfoLog(shader)}" }
        return shader
    }

    fun checkError(tag: String) {
        val err = GLES30.glGetError()
        if (err != GLES30.GL_NO_ERROR) Log.e("GlUtil", "$tag: GL error 0x${err.toString(16)}")
    }
}

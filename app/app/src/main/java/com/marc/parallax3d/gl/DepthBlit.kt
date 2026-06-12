package com.marc.parallax3d.gl

import android.opengl.GLES11Ext
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Pass 1: packed-frame depth half (OES) -> R8 texture FBO at low res. */
class DepthBlit(private val width: Int = 480, private val height: Int = 270) {
    private val program = GlUtil.buildProgram(BLIT_VERT, BLIT_FRAG)
    private val fbo = IntArray(1)
    private val tex = IntArray(1)
    private val quadVao = IntArray(1)
    private val uStMatrix = GLES30.glGetUniformLocation(program, "uStMatrix")

    val depthTexture: Int get() = tex[0]

    init {
        GLES30.glGenTextures(1, tex, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, width, height, 0,
            GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        GLES30.glGenFramebuffers(1, fbo, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, tex[0], 0)
        check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) {
            "depth FBO incomplete"
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        // Fullscreen quad as triangle strip, positions double as UVs
        val quad = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
        val vbo = IntArray(1)
        GLES30.glGenVertexArrays(1, quadVao, 0)
        GLES30.glGenBuffers(1, vbo, 0)
        GLES30.glBindVertexArray(quadVao[0])
        val buf = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder())
        buf.asFloatBuffer().put(quad).position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quad.size * 4, buf, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindVertexArray(0)
    }

    fun run(oesTexture: Int, stMatrix: FloatArray) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glViewport(0, 0, width, height)
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES30.glUniformMatrix4fv(uStMatrix, 1, false, stMatrix, 0)
        GLES30.glBindVertexArray(quadVao[0])
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }
}

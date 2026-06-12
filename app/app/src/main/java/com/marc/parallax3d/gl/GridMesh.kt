package com.marc.parallax3d.gl

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Dense grid in [0,1]^2. Vertex shader displaces by depth. 256x144 cells. */
class GridMesh(cols: Int = 256, rows: Int = 144) {
    private val vao = IntArray(1)
    val indexCount: Int

    init {
        val w = cols + 1
        val h = rows + 1
        val verts = FloatArray(w * h * 2)
        var i = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                verts[i++] = x.toFloat() / cols
                verts[i++] = y.toFloat() / rows
            }
        }
        // w*h max 37k verts, fits short indices
        val indices = ShortArray(cols * rows * 6)
        i = 0
        for (y in 0 until rows) {
            for (x in 0 until cols) {
                val tl = (y * w + x)
                val tr = tl + 1
                val bl = tl + w
                val br = bl + 1
                indices[i++] = tl.toShort(); indices[i++] = bl.toShort(); indices[i++] = tr.toShort()
                indices[i++] = tr.toShort(); indices[i++] = bl.toShort(); indices[i++] = br.toShort()
            }
        }
        indexCount = indices.size

        val vbo = IntArray(2)
        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glGenBuffers(2, vbo, 0)
        GLES30.glBindVertexArray(vao[0])

        val vb = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder())
        vb.asFloatBuffer().put(verts).position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verts.size * 4, vb, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)

        val ib = ByteBuffer.allocateDirect(indices.size * 2).order(ByteOrder.nativeOrder())
        ib.asShortBuffer().put(indices).position(0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, vbo[1])
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indices.size * 2, ib, GLES30.GL_STATIC_DRAW)

        GLES30.glBindVertexArray(0)
    }

    fun draw() {
        GLES30.glBindVertexArray(vao[0])
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_SHORT, 0)
        GLES30.glBindVertexArray(0)
    }
}

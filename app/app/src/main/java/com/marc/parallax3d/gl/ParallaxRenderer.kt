package com.marc.parallax3d.gl

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import com.marc.parallax3d.tracking.HeadPoseFusion
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Render core. Continuous mode: head pose changes between video frames,
 * so every vsync re-warps the latest frame. That IS the parallax effect.
 */
class ParallaxRenderer(
    private val headPose: HeadPoseFusion,
    private val onSurfaceReady: (SurfaceTexture) -> Unit,
) : GLSurfaceView.Renderer {

    @Volatile var strength = 0.05f        // NDC units at full head offset (~2.5% width)
    @Volatile var convergence = 0.5f      // depth value at screen plane
    @Volatile var overscan = 1.04f        // hide screen-edge disocclusion gaps
    @Volatile var eyeAspect = 1.92f       // eye_width / eye_height from metadata

    private var oesTex = 0
    private var surfaceTexture: SurfaceTexture? = null
    private val frameAvailable = AtomicBoolean(false)
    private val stMatrix = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }

    private var warpProgram = 0
    private var depthBlit: DepthBlit? = null
    private var mesh: GridMesh? = null
    private var viewW = 1
    private var viewH = 1

    private var uEyeOffset = -1
    private var uStrength = -1
    private var uConvergence = -1
    private var uScale = -1
    private var uWarpStMatrix = -1

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        oesTex = tex[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)

        surfaceTexture?.release()
        surfaceTexture = SurfaceTexture(oesTex).also { st ->
            st.setOnFrameAvailableListener { frameAvailable.set(true) }
            onSurfaceReady(st)
        }

        warpProgram = GlUtil.buildProgram(WARP_VERT, WARP_FRAG)
        uEyeOffset = GLES30.glGetUniformLocation(warpProgram, "uEyeOffset")
        uStrength = GLES30.glGetUniformLocation(warpProgram, "uStrength")
        uConvergence = GLES30.glGetUniformLocation(warpProgram, "uConvergence")
        uScale = GLES30.glGetUniformLocation(warpProgram, "uScale")
        uWarpStMatrix = GLES30.glGetUniformLocation(warpProgram, "uStMatrix")
        depthBlit = DepthBlit()
        mesh = GridMesh()
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LESS)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width
        viewH = height
    }

    override fun onDrawFrame(gl: GL10?) {
        val st = surfaceTexture ?: return
        if (frameAvailable.compareAndSet(true, false)) {
            st.updateTexImage()
            st.getTransformMatrix(stMatrix)
            depthBlit?.run(oesTex, stMatrix)
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, viewW, viewH)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        val blit = depthBlit ?: return
        GLES30.glUseProgram(warpProgram)

        // Aspect-fit eye into view, then overscan
        val viewAspect = viewW.toFloat() / viewH
        var sx = 1f
        var sy = 1f
        if (viewAspect > eyeAspect) sx = eyeAspect / viewAspect else sy = viewAspect / eyeAspect
        GLES30.glUniform2f(uScale, sx * overscan, sy * overscan)

        val (hx, hy) = headPose.offset()
        GLES30.glUniform2f(uEyeOffset, hx, hy * 0.8f)  // vertical parallax subtler, doubled for wide displays
        GLES30.glUniform1f(uStrength, strength)
        GLES30.glUniform1f(uConvergence, convergence)
        GLES30.glUniformMatrix4fv(uWarpStMatrix, 1, false, stMatrix, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(warpProgram, "uVideo"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, blit.depthTexture)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(warpProgram, "uDepth"), 1)

        mesh?.draw()
    }

    fun release() {
        surfaceTexture?.release()
        surfaceTexture = null
    }
}

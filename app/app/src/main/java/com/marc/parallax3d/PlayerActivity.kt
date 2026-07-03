package com.marc.parallax3d

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.marc.parallax3d.gl.ParallaxRenderer
import com.marc.parallax3d.player.PackedMetadata
import com.marc.parallax3d.tracking.FaceTracker
import com.marc.parallax3d.tracking.GyroTracker
import com.marc.parallax3d.tracking.HeadPoseFusion

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SIDECAR = "sidecar_json"
        private const val POS_PREFIX = "pos:"
    }

    private lateinit var glView: android.opengl.GLSurfaceView
    private lateinit var renderer: ParallaxRenderer
    private lateinit var player: ExoPlayer
    private lateinit var fusion: HeadPoseFusion
    private lateinit var gyro: GyroTracker
    private var faceTracker: FaceTracker? = null
    private var videoSurface: Surface? = null

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("player", MODE_PRIVATE) }

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startFaceTracking() else fusion.mode = HeadPoseFusion.Mode.GYRO_ONLY
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        fusion = HeadPoseFusion()
        gyro = GyroTracker(this)
        fusion.gyro = gyro

        renderer = ParallaxRenderer(fusion) { st -> mainExecutor.execute { attachSurface(st) } }
        glView = findViewById(R.id.glView)
        glView.setEGLContextClientVersion(3)
        glView.setEGLConfigChooser(8, 8, 8, 0, 16, 0)  // 16-bit depth for occlusion
        glView.setRenderer(renderer)
        glView.renderMode = android.opengl.GLSurfaceView.RENDERMODE_CONTINUOUSLY

        // Sidecar (library flow) beats mp4 comment (single-file flow)
        val sidecarMeta = PackedMetadata.fromJson(intent.getStringExtra(EXTRA_SIDECAR))
        if (sidecarMeta != null) applyPackedMetadata(sidecarMeta)

        player = ExoPlayer.Builder(this).build()
        if (sidecarMeta == null) {
            player.addListener(object : Player.Listener {
                override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                    PackedMetadata.fromJson(mediaMetadata.description?.toString())
                        ?.let { applyPackedMetadata(it) }
                }
            })
        }
        intent.data?.let { uri ->
            player.setMediaItem(MediaItem.fromUri(uri))
            val savedPos = prefs.getLong(POS_PREFIX + uri, 0L)
            if (savedPos > 0) player.seekTo(savedPos)
            player.prepare()
            player.playWhenReady = true
        }

        setupControls()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startFaceTracking()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun attachSurface(st: SurfaceTexture) {
        videoSurface?.release()
        videoSurface = Surface(st)
        player.setVideoSurface(videoSurface)
    }

    private fun applyPackedMetadata(meta: PackedMetadata) {
        renderer.convergence = meta.convergence
        renderer.eyeAspect = meta.eyeAspect
        findViewById<SeekBar>(R.id.convergenceBar).progress = (meta.convergence * 100).toInt()
    }

    private fun startFaceTracking() {
        faceTracker = FaceTracker(this, fusion).also { it.start(this) }
    }

    private fun setupControls() {
        val controls = findViewById<View>(R.id.controls)
        findViewById<View>(android.R.id.content).setOnClickListener {
            controls.visibility = if (controls.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        val playPause = findViewById<Button>(R.id.playPause)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playPause.text = if (isPlaying) "⏸" else "▶"
            }
        })
        playPause.setOnClickListener {
            if (player.isPlaying) {
                player.pause()
            } else {
                if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                player.play()
            }
        }

        val seekBar = findViewById<SeekBar>(R.id.seekBar)
        seekBar.max = 1000
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && player.duration > 0) {
                    player.seekTo(player.duration * progress / 1000)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) = Unit
            override fun onStopTrackingTouch(sb: SeekBar) = Unit
        })
        handler.post(object : Runnable {
            override fun run() {
                if (player.duration > 0) {
                    seekBar.progress = (player.currentPosition * 1000 / player.duration).toInt()
                }
                handler.postDelayed(this, 500)
            }
        })

        val strengthBar = findViewById<SeekBar>(R.id.strengthBar)
        strengthBar.progress = prefs.getInt("strength", 42)  // ~2.5% parallax
        renderer.strength = strengthBar.progress / 100f * 0.12f
        strengthBar.setOnSeekBarChangeListener(simpleSeek { p ->
            renderer.strength = p / 100f * 0.12f  // 0..12% of NDC, default mid = 6%
            prefs.edit().putInt("strength", p).apply()
        })
        findViewById<SeekBar>(R.id.convergenceBar).setOnSeekBarChangeListener(simpleSeek { p ->
            renderer.convergence = p / 100f
        })

        val sensitivityBar = findViewById<SeekBar>(R.id.sensitivityBar)
        sensitivityBar.progress = prefs.getInt("sensitivity", 50)
        fusion.sensitivity = 1f + sensitivityBar.progress / 100f * 3f  // 1x..4x, default 2.5x
        sensitivityBar.setOnSeekBarChangeListener(simpleSeek { p ->
            fusion.sensitivity = 1f + p / 100f * 3f
            prefs.edit().putInt("sensitivity", p).apply()
        })

        val modeButton = findViewById<Button>(R.id.modeButton)
        fun showMode() = modeButton.setText(when (fusion.mode) {
            HeadPoseFusion.Mode.FUSED -> R.string.mode_fused
            HeadPoseFusion.Mode.GYRO_ONLY -> R.string.mode_gyro
            HeadPoseFusion.Mode.FACE_ONLY -> R.string.mode_face
        })
        fusion.mode = HeadPoseFusion.Mode.entries[
            prefs.getInt("mode", 0).coerceIn(0, HeadPoseFusion.Mode.entries.size - 1)]
        showMode()
        modeButton.setOnClickListener {
            fusion.mode = when (fusion.mode) {
                HeadPoseFusion.Mode.FUSED -> HeadPoseFusion.Mode.GYRO_ONLY
                HeadPoseFusion.Mode.GYRO_ONLY -> HeadPoseFusion.Mode.FACE_ONLY
                HeadPoseFusion.Mode.FACE_ONLY -> HeadPoseFusion.Mode.FUSED
            }
            prefs.edit().putInt("mode", fusion.mode.ordinal).apply()
            showMode()
        }
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun simpleSeek(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
            if (fromUser) onChange(progress)
        }
        override fun onStartTrackingTouch(sb: SeekBar) = Unit
        override fun onStopTrackingTouch(sb: SeekBar) = Unit
    }

    override fun onStart() {
        super.onStart()
        gyro.start()
        glView.onResume()
    }

    override fun onStop() {
        super.onStop()
        saveResumePosition()
        player.pause()
        gyro.stop()
        glView.onPause()
    }

    private fun saveResumePosition() {
        val uri = intent.data ?: return
        val key = POS_PREFIX + uri
        if (player.playbackState == Player.STATE_ENDED) {
            prefs.edit().remove(key).apply()
        } else {
            prefs.edit().putLong(key, player.currentPosition).apply()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        faceTracker?.stop()
        player.release()
        videoSurface?.release()
        renderer.release()
    }
}

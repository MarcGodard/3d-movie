package com.marc.parallax3d.tracking

import android.annotation.SuppressLint
import android.content.Context
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

/**
 * Front camera -> ML Kit face bbox -> One-Euro filtered head offset.
 * Low res analysis keeps CPU negligible next to video decode.
 */
class FaceTracker(
    private val context: Context,
    private val fusion: HeadPoseFusion,
) {
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.2f)
            .build()
    )
    private val filterX = OneEuroFilter(minCutoff = 1.0f, beta = 0.02f)
    private val filterY = OneEuroFilter(minCutoff = 1.0f, beta = 0.02f)
    private var provider: ProcessCameraProvider? = null
    private var missedFrames = 0

    @SuppressLint("UnsafeOptInUsageError")
    fun start(lifecycleOwner: LifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val cameraProvider = future.get()
            provider = cameraProvider
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(320, 240),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                        ).build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(ContextCompat.getMainExecutor(context)) { proxy ->
                val mediaImage = proxy.image
                if (mediaImage == null) {
                    proxy.close()
                    return@setAnalyzer
                }
                val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
                detector.process(image)
                    .addOnSuccessListener { faces ->
                        val face = faces.maxByOrNull { it.boundingBox.width() }
                        if (face != null) {
                            val w = image.width.toFloat()
                            val h = image.height.toFloat()
                            val cx = face.boundingBox.exactCenterX() / w * 2f - 1f
                            val cy = face.boundingBox.exactCenterY() / h * 2f - 1f
                            val t = System.nanoTime()
                            // Front camera mirrors. Gain: head sweep covers only
                            // a fraction of the camera frame at viewing distance.
                            val gain = 3f
                            fusion.faceX = filterX.filter(-cx * gain, t).coerceIn(-1f, 1f)
                            fusion.faceY = filterY.filter(cy * gain, t).coerceIn(-1f, 1f)
                            fusion.faceVisible = true
                            missedFrames = 0
                        } else if (++missedFrames > 15) {
                            // ~0.5s without a face -> gyro-only fallback
                            fusion.faceVisible = false
                            filterX.reset()
                            filterY.reset()
                        }
                    }
                    .addOnCompleteListener { proxy.close() }
            }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        provider?.unbindAll()
        detector.close()
        fusion.faceVisible = false
    }
}

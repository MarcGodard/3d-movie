# 3d-movie

Converts SBS 3D movies to head-tracked parallax 3D for a viewer with no stereo vision (motion parallax is the depth cue). `transcoder/` (Python, uv) writes a packed `[left color|depth]` HEVC + `.p3d.json` sidecar; `app/` (Kotlin, Android tablet) plays it; `preview3d` (`transcoder/src/transcoder/preview.py`) is the desktop webcam previewer. README is the operator manual.

## Gotchas

- Torch pinned 2.7.1 + cu126: the GPU is a GTX 1080 (Pascal, sm_61), dropped in 2.8. Do not bump.
- RAFT-Stereo disparity is one-signed: crop-shift both eyes before inference, subtract after. Border-padding instead of cropping derails the model.
- App render path: vertex shaders cannot sample an OES texture, so depth is blitted to an R8 FBO first. The grid mesh must be depth-tested (z from the depth map) or background paints over foreground.
- Face tracking uses x3 gain (head sweep is small in the camera frame) fused with the gyro.
- Previewer: occlusion-aware nearest-first march. A one-shot remap loses z-order, and fixed-point iteration diverges and melts faces: do not retry it. Webcams ignore requested capture size, so YuNet needs `setInputSize` per frame.
- Parallax strength default is 2.5% on purpose (viewer is sensitive). Keep it.
- Test media lives in gitignored `media/`.
- On-device tablet verification is still pending; see `tablet-setup.txt`.

# 3d-movie: watch 3D movies with one eye

My son was born blind in one eye. Stereoscopic 3D (the side-by-side kind used by 3D TVs and most 3D movie rips) needs two working eyes, so it does nothing for him. But stereo is not the only way humans perceive depth: **motion parallax** is a monocular depth cue. If the picture warps correctly as your head moves, you see depth with one eye.

This project turns traditional side-by-side (SBS) 3D movies into a parallax 3D experience on an Android tablet:

1. **`transcoder/`** (Python, runs on a desktop with an NVIDIA GPU) extracts true depth from the stereo pair. The two SBS views differ by real disparity, so we get geometric depth, not a monocular guess. It outputs a single packed video: left-eye color on the left half, depth as grayscale on the right half, plus the original audio.
2. **`app/`** (Android, Kotlin) plays the packed file. A shader warps the picture in real time using the depth map, driven by the front camera tracking the viewer's head, fused with the gyroscope. Move your head and the scene moves the way a real 3D scene would.

If this helps your family too, that's why it's public.

## How it works

```
SBS 3D movie ──► transcoder ─────────────► packed MP4 ─────► tablet app
                 split L/R views            [color|depth]      ExoPlayer → OpenGL warp
                 stereo disparity → depth   HEVC + AAC         head pose from front camera + gyro
```

- Depth via [RAFT-Stereo](https://github.com/princeton-vl/RAFT-Stereo) (default, best quality) or OpenCV SGBM (`--fast`).
- Packed format `P3D1`: one video stream, so depth is frame-aligned with color by construction. Metadata in a `.p3d.json` sidecar.
- Renderer: grid mesh displaced per-vertex by depth, viewpoint offset from a One-Euro-filtered face position blended with high-passed gyro.

## Status

Early development. Milestones:

- [x] T1: classical (SGBM) transcode works end to end (~22 fps on CPU)
- [x] T2: RAFT-Stereo depth (~4.6 fps with `--depth-stride 2`; 2 h movie ≈ overnight)
- [x] P1: library UI, sliders, settings persistence
- [ ] A1: app plays packed file (code builds; awaiting on-device verify)
- [ ] A2: gyro parallax (code complete; awaiting on-device verify)
- [ ] A3: face tracking (code complete; awaiting on-device verify)

## Usage

```bash
# one-time setup
cd transcoder
./scripts/setup.sh          # fetches RAFT-Stereo code + weights
uv sync --extra ml          # or plain `uv sync` for --fast (SGBM) only

# transcode (RAFT, overnight for a full movie)
uv run transcode3d movie.sbs.mkv movie.p3d.mp4 --depth-stride 2

# quick preview-quality pass (CPU, ~20 fps)
uv run transcode3d movie.sbs.mkv movie.p3d.mp4 --fast

# app: build + install (tablet in developer mode)
cd ../app && ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Copy the `.p3d.mp4` and its `.p3d.json` sidecar to the tablet, pick the folder in the app.

## Testing

**Desktop** (no tablet needed, webcam drives head parallax, mouse if no webcam):

```bash
cd transcoder
uv run preview3d ../media/despicable.p3d.mp4      # or any packed .p3d.mp4
```

Keys: `q` quit, `space` pause, `[`/`]` strength, `f` freeze face (mouse drives instead).

**Mobile** — needs a real Android tablet (10+, front camera + gyro). An emulator can
check the build/UI but has no real camera or gyro, so it can't test the actual
parallax feel.

```bash
# tablet: enable developer mode + USB debugging, plug in, then confirm:
adb devices

cd app
./gradlew assembleDebug
adb install -r app/app/build/outputs/apk/debug/app-debug.apk

# push a test clip + its sidecar json
adb push ../media/despicable.p3d.mp4 /sdcard/Movies/
adb push ../media/despicable.p3d.p3d.json /sdcard/Movies/
```

Open the app, pick that folder, play.

## Requirements

- **Transcoder:** Linux, Python 3.13 ([uv](https://docs.astral.sh/uv/)), ffmpeg 7+, NVIDIA GPU (developed on a GTX 1080; PyTorch is pinned to 2.7.1, the last release line supporting Pascal).
- **App:** Android 10+ tablet, reasonably recent (developed against a 2021+ mid-range tablet). Front camera for face tracking; works gyro-only without it.

## Legal note

Transcode movies you own. This repo contains no movie content.

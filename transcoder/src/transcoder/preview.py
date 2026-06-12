"""Desktop parallax preview: packed .p3d.mp4 + webcam face tracking.

Same depth-warp math as the Android app, native OpenCV window. Tests the
parallax feel without a tablet. No audio (preview tool, not a player).

Keys: q quit, space pause, [/] strength, f freeze head (mouse drives instead).
"""

import argparse
import json
import time
import urllib.request
from pathlib import Path

import cv2
import numpy as np

from transcoder.metadata import sidecar_path

# YuNet face detector, ships in opencv_zoo (~230KB onnx)
_YUNET_URL = ("https://github.com/opencv/opencv_zoo/raw/main/models/"
              "face_detection_yunet/face_detection_yunet_2023mar.onnx")
_YUNET_PATH = Path(__file__).resolve().parents[2] / "models" / "yunet.onnx"


def _load_detector(size: tuple[int, int]):
    if not _YUNET_PATH.is_file():
        _YUNET_PATH.parent.mkdir(parents=True, exist_ok=True)
        print(f"downloading face detector -> {_YUNET_PATH}")
        urllib.request.urlretrieve(_YUNET_URL, _YUNET_PATH)
    return cv2.FaceDetectorYN.create(str(_YUNET_PATH), "", size, 0.6)


class HeadSmoother:
    """EMA stand-in for the app's One-Euro. Good enough for preview."""

    def __init__(self, alpha: float = 0.35) -> None:
        self.alpha = alpha
        self.x = 0.0
        self.y = 0.0

    def update(self, x: float, y: float) -> tuple[float, float]:
        self.x += self.alpha * (x - self.x)
        self.y += self.alpha * (y - self.y)
        return self.x, self.y


def warp(color: np.ndarray, depth01: np.ndarray, off_x: float, off_y: float,
         strength_px: float, convergence: float,
         maps: tuple[np.ndarray, np.ndarray]) -> np.ndarray:
    """Inverse remap approximation of the app's forward mesh warp."""
    # float32 throughout: remap rejects float64, and python/numpy scalar
    # promotion sneaks float64 in (e.g. mouse coords)
    # Occlusion-aware inverse warp (parallax-occlusion-mapping style).
    # Plain one-shot remap has no z-ordering: at fold-overs background can
    # win over foreground. March candidate shifts nearest-first; first
    # consistent hit wins, so foreground always occludes.
    # (Fixed-point iteration was tried earlier and reverted: diverges at edges.)
    ox = np.float32(off_x)
    oy = np.float32(off_y * 0.4)  # vertical subtler, matches app
    shift = (depth01 - convergence) * np.float32(strength_px)

    s_min, s_max = float(shift.min()), float(shift.max())
    steps = 12
    eps = np.float32(max(1e-3, (s_max - s_min) / steps))
    src_shift = np.full_like(shift, np.float32(s_min))
    resolved = np.zeros(shift.shape, bool)
    h, w = shift.shape
    for s in np.linspace(s_max, s_min, steps, dtype=np.float32):
        # Sample shift at the source this candidate implies: pure translation
        m = np.float32([[1, 0, -ox * s], [0, 1, -oy * s]])
        cand = cv2.warpAffine(shift, m, (w, h), flags=cv2.INTER_LINEAR,
                              borderMode=cv2.BORDER_REPLICATE)
        hit = ~resolved & (cand >= s - eps)
        src_shift[hit] = cand[hit]
        resolved |= hit
    map_x = (maps[0] - ox * src_shift).astype(np.float32, copy=False)
    map_y = (maps[1] - oy * src_shift).astype(np.float32, copy=False)
    return cv2.remap(color, map_x, map_y, cv2.INTER_LINEAR,
                     borderMode=cv2.BORDER_REPLICATE)


def main() -> None:
    p = argparse.ArgumentParser(prog="preview3d", description=__doc__)
    p.add_argument("input", help="packed .p3d.mp4 from transcode3d")
    p.add_argument("--camera", type=int, default=0, help="webcam index (default 0)")
    p.add_argument("--strength", type=float, default=2.5,
                   help="max parallax, %% of eye width (default 2.5)")
    p.add_argument("--scale", type=float, default=0.5,
                   help="display scale of eye video (default 0.5)")
    p.add_argument("--head-gain", type=float, default=3.0,
                   help="face offset amplifier; head moves are small in frame (default 3)")
    args = p.parse_args()

    convergence = 0.5
    sc = sidecar_path(args.input)
    if sc.is_file():
        convergence = float(json.loads(sc.read_text()).get("convergence", 0.5))

    video = cv2.VideoCapture(args.input)
    if not video.isOpened():
        raise SystemExit(f"can't open {args.input}")
    fps = video.get(cv2.CAP_PROP_FPS) or 24.0

    cam = cv2.VideoCapture(args.camera)
    cam.set(cv2.CAP_PROP_FRAME_WIDTH, 320)
    cam.set(cv2.CAP_PROP_FRAME_HEIGHT, 240)
    cam.set(cv2.CAP_PROP_BUFFERSIZE, 1)  # fresh frames, less tracking lag
    cam_w = int(cam.get(cv2.CAP_PROP_FRAME_WIDTH)) or 320
    cam_h = int(cam.get(cv2.CAP_PROP_FRAME_HEIGHT)) or 240
    detector = _load_detector((cam_w, cam_h)) if cam.isOpened() else None
    if detector is None:
        print("no webcam; mouse drives parallax")

    smoother = HeadSmoother()
    strength_pct = args.strength
    paused = False
    freeze_face = False
    mouse = [0.0, 0.0]

    win = "parallax3d preview"
    # GUI_NORMAL drops the Qt toolbar (broken tooltips on some themes)
    cv2.namedWindow(win, cv2.WINDOW_NORMAL | cv2.WINDOW_GUI_NORMAL)
    cv2.setMouseCallback(win, lambda e, x, y, f, _: mouse.__setitem__(
        slice(None), [x, y]) if e == cv2.EVENT_MOUSEMOVE else None)

    frame = None
    maps = None
    last = time.monotonic()
    while True:
        if not paused or frame is None:
            ok, raw = video.read()
            if not ok:
                video.set(cv2.CAP_PROP_POS_FRAMES, 0)  # loop the clip
                continue
            half = raw.shape[1] // 2
            scale = args.scale
            size = (int(half * scale), int(raw.shape[0] * scale))
            color = cv2.resize(raw[:, :half], size)
            depth = cv2.resize(raw[:, half:], size)[:, :, 0].astype(np.float32) / 255.0
            if maps is None or maps[0].shape != depth.shape:
                ys, xs = np.mgrid[0:size[1], 0:size[0]].astype(np.float32)
                maps = (xs, ys)
            frame = (color, depth)

        # Head offset: webcam face or mouse fallback
        hx, hy = 0.0, 0.0
        face_status = "MOUSE"
        inset = None
        if detector is not None and not freeze_face:
            face_status = "NO FACE"
            ok, shot = cam.read()
            if ok:
                # Drivers ignore requested capture size; YuNet must match frame
                h, w = shot.shape[:2]
                if (w, h) != (cam_w, cam_h):
                    cam_w, cam_h = w, h
                    detector.setInputSize((w, h))
                _, faces = detector.detect(shot)
                if faces is not None and len(faces):
                    f = max(faces, key=lambda f: f[2] * f[3])
                    cx = (f[0] + f[2] / 2) / cam_w * 2 - 1
                    cy = (f[1] + f[3] / 2) / cam_h * 2 - 1
                    # Mirror x; gain because head sweep is small in camera frame
                    hx = -float(cx) * args.head_gain
                    hy = float(cy) * args.head_gain
                    face_status = "FACE"
                    cv2.rectangle(shot, (int(f[0]), int(f[1])),
                                  (int(f[0] + f[2]), int(f[1] + f[3])), (0, 255, 0), 2)
                # PiP inset: shows what the tracker sees
                iw = 160
                ih = int(shot.shape[0] * iw / shot.shape[1])
                inset = cv2.resize(shot, (iw, ih))
        else:
            w = frame[0].shape[1]
            h = frame[0].shape[0]
            hx = -(mouse[0] / max(w, 1) * 2 - 1)
            hy = mouse[1] / max(h, 1) * 2 - 1
        sx, sy = smoother.update(np.clip(hx, -1, 1), np.clip(hy, -1, 1))

        color, depth = frame
        strength_px = strength_pct / 100.0 * color.shape[1]
        out = warp(color, depth, sx, sy, strength_px, convergence, maps)
        if inset is not None and out.shape[0] > inset.shape[0] + 10:
            ih, iw = inset.shape[:2]
            out[8:8 + ih, out.shape[1] - iw - 8:out.shape[1] - 8] = inset
        cv2.putText(out, f"[{face_status}] strength {strength_pct:.1f}%  "
                    f"head ({sx:+.2f},{sy:+.2f})  {'PAUSED' if paused else ''}",
                    (10, 24), cv2.FONT_HERSHEY_SIMPLEX, 0.6,
                    (0, 255, 0) if face_status == "FACE" else (0, 200, 255), 2)
        cv2.imshow(win, out)

        # Pace to video fps
        wait = max(1, int((1.0 / fps - (time.monotonic() - last)) * 1000))
        key = cv2.waitKey(wait) & 0xFF
        last = time.monotonic()
        if key == ord("q"):
            break
        if key == ord(" "):
            paused = not paused
        if key == ord("]"):
            strength_pct = min(12.0, strength_pct + 0.25)
        if key == ord("["):
            strength_pct = max(0.0, strength_pct - 0.25)
        if key == ord("f"):
            freeze_face = not freeze_face

    video.release()
    cam.release()
    cv2.destroyAllWindows()


if __name__ == "__main__":
    main()

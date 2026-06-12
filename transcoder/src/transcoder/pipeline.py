"""Transcode spine: ffmpeg raw pipes, two-pass (stats -> encode), backend dispatch.

Pass 1 samples disparity every few seconds -> global robust depth range.
Pass 2 streams every frame: split eyes -> disparity -> EMA -> depth luma,
packs [left color | depth] and pipes into hevc_nvenc with original audio.
"""

import subprocess
import sys
import time
from dataclasses import dataclass
from typing import Iterator

import cv2
import numpy as np

from transcoder.metadata import P3dMetadata, write_sidecar
from transcoder.normalize import DepthEncoder, DisparityStats
from transcoder.probe import SourceInfo, probe
from transcoder.scenes import DisparityEma, SceneCutDetector
from transcoder.stereo import make_backend
from transcoder.stereo.base import StereoBackend


@dataclass
class Geometry:
    crop: tuple[int, int, int, int]  # w, h, x, y on source frame
    out_w: int  # per-eye output width (square pixels)
    out_h: int
    infer_w: int  # disparity inference size, same aspect as out
    infer_h: int

    @property
    def packed_size(self) -> tuple[int, int]:
        return (self.out_w * 2, self.out_h)


def compute_geometry(info: SourceInfo, backend: StereoBackend,
                     sbs: str, max_width: int) -> Geometry:
    cw, ch, _, _ = info.crop
    if sbs == "auto":
        # Full SBS of 16:9 ~ 3.6 aspect; half SBS ~ 1.8. Split at 2.6.
        sbs = "full" if cw / ch > 2.6 else "half"
    # Half SBS: each squeezed eye (cw/2) displays at cw. Full: eye is cw/2 as-is.
    natural_w = cw if sbs == "half" else cw // 2
    if natural_w > max_width:
        scale = max_width / natural_w
        out_w = max_width & ~1
        out_h = int(round(ch * scale)) & ~1
    else:
        out_w, out_h = natural_w & ~1, ch & ~1
    infer_w = backend.infer_width
    infer_h = int(round(infer_w * out_h / out_w)) & ~1
    return Geometry(crop=info.crop, out_w=out_w, out_h=out_h,
                    infer_w=infer_w, infer_h=infer_h)


def read_frames(path: str, crop: tuple[int, int, int, int],
                extra_filter: str = "") -> Iterator[np.ndarray]:
    cw, ch, cx, cy = crop
    vf = f"crop={cw}:{ch}:{cx}:{cy}"
    if extra_filter:
        vf += f",{extra_filter}"
    proc = subprocess.Popen(
        ["ffmpeg", "-v", "error", "-i", path, "-vf", vf, "-an", "-sn",
         "-f", "rawvideo", "-pix_fmt", "bgr24", "pipe:1"],
        stdout=subprocess.PIPE,
    )
    frame_bytes = cw * ch * 3
    try:
        while True:
            buf = proc.stdout.read(frame_bytes)
            if len(buf) < frame_bytes:
                break
            yield np.frombuffer(buf, np.uint8).reshape(ch, cw, 3)
    finally:
        proc.stdout.close()
        proc.wait()


def split_eyes(frame: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    half = frame.shape[1] // 2
    return frame[:, :half], frame[:, half:]


def infer_disparity(backend: StereoBackend, frame: np.ndarray, geom: Geometry) -> np.ndarray:
    """Disparity for one SBS frame, as fraction of eye width (device-independent)."""
    left, right = split_eyes(frame)
    size = (geom.infer_w, geom.infer_h)
    li = cv2.resize(left, size, interpolation=cv2.INTER_AREA)
    ri = cv2.resize(right, size, interpolation=cv2.INTER_AREA)
    return backend.compute(li, ri) / geom.infer_w


def frame_disparities(frames: Iterator[np.ndarray], backend: StereoBackend,
                      geom: Geometry, stride: int) -> Iterator[tuple[np.ndarray, np.ndarray, bool]]:
    """Yields (frame, disparity_fraction, is_cut) per frame.

    stride > 1: disparity computed at key frames, lerped between. Depth fields
    drift slowly; halves/quarters ML cost. Scene cuts force a fresh key.
    """
    detector = SceneCutDetector()
    pending: list[np.ndarray] = []  # frames after current key, awaiting next key
    d_key: np.ndarray | None = None

    def flush_hold():
        # No next key (EOF/cut). Hold last key disparity.
        nonlocal pending
        for f in pending:
            yield f, d_key, False
        pending = []

    for frame in frames:
        cut = detector.is_cut(frame)
        if cut or d_key is None:
            yield from flush_hold()
            d_key = infer_disparity(backend, frame, geom)
            yield frame, d_key, True
            continue
        pending.append(frame)
        if len(pending) == stride:
            d_next = infer_disparity(backend, pending[-1], geom)
            n = len(pending)
            for i, f in enumerate(pending, start=1):
                t = i / n
                yield f, d_key * (1.0 - t) + d_next * t, False
            d_key = d_next
            pending = []
    yield from flush_hold()


def start_writer(output: str, source: str, info: SourceInfo, geom: Geometry,
                 cq: int, encoder: str, copy_audio: bool,
                 meta_json: str) -> subprocess.Popen:
    pw, ph = geom.packed_size
    cmd = ["ffmpeg", "-v", "error", "-y",
           "-f", "rawvideo", "-pix_fmt", "bgr24", "-s", f"{pw}x{ph}",
           "-r", info.fps_str, "-i", "pipe:0",
           "-i", source,
           "-map", "0:v"]
    if info.audio_codec:
        cmd += ["-map", "1:a:0"]
    if encoder == "nvenc":
        cmd += ["-c:v", "hevc_nvenc", "-preset", "p6", "-tune", "hq",
                "-rc", "vbr", "-cq", str(cq), "-b:v", "0",
                "-spatial-aq", "1", "-aq-strength", "8"]
    else:
        cmd += ["-c:v", "libx265", "-crf", str(cq), "-preset", "medium"]
    cmd += ["-tag:v", "hvc1", "-pix_fmt", "yuv420p"]
    if info.audio_codec:
        # SBS rips often carry DTS/TrueHD; tablets won't decode. AAC by default.
        cmd += ["-c:a", "copy"] if copy_audio else ["-c:a", "aac", "-b:a", "192k", "-ac", "2"]
    # No -shortest: it kills ffmpeg when audio ends, breaking the video pipe
    # mid-stream on clips with slightly mismatched stream durations.
    cmd += ["-movflags", "+faststart", "-metadata", f"comment={meta_json}", output]
    return subprocess.Popen(cmd, stdin=subprocess.PIPE, stderr=subprocess.PIPE)


def run(input_path: str, output_path: str, backend_name: str, *,
        depth_stride: int = 1, max_width: int = 1920, cq: int = 21,
        copy_audio: bool = False, sbs: str = "auto",
        sample_interval: float = 2.0, encoder: str = "nvenc") -> None:
    info = probe(input_path)
    backend = make_backend(backend_name)
    geom = compute_geometry(info, backend, sbs, max_width)
    cw, ch, cx, cy = info.crop
    print(f"source {info.width}x{info.height} @ {info.fps_str} fps, "
          f"{info.duration:.0f}s, audio={info.audio_codec}", file=sys.stderr)
    print(f"crop {cw}x{ch}+{cx}+{cy}, eye out {geom.out_w}x{geom.out_h}, "
          f"packed {geom.packed_size[0]}x{geom.packed_size[1]}, "
          f"infer {geom.infer_w}x{geom.infer_h}", file=sys.stderr)

    # Pass 1: global robust disparity range. Per-scene ranges cause depth pumping.
    print(f"pass 1: sampling disparity every {sample_interval}s", file=sys.stderr)
    stats = DisparityStats()
    n_samples = 0
    for frame in read_frames(input_path, info.crop, f"fps=1/{sample_interval}"):
        stats.add(infer_disparity(backend, frame, geom))
        n_samples += 1
    d_min, d_max, conv = stats.result()
    conv_norm = (conv - d_min) / (d_max - d_min)
    print(f"pass 1: {n_samples} samples, disparity [{d_min:.4f}, {d_max:.4f}] "
          f"of eye width, convergence {conv_norm:.3f}", file=sys.stderr)

    meta = P3dMetadata(
        format="P3D1", depth_position="right",
        d_min=d_min, d_max=d_max, convergence=conv_norm,
        depth_units="normalized_disparity_per_eye_width",
        fps=info.fps_str, eye_width=geom.out_w, eye_height=geom.out_h,
        source=input_path.rsplit("/", 1)[-1],
    )

    # Pass 2: full-rate streaming encode.
    depth_enc = DepthEncoder(d_min, d_max)
    ema = DisparityEma(backend.ema_alpha)
    writer = start_writer(output_path, input_path, info, geom, cq, encoder,
                          copy_audio, meta.to_json(compact=True))
    total = int(info.duration * float(info.fps))
    out_size = (geom.out_w, geom.out_h)
    t0 = time.monotonic()
    n = 0
    try:
        for frame, disp, cut in frame_disparities(
                read_frames(input_path, info.crop), backend, geom, depth_stride):
            disp = ema.update(disp, reset=cut)
            depth = depth_enc.encode(disp, out_size)
            left, _ = split_eyes(frame)
            color = cv2.resize(left, out_size, interpolation=cv2.INTER_LANCZOS4)
            packed = np.hstack([color, cv2.cvtColor(depth, cv2.COLOR_GRAY2BGR)])
            writer.stdin.write(packed.tobytes())
            n += 1
            if n % 100 == 0:
                fps = n / (time.monotonic() - t0)
                eta = (total - n) / fps if fps > 0 else 0
                print(f"pass 2: {n}/{total} frames, {fps:.1f} fps, "
                      f"ETA {eta/60:.1f} min", file=sys.stderr)
    except BrokenPipeError:
        pass  # writer died; surface its stderr below
    finally:
        try:
            writer.stdin.close()
        except BrokenPipeError:
            pass
        err = writer.stderr.read().decode(errors="replace")
        writer.wait()
    if writer.returncode != 0:
        raise RuntimeError(f"ffmpeg writer exited {writer.returncode}:\n{err}")

    path = write_sidecar(output_path, meta)
    print(f"done: {n} frames -> {output_path}, sidecar {path}", file=sys.stderr)

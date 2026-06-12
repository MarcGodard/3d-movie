"""Source inspection: ffprobe stream info + letterbox crop detection."""

import json
import re
import subprocess
from dataclasses import dataclass
from fractions import Fraction


@dataclass
class SourceInfo:
    width: int
    height: int
    fps: Fraction
    duration: float  # seconds
    audio_codec: str | None
    # Letterbox crop (w, h, x, y). Full frame when no bars found.
    crop: tuple[int, int, int, int]

    @property
    def fps_str(self) -> str:
        return f"{self.fps.numerator}/{self.fps.denominator}"


def probe(path: str) -> SourceInfo:
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-show_streams", "-show_format", "-of", "json", path],
        capture_output=True, text=True, check=True,
    ).stdout
    data = json.loads(out)
    video = next(s for s in data["streams"] if s["codec_type"] == "video")
    audio = next((s for s in data["streams"] if s["codec_type"] == "audio"), None)
    w, h = int(video["width"]), int(video["height"])
    fps = Fraction(video["r_frame_rate"])
    duration = float(data["format"]["duration"])
    crop = detect_crop(path, w, h, duration)
    return SourceInfo(
        width=w, height=h, fps=fps, duration=duration,
        audio_codec=audio["codec_name"] if audio else None,
        crop=crop,
    )


def detect_crop(path: str, width: int, height: int, duration: float) -> tuple[int, int, int, int]:
    """Sample cropdetect at several timestamps. Keep widest crop seen (safest).

    Bars carry zero disparity, poison depth normalization. Crop before processing.
    """
    crops: list[tuple[int, int, int, int]] = []
    samples = [duration * f for f in (0.1, 0.3, 0.5, 0.7, 0.9)]
    for t in samples:
        proc = subprocess.run(
            ["ffmpeg", "-ss", f"{t:.1f}", "-i", path, "-t", "2",
             "-vf", "cropdetect=limit=24:round=2", "-f", "null", "-"],
            capture_output=True, text=True,
        )
        m = re.findall(r"crop=(\d+):(\d+):(\d+):(\d+)", proc.stderr)
        if m:
            crops.append(tuple(int(v) for v in m[-1]))
    if not crops:
        return (width, height, 0, 0)
    # Union of detected regions. Dark scenes over-crop; take max extent.
    x = min(c[2] for c in crops)
    y = min(c[3] for c in crops)
    w = max(c[0] + c[2] for c in crops) - x
    h = max(c[1] + c[3] for c in crops) - y
    # Even dims for yuv420p.
    w, h, x, y = w & ~1, h & ~1, x & ~1, y & ~1
    return (w, h, x, y)

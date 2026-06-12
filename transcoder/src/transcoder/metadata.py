"""P3D1 sidecar metadata. Authoritative copy: <output>.p3d.json. Backup: mp4 comment."""

import json
from dataclasses import asdict, dataclass
from pathlib import Path


@dataclass
class P3dMetadata:
    format: str  # "P3D1"
    depth_position: str  # "right"
    d_min: float
    d_max: float
    convergence: float  # normalized [0,1] depth value at screen plane
    depth_units: str  # "normalized_disparity_per_eye_width"
    fps: str  # "24000/1001"
    eye_width: int
    eye_height: int
    source: str

    def to_json(self, compact: bool = False) -> str:
        if compact:
            return json.dumps(asdict(self), separators=(",", ":"))
        return json.dumps(asdict(self), indent=2)

    @staticmethod
    def from_json(text: str) -> "P3dMetadata":
        return P3dMetadata(**json.loads(text))


def sidecar_path(output: str | Path) -> Path:
    return Path(output).with_suffix(".p3d.json")


def write_sidecar(output: str | Path, meta: P3dMetadata) -> Path:
    p = sidecar_path(output)
    p.write_text(meta.to_json())
    return p

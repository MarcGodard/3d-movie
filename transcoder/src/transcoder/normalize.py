"""Disparity -> 8-bit depth luma. Global robust range, dither, pre-blur."""

import cv2
import numpy as np

# Limited-range luma. App maps 16->far, 235->near.
Y_MIN, Y_MAX = 16, 235


class DisparityStats:
    """Pass-1 accumulator. Pools subsampled disparity values for robust range."""

    def __init__(self) -> None:
        self._samples: list[np.ndarray] = []

    def add(self, disp: np.ndarray) -> None:
        self._samples.append(disp[::8, ::8].ravel().copy())

    def result(self) -> tuple[float, float, float]:
        """Returns (d_min, d_max, convergence). Robust p1..p99.5, median."""
        pool = np.concatenate(self._samples)
        d_min, conv, d_max = np.percentile(pool, [1.0, 50.0, 99.5])
        if d_max - d_min < 1e-3:  # flat movie / 2D source guard
            d_max = d_min + 1.0
        return float(d_min), float(d_max), float(conv)


class DepthEncoder:
    def __init__(self, d_min: float, d_max: float, rng_seed: int = 1234) -> None:
        self.d_min = d_min
        self.d_max = d_max
        self._rng = np.random.default_rng(rng_seed)

    def encode(self, disp: np.ndarray, out_size: tuple[int, int]) -> np.ndarray:
        """disp float32 (infer res) -> uint8 gray at out_size (w, h).

        Near = bright. Gaussian pre-blur softens disocclusion edges + banding.
        Triangular dither hides 8-bit steps after GPU bilinear filtering.
        """
        norm = (disp - self.d_min) / (self.d_max - self.d_min)
        np.clip(norm, 0.0, 1.0, out=norm)
        norm = cv2.resize(norm, out_size, interpolation=cv2.INTER_LINEAR)
        norm = cv2.GaussianBlur(norm, (0, 0), sigmaX=1.5)
        y = Y_MIN + norm * (Y_MAX - Y_MIN)
        dither = self._rng.random(y.shape, dtype=np.float32) - self._rng.random(y.shape, dtype=np.float32)
        return np.clip(y + dither, 0, 255).astype(np.uint8)

"""Stereo backend interface."""

from abc import ABC, abstractmethod

import numpy as np


class StereoBackend(ABC):
    """Computes disparity for left eye. Input: BGR uint8 left/right, same shape.

    Output: float32 disparity, left-image pixel shift in input-width units.
    Positive = nearer than convergence-at-infinity. Negative allowed (behind screen).
    """

    # Backend's preferred inference width. Pipeline scales eyes down to this,
    # scales disparity values back up. Disparity is smooth; upsample is cheap.
    infer_width: int = 736

    # Temporal EMA strength. Noisier backend -> lower alpha (more smoothing).
    ema_alpha: float = 0.25

    @abstractmethod
    def compute(self, left: np.ndarray, right: np.ndarray) -> np.ndarray: ...

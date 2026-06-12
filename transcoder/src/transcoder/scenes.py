"""Scene-cut detection. Gates temporal EMA reset for both backends."""

import cv2
import numpy as np

# Mean-abs-diff of downscaled luma above this = hard cut.
CUT_THRESHOLD = 18.0
_PROBE_SIZE = (96, 54)


class SceneCutDetector:
    def __init__(self, threshold: float = CUT_THRESHOLD) -> None:
        self.threshold = threshold
        self._prev: np.ndarray | None = None

    def is_cut(self, frame_bgr: np.ndarray) -> bool:
        small = cv2.cvtColor(cv2.resize(frame_bgr, _PROBE_SIZE), cv2.COLOR_BGR2GRAY)
        small = small.astype(np.float32)
        prev, self._prev = self._prev, small
        if prev is None:
            return True  # first frame: fresh state
        return float(np.abs(small - prev).mean()) > self.threshold


class DisparityEma:
    """Temporal EMA on disparity field. Reset at cuts to avoid ghost depth."""

    def __init__(self, alpha: float) -> None:
        self.alpha = alpha
        self._state: np.ndarray | None = None

    def update(self, disp: np.ndarray, reset: bool) -> np.ndarray:
        if reset or self._state is None or self._state.shape != disp.shape:
            self._state = disp.copy()
        else:
            self._state += self.alpha * (disp - self._state)
        return self._state

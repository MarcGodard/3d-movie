"""Classical backend: StereoSGBM + WLS filter. Fast preview-quality depth."""

import cv2
import numpy as np

from transcoder.stereo.base import StereoBackend

# SBS movies converge on the screen plane: content sits both in front
# (crossed, negative xR-xL) and behind. Negative minDisparity covers pop-out.
MIN_DISP = -64
NUM_DISP = 176  # multiple of 16
BLOCK = 5


class SgbmBackend(StereoBackend):
    infer_width = 736
    ema_alpha = 0.2  # SGBM noisy, smooth harder

    def __init__(self) -> None:
        self.left_matcher = cv2.StereoSGBM_create(
            minDisparity=MIN_DISP,
            numDisparities=NUM_DISP,
            blockSize=BLOCK,
            P1=8 * BLOCK * BLOCK,
            P2=32 * BLOCK * BLOCK,
            disp12MaxDiff=1,
            uniquenessRatio=10,
            speckleWindowSize=100,
            speckleRange=2,
            preFilterCap=63,
            mode=cv2.STEREO_SGBM_MODE_SGBM_3WAY,
        )
        self.right_matcher = cv2.ximgproc.createRightMatcher(self.left_matcher)
        self.wls = cv2.ximgproc.createDisparityWLSFilter(self.left_matcher)
        self.wls.setLambda(8000.0)
        self.wls.setSigmaColor(1.2)

    def compute(self, left: np.ndarray, right: np.ndarray) -> np.ndarray:
        gl = cv2.cvtColor(left, cv2.COLOR_BGR2GRAY)
        gr = cv2.cvtColor(right, cv2.COLOR_BGR2GRAY)
        dl = self.left_matcher.compute(gl, gr)
        dr = self.right_matcher.compute(gr, gl)
        # WLS uses right disparity as left-right consistency signal.
        filtered = self.wls.filter(dl, left, disparity_map_right=dr)
        disp = filtered.astype(np.float32) / 16.0  # SGBM fixed-point, 4 frac bits
        # Invalid pixels come back near minDisparity-1. Fill from background side.
        invalid = disp < (MIN_DISP - 0.5)
        if invalid.any():
            disp = _fill_invalid(disp, invalid)
        return cv2.medianBlur(disp, 5)


def _fill_invalid(disp: np.ndarray, invalid: np.ndarray) -> np.ndarray:
    """Replace invalid pixels with row-wise background (low) disparity fill."""
    valid = disp.copy()
    valid[invalid] = np.nan
    # Row medians of valid values as crude background estimate.
    row_med = np.nanmedian(np.where(invalid, np.nan, disp), axis=1)
    row_med = np.nan_to_num(row_med, nan=float(np.nanmedian(valid)) if not np.all(invalid) else 0.0)
    out = disp.copy()
    out[invalid] = np.take(row_med, np.nonzero(invalid)[0])
    return out

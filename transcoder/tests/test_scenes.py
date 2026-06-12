import numpy as np

from transcoder.scenes import DisparityEma, SceneCutDetector


def _frame(value: int) -> np.ndarray:
    return np.full((100, 200, 3), value, np.uint8)


def test_first_frame_is_cut():
    det = SceneCutDetector()
    assert det.is_cut(_frame(100))


def test_static_scene_no_cut():
    det = SceneCutDetector()
    det.is_cut(_frame(100))
    assert not det.is_cut(_frame(102))  # tiny drift


def test_hard_cut_detected():
    det = SceneCutDetector()
    det.is_cut(_frame(30))
    assert det.is_cut(_frame(220))


def test_ema_smooths():
    ema = DisparityEma(alpha=0.5)
    a = np.zeros((4, 4), np.float32)
    b = np.ones((4, 4), np.float32)
    ema.update(a, reset=True)
    out = ema.update(b, reset=False)
    assert np.allclose(out, 0.5)


def test_ema_reset_on_cut():
    ema = DisparityEma(alpha=0.1)
    ema.update(np.zeros((4, 4), np.float32), reset=True)
    out = ema.update(np.ones((4, 4), np.float32), reset=True)
    assert np.allclose(out, 1.0)  # no ghost of previous scene

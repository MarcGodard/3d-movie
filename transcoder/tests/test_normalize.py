import numpy as np

from transcoder.normalize import DepthEncoder, DisparityStats, Y_MAX, Y_MIN


def test_stats_robust_range():
    stats = DisparityStats()
    rng = np.random.default_rng(7)
    # Bulk in [-0.01, 0.01], ~0.2% wild outliers (below the p1/p99.5 tails)
    for _ in range(20):
        d = rng.uniform(-0.01, 0.01, (64, 64)).astype(np.float32)
        idx = rng.integers(0, d.size, 8)
        d.flat[idx[:4]] = 5.0
        d.flat[idx[4:]] = -5.0
        stats.add(d)
    d_min, d_max, conv = stats.result()
    assert -0.012 < d_min < 0.0
    assert 0.0 < d_max < 0.012
    assert d_min < conv < d_max


def test_stats_flat_source_guard():
    stats = DisparityStats()
    stats.add(np.zeros((64, 64), np.float32))
    d_min, d_max, _ = stats.result()
    assert d_max - d_min >= 1.0  # no div-by-zero downstream


def test_encode_near_is_bright():
    enc = DepthEncoder(d_min=-1.0, d_max=1.0)
    near = np.full((32, 32), 1.0, np.float32)
    far = np.full((32, 32), -1.0, np.float32)
    near_y = enc.encode(near, (64, 64))
    far_y = enc.encode(far, (64, 64))
    assert near_y.mean() > 200
    assert far_y.mean() < 50


def test_encode_limited_range_with_dither_headroom():
    enc = DepthEncoder(d_min=0.0, d_max=1.0)
    mid = enc.encode(np.full((32, 32), 0.5, np.float32), (64, 64))
    assert mid.dtype == np.uint8
    # Dither is sub-LSB; values hug the midpoint of [Y_MIN, Y_MAX]
    target = (Y_MIN + Y_MAX) / 2
    assert abs(float(mid.mean()) - target) < 2.0
    assert mid.std() < 2.0


def test_encode_clips_out_of_range():
    enc = DepthEncoder(d_min=0.0, d_max=1.0)
    wild = enc.encode(np.array([[-10.0, 10.0]] * 8, np.float32), (8, 8))
    assert wild.min() >= Y_MIN - 1
    assert wild.max() <= Y_MAX + 1

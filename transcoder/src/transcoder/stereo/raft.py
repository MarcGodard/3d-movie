"""ML backend: RAFT-Stereo, Middlebury checkpoint (authors' in-the-wild pick).

Pascal notes: FP32 only (GP104 FP16 rate 1/64), torch pinned 2.7.1 (2.8 dropped
sm_61). corr_implementation=reg = pure PyTorch ops, no custom CUDA build.
"""

import sys
from argparse import Namespace
from pathlib import Path

import numpy as np

from transcoder.stereo.base import StereoBackend

_ROOT = Path(__file__).resolve().parents[3]  # transcoder/
_REPO = _ROOT / "third_party" / "RAFT-Stereo"
_CKPT = _ROOT / "models" / "raftstereo-middlebury.pth"

# Middlebury checkpoint architecture (matches training args in repo README).
_MODEL_ARGS = Namespace(
    hidden_dims=[128, 128, 128],
    corr_implementation="reg",
    shared_backbone=False,
    corr_levels=4,
    corr_radius=4,
    n_downsample=2,
    context_norm="batch",
    slow_fast_gru=False,
    n_gru_layers=3,
    mixed_precision=False,
)


class RaftBackend(StereoBackend):
    infer_width = 736
    ema_alpha = 0.3

    def __init__(self, valid_iters: int = 16) -> None:
        if not _REPO.is_dir():
            raise RuntimeError("RAFT-Stereo missing. Run transcoder/scripts/setup.sh")
        if not _CKPT.is_file():
            raise RuntimeError(f"weights missing: {_CKPT}. Run transcoder/scripts/setup.sh")
        try:
            import torch
        except ImportError as e:
            raise RuntimeError("torch missing. Install with: uv sync --extra ml") from e
        if not torch.cuda.is_available():
            raise RuntimeError("CUDA unavailable. RAFT backend needs an NVIDIA GPU; use --fast")
        # Functional check beats arch-list inspection: wheels cover Pascal via
        # sm_60 PTX JIT, which get_arch_list() doesn't show.
        try:
            (torch.randn(8, 8, device="cuda") @ torch.randn(8, 8, device="cuda")).sum().item()
        except Exception as e:
            raise RuntimeError(
                f"CUDA kernels won't run on {torch.cuda.get_device_name()}. "
                "Pascal needs torch<=2.7.x from the cu126 index; check uv.lock pin."
            ) from e

        # Repo modules import via "core." prefix; root on path covers both
        sys.path.insert(0, str(_REPO))
        from core.raft_stereo import RAFTStereo  # noqa: E402
        from core.utils.utils import InputPadder  # noqa: E402

        self._torch = torch
        self._padder_cls = InputPadder
        self.valid_iters = valid_iters

        model = torch.nn.DataParallel(RAFTStereo(_MODEL_ARGS), device_ids=[0])
        model.load_state_dict(torch.load(_CKPT, map_location="cuda", weights_only=True))
        self.model = model.module.to("cuda").eval()

    # RAFT-Stereo is one-signed: flow <= 0, disparity >= 0 (rectified-rig
    # convention). Movie SBS converges on screen plane -> mixed-sign disparity.
    # Crop-shift both eyes so all disparities go positive, subtract after.
    # Crop (not border-pad): synthetic edge bands derail the global GRU.
    _SHIFT_FRAC = 0.065  # ~48px at 736; covers ~6.5% eye width of behind-screen depth

    def compute(self, left: np.ndarray, right: np.ndarray) -> np.ndarray:
        torch = self._torch
        shift = max(16, int(left.shape[1] * self._SHIFT_FRAC))
        # x in cropped-left = x_l; x in cropped-right = x_l - (d + shift)
        l_np = left[:, :-shift, ::-1]  # left eye drops right edge
        r_np = right[:, shift:, ::-1]  # right eye drops left edge
        with torch.no_grad():
            # Model wants raw 0-255 RGB floats, NCHW
            l = torch.from_numpy(l_np.copy()).permute(2, 0, 1).float()[None].cuda()
            r = torch.from_numpy(r_np.copy()).permute(2, 0, 1).float()[None].cuda()
            padder = self._padder_cls(l.shape, divis_by=32)
            l, r = padder.pad(l, r)
            _, flow = self.model(l, r, iters=self.valid_iters, test_mode=True)
            flow = padder.unpad(flow)
            disp = (-flow).squeeze().cpu().numpy().astype(np.float32) - shift
        # Disparity aligns with left-eye x in [0, W-shift). Repad right edge;
        # depth is smooth, edge strip replicates fine.
        return np.pad(disp, ((0, 0), (0, shift)), mode="edge")

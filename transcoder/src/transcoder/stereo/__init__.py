from transcoder.stereo.base import StereoBackend


def make_backend(name: str) -> StereoBackend:
    if name == "sgbm":
        from transcoder.stereo.sgbm import SgbmBackend
        return SgbmBackend()
    if name == "raft":
        from transcoder.stereo.raft import RaftBackend
        return RaftBackend()
    raise ValueError(f"unknown backend: {name}")

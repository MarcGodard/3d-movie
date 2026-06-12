from transcoder.metadata import P3dMetadata, sidecar_path


def _meta() -> P3dMetadata:
    return P3dMetadata(
        format="P3D1", depth_position="right", d_min=-0.01, d_max=0.02,
        convergence=0.3, depth_units="normalized_disparity_per_eye_width",
        fps="24000/1001", eye_width=1920, eye_height=1000, source="x.mkv",
    )


def test_round_trip():
    m = _meta()
    assert P3dMetadata.from_json(m.to_json()) == m
    assert P3dMetadata.from_json(m.to_json(compact=True)) == m


def test_sidecar_path():
    assert str(sidecar_path("/a/b/movie.p3d.mp4")).endswith("movie.p3d.p3d.json")
    assert str(sidecar_path("out.mp4")) == "out.p3d.json"

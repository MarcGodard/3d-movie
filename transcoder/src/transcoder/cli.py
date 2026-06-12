"""CLI entry. Example:

  transcode3d media/clip60.mkv media/clip60.p3d.mp4 --fast
"""

import argparse

from transcoder.pipeline import run


def main() -> None:
    p = argparse.ArgumentParser(
        prog="transcode3d",
        description="SBS 3D movie -> packed [color|depth] P3D1 video for parallax playback",
    )
    p.add_argument("input", help="SBS 3D source video")
    p.add_argument("output", help="output .mp4 path")
    p.add_argument("--backend", choices=["raft", "sgbm"], default="raft",
                   help="depth backend (default: raft, best quality)")
    p.add_argument("--fast", action="store_true",
                   help="shortcut for --backend sgbm (CPU, preview quality)")
    p.add_argument("--depth-stride", type=int, default=1, metavar="N",
                   help="compute depth every Nth frame, lerp between (default 1)")
    p.add_argument("--max-width", type=int, default=1920,
                   help="per-eye width cap; packed frame stays decoder-safe (default 1920)")
    p.add_argument("--cq", type=int, default=21, help="encoder quality (default 21)")
    p.add_argument("--copy-audio", action="store_true",
                   help="copy audio stream instead of AAC transcode (source must be tablet-decodable)")
    p.add_argument("--sbs", choices=["auto", "half", "full"], default="auto",
                   help="source SBS packing (default: auto by aspect)")
    p.add_argument("--sample-interval", type=float, default=2.0,
                   help="pass-1 stats sampling interval seconds (default 2)")
    p.add_argument("--encoder", choices=["nvenc", "x265"], default="nvenc",
                   help="video encoder (default nvenc; x265 = CPU fallback)")
    args = p.parse_args()

    backend = "sgbm" if args.fast else args.backend
    run(args.input, args.output, backend,
        depth_stride=args.depth_stride, max_width=args.max_width, cq=args.cq,
        copy_audio=args.copy_audio, sbs=args.sbs,
        sample_interval=args.sample_interval, encoder=args.encoder)


if __name__ == "__main__":
    main()

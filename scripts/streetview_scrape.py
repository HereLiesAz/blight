"""Standalone Street View scraper for ad-hoc use.

Usage:
  python scripts/streetview_scrape.py --lat 29.964 --lng -90.007 --out cache/sample.jpg
"""
from __future__ import annotations
import argparse, pathlib, sys
from scripts.lib.streetview import ScraperSession, PanoramaNotFound

def main(argv=None) -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--lat", type=float, required=True)
    p.add_argument("--lng", type=float, required=True)
    p.add_argument("--zoom", type=int, default=0)
    p.add_argument("--out", type=pathlib.Path, required=True)
    p.add_argument("--min-interval", type=float, default=3.0)
    args = p.parse_args(argv)

    sess = ScraperSession(min_interval_s=args.min_interval)
    try:
        panoid = sess.lookup_panoid(args.lat, args.lng)
    except PanoramaNotFound as e:
        print(f"no panorama: {e}", file=sys.stderr); return 2

    img = sess.fetch_tile(panoid, zoom=args.zoom)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_bytes(img)
    print(f"panoid={panoid} bytes={len(img)} → {args.out}")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())

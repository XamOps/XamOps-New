#!/usr/bin/env python3
"""
batch-file-processor.py
Walks an input directory for files matching a glob pattern, computes a SHA-256
checksum and size for each, and writes a manifest to the output directory.

Parameters:
  {{INPUT_DIR}}    - Directory containing files to process
  {{OUTPUT_DIR}}   - Directory where the manifest is written
  {{FILE_PATTERN}} - Glob pattern to match files (e.g. *.txt)
"""
import argparse
import hashlib
import json
import os
import sys
from glob import glob

INPUT_DIR = "{{INPUT_DIR}}"
OUTPUT_DIR = "{{OUTPUT_DIR}}"
FILE_PATTERN = "{{FILE_PATTERN}}"


def parse_args():
    parser = argparse.ArgumentParser(description="Batch process files into a manifest.")
    parser.add_argument("--input-dir", default=INPUT_DIR, help="Input directory.")
    parser.add_argument("--output-dir", default=OUTPUT_DIR, help="Output directory.")
    parser.add_argument("--file-pattern", default=FILE_PATTERN, help="Glob pattern.")
    return parser.parse_args()


def sha256_of(path):
    digest = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    args = parse_args()
    if not os.path.isdir(args.input_dir):
        print(f"ERROR: Input directory not found: {args.input_dir}", file=sys.stderr)
        return 1
    os.makedirs(args.output_dir, exist_ok=True)
    matches = glob(os.path.join(args.input_dir, args.file_pattern))
    manifest = []
    try:
        for path in matches:
            if os.path.isfile(path):
                manifest.append({
                    "file": os.path.basename(path),
                    "size_bytes": os.path.getsize(path),
                    "sha256": sha256_of(path),
                })
        out_path = os.path.join(args.output_dir, "manifest.json")
        with open(out_path, "w", encoding="utf-8") as out:
            json.dump(manifest, out, indent=2)
        print(f"Processed {len(manifest)} file(s). Manifest -> {out_path}")
        return 0
    except OSError as exc:
        print(f"ERROR: Failed to process files: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())

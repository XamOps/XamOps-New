#!/usr/bin/env python3
"""
gcp-cloud-storage-sync.py
Synchronizes objects from a source GCS bucket to a destination bucket,
copying any object missing or changed in the destination.

Parameters:
  {{PROJECT_ID}}    - GCP project ID
  {{SOURCE_BUCKET}} - Source Cloud Storage bucket name
  {{DEST_BUCKET}}   - Destination Cloud Storage bucket name
"""
import argparse
import sys

try:
    from google.cloud import storage
    from google.api_core.exceptions import GoogleAPIError
except ImportError:
    print("ERROR: google-cloud-storage required. Install 'google-cloud-storage'.",
          file=sys.stderr)
    sys.exit(1)

PROJECT_ID = "{{PROJECT_ID}}"
SOURCE_BUCKET = "{{SOURCE_BUCKET}}"
DEST_BUCKET = "{{DEST_BUCKET}}"


def parse_args():
    parser = argparse.ArgumentParser(description="Sync objects between GCS buckets.")
    parser.add_argument("--project-id", default=PROJECT_ID, help="GCP project ID.")
    parser.add_argument("--source-bucket", default=SOURCE_BUCKET, help="Source bucket.")
    parser.add_argument("--dest-bucket", default=DEST_BUCKET, help="Destination bucket.")
    parser.add_argument("--dry-run", action="store_true",
                        help="Preview copies without writing.")
    return parser.parse_args()


def main():
    args = parse_args()
    try:
        client = storage.Client(project=args.project_id)
        src = client.bucket(args.source_bucket)
        dst = client.bucket(args.dest_bucket)
        dest_index = {b.name: b.md5_hash for b in client.list_blobs(args.dest_bucket)}
        copied = 0
        for blob in client.list_blobs(args.source_bucket):
            if dest_index.get(blob.name) == blob.md5_hash:
                continue
            if args.dry_run:
                print(f"[dry-run] would copy: {blob.name}")
            else:
                src.copy_blob(blob, dst, blob.name)
                print(f"Copied: {blob.name}")
            copied += 1
        verb = "would be copied" if args.dry_run else "copied"
        print(f"\n{copied} object(s) {verb} from "
              f"{args.source_bucket} to {args.dest_bucket}.")
        return 0
    except GoogleAPIError as exc:
        print(f"ERROR: GCP call failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())

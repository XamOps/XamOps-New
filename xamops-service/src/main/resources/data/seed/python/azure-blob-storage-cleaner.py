#!/usr/bin/env python3
"""
azure-blob-storage-cleaner.py
Deletes blobs in an Azure Storage container that are older than a retention
window. Supports a dry-run mode to preview deletions.

Parameters:
  {{STORAGE_ACCOUNT}} - Azure Storage account name
  {{CONTAINER_NAME}}  - Blob container to clean
  {{RETENTION_DAYS}}  - Delete blobs older than this many days
"""
import argparse
import sys
from datetime import datetime, timedelta, timezone

try:
    from azure.identity import DefaultAzureCredential
    from azure.storage.blob import BlobServiceClient
    from azure.core.exceptions import AzureError
except ImportError:
    print("ERROR: Azure SDK required. Install 'azure-identity azure-storage-blob'.",
          file=sys.stderr)
    sys.exit(1)

STORAGE_ACCOUNT = "{{STORAGE_ACCOUNT}}"
CONTAINER_NAME = "{{CONTAINER_NAME}}"
RETENTION_DAYS = "{{RETENTION_DAYS}}"


def parse_args():
    parser = argparse.ArgumentParser(description="Clean old Azure blobs.")
    parser.add_argument("--storage-account", default=STORAGE_ACCOUNT,
                        help="Storage account name.")
    parser.add_argument("--container", default=CONTAINER_NAME, help="Container name.")
    parser.add_argument("--retention-days", type=int, default=int(RETENTION_DAYS),
                        help="Retention window in days.")
    parser.add_argument("--dry-run", action="store_true",
                        help="Preview deletions without deleting.")
    return parser.parse_args()


def main():
    args = parse_args()
    cutoff = datetime.now(timezone.utc) - timedelta(days=args.retention_days)
    account_url = f"https://{args.storage_account}.blob.core.windows.net"
    try:
        credential = DefaultAzureCredential()
        service = BlobServiceClient(account_url=account_url, credential=credential)
        container = service.get_container_client(args.container)
        deleted = 0
        for blob in container.list_blobs():
            if blob.last_modified and blob.last_modified < cutoff:
                if args.dry_run:
                    print(f"[dry-run] would delete: {blob.name}")
                else:
                    container.delete_blob(blob.name)
                    print(f"Deleted: {blob.name}")
                deleted += 1
        action = "would be deleted" if args.dry_run else "deleted"
        print(f"\n{deleted} blob(s) {action} (older than {args.retention_days} days).")
        return 0
    except AzureError as exc:
        print(f"ERROR: Azure call failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""
rds-snapshot-manager.py
Creates a manual RDS snapshot for a DB instance and prunes manual snapshots
older than a retention window.

Parameters:
  {{AWS_REGION}}     - AWS region, e.g. us-east-1
  {{DB_INSTANCE_ID}} - RDS DB instance identifier
  {{RETENTION_DAYS}} - Days to keep manual snapshots before deletion
"""
import argparse
import sys
from datetime import datetime, timedelta, timezone

try:
    import boto3
    from botocore.exceptions import BotoCoreError, ClientError
except ImportError:
    print("ERROR: boto3 is required. Install with 'pip install boto3'.", file=sys.stderr)
    sys.exit(1)

AWS_REGION = "{{AWS_REGION}}"
DB_INSTANCE_ID = "{{DB_INSTANCE_ID}}"
RETENTION_DAYS = "{{RETENTION_DAYS}}"


def parse_args():
    parser = argparse.ArgumentParser(description="Create and prune RDS snapshots.")
    parser.add_argument("--region", default=AWS_REGION, help="AWS region.")
    parser.add_argument("--db-instance-id", default=DB_INSTANCE_ID,
                        help="RDS DB instance identifier.")
    parser.add_argument("--retention-days", type=int, default=int(RETENTION_DAYS),
                        help="Retention window in days.")
    return parser.parse_args()


def create_snapshot(rds, db_id):
    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    snap_id = f"{db_id}-{stamp}"
    rds.create_db_snapshot(DBSnapshotIdentifier=snap_id, DBInstanceIdentifier=db_id)
    print(f"Created snapshot: {snap_id}")


def prune_snapshots(rds, db_id, retention_days):
    cutoff = datetime.now(timezone.utc) - timedelta(days=retention_days)
    resp = rds.describe_db_snapshots(DBInstanceIdentifier=db_id, SnapshotType="manual")
    pruned = 0
    for snap in resp.get("DBSnapshots", []):
        created = snap.get("SnapshotCreateTime")
        if created and created < cutoff:
            rds.delete_db_snapshot(DBSnapshotIdentifier=snap["DBSnapshotIdentifier"])
            print(f"Deleted old snapshot: {snap['DBSnapshotIdentifier']}")
            pruned += 1
    print(f"Pruned {pruned} snapshot(s) older than {retention_days} days.")


def main():
    args = parse_args()
    try:
        rds = boto3.client("rds", region_name=args.region)
        create_snapshot(rds, args.db_instance_id)
        prune_snapshots(rds, args.db_instance_id, args.retention_days)
        return 0
    except (BotoCoreError, ClientError) as exc:
        print(f"ERROR: AWS call failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())

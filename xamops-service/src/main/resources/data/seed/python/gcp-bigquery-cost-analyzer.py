#!/usr/bin/env python3
"""
gcp-bigquery-cost-analyzer.py
Analyzes BigQuery query costs over a trailing period by reading the
INFORMATION_SCHEMA.JOBS view and estimating cost from bytes billed.

Parameters:
  {{PROJECT_ID}} - GCP project ID
  {{DAYS_BACK}}  - Number of trailing days to analyze
"""
import argparse
import sys

try:
    from google.cloud import bigquery
    from google.api_core.exceptions import GoogleAPIError
except ImportError:
    print("ERROR: google-cloud-bigquery required. Install 'google-cloud-bigquery'.",
          file=sys.stderr)
    sys.exit(1)

PROJECT_ID = "{{PROJECT_ID}}"
DAYS_BACK = "{{DAYS_BACK}}"

# On-demand BigQuery pricing: ~$5 per TiB scanned.
COST_PER_BYTE = 5.0 / (1024 ** 4)


def parse_args():
    parser = argparse.ArgumentParser(description="Analyze BigQuery query cost.")
    parser.add_argument("--project-id", default=PROJECT_ID, help="GCP project ID.")
    parser.add_argument("--days-back", type=int, default=int(DAYS_BACK),
                        help="Trailing days to analyze.")
    return parser.parse_args()


def main():
    args = parse_args()
    query = """
        SELECT user_email,
               COUNT(*) AS job_count,
               SUM(total_bytes_billed) AS bytes_billed
        FROM `region-us`.INFORMATION_SCHEMA.JOBS
        WHERE creation_time >= TIMESTAMP_SUB(CURRENT_TIMESTAMP(),
                                             INTERVAL @days DAY)
          AND job_type = 'QUERY'
        GROUP BY user_email
        ORDER BY bytes_billed DESC
    """
    try:
        client = bigquery.Client(project=args.project_id)
        job_config = bigquery.QueryJobConfig(
            query_parameters=[
                bigquery.ScalarQueryParameter("days", "INT64", args.days_back)
            ]
        )
        rows = client.query(query, job_config=job_config).result()
        print(f"BigQuery cost (last {args.days_back} days), project {args.project_id}:")
        total_cost = 0.0
        for row in rows:
            cost = (row["bytes_billed"] or 0) * COST_PER_BYTE
            total_cost += cost
            print(f"  {row['user_email']:40s} jobs={row['job_count']:<6} "
                  f"${cost:,.2f}")
        print(f"  {'TOTAL':40s} ${total_cost:,.2f}")
        return 0
    except GoogleAPIError as exc:
        print(f"ERROR: GCP call failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())

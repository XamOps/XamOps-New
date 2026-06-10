#!/usr/bin/env python3
"""
ec2-cost-report.py
Produces an EC2/compute cost report using the AWS Cost Explorer API for a
trailing number of days, grouped by service.

Parameters:
  {{AWS_REGION}} - AWS region for the Cost Explorer client (e.g. us-east-1)
  {{DAYS_BACK}}  - Number of days to include in the report
"""
import argparse
import sys
from datetime import date, timedelta

try:
    import boto3
    from botocore.exceptions import BotoCoreError, ClientError
except ImportError:
    print("ERROR: boto3 is required. Install with 'pip install boto3'.", file=sys.stderr)
    sys.exit(1)

AWS_REGION = "{{AWS_REGION}}"
DAYS_BACK = "{{DAYS_BACK}}"


def parse_args():
    parser = argparse.ArgumentParser(description="Report AWS cost by service.")
    parser.add_argument("--region", default=AWS_REGION, help="AWS region.")
    parser.add_argument("--days-back", type=int, default=int(DAYS_BACK),
                        help="Trailing days to report.")
    return parser.parse_args()


def main():
    args = parse_args()
    end = date.today()
    start = end - timedelta(days=args.days_back)
    try:
        ce = boto3.client("ce", region_name=args.region)
        resp = ce.get_cost_and_usage(
            TimePeriod={"Start": start.isoformat(), "End": end.isoformat()},
            Granularity="MONTHLY",
            Metrics=["UnblendedCost"],
            GroupBy=[{"Type": "DIMENSION", "Key": "SERVICE"}],
        )
        print(f"Cost report {start} -> {end}")
        grand_total = 0.0
        for result in resp.get("ResultsByTime", []):
            for group in result.get("Groups", []):
                service = group["Keys"][0]
                amount = float(group["Metrics"]["UnblendedCost"]["Amount"])
                grand_total += amount
                if amount > 0:
                    print(f"  {service:40s} ${amount:,.2f}")
        print(f"  {'TOTAL':40s} ${grand_total:,.2f}")
        return 0
    except (BotoCoreError, ClientError) as exc:
        print(f"ERROR: AWS call failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())

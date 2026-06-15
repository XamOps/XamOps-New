#!/usr/bin/env python3
"""
azure-cost-reporter.py
Queries Azure Cost Management for actual cost over a trailing period for a
subscription and prints a daily cost summary.

Parameters:
  {{SUBSCRIPTION_ID}} - Azure subscription ID
  {{DAYS_BACK}}       - Number of trailing days to include
"""
import argparse
import sys
from datetime import date, timedelta

try:
    from azure.identity import DefaultAzureCredential
    from azure.mgmt.costmanagement import CostManagementClient
    from azure.core.exceptions import AzureError
except ImportError:
    print("ERROR: Azure SDK required. Install 'azure-identity azure-mgmt-costmanagement'.",
          file=sys.stderr)
    sys.exit(1)

SUBSCRIPTION_ID = "{{SUBSCRIPTION_ID}}"
DAYS_BACK = "{{DAYS_BACK}}"


def parse_args():
    parser = argparse.ArgumentParser(description="Report Azure subscription cost.")
    parser.add_argument("--subscription-id", default=SUBSCRIPTION_ID,
                        help="Azure subscription ID.")
    parser.add_argument("--days-back", type=int, default=int(DAYS_BACK),
                        help="Trailing days to report.")
    return parser.parse_args()


def main():
    args = parse_args()
    end = date.today()
    start = end - timedelta(days=args.days_back)
    scope = f"/subscriptions/{args.subscription_id}"
    query = {
        "type": "ActualCost",
        "timeframe": "Custom",
        "timePeriod": {"from": start.isoformat(), "to": end.isoformat()},
        "dataset": {
            "granularity": "Daily",
            "aggregation": {"totalCost": {"name": "Cost", "function": "Sum"}},
        },
    }
    try:
        credential = DefaultAzureCredential()
        client = CostManagementClient(credential)
        result = client.query.usage(scope=scope, parameters=query)
        print(f"Azure cost {start} -> {end}")
        total = 0.0
        for row in (result.rows or []):
            cost = float(row[0])
            total += cost
            print(f"  {row[1]}  {cost:,.2f}")
        print(f"  TOTAL  {total:,.2f}")
        return 0
    except AzureError as exc:
        print(f"ERROR: Azure call failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())

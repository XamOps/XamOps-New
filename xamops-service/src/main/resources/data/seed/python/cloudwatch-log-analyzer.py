#!/usr/bin/env python3
"""
cloudwatch-log-analyzer.py
Fetches and analyzes CloudWatch Logs for a log group over a time window,
counting matches of a filter pattern and printing recent matching events.

Parameters:
  {{AWS_REGION}}     - AWS region, e.g. us-east-1
  {{LOG_GROUP}}      - CloudWatch Logs log group name
  {{HOURS_BACK}}     - How many hours back to scan
  {{FILTER_PATTERN}} - CloudWatch filter pattern (e.g. ERROR)
"""
import argparse
import sys
import time

try:
    import boto3
    from botocore.exceptions import BotoCoreError, ClientError
except ImportError:
    print("ERROR: boto3 is required. Install with 'pip install boto3'.", file=sys.stderr)
    sys.exit(1)

AWS_REGION = "{{AWS_REGION}}"
LOG_GROUP = "{{LOG_GROUP}}"
HOURS_BACK = "{{HOURS_BACK}}"
FILTER_PATTERN = "{{FILTER_PATTERN}}"


def parse_args():
    parser = argparse.ArgumentParser(description="Analyze CloudWatch Logs.")
    parser.add_argument("--region", default=AWS_REGION, help="AWS region.")
    parser.add_argument("--log-group", default=LOG_GROUP, help="Log group name.")
    parser.add_argument("--hours-back", type=int, default=int(HOURS_BACK),
                        help="Hours of history to scan.")
    parser.add_argument("--filter-pattern", default=FILTER_PATTERN,
                        help="CloudWatch filter pattern.")
    parser.add_argument("--max-events", type=int, default=50,
                        help="Max matching events to print.")
    return parser.parse_args()


def main():
    args = parse_args()
    start_ms = int((time.time() - args.hours_back * 3600) * 1000)
    try:
        logs = boto3.client("logs", region_name=args.region)
        paginator = logs.get_paginator("filter_log_events")
        total = 0
        printed = 0
        for page in paginator.paginate(
                logGroupName=args.log_group,
                startTime=start_ms,
                filterPattern=args.filter_pattern):
            for event in page.get("events", []):
                total += 1
                if printed < args.max_events:
                    ts = time.strftime("%Y-%m-%dT%H:%M:%S",
                                       time.gmtime(event["timestamp"] / 1000))
                    print(f"{ts}  {event['message'].rstrip()}")
                    printed += 1
        print(f"\nTotal matching events in last {args.hours_back}h: {total}")
        return 0
    except (BotoCoreError, ClientError) as exc:
        print(f"ERROR: AWS call failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())

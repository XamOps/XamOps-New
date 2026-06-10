#!/usr/bin/env python3
"""
azure-resource-group-reporter.py
Reports all resources in an Azure resource group, grouped by resource type,
using the Azure Resource Management SDK.

Parameters:
  {{SUBSCRIPTION_ID}} - Azure subscription ID
  {{RESOURCE_GROUP}}  - Resource group to report on
"""
import argparse
import sys
from collections import Counter

try:
    from azure.identity import DefaultAzureCredential
    from azure.mgmt.resource import ResourceManagementClient
    from azure.core.exceptions import AzureError
except ImportError:
    print("ERROR: Azure SDK required. Install 'azure-identity azure-mgmt-resource'.",
          file=sys.stderr)
    sys.exit(1)

SUBSCRIPTION_ID = "{{SUBSCRIPTION_ID}}"
RESOURCE_GROUP = "{{RESOURCE_GROUP}}"


def parse_args():
    parser = argparse.ArgumentParser(description="Report Azure resource group contents.")
    parser.add_argument("--subscription-id", default=SUBSCRIPTION_ID,
                        help="Azure subscription ID.")
    parser.add_argument("--resource-group", default=RESOURCE_GROUP,
                        help="Resource group name.")
    return parser.parse_args()


def main():
    args = parse_args()
    try:
        credential = DefaultAzureCredential()
        client = ResourceManagementClient(credential, args.subscription_id)
        type_counts = Counter()
        total = 0
        for res in client.resources.list_by_resource_group(args.resource_group):
            type_counts[res.type] += 1
            total += 1
        print(f"Resource group: {args.resource_group}")
        print(f"Total resources: {total}\n")
        for res_type, count in sorted(type_counts.items()):
            print(f"  {count:4d}  {res_type}")
        return 0
    except AzureError as exc:
        print(f"ERROR: Azure call failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())

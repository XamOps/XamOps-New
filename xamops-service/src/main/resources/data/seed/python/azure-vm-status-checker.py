#!/usr/bin/env python3
"""
azure-vm-status-checker.py
Lists virtual machines in a resource group and reports their power state
using the Azure SDK and DefaultAzureCredential.

Parameters:
  {{SUBSCRIPTION_ID}} - Azure subscription ID
  {{RESOURCE_GROUP}}  - Resource group containing the VMs
"""
import argparse
import sys

try:
    from azure.identity import DefaultAzureCredential
    from azure.mgmt.compute import ComputeManagementClient
    from azure.core.exceptions import AzureError
except ImportError:
    print("ERROR: Azure SDK required. Install 'azure-identity azure-mgmt-compute'.",
          file=sys.stderr)
    sys.exit(1)

SUBSCRIPTION_ID = "{{SUBSCRIPTION_ID}}"
RESOURCE_GROUP = "{{RESOURCE_GROUP}}"


def parse_args():
    parser = argparse.ArgumentParser(description="Check Azure VM power states.")
    parser.add_argument("--subscription-id", default=SUBSCRIPTION_ID,
                        help="Azure subscription ID.")
    parser.add_argument("--resource-group", default=RESOURCE_GROUP,
                        help="Resource group name.")
    return parser.parse_args()


def power_state(client, rg, vm_name):
    view = client.virtual_machines.instance_view(rg, vm_name)
    for status in view.statuses:
        if status.code.startswith("PowerState/"):
            return status.code.split("/", 1)[1]
    return "unknown"


def main():
    args = parse_args()
    try:
        credential = DefaultAzureCredential()
        client = ComputeManagementClient(credential, args.subscription_id)
        found = 0
        for vm in client.virtual_machines.list(args.resource_group):
            found += 1
            state = power_state(client, args.resource_group, vm.name)
            print(f"{vm.name}\t{state}\t{vm.location}")
        print(f"\n{found} VM(s) in resource group '{args.resource_group}'.")
        return 0
    except AzureError as exc:
        print(f"ERROR: Azure call failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env bash
# Manual mode (Phase B): the agent <-> backend golden contract fixtures must be byte-identical copies.
#
#   Go (source of truth for what the agent sends/parses):
#     xamops-autoscaler/internal/approval/testdata/contract/
#   Java (parsed strictly by ApprovalContractTest):
#     xamops-service/src/test/resources/contract/approval/
#
# Fails when a file exists on only one side or when any pair differs. Run from anywhere.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
go_dir="$root/xamops-autoscaler/internal/approval/testdata/contract"
java_dir="$root/xamops-service/src/test/resources/contract/approval"

status=0
for dir in "$go_dir" "$java_dir"; do
  if [ ! -d "$dir" ]; then
    echo "missing fixture directory: ${dir#"$root"/}" >&2
    exit 1
  fi
done

# Every fixture on either side, by name.
names="$( (cd "$go_dir" && ls -1 *.json 2>/dev/null; cd "$java_dir" && ls -1 *.json 2>/dev/null) | sort -u )"
if [ -z "$names" ]; then
  echo "no contract fixtures found" >&2
  exit 1
fi

for name in $names; do
  if [ ! -f "$go_dir/$name" ]; then
    echo "only in Java: $name (copy it from the Go side or remove it)" >&2
    status=1
  elif [ ! -f "$java_dir/$name" ]; then
    echo "only in Go: $name (copy it to the Java test resources)" >&2
    status=1
  elif ! cmp -s "$go_dir/$name" "$java_dir/$name"; then
    echo "differs: $name" >&2
    diff -u "$go_dir/$name" "$java_dir/$name" | head -40 >&2 || true
    status=1
  fi
done

if [ "$status" -eq 0 ]; then
  echo "approval contract fixtures are in sync ($(echo "$names" | wc -l | tr -d ' ') files)"
fi
exit "$status"

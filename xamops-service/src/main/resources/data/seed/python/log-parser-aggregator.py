#!/usr/bin/env python3
"""
log-parser-aggregator.py
Parses a log file with a configurable regex, aggregates counts by a named
capture group, and writes a JSON summary of the most frequent values.

Parameters:
  {{LOG_PATH}}    - Path to the log file to parse
  {{OUTPUT_FILE}} - Path to write the JSON aggregation summary
  {{LOG_PATTERN}} - Regex with a named group 'key' to aggregate on
"""
import argparse
import json
import re
import sys
from collections import Counter

LOG_PATH = "{{LOG_PATH}}"
OUTPUT_FILE = "{{OUTPUT_FILE}}"
LOG_PATTERN = "{{LOG_PATTERN}}"


def parse_args():
    parser = argparse.ArgumentParser(description="Parse and aggregate a log file.")
    parser.add_argument("--log-path", default=LOG_PATH, help="Log file path.")
    parser.add_argument("--output-file", default=OUTPUT_FILE, help="Output JSON path.")
    parser.add_argument("--log-pattern", default=LOG_PATTERN,
                        help="Regex with a named group 'key'.")
    parser.add_argument("--top", type=int, default=20, help="Top N keys to report.")
    return parser.parse_args()


def main():
    args = parse_args()
    try:
        pattern = re.compile(args.log_pattern)
    except re.error as exc:
        print(f"ERROR: Invalid regex pattern: {exc}", file=sys.stderr)
        return 1
    counts = Counter()
    matched = 0
    total = 0
    try:
        with open(args.log_path, encoding="utf-8", errors="replace") as fh:
            for line in fh:
                total += 1
                match = pattern.search(line)
                if match:
                    matched += 1
                    key = match.groupdict().get("key", match.group(0))
                    counts[key] += 1
        summary = {
            "total_lines": total,
            "matched_lines": matched,
            "top": counts.most_common(args.top),
        }
        with open(args.output_file, "w", encoding="utf-8") as out:
            json.dump(summary, out, indent=2)
        print(f"Parsed {total} line(s), matched {matched}. Summary -> {args.output_file}")
        return 0
    except FileNotFoundError:
        print(f"ERROR: Log file not found: {args.log_path}", file=sys.stderr)
        return 1
    except OSError as exc:
        print(f"ERROR: Failed to process log: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())

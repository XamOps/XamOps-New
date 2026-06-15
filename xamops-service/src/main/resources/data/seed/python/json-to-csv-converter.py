#!/usr/bin/env python3
"""
json-to-csv-converter.py
Converts a JSON array of flat objects into a CSV file. The union of all object
keys becomes the CSV header, with missing values left blank.

Parameters:
  {{INPUT_FILE}}  - Path to the input JSON file (array of objects)
  {{OUTPUT_FILE}} - Path to write the CSV output
"""
import argparse
import csv
import json
import sys

INPUT_FILE = "{{INPUT_FILE}}"
OUTPUT_FILE = "{{OUTPUT_FILE}}"


def parse_args():
    parser = argparse.ArgumentParser(description="Convert JSON array to CSV.")
    parser.add_argument("--input-file", default=INPUT_FILE, help="Input JSON path.")
    parser.add_argument("--output-file", default=OUTPUT_FILE, help="Output CSV path.")
    return parser.parse_args()


def collect_fields(records):
    fields = []
    seen = set()
    for record in records:
        for key in record.keys():
            if key not in seen:
                seen.add(key)
                fields.append(key)
    return fields


def main():
    args = parse_args()
    try:
        with open(args.input_file, encoding="utf-8") as fh:
            data = json.load(fh)
        if not isinstance(data, list):
            print("ERROR: Input JSON must be an array of objects.", file=sys.stderr)
            return 1
        fieldnames = collect_fields(data)
        with open(args.output_file, "w", newline="", encoding="utf-8") as out:
            writer = csv.DictWriter(out, fieldnames=fieldnames)
            writer.writeheader()
            for record in data:
                writer.writerow(record)
        print(f"Converted {len(data)} record(s) -> {args.output_file}")
        return 0
    except FileNotFoundError:
        print(f"ERROR: Input file not found: {args.input_file}", file=sys.stderr)
        return 1
    except (OSError, json.JSONDecodeError) as exc:
        print(f"ERROR: Conversion failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())

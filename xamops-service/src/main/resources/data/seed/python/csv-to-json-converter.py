#!/usr/bin/env python3
"""
csv-to-json-converter.py
Converts a delimited CSV file into a JSON array of objects, using the header
row as keys. Supports custom delimiters and pretty-printed output.

Parameters:
  {{INPUT_FILE}}  - Path to the input CSV file
  {{OUTPUT_FILE}} - Path to write the JSON output
  {{DELIMITER}}   - Field delimiter character (e.g. ',' or ';')
"""
import argparse
import csv
import json
import sys

INPUT_FILE = "{{INPUT_FILE}}"
OUTPUT_FILE = "{{OUTPUT_FILE}}"
DELIMITER = "{{DELIMITER}}"


def parse_args():
    parser = argparse.ArgumentParser(description="Convert CSV to JSON.")
    parser.add_argument("--input-file", default=INPUT_FILE, help="Input CSV path.")
    parser.add_argument("--output-file", default=OUTPUT_FILE, help="Output JSON path.")
    parser.add_argument("--delimiter", default=DELIMITER, help="CSV field delimiter.")
    parser.add_argument("--indent", type=int, default=2, help="JSON indent width.")
    return parser.parse_args()


def main():
    args = parse_args()
    try:
        with open(args.input_file, newline="", encoding="utf-8") as fh:
            reader = csv.DictReader(fh, delimiter=args.delimiter)
            rows = [dict(row) for row in reader]
        with open(args.output_file, "w", encoding="utf-8") as out:
            json.dump(rows, out, indent=args.indent, ensure_ascii=False)
        print(f"Converted {len(rows)} row(s) -> {args.output_file}")
        return 0
    except FileNotFoundError:
        print(f"ERROR: Input file not found: {args.input_file}", file=sys.stderr)
        return 1
    except (OSError, csv.Error) as exc:
        print(f"ERROR: Conversion failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())

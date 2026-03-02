#!/usr/bin/env python3
from __future__ import annotations

import json
import os
import re
import sys
from typing import Any, Iterable, List, Tuple


RE_WINDOWS_ABS = re.compile(r"^[a-zA-Z]:[\\/]")


def is_abs_path_like(value: str) -> bool:
    v = value.strip()
    if not v:
        return False
    if v.startswith("${env:") or v.startswith("${workspaceFolder"):
        return False
    if v.startswith("~/") or v.startswith("~\\"):
        return True
    if v.startswith("/"):
        return True
    if v.startswith("\\\\"):
        return True
    if RE_WINDOWS_ABS.match(v):
        return True
    return False


def iter_paths(obj: Any, prefix: Tuple[str, ...] = ()) -> Iterable[Tuple[Tuple[str, ...], Any]]:
    if isinstance(obj, dict):
        for k, v in obj.items():
            yield from iter_paths(v, prefix + (str(k),))
    elif isinstance(obj, list):
        for i, v in enumerate(obj):
            yield from iter_paths(v, prefix + (str(i),))
    else:
        yield (prefix, obj)


def path_key_str(path: Tuple[str, ...]) -> str:
    # Render json path segments in a readable, stable way.
    out: List[str] = []
    for seg in path:
        if seg.isdigit():
            out.append(f"[{seg}]")
        else:
            if not out:
                out.append(seg)
            else:
                out.append("." + seg)
    return "".join(out)


def main() -> int:
    repo_root = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
    settings_path = os.path.join(repo_root, ".vscode", "settings.json")

    if not os.path.exists(settings_path):
        print("OK: .vscode/settings.json not present (nothing to validate).")
        return 0

    try:
        with open(settings_path, "r", encoding="utf-8") as f:
            settings = json.load(f)
    except Exception as e:
        print(f"ERROR: failed to parse .vscode/settings.json as JSON: {e}")
        return 2

    violations: List[str] = []

    # Guardrail: these keys frequently get committed with machine-local absolute paths.
    forbidden_exact_keys = {
        "java.jdt.ls.java.home",
        "java.home",
    }

    # Check exact keys first (if present).
    for k in forbidden_exact_keys:
        v = settings.get(k)
        if isinstance(v, str) and is_abs_path_like(v):
            violations.append(f"{k} must not be an absolute path: {v!r}")

    # Check java.configuration.runtimes[*].path if present.
    runtimes = settings.get("java.configuration.runtimes")
    if isinstance(runtimes, list):
        for idx, rt in enumerate(runtimes):
            if not isinstance(rt, dict):
                continue
            p = rt.get("path")
            if isinstance(p, str) and is_abs_path_like(p):
                violations.append(f"java.configuration.runtimes[{idx}].path must not be an absolute path: {p!r}")

    # A broader safety net: any *.home keys under java.* should not be absolute.
    for path, value in iter_paths(settings):
        if not isinstance(value, str):
            continue
        key = ".".join(seg for seg in path if not seg.isdigit())
        if not key.startswith("java."):
            continue
        if key.endswith(".home") and is_abs_path_like(value):
            violations.append(f"{path_key_str(path)} must not be an absolute path: {value!r}")

    if violations:
        print("FAIL: Found machine-specific Java paths in .vscode/settings.json")
        for v in violations:
            print(f"- {v}")
        print("")
        print("Fix: remove the absolute path, or replace with ${env:...} / ${workspaceFolder}.")
        print("Tip: put personal settings in .vscode/settings.local.json (ignored by git).")
        return 1

    print("OK: .vscode/settings.json passed portability checks.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())


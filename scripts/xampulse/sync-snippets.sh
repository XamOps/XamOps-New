#!/bin/sh
# Copies the canonical XamPulse install snippets (cloudformation/xampulse/ssm-snippets) to the places that
# ship them: the agent scripts folder and the backend classpath. Run after editing a snippet.
# SsmSnippetTest fails when a copy drifts or when a template embeds a different body.
set -eu
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SRC="$ROOT/cloudformation/xampulse/ssm-snippets"
cp "$SRC/install-linux.sh" "$ROOT/xamops-agent/scripts/xampulse_install_linux.sh"
cp "$SRC/install-windows.ps1" "$ROOT/xamops-agent/scripts/xampulse_install_windows.ps1"
echo "synced snippets from $SRC"

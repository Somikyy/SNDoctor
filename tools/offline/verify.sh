#!/usr/bin/env bash
# Build offline, then run the full assertion suite. One command, no network.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
bash "$HERE/build-offline.sh"
bash "$HERE/selftest.sh"

#!/usr/bin/env bash
set -euo pipefail

expected=$(python3 -c 'import json; print(json.load(open(".wts-contracts.json"))["fixtureChecksum"])')
if command -v shasum >/dev/null; then
  actual=$(cd contracts/v1 && find . -type f -print0 | sort -z | xargs -0 shasum -a 256 | shasum -a 256 | awk '{print $1}')
else
  actual=$(cd contracts/v1 && find . -type f -print0 | sort -z | xargs -0 sha256sum | sha256sum | awk '{print $1}')
fi
test "$actual" = "$expected" || { echo "Contract drift: expected $expected, got $actual" >&2; exit 1; }

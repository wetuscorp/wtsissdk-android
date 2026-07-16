#!/usr/bin/env bash
set -euo pipefail

checksum() {
  local directory=$1
  if command -v shasum >/dev/null; then
    (cd "$directory" && find . -type f -print0 | sort -z | xargs -0 shasum -a 256 | shasum -a 256 | awk '{print $1}')
  else
    (cd "$directory" && find . -type f -print0 | sort -z | xargs -0 sha256sum | sha256sum | awk '{print $1}')
  fi
}

expected=$(python3 -c 'import json; print(json.load(open(".wts-contracts.json"))["fixtureChecksum"])')
actual=$(checksum contracts/mobile/v2)
test "$actual" = "$expected" || { echo "Mobile contract drift: expected $expected, got $actual" >&2; exit 1; }

identity_expected=$(python3 -c 'import json; print(json.load(open(".wts-contracts.json"))["identityFixtureChecksum"])')
identity_actual=$(checksum contracts/identity/v1)
test "$identity_actual" = "$identity_expected" || { echo "Identity contract drift: expected $identity_expected, got $identity_actual" >&2; exit 1; }

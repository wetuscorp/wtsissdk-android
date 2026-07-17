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
actual=$(checksum contracts/mobile/v3)
test "$actual" = "$expected" || { echo "Mobile contract drift: expected $expected, got $actual" >&2; exit 1; }

identity_expected=$(python3 -c 'import json; print(json.load(open(".wts-contracts.json"))["identityFixtureChecksum"])')
identity_actual=$(checksum contracts/identity/v1)
test "$identity_actual" = "$identity_expected" || { echo "Identity contract drift: expected $identity_expected, got $identity_actual" >&2; exit 1; }

experiences_expected=$(ruby -rjson -e 'puts JSON.parse(File.read(".wts-contracts.json")).fetch("experiencesFixtureChecksum")')
experiences_actual=$(checksum contracts/experiences/v1)
test "$experiences_actual" = "$experiences_expected" || { echo "Experiences contract drift: expected $experiences_expected, got $experiences_actual" >&2; exit 1; }

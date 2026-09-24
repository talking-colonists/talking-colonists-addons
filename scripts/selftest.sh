#!/usr/bin/env bash
# End-to-end check of one addon on a headless dev server (its dev/DevSelfTest, run by the addon's
# selfTestServer Gradle run). Most addons talk to Gemini through Talking Colonists, so it needs a key:
#   TC_ADDONS_KEY_FILE=/path/to/key bash scripts/selftest.sh <addon> [version...]
# e.g. bash scripts/selftest.sh gazette 1.20.1-forge
# The key is copied into the disposable run/selftest config and never printed.
set -euo pipefail
cd "$(dirname "$0")/.."

key_file="${TC_ADDONS_KEY_FILE:-}"
if [[ -z "$key_file" || ! -s "$key_file" ]]; then
  echo "Set TC_ADDONS_KEY_FILE to a file containing a Gemini API key." >&2
  exit 2
fi

addon="${1:-}"
if [[ -z "$addon" || ! -d "$addon/src" ]]; then
  echo "Usage: $0 <addon directory> [version...]" >&2
  exit 2
fi
shift
marker="TC_$(tr '[:lower:]' '[:upper:]' <<<"$addon")_SELFTEST"
versions=("$@")
if [[ ${#versions[@]} -eq 0 ]]; then versions=(1.21.1-neoforge 1.20.1-forge); fi

status=0
for version in "${versions[@]}"; do
  run="$addon/versions/$version/run/selftest"
  rm -rf "$run/world"
  mkdir -p "$run/config"
  echo "eula=true" > "$run/eula.txt"
  printf 'level-type=minecraft\\:flat\nonline-mode=false\nspawn-protection=0\n' > "$run/server.properties"
  python3 - "$key_file" "$run/config/yacl-mc_talking.json5" <<'EOF'
import json, sys
key = open(sys.argv[1]).read().strip()
json.dump({"geminiApiKey": key}, open(sys.argv[2], "w"))
EOF
  log="/tmp/tc-$addon-selftest-$version.log"
  echo "== $version (log: $log)"
  timeout 900 ./gradlew ":$addon:$version:runSelfTestServer" --console=plain > "$log" 2>&1 || true
  rm -f "$run/config/yacl-mc_talking.json5"
  if grep -q "${marker}_SUCCESS" "$log"; then
    grep "$marker" "$log" | sed "s/^.*$marker/$marker/"
  else
    echo "FAILED: $version" >&2
    grep -E "$marker|Exception|Error" "$log" | head -20 >&2 || true
    status=1
  fi
done
exit $status

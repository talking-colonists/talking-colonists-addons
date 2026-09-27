#!/usr/bin/env bash
# An automated playtest pass: runs every scripted scenario on a fresh world, one after another, checks
# each (scripts/scenario-checks.py) and writes a summary with the screenshots to look at. Run it before
# playing yourself; what it cannot judge (looks, feel) is left for you.
#   bash scripts/playtest-pass.sh                       # all scenarios on 1.21.1 NeoForge
#   bash scripts/playtest-pass.sh forge windows mayor   # some scenarios on 1.20.1 Forge
# About 30 minutes for all; uses Gemini Live and a few Flash Lite requests (the judge).
set -uo pipefail
cd "$(dirname "$0")/.."

target="${1:-neoforge}"
shift || true
scenarios=("$@")
if [[ ${#scenarios[@]} -eq 0 ]]; then
  scenarios=(windows firstday campfire night construction notice election mayor)
fi
pass="build/scenario/pass-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$pass"
summary="$pass/summary.md"
echo "# Playtest pass $(date '+%Y-%m-%d %H:%M') ($target, core $(git -C "${TC_MAIN_REPO:-../talking-colonists}" rev-parse --short HEAD), addons $(git rev-parse --short HEAD))" > "$summary"

failed=0
for scenario in "${scenarios[@]}"; do
  echo "=== $scenario"
  bash scripts/scenario.sh "$scenario" "$target" --fresh > "$pass/$scenario.txt" 2>&1
  status=$?
  checks=$(ls -t build/scenario/"$scenario"-*.checks.json 2>/dev/null | head -1)
  {
    echo
    echo "## $scenario"
    if [[ -n "$checks" && "$checks" -nt "$pass" ]]; then
      python3 - "$checks" <<'PY'
import json, sys
data = json.load(open(sys.argv[1]))
for r in data["results"]:
    line = f'- {"✅" if r["status"] == "PASS" else "❌" if r["status"] == "FAIL" else "⏭️"} {r["check"]}'
    if r["status"] != "PASS" and r["detail"]:
        line += f' — {r["detail"]}'
    print(line)
print(f'\nLog: `{data["log"]}`')
PY
      shots="${checks%.checks.json}-screenshots"
      [[ -d "$shots" ]] && echo "Screenshots: \`$shots\`"
    else
      echo "- ❌ no checks ran (exit $status); see \`$pass/$scenario.txt\`"
    fi
  } >> "$summary"
  [[ $status -ne 0 ]] && failed=1
  grep -E "^(PASS|FAIL|SKIP)  " "$pass/$scenario.txt" | grep -v "^PASS" || echo "all checks passed"
done
echo
echo "Summary: $summary"
exit $failed

#!/usr/bin/env bash
# Scripted headless playtest: a real client (no window needed) joins its own copy of the playtest world,
# runs a scenario as the player, quits, and prints the speech report (who spoke when, overlaps,
# missing animations, repeats, how often citizens addressed the player).
#   bash scripts/scenario.sh                     # "campfire" scenario on 1.21.1 NeoForge
#   bash scripts/scenario.sh ambient forge       # "ambient" scenario on 1.20.1 Forge
#   bash scripts/scenario.sh campfire neoforge --fresh   # new world first
# Scenarios live in playtest/.../PlaytestScenario.java. Uses Gemini (Live) quota like a real session.
# The log is kept in build/scenario/<scenario>-<version>-<time>.log.
set -euo pipefail
cd "$(dirname "$0")/.."

scenario="${1:-campfire}"
target="${2:-neoforge}"
fresh=false
for arg in "$@"; do [[ "$arg" == "--fresh" ]] && fresh=true; done
case "$target" in
  neoforge) version=1.21.1-neoforge ;;
  forge) version=1.20.1-forge ;;
  *) echo "Usage: $0 [scenario] [neoforge|forge] [--fresh]" >&2; exit 2 ;;
esac

main_repo="${TC_MAIN_REPO:-../talking-colonists}"
run="playtest/versions/$version/run/scenario"
mkdir -p "$run/config" build/scenario
if $fresh; then rm -rf "$run/saves/TC_Playtest"; fi
config="$run/config/yacl-mc_talking.json5"
if [[ ! -f "$config" && -f "playtest/versions/$version/run/config/yacl-mc_talking.json5" ]]; then
  cp "playtest/versions/$version/run/config/yacl-mc_talking.json5" "$config"
fi
python3 scripts/ensure_key.py "$config" "${TC_ADDONS_KEY_FILE:-}" \
  "$main_repo"/versions/*/run/config/yacl-mc_talking.json5 playtest/versions/*/run/config/yacl-mc_talking.json5
if [[ ! -f "$run/options.txt" ]]; then
  printf 'onboardAccessibility:false\ntutorialStep:none\njoinedFirstServer:true\nskipMultiplayerWarning:true\npauseOnLostFocus:false\n' > "$run/options.txt"
fi

gradle=(./gradlew ":playtest:$version:runScenarioClient" "-PtcScenario=$scenario")
# Headless by default, even on a desktop; TC_SCENARIO_VISIBLE=1 shows the window.
if [[ -z "${TC_SCENARIO_VISIBLE:-}" ]] && command -v xvfb-run >/dev/null; then
  gradle=(xvfb-run -a -s "-screen 0 1280x720x24" "${gradle[@]}")
fi
echo "[$version] running scenario \"$scenario\" (a few minutes)..."
status=0
timeout --kill-after=30 "${TC_SCENARIO_TIMEOUT:-900}" "${gradle[@]}" > "build/scenario/gradle.log" 2>&1 || status=$?

log="build/scenario/$scenario-$version-$(date +%Y%m%d-%H%M%S).log"
cp "$run/logs/latest.log" "$log"
if ! grep -q "TC_PLAYTEST_SCENARIO_DONE" "$log"; then
  echo "Scenario did not finish (exit $status); see build/scenario/gradle.log and $log" >&2
fi
echo "Log: $log"
python3 "$main_repo/scripts/speech-timeline-report.py" "$log" --json "${log%.log}.json"

#!/usr/bin/env bash
# Speech report for a playtest you played yourself: who spoke when, overlaps, missing speaking
# animations, near-repeats and how often citizens addressed you.
#   bash scripts/speech-report.sh                 # latest NeoForge playtest log
#   bash scripts/speech-report.sh forge           # latest Forge playtest log
#   bash scripts/speech-report.sh path/to/some.log
set -euo pipefail
cd "$(dirname "$0")/.."
main_repo="${TC_MAIN_REPO:-../talking-colonists}"
case "${1:-neoforge}" in
  neoforge) log=playtest/versions/1.21.1-neoforge/run/logs/latest.log ;;
  forge) log=playtest/versions/1.20.1-forge/run/logs/latest.log ;;
  *) log="$1" ;;
esac
exec python3 "$main_repo/scripts/speech-timeline-report.py" "$log" "${@:2}"

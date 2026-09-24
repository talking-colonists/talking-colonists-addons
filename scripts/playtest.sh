#!/usr/bin/env bash
# One command to try every addon in a real client:
#   bash scripts/playtest.sh              # 1.21.1 NeoForge
#   bash scripts/playtest.sh forge        # 1.20.1 Forge
#   bash scripts/playtest.sh both         # both clients side by side
#   bash scripts/playtest.sh neoforge --fresh   # throw the test world away first
#
# The client (the dev-only playtest/ mod) creates or opens the creative superflat world "TC_Playtest",
# builds a colony with a teacher, a student, a tavern visitor and a campfire next to you, and prints a
# clickable checklist in chat (/playtest shows it again).
#
# Gemini key: on the first run the Talking Colonists config is copied from the main repository's dev
# client (../talking-colonists/versions/<version>/run/config). If it has no key, the key comes from
# TC_ADDONS_KEY_FILE or any other dev config that has one (scripts/ensure_key.py; never printed).
# Later runs keep whatever you change in the in-game config screen.
set -euo pipefail
cd "$(dirname "$0")/.."

target="${1:-neoforge}"
fresh=false
for arg in "$@"; do [[ "$arg" == "--fresh" ]] && fresh=true; done

case "$target" in
  neoforge|--fresh) versions=(1.21.1-neoforge) ;;
  forge) versions=(1.20.1-forge) ;;
  both) versions=(1.21.1-neoforge 1.20.1-forge) ;;
  *) echo "Usage: $0 [neoforge|forge|both] [--fresh]" >&2; exit 2 ;;
esac

main_repo="${TC_MAIN_REPO:-../talking-colonists}"
tasks=()
for version in "${versions[@]}"; do
  run="playtest/versions/$version/run"
  mkdir -p "$run/config"
  if $fresh; then rm -rf "$run/saves/TC_Playtest"; fi

  config="$run/config/yacl-mc_talking.json5"
  if [[ ! -f "$config" && -f "$main_repo/versions/$version/run/config/yacl-mc_talking.json5" ]]; then
    cp "$main_repo/versions/$version/run/config/yacl-mc_talking.json5" "$config"
    echo "[$version] Copied the Talking Colonists config from $main_repo"
  fi
  python3 scripts/ensure_key.py "$config" "${TC_ADDONS_KEY_FILE:-}" \
    "$main_repo"/versions/*/run/config/yacl-mc_talking.json5 playtest/versions/*/run/config/yacl-mc_talking.json5

  # Skip the first-launch accessibility screen and tutorial toasts.
  if [[ ! -f "$run/options.txt" ]]; then
    printf 'onboardAccessibility:false\ntutorialStep:none\njoinedFirstServer:true\nskipMultiplayerWarning:true\npauseOnLostFocus:false\n' > "$run/options.txt"
  fi
  tasks+=(":playtest:$version:runClient")
done

exec ./gradlew "${tasks[@]}" --parallel

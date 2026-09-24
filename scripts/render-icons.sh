#!/usr/bin/env bash
# Renders icons/<addon>.svg (same palette and sparkle as the Talking Colonists icon) with headless
# Chrome: icons/<addon>.png at 512x512 for mod pages, and <addon>/src/main/resources/assets/icon.png
# at 128x128 when the addon directory exists. Needs google-chrome and ImageMagick.
set -euo pipefail
cd "$(dirname "$0")/.."
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
for svg in icons/*.svg; do
  name="$(basename "$svg" .svg)"
  google-chrome --headless=new --disable-gpu --hide-scrollbars --window-size=884,884 \
    --screenshot="$tmp/$name.png" "file://$PWD/$svg" > /dev/null 2>&1
  magick "$tmp/$name.png" -resize 512x512 -strip "icons/$name.png"
  if [[ -d "$name/src/main/resources/assets" ]]; then
    magick "$tmp/$name.png" -resize 128x128 -strip "$name/src/main/resources/assets/icon.png"
    echo "$name: icons/$name.png and $name/src/main/resources/assets/icon.png"
  else
    echo "$name: icons/$name.png"
  fi
done

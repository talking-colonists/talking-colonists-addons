"""Makes sure a Talking Colonists dev config has a Gemini API key, without ever printing it.

Usage: ensure_key.py <config> <key file or ""> <other configs...>
The key comes from the key file, else from the first other config that has one.
"""
import json
import os
import re
import sys

PATTERN = re.compile(r'("?geminiApiKey"?\s*:\s*)"([^"]*)"')


def key_in(path):
    try:
        with open(path) as file:
            match = PATTERN.search(file.read())
    except OSError:
        return ""
    return match.group(2) if match else ""


def main():
    config, key_file, others = sys.argv[1], sys.argv[2], sys.argv[3:]
    if key_in(config):
        return
    key = ""
    if key_file and os.path.isfile(key_file):
        with open(key_file) as file:
            key = file.read().strip()
    for other in others:
        key = key or key_in(other)
    if not key:
        print(f"No Gemini key for {config}: citizens stay silent. Set TC_ADDONS_KEY_FILE or add it in-game.",
              file=sys.stderr)
        return
    text = ""
    if os.path.isfile(config):
        with open(config) as file:
            text = file.read()
    if PATTERN.search(text):
        text = PATTERN.sub(lambda match: match.group(1) + json.dumps(key), text, count=1)
    else:
        text = json.dumps({"geminiApiKey": key})
    with open(config, "w") as file:
        file.write(text)
    print(f"Added the Gemini key to {config}")


if __name__ == "__main__":
    main()

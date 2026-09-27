#!/usr/bin/env python3
"""What a scripted playtest (scripts/scenario.sh) must show, checked on its log.

    python3 scripts/scenario-checks.py <scenario> <log> [--report report.json] [--config yacl.json5] [--json out.json]

Every run: the scenario finished, no `await` step timed out, no errors from Talking Colonists or the
addons, no overlapping voices (from the speech report's JSON). Then the scenario's own checks:
rules on what was said and logged, and an LLM judge (the cheap Flash Lite text model) for what a
rule cannot decide, such as "campfire stories are about their lives". Judge checks are SKIPped
without a key or quota. Exit code 1 when a check FAILed.
"""
import argparse
import json
import re
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime

LINE = re.compile(r"^\[(\d{2}\w{3}\d{4} \d{2}:\d{2}:\d{2}\.\d{3})\] \[([^\]]*)/(\w+)\] \[([^\]]*)\]: (.*)$")
TIMELINE = "[SpeechTimeline] "
OUR_LOGGERS = re.compile(r"^(me\.sshcrack|tc_)")
JUDGE_MODEL = "gemini-3.5-flash-lite"
KEY = re.compile(r'"?geminiApiKey"?\s*:\s*"([^"]*)"')


class Run:
    """The parsed log: plain lines, what citizens said, and the scenario's marks."""

    def __init__(self, path):
        self.lines, self.said, self.marks = [], [], []
        with open(path, encoding="utf-8", errors="replace") as log:
            for raw in log:
                match = LINE.match(raw.rstrip("\n"))
                if not match:
                    continue
                at = int(datetime.strptime(match.group(1), "%d%b%Y %H:%M:%S.%f").timestamp() * 1000)
                level, logger, message = match.group(3), match.group(4), match.group(5)
                self.lines.append({"at": at, "level": level, "logger": logger, "text": message})
                if TIMELINE in message:
                    try:
                        entry = json.loads(message.split(TIMELINE, 1)[1])
                    except json.JSONDecodeError:
                        continue
                    if entry.get("type") == "said":
                        self.said.append(entry)
                    elif entry.get("type") == "mark":
                        self.marks.append(entry)

    def mark(self, what):
        """When the first mark containing {what} was set, or None."""
        return next((m["at"] for m in self.marks if what in m["what"]), None)

    def window(self, start=None, end=None):
        """(from, to) in ms between two marks; None ends are open."""
        return (self.mark(start) if start else 0) or 0, (self.mark(end) if end else None) or 1 << 62

    def spoken(self, start=None, end=None, kind=None, speaker=None, text=None, exclude_kind=None):
        begin, stop = self.window(start, end)
        return [s for s in self.said if begin <= s["at"] < stop
                and (kind is None or s.get("kind") == kind)
                and (exclude_kind is None or s.get("kind") != exclude_kind)
                and (speaker is None or s.get("speaker") == speaker)
                and (text is None or re.search(text, s.get("text", ""), re.I))]

    def logged(self, pattern, start=None, end=None):
        begin, stop = self.window(start, end)
        return [line for line in self.lines if begin <= line["at"] < stop and re.search(pattern, line["text"])]


def transcript(entries):
    return "\n".join(f'{e["speaker"]} ({e.get("kind", "?")}): {e.get("text", "")}' for e in entries)


class Judge:
    """Asks the text model whether a transcript passes a rubric; None when it cannot be asked."""

    def __init__(self, config):
        self.key = None
        if config:
            try:
                with open(config, encoding="utf-8") as file:
                    match = KEY.search(file.read())
                self.key = match.group(1) if match and match.group(1).strip() else None
            except OSError:
                pass

    def __call__(self, rubric, text):
        if not self.key:
            return None, "no Gemini key"
        if not text.strip():
            return False, "nothing to judge: nobody said anything here"
        body = {
            "systemInstruction": {"parts": [{"text":
                "You review transcripts from a Minecraft mod where AI villagers (citizens of a MineColonies colony) "
                "talk. The player is the colony's owner. Judge strictly against the rubric only, and quote the line "
                "that decides it in your reason."}]},
            "contents": [{"role": "user", "parts": [{"text": f"Rubric: {rubric}\n\nTranscript:\n{text}"}]}],
            "generationConfig": {"temperature": 0, "responseMimeType": "application/json", "responseSchema": {
                "type": "OBJECT", "properties": {"pass": {"type": "BOOLEAN"}, "reason": {"type": "STRING"}},
                "required": ["pass", "reason"]}},
        }
        request = urllib.request.Request(
            f"https://generativelanguage.googleapis.com/v1beta/models/{JUDGE_MODEL}:generateContent",
            data=json.dumps(body).encode(), headers={"Content-Type": "application/json", "x-goog-api-key": self.key})
        for attempt in range(3):
            try:
                with urllib.request.urlopen(request, timeout=60) as response:
                    reply = json.load(response)
                verdict = json.loads(reply["candidates"][0]["content"]["parts"][0]["text"])
                return bool(verdict["pass"]), verdict["reason"]
            except urllib.error.HTTPError as error:
                # Overloaded (503) or rate-limited (429): wait and ask again.
                if error.code in (429, 500, 503) and attempt < 2:
                    time.sleep(10 * (attempt + 1))
                    continue
                return None, f"judge unavailable (HTTP {error.code})"
            except (urllib.error.URLError, KeyError, IndexError, ValueError, TimeoutError) as error:
                return None, f"judge unavailable ({type(error).__name__})"


# A check is (name, function(run, judge) -> (True/False/None, detail)). None means SKIP.

def said_any(name, detail_if_missing="nobody said it", **selector):
    def check(run, judge):
        found = run.spoken(**selector)
        return (True, found[0]["speaker"] + ": " + found[0]["text"][:160]) if found else (False, detail_if_missing)
    return name, check


def said_none(name, **selector):
    def check(run, judge):
        found = run.spoken(**selector)
        return (False, found[0]["speaker"] + ": " + found[0]["text"][:200]) if found else (True, "")
    return name, check


def logged_any(name, pattern, start=None, end=None):
    def check(run, judge):
        found = run.logged(pattern, start, end)
        return (True, found[0]["text"][:200]) if found else (False, f"no log line matches {pattern}")
    return name, check


def logged_none(name, pattern, start=None, end=None):
    def check(run, judge):
        found = run.logged(pattern, start, end)
        return (False, found[0]["text"][:200]) if found else (True, "")
    return name, check


def judged(name, rubric, facts=None, **selector):
    """{facts}: a regex; matching log lines are given to the judge as what really happened in the game."""
    def check(run, judge):
        text = rubric
        if facts:
            lines = [line["text"] for line in run.logged(facts)]
            text += "\n\nWhat really happened in the game (from its log, oldest first):\n" + "\n".join(lines or ["(nothing logged)"])
        return judge(text, transcript(run.spoken(**selector)))
    return name, check


def talked_to_builder(run, judge):
    went = run.logged(r"TC_GOTO next to the builder (.+)")
    if not went:
        return False, "never got to the builder"
    builder = re.search(r"TC_GOTO next to the builder (.+)", went[0]["text"]).group(1)
    talk = first_after(run, r"TC_TALK", "talks to the builder")
    ok = talk is not None and talk["text"] == f"TC_TALK STARTED with {builder}"
    return ok, talk["text"] if talk else "no conversation started"


def introducer_writes_no_memory(run, judge):
    """An introduction must not call add_event_to_memory: those notes became campfire stories."""
    ids = {s["speaker"]: s["id"] for s in run.said if "id" in s}
    bad = []
    for walk in run.logged(r"\[Introductions\] (.+?) walks up to"):
        name = re.search(r"\[Introductions\] (.+?) walks up to", walk["text"]).group(1)
        uuid = ids.get(name)
        if not uuid:
            continue
        calls = [line for line in run.lines if walk["at"] <= line["at"] < walk["at"] + 60_000
                 and uuid in line["text"] and "has called tool add_event_to_memory" in line["text"]]
        if calls:
            bad.append(name)
    if not run.logged(r"\[Introductions\] .+ walks up to"):
        return None, "no introduction happened"
    return (False, "introductions that wrote a memory: " + ", ".join(bad)) if bad else (True, "")


def no_successful_placeholder_broadcast(run, judge):
    results = run.logged(r"Result of initiate_broadcast: \{\"success\":true")
    if not results:
        return True, "no broadcast went out"
    calls = run.logged(r"has called tool initiate_broadcast")
    return False, "a broadcast went out although the player gave no message: " + (calls[0]["text"][-200:] if calls else "")


def first_after(run, pattern, mark):
    at = run.mark(mark)
    if at is None:
        return None
    return next((line for line in run.lines if line["at"] >= at and re.search(pattern, line["text"])), None)


def window_checks(window):
    """The window opens, its close button takes a real click, and then it is gone."""
    def opens(run, judge):
        line = first_after(run, r"TC_SCENARIO_SCREEN", f"{window}: close clicked")
        return (line is not None and not line["text"].endswith("none"), line["text"] if line else "no screen logged")

    def click(run, judge):
        line = first_after(run, r"TC_SCENARIO_CLICK close", f"{window}: close clicked")
        return (line is not None and "handled=true" in line["text"], line["text"] if line else "no click logged")

    def closed(run, judge):
        line = first_after(run, r"TC_SCENARIO_SCREEN", f"{window}: after close")
        return (line is not None and line["text"].endswith("none"), line["text"] if line else "no screen logged")

    return [(f"{window}: opens", opens), (f"{window}: close button takes the click", click),
            (f"{window}: closed by it", closed)]


COMMON = [
    logged_any("scenario finished", r"TC_PLAYTEST_SCENARIO_DONE"),
    logged_none("every awaited event happened", r"TC_AWAIT timeout"),
]

HOSTILE = r"incompeten|useless|pathetic|how dare|disgrace|worthless|lazy"
CAMPFIRE = {"start": "campfire night starts", "end": "campfire stopped"}

SCENARIOS = {
    "firstday": [
        said_any("the welcome is said and mentions the handbook", kind="ADDON_AMBIENT", text=r"handbook",
                 end="talk to the citizen next to you"),
        judged("the welcome is warm and sincere",
               "PASS if the first line welcomes the player warmly and sincerely, with no sarcasm, complaint or "
               "reproach. FAIL otherwise.", kind="ADDON_AMBIENT", end="talk to the citizen next to you"),
        logged_any("a conversation with a citizen starts", r"TC_TALK STARTED"),
        said_any("the citizen answers about their day", kind="PLAYER", start="asks about the day", end="asks how to tell everyone"),
        said_none("no second welcome when asked about the day", kind="PLAYER", text=r"welcome",
                  start="asks about the day", end="asks how to tell everyone"),
        ("no broadcast without the player's words", no_successful_placeholder_broadcast),
        judged("asked how to tell everyone, the citizen asks what to say",
               "The player asked how to tell everyone in the colony something and whether the citizen can do it. "
               "PASS if the citizen asks what the message should be, or explains how, without claiming to have "
               "announced something already. FAIL if the citizen says it announced or broadcast something.",
               kind="PLAYER", start="asks how to tell everyone", end="walks over to another citizen"),
        said_any("a later introduction is said", kind="ADDON_AMBIENT", start="waits for the next introduction"),
        said_none("the later introduction does not welcome again", kind="ADDON_AMBIENT", text=r"welcome",
                  start="waits for the next introduction"),
        ("introductions write no memories of their own", introducer_writes_no_memory),
        said_none("nobody is hostile on day 1", text=HOSTILE),
        judged("day 1 tone is friendly",
               "It is the colony's first day. PASS if no citizen accuses the player of incompetence, neglect or "
               "failure, or is sarcastic toward them. Mild, constructive mentions of needs are fine.",
               exclude_kind="CITIZEN_PAIR"),
    ],
    "campfire": [
        logged_any("a campfire night starts", r"Campfire night in"),
        said_any("stories are told at the fire", **CAMPFIRE),
        said_none("stories are not about the mod's features", text=r"handbook|notice ?board|ballot|lectern|addon|broadcast",
                  **CAMPFIRE),
        judged("stories are about their lives, not advice",
               "Citizens tell stories at a campfire at night. PASS if they tell stories and talk with each other "
               "about their lives, the colony's people and events. FAIL if they explain how things in the colony "
               "work, give the player gameplay advice or tutorial-like instructions, or keep talking about a "
               "handbook, notice board, ballot box or similar features. An answer to a question the player asked is fine.",
               **CAMPFIRE),
        said_any("the player's question is answered", start="player speaks up", end="campfire stopped"),
        judged("the answer fits the question",
               "At the campfire the player asked: 'Can one of you tell me what lies beyond the hills to the east?'. "
               "PASS if a citizen responds to that question in character within these lines; saying they have "
               "never been there or do not know counts, as long as they respond to it. FAIL if nobody responds to it.",
               start="player speaks up", end="campfire stopped"),
    ],
    "night": [
        logged_any("most citizens are asleep at night", r"TC_PROBE .*asleep=([2-9]|\d\d)/"),
        logged_none("no pair conversation starts at night", r"\[RandomConv\] Starting conversation", start="night falls"),
        said_none("no pair conversation goes on at night", kind="CITIZEN_PAIR", start="citizens are asleep"),
    ],
    "windows": [check for window in ("notice board", "ballot box", "suggestion box", "handbook")
                for check in window_checks(window)],
    "election": [
        logged_any("a citizen stands against the player", r"stands for mayor against"),
        logged_any("the rival hands over a pamphlet", r"hands you a campaign pamphlet|campaign pamphlet"),
        logged_any("the rival gives a campaign speech", r"gave a campaign speech"),
        logged_none("the speech is not dropped", r"found no moment for a campaign speech|campaign speech was not given"),
        judged("the speech is a campaign speech",
               "PASS if one line is a short campaign speech by a citizen candidate for mayor: it presents their own "
               "plans or slogan to the colony and may contrast them with the player's. FAIL if there is no such line.",
               kind="ADDON_AMBIENT", start="a citizen stands"),
    ],
    "mayor": [
        said_any("the mayor gives a report", start="the mayor reports", end="a ballot box"),
        said_none("no guard tower upgrade is proposed", text=r"upgrad\w* (the |our |a )?guard ?tower",
                  start="the mayor reports"),
        judged("the proposals fit MineColonies' rules",
               "Rules: a guard tower holds one guard at any level, so upgrading one does not add guards (more towers "
               "or a barracks do); homes count fully from level 3; a placed hut is level 0 until a builder builds it. "
               "PASS if the mayor's report and proposals do not contradict these rules and are about the colony's "
               "actual needs.", start="the mayor reports", end="a ballot box"),
    ],
    "construction": [
        logged_any("the builder takes the order", r"TC_BUILD claimed by"),
        logged_none("the playtest buildings are pasted", r"TC_COLONY .*(did not|could not|failed)"),
        ("the conversation is with the builder", talked_to_builder),
        judged("the builder knows how the house is going",
               "The player asked the colony's builder how the new house is coming along and whether they need anything. "
               "PASS if the builder talks about building the house and what they say fits the game state below "
               "(e.g. just started, how far along, which materials are missing, or a tool they need first: a builder "
               "cannot clear or build without a shovel, axe or pickaxe, and asks for one). FAIL if they seem unaware of it, "
               "claim it is finished, or contradict the game state.",
               facts=r"TC_BUILD", kind="PLAYER", start="asks the builder about the house", end="walks over to another citizen"),
        judged("another citizen knows the house is being built",
               "The player asked a citizen who is not the builder how the new house is coming along. PASS if the "
               "citizen knows a new house is being built and does not contradict the game state below (it is not "
               "finished). FAIL if they seem unaware of it or say it is done.",
               facts=r"TC_BUILD", kind="PLAYER", start="asks another citizen about the house"),
    ],
    "notice": [
        logged_any("the notice is typed in", r"TC_SCENARIO_TYPE bodyInput"),
        logged_any("word of the notice spreads", r"Word of .Harvest fair. has reached"),
        logged_any("replies are pinned", r"Pinned [1-9]\d* replies"),
        logged_any("the board shows the replies", r"TC_SCENARIO_TEXT replies :: "),
        ("the replies are constructive", lambda run, judge: judge(
            "The player posted a notice: 'Harvest fair: Next Sunday we hold a harvest fair at the town hall. Bring your "
            "best pumpkins and something to share!'. These are citizens' written replies pinned under it. PASS if they "
            "reply to the notice itself, constructively and in character (joy, questions, offers, gentle concerns). "
            "Replies may share ideas or tone. FAIL only if they ignore the notice, are hostile, or are near-identical copies of each other.",
            "\n".join(line["text"].split(" :: ", 1)[1] for line in run.logged(r"TC_SCENARIO_TEXT replies :: ")))),
    ],
    "ambient": [
        said_any("citizens speak while the player stands in the colony"),
    ],
}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("scenario")
    parser.add_argument("log")
    parser.add_argument("--report", help="the speech report's JSON (for overlaps)")
    parser.add_argument("--config", help="a Talking Colonists config with the Gemini key, for the judge")
    parser.add_argument("--json", help="write the results as JSON")
    args = parser.parse_args()

    run = Run(args.log)
    judge = Judge(args.config)
    checks = list(COMMON)
    errors = [line for line in run.lines if line["level"] == "ERROR" and OUR_LOGGERS.match(line["logger"])]
    checks.append(("no errors from Talking Colonists or the addons",
                   lambda r, j: (False, errors[0]["text"][:200]) if errors else (True, "")))
    if args.report:
        with open(args.report, encoding="utf-8") as file:
            overlaps = json.load(file).get("overlaps", [])
        checks.append(("no two voices at once within earshot", lambda r, j: (
            (False, f'{len(overlaps)} overlaps, e.g. {overlaps[0]["a"].get("speaker")} and {overlaps[0]["b"].get("speaker")}')
            if overlaps else (True, ""))))
    checks += SCENARIOS.get(args.scenario, [])

    results = []
    for name, check in checks:
        try:
            ok, detail = check(run, judge)
        except Exception as error:  # a broken check must not hide the others
            ok, detail = False, f"check crashed: {type(error).__name__}: {error}"
        status = "SKIP" if ok is None else "PASS" if ok else "FAIL"
        results.append({"check": name, "status": status, "detail": detail})
        print(f"{status}  {name}" + (f"  -- {detail}" if detail and status != "PASS" else ""))
    if args.json:
        with open(args.json, "w", encoding="utf-8") as out:
            json.dump({"scenario": args.scenario, "log": args.log, "results": results}, out, indent=2)
    return 1 if any(r["status"] == "FAIL" for r in results) else 0


if __name__ == "__main__":
    sys.exit(main())

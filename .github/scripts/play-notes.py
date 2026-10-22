"""Turns a release's notes (Markdown, from release-notes.sh) into Google Play's "What's new": plain text, at most 500
characters. Longer notes keep their first whole bullets and point to the full notes on GitHub.

Usage: play-notes.py <notes.md> <full notes URL>  (prints the Play text)
"""
import re
import sys

LIMIT = 500
MORE = "\nFull notes: " + sys.argv[2]

text = open(sys.argv[1], encoding="utf-8").read()
text = re.sub(r"[*`]", "", text)  # Bold and code marks; Play shows plain text.
text = re.sub(r"^#+ ", "", text, flags=re.MULTILINE)
text = re.sub(r"\n +", " ", text).strip()  # The changelog wraps bullets at 120 columns; Play wraps them itself.
if len(text) > LIMIT:
    room = text[: LIMIT - len(MORE)]
    cut = room.rfind("\n")  # Whole bullets only.
    text = (room[:cut] if cut > 0 else room[: room.rfind(" ")] + "…").rstrip() + MORE
print(text)

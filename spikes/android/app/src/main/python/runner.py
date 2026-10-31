"""Runs a step of spikes/plex_spike.py ("all", "save-favourites", "cast" or "test-dvr") inside the app: its printed lines go to the
screen, its files to the app's own storage."""
import os
import sys
from pathlib import Path


class _Lines:
    """sys.stdout / sys.stderr that hands each piece of text to the app."""

    def __init__(self, emit):
        self.emit = emit

    def write(self, text):
        if text:
            self.emit.emit(text)
        return len(text)

    def flush(self):
        pass


def run(files_dir, emit, step="all"):
    """Runs [step] ("all": every step); returns the zip's path (or "" if it wasn't made)."""
    home = Path(str(files_dir))
    os.environ["HOME"] = str(home)  # the sign-in is kept in ~/.myplexplayer-spike.json
    os.chdir(home)
    sys.stdout = sys.stderr = _Lines(emit)
    (home / "spike-results.zip").unlink(missing_ok=True)  # a zip from an earlier run isn't shown as this one's
    sys.argv = ["plex_spike.py", step, "--out", str(home / "spike-out")]
    try:
        import plex_spike
        plex_spike.main()
    except SystemExit as e:
        print(f"\nStopped: {e}")
    except BaseException:  # noqa: BLE001 (shown to the person running it)
        import traceback
        print("\nStopped with an error:\n" + traceback.format_exc())
    zipped = home / "spike-results.zip"
    return str(zipped) if zipped.exists() else ""


def forget(files_dir):
    """Deletes the saved sign-in and the results, for a fresh run."""
    import shutil
    home = Path(str(files_dir))
    for f in (home / ".myplexplayer-spike.json", home / "spike-results.zip"):
        f.unlink(missing_ok=True)
    shutil.rmtree(home / "spike-out", ignore_errors=True)

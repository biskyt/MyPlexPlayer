# Plex API spikes

`plex_spike.py` proves each Plex call MyPlexPlayer relies on, before the app is built on it. It needs only Python 3.9
or later (no packages). What it found is written up in `docs/plex-api.md`.

## In CI
The **Plex spike** workflow (`.github/workflows/plex-spike.yml`) starts a throwaway Plex Media Server with a tiny
generated library (`.github/scripts/start-plex.sh`; no Plex account needed) and runs the `library` and `playback`
steps. It runs on pull requests that change the spike or that set-up, or by hand from the Actions tab.

## On your own server (the owner's part)
Sign-in, choosing a server, Live TV and DVR need a real Plex account and tuner, and intro and credits markers a Plex
Pass server whose libraries have been analysed, so run these on a computer on your home network, from the
repository's folder:

The quickest way is one command: it signs in, runs every step below (tuning and recording included), and puts the
shareable results in `spike-results.zip`:

```sh
python3 spikes/plex_spike.py all
```

Or step by step:

```sh
python3 spikes/plex_spike.py signin          # shows a code: enter it at https://plex.tv/link
python3 spikes/plex_spike.py servers         # lists your servers and which connection answers first
python3 spikes/plex_spike.py library         # libraries, home rows, search, and a progress round trip
python3 spikes/plex_spike.py playback        # direct play, and direct stream (copied, never re-encoded)
python3 spikes/plex_spike.py livetv --tune   # channels, the guide from Plex, and tuning one channel briefly
python3 spikes/plex_spike.py dvr --record    # recording options; schedules one recording and cancels it at once
python3 spikes/plex_spike.py favourites --write-back  # favourite channels: read, saved back unchanged, read again (not in `all`)
python3 spikes/plex_spike.py markers         # intro and credits markers, for Skip intro (only reads)
python3 spikes/plex_spike.py people          # cast members' biographies and other credits, similar titles (only reads)
```

- `library` uses one episode you haven't started (unwatched, no resume point): it sets a resume point, plays it
  past 90% as far as the server can tell, marks it watched and unwatched, and leaves it unwatched and not started,
  as it found it. With no such episode it skips that part.
- `--tune` uses a tuner for a few seconds; `--record` schedules a recording of a programme starting over an hour from
  now and cancels it straight away; `--write-back` saves your favourite channels back exactly as they were read,
  only after a clean and complete read and with a dated backup first (`spike-out/favourites-backup-*.json`; put one
  back with `favourites --restore <file>`). Leave any of them off to only read.
- Your sign-in token is saved in `~/.myplexplayer-spike.json` (only you can read it). Delete that file when done, and
  remove "MyPlexPlayer spike" from plex.tv › Settings › Authorized Devices.

Everything is written to `spike-out/`:
- `report-<step>.md`: what passed and failed in each step's latest run. This is the part to share.
- `sanitised/`: every response with tokens, server ids, addresses, account names, emails and file paths replaced.
  These become the app's test fixtures. **Look through them before sharing**: titles of your films, shows and
  channels are kept as they are.
- `raw/`: the responses as received. Keep these to yourself.

`spike-out/` is ignored by git, so nothing in it is committed by accident. Share the reports and the `sanitised/`
folder (as a zip) in the conversation, or commit the sanitised files on a branch once you've checked them.

## As a phone app
`spikes/android` is the same spike as an Android app (it runs `plex_spike.py` through Chaquopy): it shows the
plex.tv/link code, runs every step, and shares `spike-results.zip`. It's a build of its own, outside the app's:

```sh
./gradlew -p spikes/android assembleDebug   # spikes/android/app/build/outputs/apk/debug/app-debug.apk
```

The **Spike app** workflow runs it on an emulator against a throwaway Plex Media Server (`SpikeOnDeviceTest`).


#!/usr/bin/env bash
# Starts a throwaway, unclaimed Plex Media Server on 127.0.0.1:32400 with a tiny generated library (a film and a
# two-episode series, the second a TV recording with AAC LATM sound; 10 minutes each, since PMS ignores play progress
# in a title's first minute), for spikes and integration tests. No Plex account is needed: an unclaimed server answers local requests without a token.
# Uses Plex's own .deb (not the Docker image: Docker Hub rate-limits shared runners). Needs curl, ffmpeg, python3.
# Usage: start-plex.sh <work dir>. Prints the server's version once its libraries are scanned.
set -euo pipefail
work="$(mkdir -p "$1" && cd "$1" && pwd)"
base=http://127.0.0.1:32400

url=$(curl -fsS https://plex.tv/api/downloads/5.json | python3 -c '
import json, sys
linux = json.load(sys.stdin)["computer"]["Linux"]
print(next(r["url"] for r in linux["releases"] if r["url"].endswith("_amd64.deb")))')
curl -fsSLo "$work/pms.deb" "$url"
dpkg -x "$work/pms.deb" "$work/root"
home="$work/root/usr/lib/plexmediaserver"

# The server boots while the clips are made.
mkdir -p "$work/data"
PLEX_MEDIA_SERVER_APPLICATION_SUPPORT_DIR="$work/data" PLEX_MEDIA_SERVER_HOME="$home" \
  LD_LIBRARY_PATH="$home/lib" nohup "$home/Plex Media Server" > "$work/pms.log" 2>&1 &
pms=$!

clip() { # clip <file> <test pattern> <tone Hz>; a .ts file is a TV recording: AAC sound in LATM, as HD channels send it
  mkdir -p "$(dirname "$1")"
  local latm=()
  [[ $1 == *.ts ]] && latm=(-mpegts_flags latm)
  ffmpeg -loglevel error -y -f lavfi -i "$2=size=320x180:rate=5" -f lavfi -i "sine=frequency=$3:sample_rate=22050" \
    -t 600 -c:v libx264 -preset ultrafast -crf 40 -pix_fmt yuv420p -c:a aac -b:a 32k "${latm[@]}" "$1"
}
clips=()
clip "$work/media/Movies/Test Movie (2020)/Test Movie (2020).mp4" testsrc 440 & clips+=($!)
clip "$work/media/TV/Test Show/Season 01/Test Show - S01E01.mkv" testsrc2 660 & clips+=($!)
clip "$work/media/TV/Test Show/Season 01/Test Show - S01E02.ts" testsrc2 880 & clips+=($!)
for c in "${clips[@]}"; do wait "$c"; done # not plain `wait`: that would wait for the server too

# As start-emulator.sh does: one deadline, and the server's log if it stops or isn't ready in time.
deadline=$((SECONDS + 300))
# On failure, which wait it was and the server's own log (pms.log only has what it prints to the console).
failed() {
  echo "$1 (waiting for: $2)" >&2
  tail -n 50 "$work/pms.log" >&2
  tail -n 80 "$work/data/Plex Media Server/Logs/Plex Media Server.log" >&2 || true
  exit 1
}
waitFor() {
  until "$@"; do
    kill -0 "$pms" 2> /dev/null || failed "Plex Media Server stopped." "$1"
    ((SECONDS <= deadline)) || failed "Plex Media Server wasn't ready within 5 minutes." "$1"
    sleep 1
  done
}
# Libraries can be added only once startup ends: until then /identity carries a startState and adding one fails.
started() {
  curl -fs -H "Accept: application/json" "$base/identity" 2> /dev/null |
    python3 -c 'import json, sys; sys.exit("startState" in json.load(sys.stdin)["MediaContainer"])' 2> /dev/null
}
scanned() { # both episodes are in (all libraries, type 4: episodes)
  curl -fs -H "Accept: application/json" "$base/library/all?type=4" 2> /dev/null |
    python3 -c 'import json, sys; sys.exit(json.load(sys.stdin)["MediaContainer"].get("size", 0) != 2)' 2> /dev/null
}
waitFor started

add() { # add <name> <type> <agent> <scanner> <folder>; the "none" agent keeps the scan offline and predictable
  curl -fsS -X POST -G "$base/library/sections" --data-urlencode "name=$1" --data-urlencode "type=$2" \
    --data-urlencode "agent=$3" --data-urlencode "scanner=$4" --data-urlencode "language=xn" \
    --data-urlencode "location=$work/media/$5" > /dev/null
}
add Movies movie tv.plex.agents.none "Plex Movie" Movies
add "TV Shows" show tv.plex.agents.none "Plex TV Series" TV
waitFor scanned
curl -fsS -H "Accept: application/json" "$base/identity" | python3 -c 'import json, sys; print("PMS", json.load(sys.stdin)["MediaContainer"]["version"])'

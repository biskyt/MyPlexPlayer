#!/usr/bin/env bash
# Installs a static ffmpeg into /usr/local/bin, for start-plex.sh's clips: BtbN's GPL build (libx264) of the newest
# FFmpeg release branch (not master), from GitHub's release downloads, checked against the release's own checksums.
# Not from apt: the runners' Ubuntu mirror has crawled at about 40 KB/s on ffmpeg's 66 MB of packages, so the job ran
# out of time before any test.
set -euo pipefail
base=https://github.com/BtbN/FFmpeg-Builds/releases/download/latest
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
get() { curl -fsSL --retry 5 --retry-all-errors -o "$work/$1" "$base/$1"; }
get checksums.sha256
# e.g. ffmpeg-n8.1-latest-linux64-gpl-8.1.tar.xz: the release branches' builds, newest last.
name=$(grep -oE 'ffmpeg-n[0-9.]+-latest-linux64-gpl-[0-9.]+\.tar\.xz$' "$work/checksums.sha256" | sort -V | tail -n 1)
get "$name"
(cd "$work" && grep " $name\$" checksums.sha256 | sha256sum -c --strict -)
tar -xJf "$work/$name" -C "$work" --wildcards "*/bin/ffmpeg"
sudo install -m 755 "$work"/ffmpeg-*/bin/ffmpeg /usr/local/bin/ffmpeg
ffmpeg -hide_banner -version | sed -n 1p # sed, not head: it reads it all, so pipefail sees no SIGPIPE

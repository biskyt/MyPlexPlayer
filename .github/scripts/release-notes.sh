#!/usr/bin/env bash
# Prints the release notes for version $1: its CHANGELOG.md section, plus the sections of any versions since the last
# published release that never got one of their own (e.g. 0.8.1 and 0.8.2, released together in 0.8.3).
# Needs GH_TOKEN (to list the releases) when run in a workflow. Fails if CHANGELOG.md has no section for $1.
set -euo pipefail
version="$1"
# The newest published release before this one; none (the first release) means every section from this version down.
# Listed first, on its own: a failure to list them must stop here, not pass for "no earlier release".
releases=$(gh release list --exclude-drafts --limit 50 --json tagName --jq '.[].tagName')
previous=$({
  sed 's/^v//' <<< "$releases"
  echo "$version"
} | sort -uV | grep -B1 -xF "$version" | grep -vxF "$version" || true)
# CHANGELOG.md lists the newest version first: take every section from this version down to (not including) the
# previous release. With one section its heading is dropped (the release has the version in its name).
awk -v v="$version" -v prev="$previous" '
  /^## / { if ($2 == v) on = 1; else if ($2 == prev) on = 0; if (on) n++ }
  on { lines[++count] = $0 }
  END {
    if (count == 0) { print "CHANGELOG.md has no section for " v > "/dev/stderr"; exit 1 }
    for (i = 1; i <= count; i++) {
      if (n == 1 && i == 1) continue
      if (n > 1 && lines[i] ~ /^## /) sub(/^## /, "### ", lines[i])
      print lines[i]
    }
  }' CHANGELOG.md

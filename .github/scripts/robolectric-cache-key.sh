#!/usr/bin/env bash
# Prints the cache key for Robolectric's Android images (~/.m2/repository/org/robolectric), shared by ci.yml (which
# saves them) and release.yml: the Robolectric version and the SDK levels the tests ask for (@Config).
set -euo pipefail
version=$(sed -n 's/^robolectric = "\(.*\)"/\1/p' gradle/libs.versions.toml)
sdks=$(grep -rhoE 'sdk = \[[0-9, ]+\]' app/src/test | sort -u | md5sum | cut -c1-8)
echo "robolectric-$version-$sdks"

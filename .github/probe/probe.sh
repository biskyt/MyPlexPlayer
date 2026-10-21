#!/usr/bin/env bash
# Temporary: how archive.org film links answer (redirects, speed, MP4 layout), with the app's User-Agent and others.
APP='MyTVPlayer/0.9.6 (+https://github.com/biskyt/MyTVPlayer)'
for u in https://archive.org/download/BigBuckBunny_124/Content/big_buck_bunny_720p_surround.mp4 \
         https://archive.org/download/Sintel/sintel-2048-stereo.mp4 \
         https://archive.org/services/img/BigBuckBunny_124; do
  for ua in "$APP" "okhttp/4.12.0" "Mozilla/5.0"; do
    echo "=== $u  [UA: $ua]"
    timeout 40 curl -sS -A "$ua" -L -r 0-2097151 -o first.bin --connect-timeout 10 -m 35 \
      -w 'code=%{http_code} redirects=%{num_redirects} type=%{content_type} ttfb=%{time_starttransfer}s total=%{time_total}s bytes=%{size_download} speed=%{speed_download}B/s\nfinal=%{url_effective}\n' "$u"
  done
  timeout 30 curl -sS -A "$APP" -I --connect-timeout 10 -m 25 "$u" | grep -iE '^(HTTP|location|content-length|accept-ranges|content-type|retry-after)'
  timeout 10 python3 - <<'PY'
import struct
d = open('first.bin', 'rb').read()
i = 0
while i + 8 <= len(d):
    size, kind = struct.unpack('>I4s', d[i:i + 8])
    if size == 1: size = struct.unpack('>Q', d[i + 8:i + 16])[0]
    print(f'  atom {kind.decode("latin1")} at {i} size {size}')
    if size < 8: break
    i += size
PY
done

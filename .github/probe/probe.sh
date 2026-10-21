#!/usr/bin/env bash
# Temporary: how archive.org film links answer (redirects, speed, MP4 layout, codecs).
UA='MyTVPlayer/0.9.6 (+https://github.com/biskyt/MyTVPlayer)'
curl -sS -A "$UA" -o movies.m3u https://biskyt.github.io/legalFreeIPTVplaylist/movies.m3u
urls=$(grep -A1 'Open Movies' movies.m3u | grep '^http'; grep -A1 'Movies: Classics' movies.m3u | grep '^https://archive' | head -3)
for u in $urls; do
  echo "=== $u"
  curl -sS -A "$UA" -L -r 0-4194303 -o first.bin -m 120 \
    -w 'code=%{http_code} redirects=%{num_redirects} type=%{content_type} ttfb=%{time_starttransfer}s total=%{time_total}s speed=%{speed_download}B/s\nfinal=%{url_effective}\n' "$u"
  curl -sS -A "$UA" -L -I -m 60 "$u" | grep -iE '^(HTTP|location|content-length|accept-ranges|content-type)'
  python3 - <<'PY'
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
  timeout 90 ffprobe -v error -user_agent "$UA" -show_entries stream=codec_type,codec_name,profile,width,height,channels,bit_rate:format=format_name,bit_rate,duration -of compact "$u"
done

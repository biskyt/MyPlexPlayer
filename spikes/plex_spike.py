#!/usr/bin/env python3
"""
Plex API spikes for MyPlexPlayer: small, throwaway checks that prove each Plex call the app will rely on, before the
app is built on it. Standard library only (Python 3.9+). Results go in docs/plex-api.md.

Every response is saved under --out (default spike-out/): raw/ as received, and sanitised/ with tokens, machine ids,
addresses, account names, emails and file paths replaced, for test fixtures. report-<step>.md lists what passed.

    python3 spikes/plex_spike.py all                     # everything below, in order, then spike-results.zip
    python3 spikes/plex_spike.py signin                  # S1: PIN sign-in at plex.tv/link (saves the token)
    python3 spikes/plex_spike.py servers                 # S2: your servers and which connection answers first
    python3 spikes/plex_spike.py library   [--server URL] # S3: libraries, hubs, search, progress round trip
    python3 spikes/plex_spike.py playback  [--server URL] # S4: direct play and transcoder URLs
    python3 spikes/plex_spike.py livetv    [--server URL] [--tune]    # S5: Live TV channels, guide grid, tuning
    python3 spikes/plex_spike.py dvr       [--server URL] [--record]  # S6: recording options, schedule and cancel
    python3 spikes/plex_spike.py favourites [--server URL] [--write-back] # S7: favourite channels (on plex.tv)
    python3 spikes/plex_spike.py save-favourites         # S7 with --write-back, then spike-results.zip
    python3 spikes/plex_spike.py markers   [--server URL] # S8: intro and credits markers, for skip intro
    python3 spikes/plex_spike.py deletion  [--server URL] [--delete] # S9: deleting, and a show's automatic deletion
    python3 spikes/plex_spike.py people    [--server URL] # S10: cast members' biographies and credits, similar titles
    python3 spikes/plex_spike.py cast                    # S10 (signing in first if needed), then spike-results.zip
    python3 spikes/plex_spike.py series_dvr [--server URL] [--record] # S11: series recordings: made, edited, removed
    python3 spikes/plex_spike.py test-dvr                # S11 with --record (signing in first if needed), then the zip
    python3 spikes/plex_spike.py recordings [--server URL] # S12: recordings in progress and recent ones, and how each plays (only reads)
    python3 spikes/plex_spike.py test-recordings         # S12 (signing in first if needed), then the zip

Without --server the first server from `servers` is used. --tune briefly uses a tuner; --record schedules a recording
of a programme starting in over an hour and cancels it straight away. --write-back (never part of `all`) saves the
account's favourite channels back exactly as read, after a dated backup in spike-out/ (--restore FILE puts one back).
The progress round trip uses an episode nobody has started and leaves it that way. How Live TV is tuned and played
follows Plezy (github.com/edde746/plezy).
"""
import argparse
import json
import os
import re
import sys
import time
import traceback
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

STATE = Path.home() / ".myplexplayer-spike.json"
PRODUCT = "MyPlexPlayer spike"
PLEX_TV = "https://plex.tv"
# Direct stream only, never transcoding (the owner's server is a low-powered machine): Plex's empty "Generic" profile plus
# the codecs the app plays, so the server copies them. A platform profile (Android) re-encodes past its limits.
# Keep the codec lists in step with PlexCodecs (core/.../plex/PlexClient.kt).
HLS_TARGET = ("add-transcode-target(type=videoProfile&context=streaming&protocol=hls&container=mpegts"
              "&videoCodec=h264,hevc,mpeg2video,mpeg4,vp9,av1&audioCodec=aac,ac3,eac3,mp3,mp2,dts,truehd,flac,opus,vorbis)")
DIRECT_STREAM = {"X-Plex-Client-Profile-Name": "Generic", "X-Plex-Client-Profile-Extra": HLS_TARGET}


# ---------------------------------------------------------------- state, output and sanitising

# What every sanitised copy hides (tokens, account names, server names and ids), whichever step found it: one map,
# kept in the state file so later runs and steps hide the same values with the same stand-ins.
KNOWN_SECRETS = {}


# Long values (tokens, uuids, machine ids) are replaced wherever they appear; shorter ones (an account id, a server
# name) only where they are the whole value, so "1234" isn't cut out of a timestamp nor "Home" out of a film's title.
ANYWHERE = 16


def remember(value, stand_in, table=None):
    """Hides [value] as [stand_in] (in every step's copies unless another [table] is given)."""
    if value and len(str(value)) >= 4:  # shorter would match by chance
        (KNOWN_SECRETS if table is None else table)[str(value)] = stand_in


def replace_secret(text, value, stand_in):
    if len(value) >= ANYWHERE:
        return text.replace(value, stand_in)
    v = re.escape(value)
    text = text.replace(f'"{value}"', f'"{stand_in}"')  # a whole JSON string
    return re.sub(rf"(?<=:\s){v}(?=[,\s}}\]])", f'"{stand_in}"', text) if value.isdigit() else text  # a JSON number


def keep_secrets(state):
    """Saves what's hidden so far with the sign-in, for later steps and runs."""
    state["hidden"] = dict(KNOWN_SECRETS)
    save_state(state)


def load_state():
    state = json.loads(STATE.read_text()) if STATE.exists() else {}
    if "clientId" not in state:
        state["clientId"] = str(uuid.uuid4())
        save_state(state)
    return state


def save_state(state):
    # Created readable by the owner only, before the tokens are written.
    with os.fdopen(os.open(STATE, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w") as f:
        f.write(json.dumps(state, indent=2))


class Recorder:
    """Saves responses (raw and sanitised) and the pass/fail of each check."""

    def __init__(self, out: Path, step: str):
        self.raw = out / "raw" / step
        self.clean = out / "sanitised" / step
        self.report = out / f"report-{step}.md"  # each run of a step replaces its report
        self.step = step
        self.results = []
        self.local = {}  # values only this step hides (a one-time sign-in code)
        self.raw.mkdir(parents=True, exist_ok=True)
        self.clean.mkdir(parents=True, exist_ok=True)

    def secret(self, value, stand_in, keep=True):
        """Hides [value] as [stand_in] in this step's copies and, if [keep], in every later step's and run's."""
        remember(value, stand_in, KNOWN_SECRETS if keep else self.local)

    def sanitise(self, text: str) -> str:
        for value, stand_in in sorted({**KNOWN_SECRETS, **self.local}.items(), key=lambda kv: -len(kv[0])):
            text = replace_secret(text, value, stand_in)
        text = re.sub(r'("(?:authToken|accessToken|authenticationToken|token|X-Plex-Token)"\s*:\s*)"[^"]*"',
                      r'\1"TOKEN"', text)
        text = re.sub(r"(X-Plex-Token=)[^&\"'\s]+", r"\1TOKEN", text)
        text = re.sub(r'("(?:email|username|friendlyName|title)"\s*:\s*)"[^"]*@[^"]*"', r'\1"user@example.com"', text)
        # Server-side file and folder paths: keep only the last part ("/media/Film (2020).mkv").
        text = re.sub(r'("(?:file|path)"\s*:\s*)"[^"]*?([^"/\\]*)"', r'\1"/media/\2"', text)
        text = re.sub(r"\b(?:\d{1,3}\.){3}\d{1,3}\b", "192.0.2.1", text)  # IPv4 -> documentation range
        text = re.sub(r"\b(?:\d{1,3}-){3}\d{1,3}\.[0-9a-f]{32}\.plex\.direct", "192-0-2-1.SERVERID.plex.direct", text)
        text = re.sub(r"\b[0-9a-f]{40}\b", "MACHINEID", text)
        text = re.sub(r"\b[0-9a-f]{32}\b", "SERVERID", text)
        return text

    def save(self, name, body):
        if not isinstance(body, str):
            (self.raw / f"{name}.json").write_text(json.dumps(body, indent=2))
            text = json.dumps(without_location(body), indent=2)
            (self.clean / f"{name}.json").write_text(self.sanitise(text))
            return
        text = body
        ext = "json" if text.lstrip().startswith(("{", "[")) else "txt"
        (self.raw / f"{name}.{ext}").write_text(text)
        (self.clean / f"{name}.{ext}").write_text(self.sanitise(text))

    def check(self, name, ok, detail=""):
        self.results.append((name, bool(ok), detail))
        print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f": {detail}" if detail else ""))
        return ok

    def finish(self):
        lines = [f"## {self.step} ({time.strftime('%Y-%m-%d %H:%M')})\n"]
        lines += [f"- {'PASS' if ok else '**FAIL**'} {name}" + (f": {detail}" if detail else "")
                  for name, ok, detail in self.results]
        # Reports are shared too; they never name a server or the account (see servers), only long ids could appear.
        self.report.write_text(self.sanitise("\n".join(lines) + "\n"))  # shared too: no addresses or names
        print(f"\nSaved to {self.raw.parent.parent} (commit only sanitised/; check it first).")


def without_location(body):
    """plex.tv adds where it thinks the caller is (city, postcode, coordinates): never kept in sanitised copies."""
    if isinstance(body, dict):
        return {k: without_location(v) for k, v in body.items() if k not in ("location", "coordinates")}
    if isinstance(body, list):
        return [without_location(v) for v in body]
    return body


# ---------------------------------------------------------------- HTTP

def headers(state, token=None):
    h = {
        "Accept": "application/json",
        "X-Plex-Client-Identifier": state["clientId"],
        "X-Plex-Product": PRODUCT,
        "X-Plex-Version": "0.1",
        "X-Plex-Platform": "Android",
        "X-Plex-Device": "Spike",
        "X-Plex-Device-Name": PRODUCT,
    }
    if token:
        h["X-Plex-Token"] = token
    return h


class Http:
    def __init__(self, state, base, token=None, timeout=20):
        self.state, self.base, self.token, self.timeout = state, base.rstrip("/"), token, timeout

    def url(self, endpoint, **params):
        params = {k: v for k, v in params.items() if v is not None}
        return self.base + endpoint + (("&" if "?" in endpoint else "?") + urllib.parse.urlencode(params) if params else "")

    def request(self, method, endpoint, extra_headers=None, raw=False, json_body=None, **params):
        data = None if json_body is None else json.dumps(json_body).encode()
        extra = {**(extra_headers or {}), **({"Content-Type": "application/json"} if data else {})}
        req = urllib.request.Request(self.url(endpoint, **params), data=data, method=method,
                                     headers={**headers(self.state, self.token), **extra})
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                body = r.read()
                return r.status, dict(r.headers), body if raw else decode(body)
        except urllib.error.HTTPError as e:
            body = e.read()
            return e.code, dict(e.headers), body if raw else decode(body)

    def get(self, endpoint, **params):
        return self.request("GET", endpoint, **params)


def decode(body: bytes):
    text = body.decode("utf-8", "replace")
    try:
        return json.loads(text) if text.strip() else {}
    except ValueError:
        return text


def mc(body):
    """The MediaContainer of a JSON response ({} if it isn't one)."""
    return body.get("MediaContainer", {}) if isinstance(body, dict) else {}


def first(d, key):
    """The first element of the list d[key] ({} if there's none); a lone object, as some replies send, as it is."""
    value = d.get(key)
    return value if isinstance(value, dict) else (value or [{}])[0]


def items(body):
    """A response's Metadata, whether directly in its MediaContainer or in each of its Hubs."""
    m = mc(body)
    return m.get("Metadata", []) + [x for h in m.get("Hub", []) for x in h.get("Metadata", [])]


def size_of(body):
    """A reply's size in KB (as decoded, near what the app would download)."""
    return round(len(body if isinstance(body, str) else json.dumps(body)) / 1024, 1)


def listing(body):
    """"12 titles; hubs ['Similar']; 3.4 KB": what a list reply holds, and its size."""
    hubs = [h.get("title") for h in mc(body).get("Hub", [])]
    return f"{len(items(body))} titles; hubs {hubs}; {size_of(body)} KB"


def part_of(item):
    return first(first(item, "Media"), "Part")


def grid_key(channel):
    return channel.get("gridKey") or channel.get("key")


# ---------------------------------------------------------------- S1, S2: plex.tv

def signin(args, state):
    rec = Recorder(args.out, "s1-signin")
    tv = Http(state, PLEX_TV)
    status, _, pin = tv.request("POST", "/api/v2/pins")  # no strong=true: the short code for plex.tv/link
    rec.save("pins-create", pin)
    if not rec.check("POST /api/v2/pins gives id and a short code", status == 201 and pin.get("code"), f"HTTP {status}"):
        return rec.finish()
    rec.secret(pin["code"], "ABCD", keep=False)
    print(f"\n  Go to https://plex.tv/link and enter: {pin['code']}\n  (waiting up to {pin.get('expiresIn', 900)} s)\n",
          flush=True)
    token, deadline = None, time.time() + pin.get("expiresIn", 900)
    while time.time() < deadline and not token:
        time.sleep(2)
        status, _, polled = tv.get(f"/api/v2/pins/{pin['id']}")
        token = polled.get("authToken") if isinstance(polled, dict) else None
    rec.secret(token, "TOKEN")
    rec.save("pins-claimed", polled)
    if not rec.check("GET /api/v2/pins/{id} gives authToken once claimed", token):
        return rec.finish()
    state["token"] = token
    save_state(state)
    status, user = hide_account(state)
    keep_secrets(state)
    rec.save("user", user)
    rec.check("GET /api/v2/user accepts the token", status == 200, f"HTTP {status}")
    rec.check("token saved", True, str(STATE))
    rec.finish()


def hide_account(state):
    """The account's names and ids, hidden in every sanitised copy; returns the /api/v2/user reply."""
    status, _, user = Http(state, PLEX_TV, state["token"]).get("/api/v2/user")
    for key in ("uuid", "username", "email", "title", "friendlyName", "id"):
        remember(user.get(key) if isinstance(user, dict) else None, f"USER_{key.upper()}")
    return status, user


def hide_known(state):
    """For a sign-in saved before what's hidden was kept with it (the owner's first run): hides the account and the
    saved servers' names before any step saves a copy."""
    if not state.get("token") or "hidden" in state:
        return
    remember(state["token"], "TOKEN")
    try:
        hide_account(state)
    except OSError:
        pass  # offline: the servers step tries again
    for i, s in enumerate(state.get("servers", [])):
        remember(s.get("name"), f"Server {i + 1}")
        remember(s.get("machineId"), f"MACHINEID{i}")
        remember(s.get("token"), "TOKEN")
    keep_secrets(state)


def servers(args, state):
    rec = Recorder(args.out, "s2-servers")
    token = need_token(state)
    if not any(v.startswith("USER_") for v in KNOWN_SECRETS.values()):
        hide_account(state)  # not hidden yet (offline when this run started)
    status, _, res = Http(state, PLEX_TV, token).get("/api/v2/resources", includeHttps=1, includeRelay=1, includeIPv6=1)
    found = [r for r in (res if isinstance(res, list) else []) if "server" in r.get("provides", "")]
    for i, r in enumerate(found):
        rec.secret(r.get("clientIdentifier"), f"MACHINEID{i}")
        rec.secret(r.get("accessToken"), "TOKEN")
        rec.secret(r.get("name"), f"Server {i + 1}")
        rec.secret(r.get("publicAddress"), "198.51.100.1")
    rec.save("resources", res)
    if not rec.check("GET /api/v2/resources lists servers", found, f"HTTP {status}, {len(found)} server(s)"):
        return rec.finish()
    rec.check("each server has its own accessToken", all(r.get("accessToken") for r in found))
    chosen = []
    for i, r in enumerate(found):
        label = f"Server {i + 1}"  # never the server's own name: it would be in the shared report
        conns = r.get("connections", [])
        print(f"\n  {r.get('name')}: {len(conns)} connection(s), owned={r.get('owned')}")
        timings = race(state, r["accessToken"], conns)
        for c, ms, ok in timings:
            print(f"    {'ok ' if ok else 'no '} {ms:6.0f} ms  local={c.get('local')} relay={c.get('relay')}  {c.get('uri')}")
        rec.save(f"race-server{i + 1}", [{"uri": c.get("uri"), "local": c.get("local"),
                 "relay": c.get("relay"), "ms": round(ms), "ok": ok} for c, ms, ok in timings])
        best = pick(timings)
        rec.check(f"a connection answers /identity for {label} (owned: {r.get('owned')})", best, best and best.get("uri"))
        if best:
            chosen.append({"name": r.get("name"), "uri": best["uri"], "token": r["accessToken"],
                           "machineId": r.get("clientIdentifier")})
    state["servers"] = chosen
    keep_secrets(state)
    rec.finish()


def race(state, token, conns):
    def probe(c):
        start = time.time()
        try:
            status, _, body = Http(state, c["uri"], token, timeout=6).get("/identity")
            ok = status == 200 and bool(mc(body).get("machineIdentifier"))
        except Exception:  # noqa: BLE001 (a spike: any failure means "doesn't answer")
            ok = False
        return c, (time.time() - start) * 1000, ok
    with ThreadPoolExecutor(max_workers=max(1, len(conns))) as pool:
        return [f.result() for f in as_completed([pool.submit(probe, c) for c in conns])]


def pick(timings):
    """Prefer a local connection, then remote, then relay; the fastest of each kind."""
    ok = [(c, ms) for c, ms, good in timings if good]

    def rank(c):
        return 0 if c.get("local") and not c.get("relay") else (2 if c.get("relay") else 1)
    return min(ok, key=lambda cm: (rank(cm[0]), cm[1]))[0] if ok else None


def need_token(state):
    if not state.get("token"):
        sys.exit("Sign in first: python3 spikes/plex_spike.py signin")
    return state["token"]


def server_http(args, state):
    if args.server:
        return Http(state, args.server, args.token or None)
    if not state.get("servers"):
        sys.exit("Give --server URL, or run `servers` first.")
    s = state["servers"][0]
    return Http(state, s["uri"], s["token"])


def note_server(rec, http):
    rec.secret(http.token, "TOKEN")
    rec.secret(urllib.parse.urlparse(http.base).hostname, "pms.example")


# ---------------------------------------------------------------- S3: library, hubs, search, progress

def library(args, state):
    rec = Recorder(args.out, "s3-library")
    http = server_http(args, state)
    note_server(rec, http)
    status, _, ident = http.get("/identity")
    rec.save("identity", ident)
    rec.check("GET /identity", status == 200 and mc(ident).get("machineIdentifier"), f"version {mc(ident).get('version')}")

    status, _, sections = http.get("/library/sections")
    rec.save("sections", sections)
    dirs = mc(sections).get("Directory", [])
    rec.check("GET /library/sections lists libraries", status == 200 and dirs,
              ", ".join(f"{d.get('key')}:{d.get('type')}" for d in dirs))
    movie = next((d for d in dirs if d.get("type") == "movie"), None)
    show = next((d for d in dirs if d.get("type") == "show"), None)

    first_movie = first_show = None
    for sec, kind, type_no in ((movie, "movie", 1), (show, "show", 2)):
        if not sec:
            rec.check(f"a {kind} library exists", False)
            continue
        path = f"/library/sections/{sec['key']}/all"
        status, hdrs, page = http.request("GET", path, {"X-Plex-Container-Start": "0", "X-Plex-Container-Size": "1"},
                                          type=type_no, sort="titleSort")
        rec.save(f"{kind}-all-page1", page)
        m = mc(page)
        rec.check(f"{path}?type={type_no} pages with X-Plex-Container-Start/Size headers",
                  status == 200 and m.get("size") == min(1, m.get("totalSize", 0)),
                  f"size={m.get('size')} totalSize={m.get('totalSize')} offset={m.get('offset')}")
        status, _, page_q = http.get(path, type=type_no, **{"X-Plex-Container-Start": 0, "X-Plex-Container-Size": 1})
        rec.check(f"{path} also pages with the same as query parameters", mc(page_q).get("size") == m.get("size"),
                  f"size={mc(page_q).get('size')}")
        top = (m.get("Metadata") or [None])[0]
        if kind == "movie":
            first_movie = top
        else:
            first_show = top
        status, _, sec_detail = http.get(f"/library/sections/{sec['key']}")
        rec.save(f"{kind}-section", sec_detail)
        rec.check(f"GET /library/sections/{{id}} ({kind})", status == 200)

    leaves = []
    if first_movie:
        status, _, meta = http.get(f"/library/metadata/{first_movie['ratingKey']}")
        rec.save("movie-metadata", meta)
        md = first(mc(meta), "Metadata")
        part = part_of(md)
        rec.check("GET /library/metadata/{ratingKey} (movie) has Media[].Part[].key and duration",
                  part.get("key") and md.get("duration"), part.get("key"))
    if first_show:
        key = first_show["ratingKey"]
        status, _, seasons = http.get(f"/library/metadata/{key}/children")
        rec.save("show-children", seasons)
        season_list = mc(seasons).get("Metadata", [])
        rec.check("GET /library/metadata/{show}/children lists seasons", status == 200 and season_list,
                  f"{len(season_list)} season(s), types {sorted({s.get('type') for s in season_list})}")
        if season_list:
            status, _, eps = http.get(f"/library/metadata/{season_list[0]['ratingKey']}/children")
            rec.save("season-children", eps)
            ep_list = mc(eps).get("Metadata", [])
            rec.check("GET /library/metadata/{season}/children lists episodes", ep_list,
                      f"{len(ep_list)} episode(s), index={[e.get('index') for e in ep_list]}")
        status, _, all_leaves = http.get(f"/library/metadata/{key}/allLeaves")
        rec.save("show-allLeaves", all_leaves)
        leaves = mc(all_leaves).get("Metadata", [])
        rec.check("GET /library/metadata/{show}/allLeaves lists every episode", leaves, f"{len(leaves)} episode(s)")

    hubs(http, rec)
    status, _, all_sections = http.get("/library/sections/all")  # the spec's path for the library list
    rec.check("GET /library/sections/all lists the same libraries", len(mc(all_sections).get("Directory", [])) == len(dirs),
              f"HTTP {status}")
    if show:
        # Home's "Recently added series" as shows (the recentlyAdded hub gives seasons), newest episode first.
        status, _, recent = http.request("GET", f"/library/sections/{show['key']}/all",
                                         {"X-Plex-Container-Start": "0", "X-Plex-Container-Size": "20"},
                                         type=2, sort="episode.addedAt:desc")
        rec.save("shows-by-episode-addedAt", recent)
        rec.check("shows sorted by episode.addedAt:desc (recently added series)", status == 200,
                  f"{[m.get('title') for m in mc(recent).get('Metadata', [])][:5]}")
        status, _, ra = http.get(f"/library/sections/{show['key']}/recentlyAdded")
        rec.check("GET /library/sections/{id}/recentlyAdded (TV)", status == 200,
                  f"types {sorted({m.get('type') for m in mc(ra).get('Metadata', [])})}")
    # A transient token needs an account: an unclaimed test server answers 400.
    for method, params in (("POST", {"type": "delegation", "scope": "all"}), ("GET", {})) if http.token else ():
        status, _, tok = http.request(method, "/security/token", **params)
        rec.secret(mc(tok).get("token"), "TRANSIENT_TOKEN")
        rec.check(f"{method} /security/token gives a transient token", status == 200 and mc(tok).get("token"),
                  f"HTTP {status}")
    for q in (first_movie, first_show):
        if q:
            word = q["title"].split()[0]
            status, _, res = http.get("/hubs/search", query=word, limit=10)
            rec.save(f"hubs-search-{q['type']}", res)
            found = items(res)
            rec.check(f"GET /hubs/search?query={word} finds the {q['type']}",
                      any(m.get("ratingKey") == q["ratingKey"] for m in found),
                      f"hub types {[h.get('type') for h in mc(res).get('Hub', []) if h.get('size')]}")
    if first_movie and first_movie.get("thumb"):
        status, hdrs, img = http.request("GET", "/photo/:/transcode", raw=True, url=first_movie["thumb"],
                                         width=240, height=360, minSize=1, upscale=1)
        rec.check("GET /photo/:/transcode returns an image", status == 200 and hdrs.get("Content-Type", "").startswith("image/"),
                  f"HTTP {status} {hdrs.get('Content-Type')} {len(img)} bytes")
    if leaves:
        progress_round_trip(http, rec, leaves)
    rec.finish()


def hubs(http, rec):
    for path, params in (("/hubs/continueWatching", {}), ("/hubs/home/continueWatching", {}),
                         ("/hubs/home/onDeck", {}), ("/library/onDeck", {}),
                         ("/hubs/home/recentlyAdded", {"type": 1}), ("/hubs/home/recentlyAdded", {"type": 2}),
                         ("/library/recentlyAdded", {}), ("/hubs", {})):
        status, _, body = http.get(path, **params)
        name = path.strip("/").replace("/", "-") + "".join(f"-type{v}" for v in params.values())
        rec.save(name, body)
        m = mc(body)
        hub_list = m.get("Hub", [])
        found = items(body)
        rec.check(f"GET {path}{'?type=' + str(params['type']) if params else ''}", status == 200,
                  f"HTTP {status}, {len(found)} item(s), types {sorted({i.get('type') for i in found})}"
                  + (f", hubs {[h.get('hubIdentifier') for h in hub_list][:8]}" if hub_list else ""))


def progress_round_trip(http, rec, leaves):
    """On an episode nobody has started: timeline and /:/progress save a resume point (Continue watching shows it);
    a session played past 90% is marked watched by the server itself; unscrobble and scrobble set it by hand. It is
    left as it was found: unwatched, with no resume point."""
    fresh = [e for e in leaves if not e.get("viewCount") and not e.get("viewOffset") and (e.get("duration") or 0) > 120000]
    if not fresh:
        rec.check("progress round trip skipped: no unwatched, unstarted episode over 2 minutes", True)
        return
    episode = fresh[0]
    key, duration = episode["ratingKey"], episode["duration"]
    library_item = {"identifier": "com.plexapp.plugins.library", "key": key}

    def state():
        _, _, meta = http.get(f"/library/metadata/{key}")
        return first(mc(meta), "Metadata")

    def report(session, state_name, ms):
        return http.request("POST", "/:/timeline", {"X-Plex-Session-Identifier": session}, ratingKey=key,
                            key=f"/library/metadata/{key}", state=state_name, time=ms, duration=duration)

    # PMS ignores progress in a title's first minute ("Ignoring premature play progress" in its log).
    position = max(90000, int(duration * 0.3))
    session = str(uuid.uuid4())
    for state_name in ("playing", "paused", "stopped"):
        status, _, body = report(session, state_name, position)
        rec.save(f"timeline-{state_name}", body)
        rec.check(f"POST /:/timeline state={state_name}", status == 200, f"HTTP {status}")
    offset = state().get("viewOffset")
    rec.check("viewOffset saved by the timeline", offset and abs(offset - position) < 5000, f"viewOffset={offset}")
    _, _, cw = http.get("/hubs/continueWatching")
    rec.save("continueWatching-after-timeline", cw)
    rec.check("the episode shows in /hubs/continueWatching", any(m.get("ratingKey") == key for m in items(cw)))

    status, _, _ = http.request("PUT", "/:/progress", time=position + 30000, state="stopped", **library_item)
    offset = state().get("viewOffset")
    rec.check("PUT /:/progress (Plexopedia's call) saves viewOffset too", status == 200 and offset == position + 30000,
              f"HTTP {status}, viewOffset={offset}")

    # Playing on past the server's watched threshold (90% by default) marks it watched, with no scrobble.
    session = str(uuid.uuid4())
    for fraction in (0.5, 0.7, 0.93):
        report(session, "playing", int(duration * fraction))
    report(session, "stopped", int(duration * 0.93))
    now = state()
    rec.check("a session played past 90% is marked watched by the server", now.get("viewCount") and not now.get("viewOffset"),
              f"viewCount={now.get('viewCount')} viewOffset={now.get('viewOffset')}")

    status, _, _ = http.request("PUT", "/:/unscrobble", **library_item)
    now = state()
    rec.check("PUT /:/unscrobble clears watched and the resume point", status == 200 and not now.get("viewCount")
              and not now.get("viewOffset"), f"HTTP {status}")
    status, _, _ = http.request("PUT", "/:/scrobble", **library_item)
    rec.check("PUT /:/scrobble marks it watched", status == 200 and state().get("viewCount"), f"HTTP {status}")
    http.request("PUT", "/:/unscrobble", **library_item)
    now = state()
    rec.check("the episode is left as it was (unwatched, not started)", not now.get("viewCount") and not now.get("viewOffset"))


# ---------------------------------------------------------------- S4: playback

def playback(args, state):
    rec = Recorder(args.out, "s4-playback")
    http = server_http(args, state)
    note_server(rec, http)
    _, _, sections = http.get("/library/sections")
    movie = next((d for d in mc(sections).get("Directory", []) if d.get("type") == "movie"), None)
    _, _, page = http.get(f"/library/sections/{movie['key']}/all", type=1,
                          **{"X-Plex-Container-Start": 0, "X-Plex-Container-Size": 1}) if movie else (0, {}, {})
    item = first(mc(page), "Metadata")
    if not rec.check("a movie library with a movie in it", item.get("ratingKey")):
        return rec.finish()
    _, _, meta = http.get(f"/library/metadata/{item['ratingKey']}")
    part = part_of(first(mc(meta), "Metadata"))
    # As the player will fetch it: the token in the URL, no X-Plex headers.
    direct = http.url(part["key"], **({"X-Plex-Token": http.token} if http.token else {}))
    try:
        with urllib.request.urlopen(urllib.request.Request(direct, headers={"Range": "bytes=0-1023"}), timeout=20) as r:
            status, hdrs = r.status, dict(r.headers)
    except urllib.error.HTTPError as e:
        status, hdrs = e.code, dict(e.headers)
    rec.check("direct play: the Part key with ?X-Plex-Token serves byte ranges", status == 206,
              f"HTTP {status}, {hdrs.get('Content-Type')}, Accept-Ranges={hdrs.get('Accept-Ranges')}")
    rec.save("direct-play-url", direct)

    session = str(uuid.uuid4())
    common = dict(path=f"/library/metadata/{item['ratingKey']}", mediaIndex=0, partIndex=0, protocol="hls",
                  directPlay=0, directStream=1, directStreamAudio=1, subtitles="none", session=session)
    sid = {"X-Plex-Session-Identifier": session, **DIRECT_STREAM}
    status, _, decision = http.request("GET", "/video/:/transcode/universal/decision", sid, **common)
    rec.save("direct-stream-decision", decision)
    copied = stream_decisions(decision)
    rec.check("direct stream: the decision copies video and audio, re-encoding nothing",
              status == 200 and copied and all(d == "copy" for _, _, d in copied) and any(t == 1 for t, _, _ in copied),
              f"{copied}")
    hls_ok = check_hls(http, rec, "direct-stream", "/video/:/transcode/universal/start.m3u8", sid, common)
    status, _, _ = http.request("GET", "/video/:/transcode/universal/stop", sid, session=session)
    rec.check("GET /video/:/transcode/universal/stop?session= ends it", hls_ok and status == 200, f"HTTP {status}")
    rec.finish()


def stream_decisions(decision):
    """(stream type, codec, decision) for the video (1) and audio (2) streams in a transcoder decision."""
    return [(s.get("streamType"), s.get("codec"), s.get("decision")) for m in mc(decision).get("Metadata", [])
            for md in m.get("Media", []) for p in md.get("Part", []) for s in p.get("Stream", [])
            if s.get("streamType") in (1, 2)]


def check_hls(http, rec, name, endpoint, extra_headers, params):
    """Fetches an HLS master playlist and its first variant; True if both look right."""
    status, hdrs, playlist = http.request("GET", endpoint, extra_headers, raw=True, **params)
    text = playlist.decode("utf-8", "replace")
    rec.save(f"{name}-start-m3u8", text)
    if not rec.check(f"GET {endpoint} gives an HLS playlist", status == 200 and "#EXTM3U" in text,
                     f"HTTP {status}, {hdrs.get('Content-Type')}"):
        return False
    child = next((ln for ln in text.splitlines() if ln and not ln.startswith("#")), None)
    if not child:
        return rec.check("the playlist names a variant", False)
    child_path = child if child.startswith("/") else endpoint.rsplit("/", 1)[0] + "/" + child
    status, _, sub = http.request("GET", child_path, extra_headers, raw=True)
    sub_text = sub.decode("utf-8", "replace")
    rec.save(f"{name}-media-m3u8", sub_text)
    return rec.check("the variant playlist lists segments", status == 200 and "#EXTINF" in sub_text, f"HTTP {status}")


# ---------------------------------------------------------------- S5, S6: Live TV, guide, DVR

def epg_provider(http, rec):
    """The guide's provider and its grid address, from /media/providers (as Plezy finds them)."""
    status, _, providers = http.get("/media/providers")
    rec.save("media-providers", providers)
    found = [p for p in mc(providers).get("MediaProvider", []) if "livetv" in str(p.get("protocols", ""))
             or str(p.get("identifier", "")).startswith("tv.plex.providers.epg")]
    rec.check("GET /media/providers lists a Live TV guide provider", found,
              ", ".join(f"{p.get('identifier')} ({p.get('title')})" for p in found))
    if not found:
        return None, None
    grid = next((f.get("key") for f in found[0].get("Feature", []) if f.get("type") == "grid"), None)
    rec.check("the provider has a grid feature (the guide's address)", grid, grid)
    return found[0], grid


def channel_key(ch):
    """As Plezy: the key the DVR's ChannelMapping and the tune call use."""
    return ch.get("key") or ch.get("ratingKey") or ch.get("identifier") or ch.get("id")


def guide_window(http, grid, start, end):
    """Every channel's programmes overlapping [start, end] (epoch seconds), in one call."""
    return http.get(grid, **{"endsAt>": int(start), "beginsAt<": int(end)})


def livetv(args, state):
    rec = Recorder(args.out, "s5-livetv")
    http = server_http(args, state)
    note_server(rec, http)
    status, _, dvrs = http.get("/livetv/dvrs")
    rec.save("livetv-dvrs", dvrs)
    dvr_list = mc(dvrs).get("Dvr", []) or mc(dvrs).get("DVR", [])
    rec.check("GET /livetv/dvrs lists a DVR (as Dvr or DVR)", dvr_list,
              f"keys {[d.get('key') for d in dvr_list]}, container keys {sorted(mc(dvrs))[:8]}")
    provider, grid = epg_provider(http, rec)
    if not provider:
        return rec.finish()
    prefix = "/" + provider["identifier"]
    rec.save("provider-features", provider.get("Feature", []))
    channels = []
    for path in ("/lineups/plex/channels", f"{prefix}/lineups/dvr/channels"):
        status, _, lineup = http.get(path)
        rec.save(path.strip("/").replace("/", "-").replace(":", "_"), lineup)
        found = mc(lineup).get("Channel", [])
        rec.check(f"GET {path}", status == 200, f"HTTP {status}, {len(found)} channel(s); "
                  f"fields {sorted(found[0])[:14] if found else []}")
        channels = channels or found
    if dvr_list:
        enabled = {m.get("channelKey") for m in dvr_list[0].get("ChannelMapping", []) if m.get("enabled") in (True, 1, "1")}
        rec.check("channel keys match the DVR's enabled ChannelMapping",
                  not enabled or any(channel_key(c) in enabled for c in channels), f"{len(enabled)} enabled mapping(s)")
    if not (channels and grid):
        return rec.finish()
    now = time.time()
    status, _, window = guide_window(http, grid, now - 3600, now + 5 * 3600)
    rec.save("grid-window-all-channels", window)
    progs = mc(window).get("Metadata", [])
    rec.check("GET {grid}?endsAt>=&beginsAt<= gives every channel's next hours in one call (guide straight from PMS)",
              status == 200 and progs, f"HTTP {status}, {len(progs)} programme(s)")
    if progs:
        media = first(progs[0], "Media")
        rec.check("programmes carry start/end times and a channel", media.get("beginsAt") and media.get("endsAt"),
                  f"Media keys {sorted(media)[:14]}; Channel {first(progs[0], 'Channel').get('id')}")
    ch = channels[0]
    status, _, grid_day = http.get(grid, channelGridKey=ch.get("gridKey") or channel_key(ch), date=time.strftime("%Y-%m-%d"))
    rec.save("grid-one-channel-today", grid_day)
    rec.check("GET {grid}?channelGridKey=&date= (one channel, one day; the spec's form)", status == 200,
              f"HTTP {status}, {len(mc(grid_day).get('Metadata', []))} programme(s)")
    if args.tune and dvr_list:
        tune(http, rec, dvr_list[0], ch)
    rec.finish()


def tune(http, rec, dvr, ch):
    """As Plezy: tune, then play the session's path through the universal transcoder; a stopped timeline frees
    the tuner."""
    session = str(uuid.uuid4())
    status, _, tuned = http.request("POST", f"/livetv/dvrs/{dvr['key']}/channels/{channel_key(ch)}/tune",
                                    **{"X-Plex-Session-Identifier": session})
    rec.save("tune", tuned)
    m = mc(tuned)
    # The tuned programme: in the grab, where Metadata is a single object (as on the owner's server), else Metadata[0].
    meta = first(first(first(m, "MediaSubscription"), "MediaGrabOperation"), "Metadata") or first(m, "Metadata")
    rec.check(f"POST /livetv/dvrs/{{dvr}}/channels/{channel_key(ch)}/tune", status == 200 and meta.get("key"),
              f"HTTP {status}, session path {meta.get('key')}, programme {meta.get('ratingKey')}")
    if not meta.get("key"):
        return
    play = str(uuid.uuid4())
    params = dict(path=meta["key"], protocol="hls", directPlay=0, directStream=1, directStreamAudio=1, session=play,
                  subtitles="none", location="lan", fastSeek=1, hasMDE=1)
    sid = {"X-Plex-Session-Identifier": session, **DIRECT_STREAM}
    status, _, decision = http.request("GET", "/video/:/transcode/universal/decision", sid, **params)
    rec.save("live-decision", decision)
    copied = stream_decisions(decision)
    rec.check("live: the decision copies video and audio (a direct stream, no transcoding)",
              status == 200 and copied and all(d == "copy" for _, _, d in copied), f"{copied}")
    check_hls(http, rec, "live", "/video/:/transcode/universal/start.m3u8", sid, params)
    status, _, sessions = http.get("/livetv/sessions")
    rec.save("livetv-sessions", sessions)
    status, _, _ = http.request("POST", "/:/timeline", {"X-Plex-Session-Identifier": session}, ratingKey=meta.get("ratingKey"),
                                key=meta["key"], state="stopped", time=10000, duration=10000)
    rec.check("a stopped timeline for the session path (frees the tuner)", status == 200, f"HTTP {status}")


def pref_value(setting):
    """A template setting as Plex sends it: its value, else its default; true/false in lower case."""
    value = setting.get("value")
    if value in (None, ""):
        value = setting.get("default", "")
    return str(value).lower() if isinstance(value, bool) else value


def subscribe_query(option, **overrides):
    """POST /media/subscriptions' query for a template [option]: its parameters as they are (repeated hints/params
    keys included), what it leaves out, and every setting as prefs[id]=value (its value, else its default, else an
    [overrides] value), as Plezy sends them. Returns the query and the prefs sent."""
    query = option.get("parameters", "")
    for name in ("targetLibrarySectionID", "targetSectionLocationID", "type"):
        if f"{name}=" not in query and option.get(name) is not None:
            query += f"&{name}={option[name]}"
    prefs = {st["id"]: pref_value(st) for st in option.get("Setting", []) if st.get("id")}
    prefs.update({k: v for k, v in overrides.items() if k in prefs})
    if prefs:
        query += "&" + urllib.parse.urlencode({f"prefs[{k}]": v for k, v in prefs.items()})
    return query.lstrip("&"), prefs


def dvr(args, state):
    rec = Recorder(args.out, "s6-dvr")
    http = server_http(args, state)
    note_server(rec, http)
    for path in ("/media/subscriptions", "/media/subscriptions/scheduled"):
        status, _, body = http.get(path, includeGrabs=1, includeStorage=1)
        rec.save(path.strip("/").replace("/", "-"), body)
        rec.check(f"GET {path}", status == 200, f"HTTP {status}, {mc(body).get('size')} item(s)")
    _, grid = epg_provider(http, rec)
    if not grid:
        return rec.finish()
    now = time.time()
    _, _, window = guide_window(http, grid, now + 3600, now + 12 * 3600)  # any time of day, over midnight too
    later = next((p for p in mc(window).get("Metadata", []) if first(p, "Media").get("beginsAt", 0) > now + 3600), None)
    if not rec.check("a programme starting in over an hour", later, later and later.get("title")):
        return rec.finish()
    status, _, template = http.get("/media/subscriptions/template", guid=later.get("guid"))
    rec.save("subscription-template", template)
    tmpl = first(mc(template), "SubscriptionTemplate").get("MediaSubscription", [])
    rec.check("GET /media/subscriptions/template?guid=", status == 200 and tmpl,
              f"{len(tmpl)} option(s): {[t.get('title') for t in tmpl]}")
    if not (args.record and tmpl):
        return rec.finish()
    one_off = next((t for t in tmpl if t.get("type") in (4, 1)), tmpl[-1])  # an episode or movie: this airing only
    query, prefs = subscribe_query(one_off)
    rec.check("the recording's settings, sent as prefs[...]", True,
              ", ".join(f"{k}={v}" for k, v in prefs.items()) or "none offered")
    status, _, created = http.request("POST", f"/media/subscriptions?{query}")
    rec.save("subscription-created", created)
    sub = first(mc(created), "MediaSubscription")
    rec.check("POST /media/subscriptions schedules it", status in (200, 201) and sub.get("key"), f"HTTP {status}")
    status, _, scheduled = http.get("/media/subscriptions/scheduled")
    rec.save("scheduled-after-create", scheduled)
    grabs = [g for g in mc(scheduled).get("MediaGrabOperation", []) if str(g.get("mediaSubscriptionID")) == str(sub.get("key"))]
    rec.check("it shows in /media/subscriptions/scheduled", grabs,
              f"{len(grabs)} grab(s), ids {[g.get('id') for g in grabs]}, keys {[g.get('key') for g in grabs]}")
    if sub.get("key"):
        status, _, _ = http.request("DELETE", f"/media/subscriptions/{sub['key']}")
        rec.check("DELETE /media/subscriptions/{id} cancels it", status == 200, f"HTTP {status}")
    rec.finish()


# ---------------------------------------------------------------- S11: series recordings, for the DVR tab
def series_dvr(args, state):
    """What the DVR tab and Record series need, on a show with no recording rule yet: every grab status and rule
    field already on the server, then a series rule (All Episodes, new
    airings only) made, read, edited and put back, one of its airings cancelled, and the rule deleted, so nothing is
    left. Only rules and grabs this step made are changed or deleted."""
    rec = Recorder(args.out, "s11-series-dvr")
    http = server_http(args, state)
    note_server(rec, http)
    status, _, rules = http.get("/media/subscriptions", includeGrabs=1, includeStorage=1)
    rec.save("rules-before", rules)
    existing = mc(rules).get("MediaSubscription", [])
    rec.check(f"GET /media/subscriptions (HTTP {status}): the rules there already", status == 200,
              "; ".join(f"type {r.get('type')} '{r.get('title')}': fields {sorted(r)}, "
                        f"show {first(r, 'Directory').get('guid') or first(r, 'Directory').get('title')}" for r in existing))
    _, _, scheduled = http.get("/media/subscriptions/scheduled")
    rec.save("scheduled-before", scheduled)
    rec.check("the statuses of scheduled recordings", True,
              str(Counter(g.get("status") for g in mc(scheduled).get("MediaGrabOperation", []))))
    provider, grid = epg_provider(http, rec)
    if not grid:
        return rec.finish()
    now = time.time()
    _, _, window = guide_window(http, grid, now + 3600, now + 48 * 3600)
    programmes = [p for p in mc(window).get("Metadata", []) if first(p, "Media").get("beginsAt", 0) > now + 3600]


    # A show with two or more airings ahead, no rule and nothing scheduled: the rule made below changes nothing else.
    taken = {str(r.get("title")) for r in existing} | {str(first(r, "Directory").get("guid")) for r in existing}
    taken |= {str(g.get("Metadata", {}).get("grandparentGuid")) for g in mc(scheduled).get("MediaGrabOperation", [])}
    # New airings carry Media[].premiere=1 (the second run's show had only repeats, so new-only scheduled nothing):
    # a show with two or more new airings ahead records new airings only; failing that, one with two or more airings
    # records new and repeat ones, so there are airings to cancel one of.
    def counts(only_new):
        return Counter(p.get("grandparentGuid") for p in programmes if p.get("type") == "episode"
                       and (not only_new or str(first(p, "Media").get("premiere")) == "1"))
    new, every = counts(True), counts(False)
    rec.check("new airings in the next 48 hours (Media[].premiere=1)", True,
              f"{sum(new.values())} of {sum(every.values())} episode airings")
    free = [p for p in programmes if p.get("type") == "episode" and p.get("grandparentGuid") not in taken
            and p.get("grandparentTitle") not in taken]
    show = next((p for p in free if new[p.get("grandparentGuid")] > 1), None)
    only_new = show is not None
    show = show or next((p for p in free if every[p.get("grandparentGuid")] > 1), None)
    if not rec.check("a show with two or more airings ahead and no rule", show,
                     show and f"{(new if only_new else every)[show.get('grandparentGuid')]} "
                              f"{'new' if only_new else 'new or repeat'} airings"):
        return rec.finish()
    status, _, template = http.get("/media/subscriptions/template", guid=show.get("guid"))
    options = first(mc(template), "SubscriptionTemplate").get("MediaSubscription", [])
    series = next((o for o in options if o.get("type") == 2), None)
    if not rec.check("the template's All Episodes option", series, series and series.get("parameters")):
        return rec.finish()
    if not args.record:
        return rec.finish()

    query, prefs = subscribe_query(series, onlyNewAirings=1 if only_new else 0)
    status, _, created = http.request("POST", f"/media/subscriptions?{query}")
    rec.save("series-created", created)
    rule = first(mc(created), "MediaSubscription")
    key = rule.get("key")
    if not rec.check(f"POST /media/subscriptions: a series rule, {'new airings only' if only_new else 'new and repeat'} "
                     f"(HTTP {status})", key,
                     f"fields {sorted(rule)}"):
        return rec.finish()
    try:
        series_checks(http, rec, key)
    finally:
        status, _, _ = http.request("DELETE", f"/media/subscriptions/{key}")
        rec.check("DELETE /media/subscriptions/{id}: the rule and its airings are gone", status == 200, f"HTTP {status}")
        _, _, after = http.get("/media/subscriptions/scheduled")
        left = [g for g in mc(after).get("MediaGrabOperation", []) if str(g.get("mediaSubscriptionID")) == str(key)]
        rec.check("nothing of it is left scheduled", not left, f"{len(left)} left")
    rec.finish()


def series_checks(http, rec, key):
    """The series rule [key]: reading it, its airings, editing it, and cancelling one airing."""
    status, _, body = http.get(f"/media/subscriptions/{key}", includeGrabs=1)
    rec.save("series-read", body)
    rule = first(mc(body), "MediaSubscription")
    rec.check(f"GET /media/subscriptions/{{id}} (HTTP {status})", rule, f"fields {sorted(rule)}; show {first(rule, 'Directory')}")
    # A series rule's airings weren't scheduled straight away (first run): ask the server to work its rules out
    # (as Plezy's Refresh does), then look again every few seconds for up to a minute.
    status, _, _ = http.request("POST", "/media/subscriptions/process")
    rec.check(f"POST /media/subscriptions/process (HTTP {status})", status in (200, 204))
    started, grabs = time.time(), []
    while not grabs and time.time() - started < 60:
        time.sleep(3)
        _, _, scheduled = http.get("/media/subscriptions/scheduled")
        grabs = [g for g in mc(scheduled).get("MediaGrabOperation", []) if str(g.get("mediaSubscriptionID")) == str(key)]
    rec.check("how long its airings took to be scheduled", True, f"{round(time.time() - started)} s")
    rec.save("series-scheduled", {"MediaContainer": {"MediaGrabOperation": grabs}})
    rec.check("its airings are scheduled", grabs, f"{len(grabs)} airing(s); statuses {Counter(g.get('status') for g in grabs)}")

    setting = next((s for s in rule.get("Setting", []) if s.get("id") == "endOffsetMinutes"), {})
    before = setting.get("value", setting.get("default", 0))
    status, _, edited = http.request("PUT", f"/media/subscriptions/{key}?prefs%5BendOffsetMinutes%5D=1")
    rec.save("series-edited", edited)
    now = next((s.get("value") for s in first(mc(http.get(f"/media/subscriptions/{key}")[2]), "MediaSubscription")
                .get("Setting", []) if s.get("id") == "endOffsetMinutes"), None)
    rec.check(f"PUT /media/subscriptions/{{id}}?prefs[endOffsetMinutes]=1 (HTTP {status}), read back", str(now) == "1",
              f"was {before}, now {now}")
    status, _, _ = http.request("PUT", f"/media/subscriptions/{key}?" + urllib.parse.urlencode({"prefs[endOffsetMinutes]": before}))
    rec.check("and put back", status == 200, f"HTTP {status}")

    if grabs:
        grab = grabs[-1]
        path = grab.get("key") or f"/media/grabbers/operations/{grab.get('id')}"
        status, _, _ = http.request("DELETE", path)
        _, _, after = http.get("/media/subscriptions/scheduled")
        still = [g for g in mc(after).get("MediaGrabOperation", []) if str(g.get("mediaSubscriptionID")) == str(key)]
        rec.check(f"DELETE {{grab key}}: one airing cancelled, the rule kept (HTTP {status})",
                  status == 200 and len(still) == len(grabs) - 1, f"{len(grabs)} then {len(still)} airing(s)")
        rules = mc(http.get("/media/subscriptions")[2]).get("MediaSubscription", [])
        rec.check("the rule is still there", any(str(r.get("key")) == str(key) for r in rules))


# ---------------------------------------------------------------- S12: recordings in progress and recent ones
def grab_programme(grab):
    """A recording's programme: in `Metadata` while scheduled or recording, in `Video` once complete (S12, first run)."""
    return grab.get("Metadata") or grab.get("Video") or {}


def library_match(http, rec, name, grab):
    """The library item of a recording (grab): looked up by the programme's guid, as the library may list it, and by
    its show's guid, then the episode's series and number."""
    meta = grab_programme(grab)
    found = {}
    for label, endpoint, params in (
            ("by guid", "/library/all", {"guid": meta.get("guid")}),
            ("by guid, episodes", "/library/all", {"type": 4, "guid": meta.get("guid")}),
            ("recently added", "/library/recentlyAdded", {})):
        status, _, body = http.get(endpoint, **params)
        rec.save(f"{name}-library-{label.replace(' ', '-').replace(',', '')}", body)
        items = [m for m in mc(body).get("Metadata", []) if m.get("guid") == meta.get("guid")]
        rec.check(f"{name}: the library item {label} ({endpoint})", items,
                  f"HTTP {status}, {len(items)} match(es) of {mc(body).get('size')}")
        for m in items:
            found.setdefault(m.get("ratingKey"), m)
    show_guid = meta.get("grandparentGuid")
    if show_guid:
        status, _, body = http.get("/library/all", type=2, guid=show_guid)
        rec.save(f"{name}-library-show", body)
        shows = [m for m in mc(body).get("Metadata", []) if m.get("guid") == show_guid]
        rec.check(f"{name}: its show in the library, by guid", shows, f"HTTP {status}, {len(shows)} match(es) of {mc(body).get('size')}")
        for show in shows[:1]:
            _, _, leaves = http.get(f"/library/metadata/{show.get('ratingKey')}/allLeaves")
            same = [m for m in mc(leaves).get("Metadata", []) if str(m.get("parentIndex")) == str(meta.get("parentIndex"))
                    and str(m.get("index")) == str(meta.get("index"))]
            rec.check(f"{name}: the episode in its show, by series and number", same,
                      f"{len(same)} match(es); guids {[m.get('guid') for m in same]} (the recording's {meta.get('guid')})")
            for m in same:
                found.setdefault(m.get("ratingKey"), m)
    return list(found.values())


def part_check(http, rec, name, item):
    """An item's file: its details twice, 15 s apart (does it grow?), and its first bytes, as a player asks."""
    key = item.get("ratingKey")
    _, _, first_body = http.get(f"/library/metadata/{key}")
    rec.save(f"{name}-item", first_body)
    meta = first(mc(first_body), "Metadata")
    media = first(meta, "Media")
    part = first(media, "Part")
    rec.check(f"{name}: its file", part.get("key"),
              f"duration {media.get('duration')} ms, size {part.get('size')}, container {part.get('container') or media.get('container')}, "
              f"fields {sorted(meta)}")
    if not part.get("key"):
        return
    status, hdrs, body = http.request("GET", part["key"], {"Range": "bytes=0-65535"}, raw=True)
    rec.check(f"{name}: the first 64 KB of its file", status in (200, 206),
              f"HTTP {status}, {len(body)} bytes, Content-Range {hdrs.get('Content-Range')}, length {hdrs.get('Content-Length')}")
    time.sleep(15)
    _, _, later_body = http.get(f"/library/metadata/{key}")
    later = first(first(first(mc(later_body), "Metadata"), "Media"), "Part")
    rec.check(f"{name}: 15 s later", True,
              f"size {part.get('size')} then {later.get('size')}, duration {part.get('duration')} then {later.get('duration')}")


def linked_items(http, rec, name, grabs, limit=3):
    """The library items of recordings (grabs) through their `linkedKey` (S12, second run: two complete grabs had one,
    `/library/metadata/<id>`; the others, deleted, had none). Each one's file is checked once."""
    linked = [g for g in grabs if g.get("linkedKey")]
    rec.check(f"{name}: recordings with a linkedKey", linked,
              f"{len(linked)} of {len(grabs)}; keys {[g.get('linkedKey') for g in linked[:limit]]}, "
              f"sections {[g.get('linkedLibrarySectionID') for g in linked[:limit]]}")
    for i, g in enumerate(linked[:limit]):
        status, _, body = http.get(g["linkedKey"])
        rec.save(f"{name}-linked-{i}", body)
        meta = first(mc(body), "Metadata")
        same = meta.get("guid") == grab_programme(g).get("guid")
        rec.check(f"{name}: GET its linkedKey ({i})", status == 200 and meta,
                  f"HTTP {status}, guid {'the same as' if same else 'not'} the programme's, type {meta.get('type')}, "
                  f"ratingKey {meta.get('ratingKey')}")
        if meta and i == 0:
            part_check(http, rec, f"{name}-linked", meta)


def same_programme(item, meta):
    """Whether a library item is the recording's programme: by guid, else (Plex's library and the guide can give
    different guids, S12 second run) a show's title, series and episode number, or a film's title and year."""
    if item.get("guid") and item.get("guid") == meta.get("guid"):
        return True
    if meta.get("type") == "episode":
        return (item.get("grandparentTitle") == meta.get("grandparentTitle")
                and str(item.get("parentIndex")) == str(meta.get("parentIndex"))
                and str(item.get("index")) == str(meta.get("index")))
    return item.get("title") == meta.get("title") and str(item.get("year")) == str(meta.get("year"))


def newest_in_section(http, rec, name, grab):
    """A recording in progress may be in its library before it ends: the newest items (by addedAt) of every film and
    TV library, matched by [same_programme]. Plex puts a recording in a TV or film library by its kind, or in one
    library it is told to always use (the owner), and the grab's own librarySectionID is the guide service's, not the
    library's (S12 second run: 404), so every library is looked in."""
    meta = grab_programme(grab)
    _, _, body = http.get("/library/sections")
    sections = [(d.get("key"), 4 if d.get("type") == "show" else 1)
                for d in mc(body).get("Directory", []) if d.get("type") in ("show", "movie")]
    found = []
    for section, kind in sections:
        status, _, body = http.get(f"/library/sections/{section}/all", type=kind, sort="addedAt:desc",
                                   **{"X-Plex-Container-Start": 0, "X-Plex-Container-Size": 20})
        rec.save(f"{name}-section-{section}-newest", body)
        items = mc(body).get("Metadata", [])
        same = [m for m in items if same_programme(m, meta)]
        rec.check(f"{name}: in library section {section}'s newest 20", same,
                  f"HTTP {status}, {len(same)} match(es); newest addedAt {[m.get('addedAt') for m in items[:3]]}, "
                  f"the recording began {first(meta, 'Media').get('beginsAt')}")
        found += same
    return found


def playlist_facts(text):
    """What an HLS playlist says about where it starts: its type, first sequence, segments and first date-time."""
    if not isinstance(text, str):
        return {"not a playlist": str(text)[:80]}
    lines = text.splitlines()
    find = lambda tag: next((l.split(":", 1)[1] for l in lines if l.startswith(tag)), None)
    return {"type": find("#EXT-X-PLAYLIST-TYPE"), "sequence": find("#EXT-X-MEDIA-SEQUENCE"),
            "segments": sum(1 for l in lines if l.startswith("#EXTINF")), "first date-time": find("#EXT-X-PROGRAM-DATE-TIME"),
            "variants": [l for l in lines if l and not l.startswith("#") and ".m3u8" in l][:2]}


def session_playlists(http, rec, grabs):
    """Each live session (a recording in progress is one) and its HLS playlist with offset -1 (as listed), 0 and none:
    does one start at the recording's beginning? Compared with the recordings' start times. Only reads."""
    status, _, body = http.get("/livetv/sessions")
    rec.save("livetv-sessions", body)
    sessions = mc(body).get("Metadata", [])
    rec.check("GET /livetv/sessions", status == 200, f"HTTP {status}, {len(sessions)} session(s)")
    starts = [first(grab_programme(g), "Media").get("beginsAt") for g in grabs]
    rec.check("the recordings' start times (beginsAt)", True, f"{starts}")
    for i, s in enumerate(sessions[:2]):
        st, _, detail = http.get(s.get("key"))
        rec.save(f"session-{i}", detail)
        rec.check(f"session {i}: GET its key", st == 200, f"HTTP {st}, fields {sorted(first(mc(detail), 'Metadata'))}")
        part_key = first(first(s, "Media"), "Part").get("key", "")
        base = part_key.split("?", 1)[0]
        for label, params in (("offset -1", {"offset": "-1.000000"}), ("offset 0", {"offset": "0"}), ("no offset", {})):
            st, _, text = http.request("GET", base, raw=False, **params)
            facts = playlist_facts(text)
            for variant in facts.get("variants") or []:
                url = variant if variant.startswith("/") else base.rsplit("/", 1)[0] + "/" + variant
                st, _, text = http.request("GET", url, raw=False)
                facts = {"variant": True, **playlist_facts(text)}
                break
            rec.save(f"session-{i}-playlist-{label.replace(' ', '')}", text if isinstance(text, str) else json.dumps(text))
            rec.check(f"session {i}: its playlist, {label}", st == 200, f"HTTP {st}, {facts}")


def transcode_sessions(m):
    """The capture buffers a tune or live timeline answer carries (as Plezy reads them): under `CaptureBuffer`, at the
    top level, or in the tuned programme."""
    found = []
    for holder in (first(m, "CaptureBuffer"), m, first(first(first(m, "MediaSubscription"), "MediaGrabOperation"), "Metadata")):
        ts = holder.get("TranscodeSession") if isinstance(holder, dict) else None
        found += ts if isinstance(ts, list) else [ts] if isinstance(ts, dict) else []
    return found


def recording_tune(http, rec, grab):
    """"Watch from start" (as Plezy, which offers it on a programme being recorded): tune the recording's channel and
    read the capture buffer Plex gives, then play from offset 0 and from the programme's start. The app must offer
    "from start" only when the buffer reaches back to the programme's start (Plezy offers it whenever it has a buffer).
    Tunes the channel already being recorded, then frees the session."""
    media = first(grab_programme(grab), "Media")
    channel, begins = media.get("channelIdentifier"), media.get("beginsAt")
    _, _, dvrs = http.get("/livetv/dvrs")
    dvr = first(mc(dvrs), "Dvr") or first(mc(dvrs), "DVR")
    session = str(uuid.uuid4())
    status, _, tuned = http.request("POST", f"/livetv/dvrs/{dvr.get('key')}/channels/{channel}/tune",
                                    **{"X-Plex-Session-Identifier": session})
    rec.save("recording-tune", tuned)
    m = mc(tuned)
    meta = first(first(first(m, "MediaSubscription"), "MediaGrabOperation"), "Metadata") or first(m, "Metadata")
    buffers = transcode_sessions(m)
    rec.check("tune the recording's channel (no new tuner: it is recording)", status == 200 and meta.get("key"),
              f"HTTP {status}, session path {meta.get('key')}, grab status "
              f"{first(first(m, 'MediaSubscription'), 'MediaGrabOperation').get('status')}")
    rec.check("its capture buffer (TranscodeSession)", buffers,
              f"{[{k: b.get(k) for k in sorted(b) if 'ffset' in k or 'ime' in k or k in ('key', 'progress', 'duration')} for b in buffers]}; "
              f"the programme began at {begins}, now {int(time.time())}")
    if not meta.get("key"):
        return
    status, _, timeline = http.request("POST", "/:/timeline", {"X-Plex-Session-Identifier": session},
                                       ratingKey=meta.get("ratingKey"), key=meta["key"], state="playing", time=0,
                                       duration=0, playbackTime=0, hasMDE=1)
    rec.save("recording-timeline", timeline)
    later = transcode_sessions(mc(timeline))
    rec.check("a live timeline: the capture buffer", later,
              f"HTTP {status}, {[{k: b.get(k) for k in sorted(b) if 'ffset' in k or 'ime' in k} for b in later]}")
    sid = {"X-Plex-Session-Identifier": session, **DIRECT_STREAM}
    # Plezy's "from start" (playback_start.dart): offset = the programme's beginsAt minus the buffer's timeStamp, which
    # is negative when the buffer began after the programme; Plezy then uses the buffer's start. The owner reports that
    # Plezy reaches the recording's start, so the negative offset is tried as it is, and each playlist's first date-time
    # says where it really starts.
    origin = next((b.get("timeStamp") for b in buffers if b.get("timeStamp")), None)
    from_start = round(float(begins) - float(origin)) if begins and origin else None
    rec.check("the programme's start as an offset into the buffer (Plezy's way)", from_start is not None,
              f"beginsAt {begins} minus timeStamp {origin} = {from_start} s")
    for label, offset in (("offset 0", 0), ("no offset", None), ("offset to programme start", from_start)):
        if label.startswith("offset to") and offset is None:
            continue
        params = dict(path=meta["key"], protocol="hls", directPlay=0, directStream=1, directStreamAudio=1,
                      session=str(uuid.uuid4()), subtitles="none", location="lan", fastSeek=1, hasMDE=1, offset=offset)
        status, _, decision = http.request("GET", "/video/:/transcode/universal/decision", sid, **params)
        rec.save(f"recording-decision-{label.replace(' ', '')}", decision)
        copied = stream_decisions(decision)
        rec.check(f"{label}: the decision copies video and audio", status == 200 and copied and all(d == "copy" for _, _, d in copied),
                  f"HTTP {status}, {copied}")
        if check_hls(http, rec, f"recording-{label.replace(' ', '')}", "/video/:/transcode/universal/start.m3u8", sid, params):
            text = (rec.clean / f"recording-{label.replace(' ', '')}-media-m3u8.txt").read_text()
            rec.check(f"{label}: where its playlist starts", True, f"{playlist_facts(text)}")
    status, _, _ = http.request("POST", "/:/timeline", {"X-Plex-Session-Identifier": session}, ratingKey=meta.get("ratingKey"),
                                key=meta["key"], state="stopped", time=10000, duration=10000)
    rec.check("a stopped timeline for the session (frees it)", status == 200, f"HTTP {status}")


def recordings(args, state):
    """S12 (second run): what the DVR tab needs to play a recording that is still going, and recent ones. The
    scheduled list (and the rules' own list of grabs), each finished recording's library item through its linkedKey,
    a recording in progress in its library section, and the live sessions' playlists. Only reads."""
    rec = Recorder(args.out, "s12-recordings")
    http = server_http(args, state)
    note_server(rec, http)
    status, _, body = http.get("/media/subscriptions/scheduled")
    rec.save("scheduled", body)
    grabs = mc(body).get("MediaGrabOperation", [])
    rec.check(f"GET /media/subscriptions/scheduled (HTTP {status})", status == 200,
              str(Counter(g.get("status") for g in grabs)))
    status, _, body = http.get("/media/subscriptions", includeGrabs=1, includeStorage=1)
    rec.save("subscriptions-with-grabs", body)
    rec.check("GET /media/subscriptions?includeGrabs=1", status == 200,
              f"HTTP {status}, linkedKey {'found' if 'linkedKey' in json.dumps(body) else 'not found'}")
    now_s = time.time()
    going = [g for g in grabs if g.get("status") in ("inprogress", "grabbing", "postprocessing")]
    done = [g for g in grabs if g.get("status") == "complete"]
    if rec.check("a recent recording (complete) that still exists", done, f"{len(done)} complete"):
        linked_items(http, rec, "recent", done)
    if rec.check("a recording in progress (start one first, for this step)", going,
                 "; ".join(f"{g.get('status')}, started "
                           f"{round((now_s - float(first(grab_programme(g), 'Media').get('beginsAt', now_s))) / 60)} min ago, "
                           f"fields {sorted(g)}" for g in going[:2])):
        linked_items(http, rec, "in-progress", going)
        for item in newest_in_section(http, rec, "in-progress", going[0])[:1]:
            part_check(http, rec, "in-progress", item)
        session_playlists(http, rec, going)
        try:  # last, and never in the way of the report
            recording_tune(http, rec, going[0])
        except Exception as e:  # noqa: BLE001 (a spike: say what failed)
            rec.check("tune the recording's channel", False, f"{type(e).__name__}: {e}")
    rec.finish()


def test_recordings(args, state):
    """The spike app's "Test recordings": S12 (signing in and finding the servers first if needed), then the zip."""
    if not state.get("token"):
        signin(args, state)
    if not state.get("servers") and not args.server:
        servers(args, state)
    recordings(args, state)
    share_results(args)


# ---------------------------------------------------------------- S7: favourite channels
EPG_CLOUD = "https://epg.provider.plex.tv"
PROVIDER_VERSION = {"X-Plex-Provider-Version": "5.1"}


def favourites(args, state):
    """The account's favourite Live TV channels, kept on plex.tv (as Plezy reads and saves them), checked against the
    server's channels. With --write-back, the same list is saved back unchanged and read again, proving a change would
    be kept: only after a clean, complete read, and with a dated backup first (put back with --restore FILE)."""
    rec = Recorder(args.out, "s7-favourites")
    cloud = Http(state, EPG_CLOUD, need_token(state))

    def read():
        status, _, body = cloud.request("GET", "/settings/favoriteChannels", PROVIDER_VERSION)
        found = mc(body).get("FavoriteChannel", [])
        return status, body, [found] if isinstance(found, dict) else found  # a lone one may come as an object

    def source(f):
        return str(f.get("source", ""))

    def same(a, b):
        return sorted(json.dumps(f, sort_keys=True) for f in a) == sorted(json.dumps(f, sort_keys=True) for f in b)

    if args.restore:
        saved = json.loads(Path(args.restore).read_text())
        status, _, _ = cloud.request("PUT", "/settings/favoriteChannels", PROVIDER_VERSION, json_body=saved)
        _, _, after = read()
        rec.check(f"restored {len(saved)} favourite(s) from {args.restore}", 200 <= status < 300 and same(after, saved),
                  f"HTTP {status}, {len(after)} favourite(s) now")
        return rec.finish()
    status, body, favs = read()
    rec.save("favourite-channels", body)
    total = mc(body).get("totalSize", mc(body).get("size", len(favs)))
    read_ok = status == 200 and isinstance(body, dict) and "MediaContainer" in body
    rec.check("GET epg.provider.plex.tv/settings/favoriteChannels gives the account's favourite channels",
              read_ok, f"HTTP {status}, {len(favs)} favourite(s) of {total}; fields {sorted(favs[0]) if favs else []}; "
                       f"container keys {sorted(mc(body))[:8]}")
    if not favs:
        rec.check("the account has favourite channels to check", False,
                  "mark one or two channels as favourites in Plex or Plezy, then run this again")
        return rec.finish()
    named = all(source(f).startswith("server://") and f.get("id") for f in favs)
    rec.check("each names its server and guide (source server://{machine id}/{provider}) and channel (id)", named,
              f"sources {sorted({source(f).split('/')[-1] for f in favs})}")
    http = server_http(args, state)
    note_server(rec, http)
    provider, _ = epg_provider(http, rec)
    if provider:
        _, _, lineup = http.get(f"/{provider['identifier']}/lineups/dvr/channels")
        # The key the app tunes a channel by (and would save a favourite under).
        keys = {channel_key(c) for c in mc(lineup).get("Channel", [])}
        ours = [f for f in favs if source(f).endswith("/" + provider["identifier"])]
        rec.check("a favourite's id is its channel's key in the server's lineup", ours and all(f.get("id") in keys for f in ours),
                  f"{sum(f.get('id') in keys for f in ours)} of {len(ours)} favourite(s) on this server matched")
    if not args.write_back:
        return rec.finish()
    if not (read_ok and named and int(total) == len(favs)):
        rec.check("write-back skipped: the list wasn't read cleanly and in full, so nothing was written", False)
        return rec.finish()
    backup = args.out / f"favourites-backup-{time.strftime('%Y%m%d-%H%M%S')}.json"  # never overwritten
    backup.parent.mkdir(parents=True, exist_ok=True)
    backup.write_text(json.dumps(favs, indent=1))
    # As Plezy sends them (and the app will): a JSON array of each one's source, id, title and vcn.
    sent = [{k: str(f.get(k, "")) for k in ("source", "id", "title", "vcn")} for f in favs]
    status, _, put = cloud.request("PUT", "/settings/favoriteChannels", PROVIDER_VERSION, json_body=sent)
    rec.save("favourite-channels-put", put)
    rec.check("PUT the same list (a JSON array of source, id, title, vcn, as Plezy sends it) is accepted",
              200 <= status < 300, f"HTTP {status}")
    _, _, after = read()
    channels = lambda fs: sorted((source(f), str(f.get("id"))) for f in fs)  # noqa: E731
    kept = channels(after) == channels(favs)
    rec.check("read again, the same channels are favourites, in full", kept and same(after, favs),
              f"{len(after)} favourite(s) after, {len(favs)} before; same channels: {kept}; same order: "
              f"{[(source(f), f.get('id')) for f in after] == [(source(f), f.get('id')) for f in favs]}")
    if not kept:  # put straight back as they were, from the backup (as read)
        status, _, _ = cloud.request("PUT", "/settings/favoriteChannels", PROVIDER_VERSION, json_body=favs)
        _, _, restored = read()
        rec.check("the favourites were put back from the backup", same(restored, favs),
                  f"HTTP {status}, {len(restored)} favourite(s) now; if not, run: favourites --restore {backup}")
    rec.finish()


# ---------------------------------------------------------------- S8: intro and credits markers
MARKER_SAMPLE = 60  # the newest episodes, and films, of each library: likeliest to have been analysed
MARKER_BATCH = 20  # ids per request: a long list of full items is slow on a small server
MARKER_SLACK_MS = 1000  # a final credits marker can end a moment past the listed length
# Marker settings (only a Plex Pass server lists them; their names aren't documented): any whose id says so.
MARKER_SETTING = re.compile(r"marker|intro|credit", re.I)


def markers(args, state):
    """Intro and credits markers (Plex Pass finds them), for Skip intro and Skip credits: how many of the newest
    episodes and films have each, then one of each fetched as the app will before playing it, with and without
    includeMarkers=1, for the markers' fields and times. Only reads."""
    rec = Recorder(args.out, "s8-markers")
    http = server_http(args, state)
    note_server(rec, http)
    _, _, sections = http.get("/library/sections")
    dirs = [d for d in mc(sections).get("Directory", []) if d.get("type") in ("movie", "show")]

    # For information: the settings that decide whether the server looks for markers. Only those are kept.
    settings = {}
    for label, path in [("server", "/:/prefs")] + [(f"library {d.get('key')} ({d.get('type')})",
                                                    f"/library/sections/{d['key']}/prefs") for d in dirs]:
        _, _, body = http.get(path)
        settings.update({f"{label}: {st.get('id')}": pref_value(st) for st in mc(body).get("Setting", [])
                         if MARKER_SETTING.search(st.get("id", ""))})
    rec.save("marker-settings", settings)
    rec.check("marker settings (for information)", True,
              ", ".join(f"{k}={v}" for k, v in settings.items()) or "none listed (a Plex Pass server lists them)")

    def newest(type_no):
        """The ids of each library's newest [type_no] items (1 films, 4 episodes)."""
        kind = "movie" if type_no == 1 else "show"
        return [i["ratingKey"] for d in dirs if d.get("type") == kind
                for i in mc(http.get(f"/library/sections/{d['key']}/all", type=type_no, sort="addedAt:desc",
                                     **{"X-Plex-Container-Start": 0, "X-Plex-Container-Size": MARKER_SAMPLE})[2]
                            ).get("Metadata", [])]

    for kind, keys in (("episodes", newest(4)), ("films", newest(1))):
        if not keys:  # no library of this kind
            continue
        # How many have each kind of marker: several ids (comma-separated) per call.
        got, statuses = [], set()
        for at in range(0, len(keys), MARKER_BATCH):
            status, _, body = http.get(f"/library/metadata/{','.join(keys[at:at + MARKER_BATCH])}", includeMarkers=1)
            statuses.add(status)
            got += mc(body).get("Metadata", [])
        counts = Counter(t for i in got for t in {m.get("type") for m in i.get("Marker", [])})
        wanted = ("intro", "credits") if kind == "episodes" else ("credits",)
        for t in wanted:
            rec.check(f"{kind} with {t} markers", counts[t], f"{counts[t]} of {len(keys)} (HTTP {sorted(statuses)}); "
                      f"by type: {dict(counts) or 'none'}" + ("" if counts[t] else
                                                             " (Plex Pass finds them when a library is analysed)"))
        # As the app will ask, before playing it: one item, with includeMarkers=1. One with the marker wanted most,
        # else any marker, else the newest (so this is tried even if the batches sent no markers).
        sample = (next((i for i in got if any(m.get("type") == wanted[0] for m in i.get("Marker", []))), None)
                  or next((i for i in got if i.get("Marker")), None) or {"ratingKey": keys[0]})
        path = f"/library/metadata/{sample['ratingKey']}"
        status, _, reply = http.get(path, includeMarkers=1)
        rec.save(f"{kind}-item-with-markers", reply)
        item = first(mc(reply), "Metadata")
        marks = item.get("Marker", [])
        length = item.get("duration") or 0
        inside = marks and all(0 <= m.get("startTimeOffset", -1) < m.get("endTimeOffset", -1) <= length + MARKER_SLACK_MS
                               for m in marks)
        rec.check(f"GET {{one {kind[:-1]}}}?includeMarkers=1 (HTTP {status}): its markers, in ms within its length",
                  inside,
                  f"fields {sorted({k for m in marks for k in m})}; " +
                  "; ".join(f"{m.get('type')} {m.get('startTimeOffset')}-{m.get('endTimeOffset')} ms" +
                            (" (final)" if m.get("final") else "") for m in marks) + f"; length {item.get('duration')} ms")
        unasked = first(mc(http.get(path)[2]), "Metadata").get("Marker")
        rec.check(f"the same request without includeMarkers=1 ({kind[:-1]})", True,
                  "markers sent anyway" if unasked else
                  "no markers: the app must ask for them" if marks else "no markers either way")
    rec.finish()


# ---------------------------------------------------------------- S9: deleting, and a show's automatic deletion
AUTO_DELETE = "autoDeletionItemPolicyWatchedLibrary"  # 0 never, 1 after a day, 7 after a week, 100 on next refresh


def deletion(args, state):
    """Deleting media, for the options menu's Delete, and a show's "delete watched episodes" setting, for Delete
    after. Reads where the server allows deletion (the server's setting, and anything a library or item says), and a
    show's prefs; sets the show's setting to a week and back. With --delete (a throwaway server only: it deletes a
    file), turns deletion off and checks a delete is refused, then on, deletes an episode and checks it's gone, then
    puts the setting back."""
    rec = Recorder(args.out, "s9-deletion")
    http = server_http(args, state)
    note_server(rec, http)
    _, _, root = http.get("/")
    rec.save("root", root)
    allowed = mc(root).get("allowMediaDeletion")
    rec.check("GET / says whether the server allows deleting media (allowMediaDeletion)", allowed is not None,
              f"allowMediaDeletion={allowed}")
    _, _, prefs = http.get("/:/prefs")
    setting = next((x for x in mc(prefs).get("Setting", []) if x.get("id") == "allowMediaDeletion"), None)
    rec.check("GET /:/prefs has the setting (allowMediaDeletion)", setting is not None,
              f"{setting and pref_value(setting)} ({setting and setting.get('label')})")
    _, _, sections = http.get("/library/sections")
    dirs = mc(sections).get("Directory", [])
    per_library = sorted({k for d in dirs for k in d if "delet" in k.lower()})
    rec.check("a library's own say on deleting (any field naming it; none expected)", True,
              f"fields: {per_library or 'none'}")
    show_lib = next((d for d in dirs if d.get("type") == "show"), None)
    if not show_lib:
        rec.check("a TV library exists", False)
        return rec.finish()
    _, _, shows = http.get(f"/library/sections/{show_lib['key']}/all", type=2)
    show = first(mc(shows), "Metadata")
    _, _, episodes = http.get(f"/library/metadata/{show.get('ratingKey')}/allLeaves")
    eps = mc(episodes).get("Metadata", [])
    item_fields = sorted({k for e in eps for k in e if "delet" in k.lower()})
    rec.check("an item's own say on deleting (any field naming it)", True, f"fields: {item_fields or 'none'}")

    def show_pref():
        # Read as python-plexapi does: the show with includePreferences=1 (GET .../prefs lists nothing)
        _, _, body = http.get(f"/library/metadata/{show.get('ratingKey')}", includePreferences=1)
        rec.save("show-prefs", body)
        settings = (first(mc(body), "Metadata").get("Preferences") or {}).get("Setting", [])
        found = next((x for x in settings if x.get("id") == AUTO_DELETE), None)
        return found

    found = show_pref()
    rec.check(f"GET /library/metadata/{{show}}?includePreferences=1 has {AUTO_DELETE}", found is not None,
              f"value {found and pref_value(found)}, choices {found and found.get('enumValues')}")
    if found is not None:
        before = pref_value(found)
        status, _, _ = http.request("PUT", f"/library/metadata/{show.get('ratingKey')}/prefs", **{AUTO_DELETE: 7})
        after = show_pref()
        rec.check(f"PUT the show's prefs with {AUTO_DELETE}=7 (after a week) is kept", 200 <= status < 300 and
                  str(pref_value(after)) == "7", f"HTTP {status}, now {after and pref_value(after)}")
        http.request("PUT", f"/library/metadata/{show.get('ratingKey')}/prefs", **{AUTO_DELETE: before})
        back = show_pref()
        rec.check("and put back as it was", str(pref_value(back)) == str(before), f"{before} -> {back and pref_value(back)}")
    if not args.delete:
        return rec.finish()
    if len(eps) < 2:
        rec.check("an episode to delete", False)
        return rec.finish()
    victim = eps[-1]["ratingKey"]
    was = setting and pref_value(setting)
    http.request("PUT", "/:/prefs", allowMediaDeletion=0)
    status, _, _ = http.request("DELETE", f"/library/metadata/{victim}")
    rec.check("with deletion off, DELETE /library/metadata/{id} is refused", status >= 400, f"HTTP {status}")
    http.request("PUT", "/:/prefs", allowMediaDeletion=1)
    _, _, root = http.get("/")
    rec.check("turned on, / says so", str(mc(root).get("allowMediaDeletion")).lower() in ("1", "true"),
              f"allowMediaDeletion={mc(root).get('allowMediaDeletion')}")
    status, _, _ = http.request("DELETE", f"/library/metadata/{victim}")
    rec.check("with deletion on, DELETE /library/metadata/{id} deletes the episode", 200 <= status < 300, f"HTTP {status}")
    status, _, _ = http.get(f"/library/metadata/{victim}")
    rec.check("and it's gone from the library", status == 404, f"HTTP {status}")
    http.request("PUT", "/:/prefs", allowMediaDeletion=1 if str(was).lower() in ("1", "true") else 0)
    rec.finish()


# ---------------------------------------------------------------- S10: cast members, their credits, similar titles
DISCOVER = "https://discover.provider.plex.tv"
# Where a person's other films and shows might be on Discover: none is documented, so each is tried and reported.
CREDIT_PATHS = {"media": "/library/people/{}/media", "credits": "/library/people/{}/credits",
                "hubs": "/library/people/{}/hubs", "hubs-people": "/hubs/people/{}"}


def people(args, state):
    """A cast member's page and similar titles: the cast of the newest show and one of its episodes (with each
    person's Plex id, tagKey), a person on the server, the person's biography from Plex Discover (with the account's
    token), the likely calls for their other credits, and similar or related titles from the server and from
    Discover, with each reply's size. Only reads."""
    rec = Recorder(args.out, "s10-people")
    http = server_http(args, state)
    note_server(rec, http)
    discover = Http(state, DISCOVER, need_token(state))
    _, _, sections = http.get("/library/sections")
    shows = [d for d in mc(sections).get("Directory", []) if d.get("type") == "show"]
    if not rec.check("a TV library exists", shows):
        return rec.finish()
    _, _, page = http.get(f"/library/sections/{shows[0]['key']}/all", type=2, sort="addedAt:desc",
                          **{"X-Plex-Container-Start": 0, "X-Plex-Container-Size": 1})
    key = first(mc(page), "Metadata").get("ratingKey")
    if not rec.check("a show in it", key):
        return rec.finish()
    status, _, body = http.get(f"/library/metadata/{key}")
    show = first(mc(body), "Metadata")
    rec.save("show", body)
    roles = show.get("Role", [])
    rec.check(f"the show's cast (HTTP {status})", roles,
              f"{len(roles)} people; fields {sorted({k for r in roles for k in r})}; "
              f"{sum(1 for r in roles if r.get('tagKey'))} with a tagKey")

    _, _, leaves = http.get(f"/library/metadata/{key}/allLeaves", **{"X-Plex-Container-Start": 0, "X-Plex-Container-Size": 1})
    episode = first(mc(leaves), "Metadata").get("ratingKey")
    if episode:
        status, _, body = http.get(f"/library/metadata/{episode}")
        rec.save("episode", body)
        item = first(mc(body), "Metadata")
        cast = item.get("Role", [])
        # The same call gives the episode page its ratings and its full badges (HDR, Dolby Vision): the list has neither.
        streams = [s for m in item.get("Media", []) for p in m.get("Part", []) for s in p.get("Stream", [])]
        hdr = sorted({k for s in streams for k in s if k.startswith(("DOVI", "colo"))})
        rec.check(f"an episode's own cast (HTTP {status})", True,
                  f"{len(cast)} people ({sum(1 for r in cast if r.get('tagKey'))} with a tagKey); "
                  f"ratings {[r.get('image') for r in item.get('Rating', [])]}; HDR fields {hdr}; {size_of(body)} KB")

    person = next((r for r in roles if r.get("tagKey")), roles[0] if roles else {})
    if person.get("id"):
        status, _, body = http.get(f"/library/people/{person['id']}")
        rec.save("person-on-server", body)
        found = first(mc(body), "Directory")
        rec.check(f"GET /library/people/{{id}} on the server (HTTP {status})", found.get("tagKey"),
                  f"fields {sorted(found)}")
        status, _, body = http.get(f"/library/people/{person['id']}/media")
        rec.check(f"GET /library/people/{{id}}/media on the server (HTTP {status}): this library only", True,
                  f"{len(mc(body).get('Metadata', []))} titles")
    tag_key = person.get("tagKey") or (found.get("tagKey") if person.get("id") else None)
    if not rec.check("a person with a tagKey (their Plex id)", tag_key):
        return rec.finish()

    status, _, body = discover.get(f"/library/people/{tag_key}")
    rec.save("person-discover", body)
    who = first(mc(body), "Metadata")
    rec.check(f"Discover: GET /library/people/{{tagKey}} (HTTP {status}): biography", who.get("summary"),
              f"fields {sorted(who)}; {len(who.get('summary') or '')} characters; {size_of(body)} KB")
    for name, path in CREDIT_PATHS.items():
        status, _, body = discover.get(path.format(tag_key), includeMeta=1,
                                       **{"X-Plex-Container-Start": 0, "X-Plex-Container-Size": 50})
        rec.save(f"credits-{name}", body)
        rec.check(f"Discover: GET {path.format('{tagKey}')} (HTTP {status}): their other titles", True,
                  f"{listing(body)}; total {mc(body).get('totalSize')}; "
                  f"fields {sorted({k for i in items(body)[:5] for k in i})}")

    for name, path in (("similar", "/library/metadata/{}/similar"), ("related", "/hubs/metadata/{}/related")):
        status, _, body = http.get(path.format(key), count=10)
        rec.save(f"server-{name}", body)
        rec.check(f"server: GET {path.format('{show}')} (HTTP {status}): similar, in this library", True,
                  listing(body))
    guid = show.get("guid", "")
    plex_id = guid.rsplit("/", 1)[-1] if guid.startswith("plex://") else None
    if rec.check("the show's Plex guid", plex_id, guid or "none"):
        status, _, body = discover.get(f"/library/metadata/{plex_id}/related")
        rec.save("discover-related", body)
        rec.check(f"Discover: GET /library/metadata/{{guid}}/related (HTTP {status}): similar, anywhere", True,
                  listing(body))
        status, _, body = discover.get(f"/library/metadata/{plex_id}", includeGuids=1)
        rec.save("discover-title", body)
        title = first(mc(body), "Metadata")
        rec.check(f"Discover: GET /library/metadata/{{guid}} (HTTP {status}): a title's page off the server",
                  title.get("summary"), f"fields {sorted(title)}; cast {len(title.get('Role', []))}; "
                  f"ratings {[r.get('image') for r in title.get('Rating', [])]}; {size_of(body)} KB")
    rec.finish()


def run_all(args, state):
    """Every step in turn (signing in first if needed, tuning and recording included), carrying on past a step
    that fails, then the shareable results zipped into spike-results.zip."""
    args.tune = args.record = True
    steps = ([] if state.get("token") else [signin]) + [servers, library, playback, livetv, dvr, favourites, markers, deletion, people, series_dvr]
    for step in steps:
        print(f"\n=== {step.__name__} ===", flush=True)
        try:
            step(args, state)
        except (Exception, SystemExit):  # noqa: BLE001 (a spike: note it and go on to the next step)
            rec = Recorder(args.out, f"{step.__name__}-error")
            rec.check(f"{step.__name__} ran to the end", False, traceback.format_exc(limit=3).replace("\n", " | "))
            rec.finish()
    share_results(args)


def save_favourites(args, state):
    """The spike app's "Test saving favourite channels": favourites with --write-back (signing in first if needed),
    then the results zipped as `all` does."""
    if not state.get("token"):
        signin(args, state)
    if not state.get("servers") and not args.server:
        servers(args, state)
    args.write_back = True
    favourites(args, state)
    share_results(args)


def cast(args, state):
    """The spike app's "Test cast and similar titles": people (signing in and finding the servers first if needed),
    then the results zipped as `all` does."""
    if not state.get("token"):
        signin(args, state)
    if not state.get("servers") and not args.server:
        servers(args, state)
    people(args, state)
    share_results(args)


def test_dvr(args, state):
    """The spike app's "Test series recording": series_dvr with --record (signing in and finding the servers first
    if needed), then the results zipped as `all` does."""
    if not state.get("token"):
        signin(args, state)
    if not state.get("servers") and not args.server:
        servers(args, state)
    args.record = True
    series_dvr(args, state)
    share_results(args)


def share_results(args):
    """The shareable results (reports and sanitised responses) zipped into spike-results.zip."""
    zipped = Path("spike-results.zip").resolve()
    with zipfile.ZipFile(zipped, "w", zipfile.ZIP_DEFLATED) as z:
        for f in sorted(args.out.glob("report-*.md")) + sorted((args.out / "sanitised").rglob("*")):
            if f.is_file():
                z.write(f, f.relative_to(args.out))
    print(f"\nDone. Upload this file to the conversation with Claude:\n  {zipped}\n"
          "It holds the reports and the sanitised responses: no tokens, addresses or account details, but the "
          "titles of your films, shows and channels are kept as they are.", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("step", choices=["all", "signin", "servers", "library", "playback", "livetv", "dvr",
                                             "favourites", "save-favourites", "markers", "deletion", "people", "cast", "series_dvr", "test-dvr",
                                             "recordings", "test-recordings"])
    parser.add_argument("--server", help="server URL, e.g. http://192.168.1.20:32400 (default: from `servers`)")
    parser.add_argument("--token", help="token for --server (not needed for a local unclaimed test server)")
    parser.add_argument("--out", type=Path, default=Path("spike-out"))
    parser.add_argument("--tune", action="store_true", help="livetv: also tune a channel (uses a tuner briefly)")
    parser.add_argument("--record", action="store_true", help="dvr: schedule a recording, then cancel it")
    parser.add_argument("--write-back", action="store_true",
                        help="favourites: save the favourite channels back unchanged (a backup first), and read them again")
    parser.add_argument("--restore", type=Path, help="favourites: put back a backup saved by --write-back")
    parser.add_argument("--delete", action="store_true",
                        help="deletion: really delete an episode (a throwaway test server only; never part of `all`)")
    args = parser.parse_args()
    state = load_state()
    KNOWN_SECRETS.update(state.get("hidden", {}))
    hide_known(state)
    if args.server and not args.token and state.get("servers"):
        args.token = next((s["token"] for s in state["servers"] if s["uri"] == args.server), None)
    steps = {"all": run_all, "save-favourites": save_favourites, "cast": cast, "test-dvr": test_dvr,
             "test-recordings": test_recordings}
    (steps.get(args.step) or globals()[args.step])(args, state)


if __name__ == "__main__":
    main()

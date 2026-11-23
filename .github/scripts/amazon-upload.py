"""Sends a release's APK and notes to the Amazon Appstore, through Amazon's App Submission API, and submits it.

Usage: amazon-upload.py <apk> <notes.txt> <versionCode> [--no-submit] [--use-open-edit]
Needs AMAZON_CLIENT_ID, AMAZON_CLIENT_SECRET and AMAZON_APP_ID in the environment (see RELEASING.md).

The steps:
1. Gets an access token.
2. Uses the app's open edit, or makes one. Amazon allows only one open edit for an app.
3. Uploads the new APK to the edit and deletes the old one. Amazon sets the new APK's device targeting from its
   manifest.
4. Puts the notes in the "Recent changes" of every English listing.
5. Commits the edit, which sends it to Amazon's review. --no-submit leaves the edit open, to check in the console.
An edit that is open already is used only with --use-open-edit: it can hold changes made by hand in the console. A
second run with --use-open-edit submits an edit that an earlier --no-submit run left open.
Every PUT, DELETE and the commit send the ETag of the item they change (If-Match), as the API asks.
"""
import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

TOKEN_URL = "https://api.amazon.com/auth/o2/token"
API = "https://developer.amazon.com/api/appstore/v1/applications/"


def fail(message):
    print(f"::error::{message}")
    sys.exit(1)


def call(method, url, token=None, body=None, headers=None):
    """Sends one request. Returns the reply's JSON (None when empty) and its ETag."""
    request = urllib.request.Request(url, data=body, method=method, headers=headers or {})
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(request, timeout=600) as reply:
            text = reply.read().decode("utf-8")
            return (json.loads(text) if text.strip() else None), reply.headers.get("ETag")
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")[:500]
        fail(f"{method} {url.replace(API, '')}: HTTP {e.code} {detail}")
    except (urllib.error.URLError, TimeoutError) as e:
        # The request may still have reached Amazon (an upload stored, say): a run again with --use-open-edit carries on.
        fail(f"{method} {url.replace(API, '')}: no answer ({e})")


def etag_of(method, url, token):
    """The ETag of what a GET returns, for the If-Match of the change that follows."""
    body, etag = call(method, url, token)
    if not etag:
        fail(f"GET {url.replace(API, '')} sent no ETag")
    return body, etag


def listing_languages(reply):
    """The language codes in a reply that lists listings. The docs show no example, so the likely shapes are read:
    {"listings": {"en-US": {...}}}, {"en-US": {...}} or [{"language": "en-US", ...}]."""
    if isinstance(reply, dict):
        reply = reply.get("listings", reply)
    if isinstance(reply, dict):
        return list(reply)
    return [item.get("language", "") for item in reply or [] if isinstance(item, dict)]


def main():
    parser = argparse.ArgumentParser(description="Sends a release's APK and notes to the Amazon Appstore.")
    parser.add_argument("apk")
    parser.add_argument("notes")
    parser.add_argument("version_code", type=int)
    parser.add_argument("--no-submit", action="store_true", help="leave the edit open, unsubmitted")
    parser.add_argument("--use-open-edit", action="store_true", help="carry on in an edit that is open already")
    args = parser.parse_args()
    apk_path, notes_path, version_code, submit = args.apk, args.notes, args.version_code, not args.no_submit
    names = ("AMAZON_CLIENT_ID", "AMAZON_CLIENT_SECRET", "AMAZON_APP_ID")
    missing = [n for n in names if not os.environ.get(n)]
    if missing:
        fail(f"No {', '.join(missing)} secret (see RELEASING.md)")
    notes = open(notes_path, encoding="utf-8").read().strip()

    form = urllib.parse.urlencode({
        "grant_type": "client_credentials",
        "client_id": os.environ["AMAZON_CLIENT_ID"],
        "client_secret": os.environ["AMAZON_CLIENT_SECRET"],
        "scope": "appstore::apps:readwrite",
    }).encode()
    reply, _ = call("POST", TOKEN_URL, body=form, headers={"Content-Type": "application/x-www-form-urlencoded"})
    token = reply["access_token"]
    app = API + os.environ["AMAZON_APP_ID"]

    # The open edit, if there is one ({} when none), else a new one: a copy of the live app.
    edit, _ = call("GET", f"{app}/edits", token)
    if edit and edit.get("id"):
        if edit.get("status") != "IN_PROGRESS":
            fail(f"The open edit is {edit.get('status')}: wait until Amazon's review of it ends")
        # An open edit can hold changes made by hand in the Developer Console: a commit would submit them too.
        if not args.use_open_edit:
            fail("The app has an open edit: finish or delete it in the Developer Console, or run with 'Use open edit'")
        print(f"Using the open edit {edit['id']}")
    else:
        edit, _ = call("POST", f"{app}/edits", token)
        print(f"Made the edit {edit['id']}")
    edits = f"{app}/edits/{edit['id']}"

    # The release's APK goes in, and every older APK goes out. A run that stopped half way (the new APK uploaded, an
    # old one not deleted yet) carries on from there when it runs again with --use-open-edit.
    apks, _ = call("GET", f"{edits}/apks", token)
    apks = apks or []
    print("The edit's APKs: " + ", ".join(f"{a.get('id')} ({a.get('versionCode')}, {a.get('name')})" for a in apks))
    newer = [a for a in apks if a.get("versionCode", 0) > version_code]
    if newer:
        fail(f"Amazon already has versionCode {newer[0].get('versionCode')}, newer than {version_code}")
    older = [a for a in apks if a.get("versionCode", 0) < version_code]
    # Their ETags first: an id that Amazon refuses stops the run before the edit changes.
    for old in older:
        etag_of("GET", f"{edits}/apks/{old['id']}", token)
    if any(a.get("versionCode") == version_code for a in apks):
        print(f"The edit has versionCode {version_code} already")
    else:
        # Amazon refuses the documented replace call ("Invalid asset id", 2026-11-23), so the new APK is uploaded and
        # the old one deleted, as Amazon's own guide does. Amazon sets the new APK's device targeting from its manifest.
        with open(apk_path, "rb") as f:
            data = f.read()
        added, _ = call("POST", f"{edits}/apks/upload", token, data, {
            "Content-Type": "application/vnd.android.package-archive",
            "fileName": os.path.basename(apk_path),
        })
        print(f"Uploaded versionCode {(added or {}).get('versionCode', version_code)}")
    for old in older:
        _, etag = etag_of("GET", f"{edits}/apks/{old['id']}", token)
        call("DELETE", f"{edits}/apks/{old['id']}", token, headers={"If-Match": etag})
        print(f"Deleted versionCode {old.get('versionCode')}")

    # The notes are English only (the owner's rule), so only the English listings get them.
    languages = [code for code in listing_languages(call("GET", f"{edits}/listings", token)[0]) if code.startswith("en")]
    if not languages:
        fail("No English listing in the edit")
    for code in languages:
        listing, etag = etag_of("GET", f"{edits}/listings/{code}", token)
        listing["recentChanges"] = notes
        call("PUT", f"{edits}/listings/{code}", token, json.dumps(listing).encode(), {
            "Content-Type": "application/json",
            "If-Match": etag,
        })
        print(f"Recent changes set for {code}")

    if not submit:
        print("The edit stays open (--no-submit): check it in the Developer Console")
        return
    _, etag = etag_of("GET", f"{app}/edits", token)
    call("POST", f"{edits}/commit", token, b"", {"If-Match": etag})
    print("Submitted: Amazon reviews it, then publishes it (this can take some hours)")


if __name__ == "__main__":
    main()

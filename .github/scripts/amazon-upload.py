"""Sends a release's APK and notes to the Amazon Appstore, through Amazon's App Submission API, and submits it.

Usage: amazon-upload.py <apk> <notes.txt> <versionCode> [--no-submit] [--use-open-edit]
Needs AMAZON_CLIENT_ID, AMAZON_CLIENT_SECRET and AMAZON_APP_ID in the environment (see RELEASING.md).

The steps:
1. Gets an access token.
2. Uses the app's open edit, or makes one. Amazon allows only one open edit for an app.
3. Replaces the old APK's file with the new one, which keeps its device list and DRM answer. If Amazon refuses that
   (it refuses to replace an app bundle), it uploads the new APK, copies the old one's device list to it, and deletes
   the old one. Then it does not submit: the DRM question must be answered in the Developer Console first (the API
   cannot set it), and the release submitted there.
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
APK_TYPE = "application/vnd.android.package-archive"


def fail(message):
    print(f"::error::{message}")
    sys.exit(1)


class Refused(Exception):
    """Amazon answered a request with an HTTP error (for the calls that can be refused and handled)."""


def call(method, url, token=None, body=None, headers=None, refusal_ok=False):
    """Sends one request. Returns the reply's JSON (None when empty) and its ETag. An HTTP error stops the run, or
    raises Refused when refusal_ok is set."""
    request = urllib.request.Request(url, data=body, method=method, headers=headers or {})
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(request, timeout=600) as reply:
            text = reply.read().decode("utf-8")
            return (json.loads(text) if text.strip() else None), reply.headers.get("ETag")
    except urllib.error.HTTPError as e:
        detail = f"{method} {url.replace(API, '')}: HTTP {e.code} {e.read().decode('utf-8', 'replace')[:500]}"
        if refusal_ok:
            raise Refused(detail) from e
        fail(detail)
    except (urllib.error.URLError, TimeoutError) as e:
        # The request may still have reached Amazon (an upload stored, say): a run again with --use-open-edit carries on.
        fail(f"{method} {url.replace(API, '')}: no answer ({e})")


def etag_of(method, url, token):
    """The ETag of what a GET returns, for the If-Match of the change that follows."""
    body, etag = call(method, url, token)
    if not etag:
        fail(f"GET {url.replace(API, '')} sent no ETag")
    return body, etag


def replaced(edits, old, etag, data, token):
    """Replaces the old APK's file with the new one, which keeps its device list and its DRM answer. False when
    Amazon refuses it: it refused to replace an app bundle uploaded in the console ("Invalid asset id")."""
    try:
        call("PUT", f"{edits}/apks/{old['id']}/replace", token, data, {
            "Content-Type": APK_TYPE,
            "If-Match": etag,
        }, refusal_ok=True)
    except Refused as e:
        # Only the app bundle's refusal goes on as a new file. Any other (a server error, a stale ETag) stops the run,
        # so that a run again tries the replace once more and keeps the DRM answer.
        if "error_invalid_asset_id" not in str(e):
            fail(str(e))
        print(f"Amazon refused the replace, so the APK is uploaded as a new file: {e}")
        return False
    return True


def copy_targeting(edits, old, new, token):
    """Gives the new APK the old one's device list, which Amazon would otherwise set again from the manifest. A refusal
    only warns: then the list is set by hand in the Developer Console."""
    try:
        if not new or not new.get("id"):
            raise Refused("no id for the new APK")
        targeting, _ = call("GET", f"{edits}/apks/{old['id']}/targeting", token, refusal_ok=True)
        _, etag = call("GET", f"{edits}/apks/{new['id']}/targeting", token, refusal_ok=True)
        if not targeting or not etag:
            raise Refused("no device list or ETag to copy")
        call("PUT", f"{edits}/apks/{new['id']}/targeting", token, json.dumps(targeting).encode(), {
            "Content-Type": "application/json",
            "If-Match": etag,
        }, refusal_ok=True)
        print(f"Copied the device list of versionCode {old.get('versionCode')}")
    except Refused as e:
        print(f"::warning::Check the new APK's device list in the Developer Console: it was not copied ({e})")


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
    etags = [etag_of("GET", f"{edits}/apks/{old['id']}", token)[1] for old in older]
    with open(apk_path, "rb") as f:
        data = f.read()
    current = next((a for a in apks if a.get("versionCode") == version_code), None)
    if current:
        print(f"The edit has versionCode {version_code} already")
    elif len(older) == 1 and replaced(edits, older[0], etags[0], data, token):
        print(f"Replaced versionCode {older[0].get('versionCode')} with {version_code}")
        older = []
    else:
        current, _ = call("POST", f"{edits}/apks/upload", token, data, {
            "Content-Type": APK_TYPE,
            "fileName": os.path.basename(apk_path),
        })
        print(f"Uploaded versionCode {version_code}")
    # A new file in the edit has no answer to Amazon's DRM question, which the API cannot set: Amazon refuses to take
    # the release for review until it is answered in the console.
    new_file = bool(older)
    if older:
        # The newest old APK's device list: each copy would replace the one before it.
        copy_targeting(edits, max(older, key=lambda a: a.get("versionCode", 0)), current, token)
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

    if new_file:
        print("::warning::Not submitted: the APK is a new file in the edit. In the Developer Console's App Files, answer "
              "\"Allow Amazon to apply DRM?\" (No), check the device list, then submit the release there.")
        return
    if not submit:
        print("The edit stays open (--no-submit): check it in the Developer Console")
        return
    _, etag = etag_of("GET", f"{app}/edits", token)
    call("POST", f"{edits}/commit", token, b"", {"If-Match": etag})
    print("Submitted: Amazon reviews it, then publishes it (this can take some hours)")


if __name__ == "__main__":
    main()

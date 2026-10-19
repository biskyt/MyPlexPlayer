# Google Play listing

Everything for the MyTVPlayer store listing: graphics, screenshots and the text to paste in.

## Graphics

| File | Play Console field | Size |
|---|---|---|
| `graphics/icon-512.png` | App icon | 512 × 512, 32-bit PNG |
| `graphics/feature-graphic-1024x500.png` | Feature graphic | 1024 × 500, 24-bit PNG |
| `graphics/tv-banner-1280x720.png` | Android TV banner | 1280 × 720, 24-bit PNG |
| `screenshots/*.png` | Android TV screenshots (and phone screenshots, if asked) | 1920 × 1080, 24-bit PNG |

They're drawn by the app itself (`app/src/test/.../ui/StoreAssetsTest.kt`), with invented channels and programmes, so
they never show a real broadcaster or title. To make them again after the app's look changes:

```
./gradlew :app:recordRoborazziDebug --tests '*StoreAssetsTest*' -PstoreAssets
```

## Privacy policy

`docs/privacy.html`, published with GitHub Pages at **https://biskyt.github.io/MyTVPlayer/privacy.html**.

## Listing text

**App name** (30 characters max): `MyTVPlayer`

**Short description** (80 max):

> Live TV, TV guide, catch-up, movies and series from your own IPTV provider.

**Full description** (4000 max):

> MyTVPlayer is a fast, simple player for Android TV that brings your own IPTV service to the big screen.
>
> MyTVPlayer does not include any channels or content. Add the IPTV service you subscribe to (Xtream Codes login or
> M3U playlist), or start with the free, public channels collected by the iptv-org project.
>
> • Live TV with categories, favourites and recently watched channels
> • A full TV guide, with guides from your provider or any XMLTV address, matched to your channels automatically
> • Catch-up and start-over on channels where your provider offers them
> • Movies and series, with continue watching and next episode
> • Search across channels, programmes, movies and series
> • Made for the remote: number keys, channel up/down, the Guide button
> • Light on older TV boxes, with no ads, no accounts and no tracking
> • Backup and restore your set-up
>
> You need a subscription to an IPTV service, or a playlist address, to watch anything. Only use services you have the
> right to watch.

**Category:** Video Players & Editors

## Play Console answers

- **Data safety:** *No data collected* and *No data shared*.
  - Logins and history stay on the device.
  - The app talks directly to the services the user adds.
  - Nothing reaches the developer.
  - Play doesn't count data handled only on the device, or sent directly to services the user chooses, as
    "collected".
- **Ads:** No ads.
- **Content rating:** answer for a media player with no built-in content.
  - It shows whatever the user's own service provides.
  - It has no user-to-user features.
- **Target audience:** 18+ (or 13+), not directed at children.
- **App access:** reviewers need a way to sign in. Tell them to choose **Free channels (iptv-org)** on the first
  screen, which needs no login.
- **Android TV:** the app declares `LEANBACK_LAUNCHER` and a TV banner, and doesn't require a touchscreen. Opt in to
  Android TV under *Advanced settings › Form factors*.

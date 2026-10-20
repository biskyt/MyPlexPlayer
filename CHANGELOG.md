# Changelog

Each version's section becomes its GitHub release notes, so it is written for the people using the app.

## 0.8.7
- While watching, up and down show the controls; the remote's channel buttons change channel. To change channel with
  up and down as before, turn on Settings › Playback › **Change channel with up and down**.
- Scrub through movies, episodes and catch-up: hold ◀ or ▶ and a marker runs along the play bar, faster the longer
  you hold; playback jumps there when you let go. A single press still skips.
- The Catch-up tab lists its channels by category (e.g. UK and US separately), with "All catch-up" at the top.
- Channels whose provider marks them as having catch-up without saying for how long now show as catch-up channels
  (taken as a week).
- Back in the channel list or TV guide goes up to the filter box; Back again leaves.
- Settings › Appearance: the custom colour bar is gone (it didn't work); pick one of the preset colours.

## 0.8.6
- **Catch-up** has its own tab (in place of Favourites, which Home and each section already show), for playlists
  with catch-up channels. It lists those channels; OK opens a channel's past programmes. Live TV also has a Catch-up
  list.
- Channels with catch-up have a small ⟲ clock mark in the channel list, the TV guide and on Home.
- Hold OK (or press Menu) on a category in Live TV, Movies or Series to hide it. Show it again in Settings ›
  Languages & categories.
- Hold OK on a Home card for its options: watch, favourites, catch-up, change guide source, or take it off "Recently
  watched" or "Carry on watching".
- Fixed: the TV guide sometimes jumped to another category, when switching from the channel list or when scrolling
  back through catch-up. A category now only changes when you move through the categories yourself, and moving
  left into them lands on the one you're in.

## 0.8.5
- Adding a TV guide is much quicker: only the new guide's programmes are loaded (and those of channels whose guide
  changed), not every guide again. "Downloading" and "Loading programmes" now show how far they've got.
- The daily guide update skips guides that haven't changed on their server.
- Search results are easier to read: channel names have more room, and long names scroll while highlighted (movie
  and series titles too). Hold OK, or press the Menu button, on a result for its options: add to favourites,
  catch-up, change guide source, and more.
- The player's More menu has actions for what's playing: add or remove a channel from favourites, catch-up and change
  guide source on live TV; add to favourites and open the details or all episodes for movies and series.
- Fixed: the player's More menu always said "No audio", even while sound was playing.

## 0.8.4
- When a channel, film or episode won't play, the message now says why: the stream's server refused it (often because
  it only plays in its own country), the stream has gone from its address, the server didn't answer, or this TV box
  can't decode or read what it sent. It also names the server the stream comes from.
- Settings › Playlists shows when your provider's subscription expires, in amber when it's two weeks away or has
  passed. This now works for providers added with an M3U link (`…/get.php?username=…`) as well as with a login.

## 0.8.3
- The **Guide** button on the remote opens the TV guide from anywhere in the app; Back returns to where you were.
  While watching a channel it still shows that channel's schedule.
- Faster on TV boxes:
  - Guide updates read just the start of each guide file for its channel list, instead of the whole file twice.
  - Matching channels to guides does less work per channel.
  - Screens you've left (e.g. Home while watching) stop updating their "on now" details until you go back to them.

## 0.8.2
- Settings › TV guide sources is easier to use:
  - One status box, right under the buttons, shows what's happening while a guide is added, updated or matched,
    then says clearly how it went.
  - Matching says how many channels already have a guide and how many still have none, not just that nothing new
    matched.
  - Your guides, free country guides included, are all in one list. **Add a guide** opens a panel with the free
    guides by country (yours first) and a box for your own XMLTV address.
- Setting up the free channels now adds the free TV guide for each country you pick, where there is one.

## 0.8.1
- First launch asks what you'd like: add your TV provider, start with the free channels, or restore a backup.
  Nothing is set up until you choose.
- Settings › Languages & categories has a filter for countries, categories and hidden channels.
- Fixed: some free channels (e.g. BBC Four) closed the app. DASH streams now play, and any stream the app can't play
  shows a message instead.
- Fixed: a channel that kept failing just after starting (e.g. 5USA) looped on "Loading…" for ever. It now says it
  isn't playing after a few tries.

## 0.8.0
- **Catch-up TV** on channels whose provider offers it:
  - **Catch-up** in a channel's menu: the last few days, day by day; pick a programme to watch it.
  - **Start over**, in the channel menu and the player: watch what's on now from its beginning.
  - In the TV guide, press ◀ past "now" to scroll back on catch-up channels and press OK on a finished programme.
  - Catch-up plays on from one programme into the next, like the live channel only earlier. ◀ ▶ skip, and "Watch
    live" goes back to now.
- The TV guide loads exactly as before: catch-up listings are only fetched when you use catch-up.

## 0.7.0
- **Find it on demand from the TV guide**: hold OK (or press Info) on a programme.
  - **Find series** opens the series, on the episode that's on.
  - **Find in Movies** opens the film.
  - Anything else offers a search for its title. News and sport offer nothing.

## 0.6.0
- **Better TV guide matching**, especially for US local channels: call signs in brackets ("NBC 4 (WNBC) NEW YORK")
  match guide names like "US - NBC WNBC NY", and state names match their abbreviations.
- Settings › TV guide sources › **Match channels without a guide**: finds guides for channels that have none (90%
  match or better), shows its progress, and says how many it found.
- Guide updates are quicker: channels are only matched again when a guide is first added, when it changes, or for
  new channels. Matches you set by hand are never changed.
- Live TV opens on the list you used last.
- "Change guide source" in a channel's menu opens guide matching on just that channel.

## 0.5.0
- **Backup & restore** (Settings › Backup & restore): saves your playlists and logins, category and channel choices,
  favourites, TV guide sources and choices, watching progress and settings to Download/MyTVPlayer/mytvplayer-backup.json.
  Each backup replaces the last. No permissions needed. Copy the file with a file manager to keep it elsewhere.
- Restore from that file, or pick another one (e.g. copied from another TV). Channels, movies and the guide download again afterwards.

## 0.4.0
- **Search**: one box for channels (by name or number), what's on TV now and later, movies and series.
- Menus and side panels (channel options, audio, subtitles, guide) are the same size as the rest of the app.

## 0.3.3
- TV guide sources: free guides for 75 countries from iptv-epg.org, added with one press (your country first).
- The sources screen shows what an update is doing ("Downloading…", "Reading…", "Matching channels…").

## 0.3.2
- Movies and series appear after updating: they load on the next start, and untagged categories ("Action",
  "Netflix Movies") are included by default.
- "Update now" for each playlist in Settings › Playlists, and "Load now" when the Movies or Series list is empty.

## 0.3.1
- Settings › Playlists has an "Add free channels (iptv-org)" button, shown until the free channels are added.

## 0.3.0
- **Movies and Series** (Xtream accounts): browse by category with posters, filter by title, favourites,
  "Recently added", and pages with plot, cast and running time.
- Movies and episodes resume where you left off; the next episode starts on its own.
- **Carry on watching** on Home, including the next episode of a series you're watching.
- Settings › Languages & categories now has Live TV, Movies and Series tabs: only ticked categories are downloaded.
- The settings button is now a cog.

## 0.2.4
- Faster first setup of the free channels, and lighter on memory in the TV guide.
- Channels whose links don't say they're HLS streams now start first time when you come back to them.
- Country groups are recognised for every country (e.g. "Austria"), so the right ones are ticked automatically.

## 0.2.3
- The TV guide scrolls to earlier and later times with ◀ and ▶, keeping your place.
- Switch between the channel list and the TV guide with the remote's Guide button or from the channel menu, without losing your place.

## 0.2.2
- Holding OK on a channel opens its menu without starting the channel.
- The remote's Info or Menu button also opens the channel menu.
- Text is a step smaller (turn "Large text" on in Settings › Appearance for the bigger size); channel names get more room.

## 0.2.1
- Typing addresses, user names and passwords: no autocorrect, passwords shown as typed, and the keyboard's Next button moves to the next box.

## 0.2.0
- Free TV channels from iptv-org are set up on first launch, with your country's channels selected.
- M3U playlists can be added alongside Xtream accounts.

## 0.1.0
- First test release: Live TV list and guide, player with now/next, guide matching, playlists, settings.

# Changelog

Each version's section becomes its GitHub release notes, so it is written for the people using the app.

## 0.15.0
- Back with the player's controls showing now hides them, instead of moving to the progress bar.
- The sleep timer is gone from the player's More menu.

## 0.14.1
- Fixed: the Options button pressed in the player's Audio or Subtitles panel could also open the TV's own menu.
- Fixed: at the very end of a live programme, the player's controls could say "0 min left".

## 0.14.0
- The player's controls show the time of day, and how far in you are, how long it is and how long is left.
- On Live TV, the controls show how long the programme has left.
- The remote's Options button brings up the controls with More picked out.
- The hints about the remote's buttons are gone, and so are Restart and Next episode in the player.

## 0.13.0
- HD Live TV channels with AAC LATM sound now play.
- A file the device can't play now says "Your device cannot play this type of file" and names its format.
- Playback info shows "Playback format: Direct Play" or "Direct Stream", with why it streams.
- The next episode to watch no longer has an "Up next" label.
- The player's More menu shows the episode's description.

## 0.12.1
- Fixed a rare case where starting the next episode could save the wrong place in it.

## 0.12.0
- Carry on watching cards show the episode and time left, such as "S1E2 22m".
- Carry on watching shows up to 20 titles.
- Carry on watching cards show no progress bar for something not yet started.
- Something goes on Carry on watching only once you've watched it for 30 seconds.
- In the player, Up from the play button shows a handle on the progress bar.
- The dark background behind the player controls is lighter.
- Movies and TV Shows are sorted by title to start with.

## 0.11.1
- Carry on watching on Home shows posters, with how far you got across the bottom, like the other rows.
- Back from playing something from Carry on watching, the highlight is on its card, wherever it has moved to on the row.

## 0.11.0
- The TV guide's highlight moves one channel or programme per press, held buttons included, and stays in the guide.
- Right goes on through the whole guide, and Left comes back along the same channel.
- Up from the first channel, or Back, goes to the filter, and from there to the categories.
- The guide's time bar names the day, with a line where a new day starts.
- The List and TV guide buttons keep the highlight, so you can switch back and forth.
- All channels shows how many channels there are.

## 0.10.3
- **Fixed a crash in the TV guide:** pressing Right from the categories right after switching to the guide, before its
  programmes had loaded, could close the app.

## 0.10.2
- **The TV guide keeps the highlight in the guide.** Clicking TV guide no longer sends it to the Home tab while the
  programmes load, and Right from the categories goes back into the guide (to the programme you left) instead of
  to the buttons above it, which led on along the top bar.

## 0.10.1
- **An episode's description** is in its options (hold OK, or Menu), when Plex has one.
- Tidier options: "Delete watched episodes" loses its explanation, the Links row lines up with the options above it,
  and the episodes page no longer carries the hint about the options.

## 0.10.0
- **Delete what you've watched:** a film's or an episode's options have Delete…, asked once more before the file goes
  from your Plex server. It shows only where the server allows deleting (Plex's "Allow media deletion").
- **Delete watched episodes, per show:** change it with left and right in a show's options (never, after a day, a week
  or a month, or on the next refresh). It's saved to Plex, which deletes episodes once watched with the server's own account.
- **New film and show pages:** the picture size, HDR and sound at a glance, the cast, who made it, and More info for
  the rest (file details, release date, studio). A show's page opens on the episode to watch next, with watched ones ticked.
- **Movies and TV Shows** have Category, Filter (all, unwatched, favourites) and Sort at the top, ticks on what you've
  watched, progress on films part-watched, and the year and running time of the one picked.
- **Options everywhere** (hold OK, or Menu): links to IMDb, TMDB and TVDB in one row, and the cast, also while watching.
- "From the start" is now "Restart", the menu says TV Shows, and in UK English a season is a series.

## 0.9.0
- **Favourite channels are your Plex favourites,** the same in every Plex app: one you add, take off or move here is
  saved to Plex straight away, and one changed in another Plex app changes here at the next update. If Plex can't be
  reached, the change isn't made, and a message says so.

## 0.8.1
- **Credits are no longer skipped by themselves** to start with: a Skip credits button shows while they play, for
  you to press. Settings › Playback can still skip them after a countdown. Intros are skipped as before.

## 0.8.0
- **Skip intro and credits:** where your Plex server has found a title's intro or credits (Plex Pass), a Skip button
  counts down and skips them by itself 2 seconds in. Press OK to skip at once, or Back to cancel. An episode's last
  credits go on to the next episode. Settings › Playback sets the countdown (0 to 10 seconds, 3 to start with) for
  intros and credits, or turns it off, leaving a Skip button to press.

## 0.7.0
- **Your favourite channels from Plex:** the Live TV channels you've made favourites in Plex (in Plex's own apps
  or on the web) are favourites here too, for each of your servers, added after your own at the first update. One
  you add or take off in Plex changes here at the next update; a change you make here stays as you left it.

## 0.6.1
- Languages & categories: switching between Live TV, Movies and Series now always shows the first language's
  categories (it could show none).

## 0.6.0
- **MyPlexPlayer is now for Plex only.** TV services added with an Xtream Codes login, TV guides added by hand and
  guide matching are gone: channels, films, series and the TV guide all come from your Plex servers. An Xtream
  account set up in an earlier version is removed when the app updates, with its favourites and choices.
- If a Plex server's TV guide couldn't be loaded, Settings › Playlists says why, on that server.
- The live stream format setting is gone: it only applied to Xtream accounts.

## 0.5.2
- **Update now** (Settings › Playlists, on a Plex server) says when the server updated but its TV guide couldn't
  be loaded, rather than that all went well.

## 0.5.1
- A film or episode opened while its details were still on their way from the server no longer closes the player
  straight away (or on coming back to the app before they arrived).

## 0.5.0
- **The TV guide from your Plex server now shows**, on the Live TV channels and in the TV guide: the app couldn't
  read the programmes as the server sends them, so every channel said there was no information.
- **Home leads with what's new:** after Carry on watching come the series with new episodes and the newest
  movies, then your favourite series and movies, and Live TV (favourite channels, then those watched lately) last.
  Rows with nothing in them are hidden.
- **Live TV comes last** in the menu (Home, Series, Movies, Live TV) and in search results.
- Holding the remote's Guide or Menu button no longer carries on into the panel it opens (which on some TVs could
  open the TV's own guide too).
- If a guide arrives that the app can't read at all, Settings › TV guides says so, rather than every channel quietly
  showing no information.

## 0.4.4
- While watching live TV with the channel guide open, the remote's Menu button now opens the menu in the guide's
  place (it did nothing before).

## 0.4.3
- **The TV guide:** one programme the app couldn't read made it drop the whole guide from your Plex server, leaving
  channels without what's on. Now only that programme is left out.
- A part-watched episode's options offer both Mark as watched and Mark as unwatched (which starts it again). Marking
  an episode keeps the list on its season, and the remote on the episode.

## 0.4.2
- **Catch-up is gone**, from the menu, the TV guide, channel options and the player: it came from IPTV providers,
  and the DVR (recording from the Plex guide) is planned in its place. The TV guide now starts from what's on now.

## 0.4.1
- **Mark an episode watched or unwatched** from its series' page: hold OK on it in the episode list. It changes on
  your Plex server too.

## 0.4.0
- **Live TV from your Plex server:** its tuner's channels are in Live TV, with what's on now and next and the TV
  guide from Plex. Choosing a channel tunes it (it says "Tuning…" until the picture starts), and the tuner is let go
  when you change channel or stop watching. Channels can be favourites, as before.
- The dimmed paused screen: a stream that keeps dropping and coming back while paused still dims, and a button never
  needs pressing twice.

## 0.3.8
- **Back in the app, what you watched elsewhere shows here:** coming back from another app (the Plex app, say), or
  opening the app again, brings in how far you got and what you finished meanwhile, and Carry on watching, in moments
  rather than with a full refresh.

## 0.3.7
- **Paused, the controls stay on screen.** Left paused for two minutes, the screen dims, to spare OLED screens from
  burn-in. Any button lights it again, and the remote's play button plays straight away.

## 0.3.6
- **Playback info:** while watching, More › Playback info shows how it's playing (the file as it is, or repackaged by
  your server), the picture (size, codec, Dolby Vision or HDR, frame rate), which decoder plays it, the sound and
  where it goes, and any dropped frames.

## 0.3.5
- **Mark as watched / unwatched:** hold OK on a poster in Films or Series, or on something in "Carry on watching". It
  changes on your Plex server too, so your other Plex apps agree.

## 0.3.4
- **Files in a format the player can't open now play:** the Plex server repackages them as they are, with the
  picture and sound untouched, never converted. A file the server could only play by converting it says so instead.
- Your Plex server's access key is no longer part of the address of what you're playing.

## 0.3.3
- **Clearer text:** secondary text across the app (descriptions and channel names while watching, times in the TV
  guide, what's on next, counts and hints) is much brighter. While watching, the shading behind the controls is
  darker, so they read over a bright picture too.

## 0.3.2
- **Dolby Vision files that also carry HDR10+ now play in Dolby Vision** on TVs that showed them as HDR10 (such as
  some with a MediaTek chip). On a screen without Dolby Vision, they still play in HDR10+.

## 0.3.1
- Hiding a category from the column now always leaves the remote on the category shown next.

## 0.3.0
- **What you've watched now comes from Plex, and goes back to it**: films and episodes carry on from where you left
  them in any of your Plex apps, and what you watch here shows as watched there. "Carry on watching" on Home is your
  server's Continue Watching, including the next episode of a series.
- Each series' poster shows how many of its episodes you haven't watched yet.
- Removing something from "Carry on watching" takes it off Plex's Continue Watching too. It keeps how far you got.
- While watching, pressing the remote's Menu button again closes the menu it opened, as Back does.

## 0.2.2
- M3U playlists and the free channels, films and series are no longer offered: MyPlexPlayer is for your Plex servers.
  One added in an earlier version is removed when the app updates, with its favourites and progress.
- The sign-in code on the Plex sign-in screen is shown in the accent colour: it was black on the dark background.

## 0.2.1
- While watching TV, the controls show the programme's episode and the start of its description, and "About this
  programme" shows all of it. The TV guide shows more of the description too.
- Long channel names take two lines in the TV guide, the channel list and the channel cards, so most show in full.
  Channels found in Search use the same card as on Home.
- Hiding a category keeps your place in the list: the next category shows, instead of going back to the top.

## 0.2.0
- **Sign in to Plex**: on first launch, or in Settings › Playlists. Enter the code shown at plex.tv/link on your phone
  or computer (or scan the QR code), and each of your Plex servers that answers is added and can be chosen from the top
  bar.
- Your Plex films and series appear under Movies and Series, one category per library, with their details and
  episodes. They play the file as it is: the server is never asked to convert anything. Resume points kept in Plex,
  Live TV and DVR come in the next versions.

## 0.1.0
- **MyPlexPlayer begins**: a player for your own Plex Media Server on Android TV, Google TV and Fire TV, that also
  works by touch on phones and tablets. It starts from MyTVPlayer's look and player, with a yellow accent and its own
  icon. Signing in to Plex comes in the next versions.

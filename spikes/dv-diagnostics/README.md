# Dolby Vision diagnostics

A small app for the owner's TV, to find out why MyPlexPlayer plays a Dolby Vision file (profile 8.1, with HDR10+ too)
as HDR10, when Plex's own app and Plezy play it in Dolby Vision. It is not part of MyPlexPlayer.

It comes in two builds that install side by side:
- **DV diagnostics (Media3 1.8.0)**: the player library MyPlexPlayer uses (without its FFmpeg decoders, which only
  play what the TV can't, and have no version for the newer library).
- **DV diagnostics (Media3 1.11.1)**: the newer library Plezy uses.

Each one:
1. **Reports on the device:** the TV model and Android version, the HDR formats the display reports, every video
   decoder with the Dolby Vision and HEVC profiles it claims, and the decoder Media3 would pick for sample Dolby Vision
   and HDR10 formats.
2. **Test plays a title** from a Plex server (sign in with a code at plex.tv/link), 40 seconds at most each way, then
   asks what the TV showed:
   - **A:** as MyPlexPlayer plays it (Media3 picks the decoder).
   - **B:** only the Dolby Vision decoder may play it.
   - **C:** as B, with the HDR10+ data switched off first (Plezy cuts it out; this changes one byte per message,
     so it costs next to nothing and is what the app would do).

   For each, it records the decoder used, the format it was given, the decoder's output format, and how many Dolby
   Vision RPUs and HDR10+ messages the stream carried.
3. **Shows the report:** on screen, at an address on the home network (with a QR code, to open it on a phone and copy
   it), by Share, or saved to Downloads. The report never holds the title's name, its file, the server's address or a
   token.

Build: `./gradlew -p spikes/dv-diagnostics assembleDebug`, or run the **DV diagnostics app** workflow by hand, which
keeps both APKs as an artifact.

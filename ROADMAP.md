# Openwave roadmap

Openwave is a free, open-source (GPL-3.0) offline music player for Android, forked from
[Auxio](https://github.com/OxygenCobalt/Auxio). The goal is a simple, familiar player in the
style of Samsung Music, with an original design, plus a few extras.

Already inherited from Auxio: Android Auto, lock-screen and notification controls, ReplayGain
playback, gapless playback, folders, playlists, search, widgets.

## Milestone 0 — Setup
- [x] Rename app to Openwave with its own package id (installs alongside Auxio)
- [x] GitHub Actions builds a debug APK on every push

## Milestone 1 — Fetch artwork and info
- Settings > "Fetch artwork & info" scans the library for missing artwork or incomplete tags
- Review list: approve or skip each song
- Identify songs by audio fingerprint (Chromaprint + AcoustID), then MusicBrainz for tags
  and Cover Art Archive for artwork
- Write approved tags and artwork permanently into the files (taglib), with an optional
  "back up originals first" switch

## Milestone 2 — Volume normalization
- Scan each song's loudness on the device (EBU R128)
- Write ReplayGain tags into every file
- MP3: lossless gain adjustment (mp3gain-style) so volume is even in any app
- FLAC/WAV: rewrite with gain applied (lossless)
- Optional "Remaster" mode: re-encode M4A/OGG/Opus at high quality with gain applied

## Milestone 3 — Playback extras
- Built-in equalizer with presets
- Floating pop-out mini player over other apps

## Milestone 4 — Look and feel
- Samsung Music-style layout (tabs, now-playing screen, album grid), original design
- Animated backgrounds behind now-playing artwork: drifting blurred art, colour gradient
  from artwork, floating particles, waves that pulse with the music, or off
- Openwave icon and branding

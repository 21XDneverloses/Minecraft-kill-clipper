# Kill Clipper

A Fabric client mod for Minecraft 1.21.11. It keeps a rolling replay buffer of your game and saves an mp4 clip when you get a kill or a multi-kill streak. Nothing else needs installing: FFmpeg ships inside the mod, and game audio is captured from Minecraft itself.

## What it does

- **Kills:** detected from vanilla death messages and per-server chat rules. It also counts a kill when a player you damaged dies within 10 seconds (`trackHits`, `hitWindowMs`), whatever kills them: lava, falling, fire, the void. Melee hits, your arrows and tridents, and crystals, respawn anchors and beds you set off near a player all count. This works on any server.
- **Streaks:** kills within the streak window (default 10 s) are grouped into one clip. Two or more kills count as a streak (`streakMinKills`).
- **Clips:** saved to `.minecraft/killclips/`. Each clip starts a few seconds before the first kill (`preRollMs`) and runs past the last one (`postRollMs`).
- **Settings:** Mod Menu or `/killclip menu`. FPS, resolution, quality, encoder (CPU, NVIDIA, AMD, Intel), buffer length, audio, and streak options.
- **Commands:** `/killclip status`, `test`, `folder`, `on`, `off`, `reload`, `learn`.

## Audio

`audioSource` controls clip audio:

- `game` (default): Minecraft's own sound only. The sound engine runs on an OpenAL loopback device; the mod plays that output to your speakers through Java Sound and records a copy. If that cannot be set up, the normal sound device is used and clips have no audio.
- `system`: everything playing on the computer, through an FFmpeg capture device named in `audioDevice` (Windows: a DirectShow device such as `Stereo Mix`; Linux: a PulseAudio source; macOS: an AVFoundation index).
- `off`: no audio.

## FFmpeg

FFmpeg builds for Windows x64, macOS (Intel and Apple Silicon) and Linux x64 come from [bytedeco javacpp-presets](https://github.com/bytedeco/javacpp-presets/tree/master/ffmpeg). The mod unpacks the matching one into `killclips/.ffmpeg/` on first launch. Set `ffmpegPath` to use a different FFmpeg. These builds are GPL; see `FFMPEG_NOTICE.txt`.

The bundled builds make the jar about 114 MB. To build for one platform only:

    ./gradlew build -Pffmpeg_platforms=windows-x86_64-gpl

## Build

    ./gradlew build

The jar is written to `build/libs/`. Needs Java 21.

## Tests

The files in `tests/` are standalone and run with `javac` and `java`:

- `StreakTrackerTest`, `HitTrackerTest`: streak grouping, hit tracking, and unpacking the bundled FFmpeg.
- `ReplayBufferTest`, `GameAudioTest`: record synthetic frames and audio through FFmpeg and save clips. Both need `ffmpeg` on PATH.

To check the whole pipeline in the game, launch with `-Dkillclip.selfTestClipSeconds=40`, which saves a test clip 40 seconds after startup. On a machine with no speakers, `-Dkillclip.audioWithoutSpeakers=true` runs game audio capture without playback.

## Status

Tested in the game on a Linux virtual display: video, game audio from the title music, and clip saving. Not yet tested in a real PvP fight, on Windows or macOS, or with real speakers.

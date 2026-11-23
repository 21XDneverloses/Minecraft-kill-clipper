# Kill Clipper

A Fabric client mod for Minecraft 1.21.11. It keeps a rolling replay buffer of your game and saves an mp4 clip when you get a kill or a multi-kill streak. Nothing else needs installing: FFmpeg ships inside the mod, and game audio is captured from Minecraft itself.

## What it does

- **Kills:** detected from vanilla death messages and per-server chat rules. It also counts a kill when a player you damaged dies within 10 seconds (`trackHits`, `hitWindowMs`), whatever kills them: lava, falling, fire, the void. Melee hits, your arrows and tridents, and crystals, respawn anchors and beds you set off near a player all count. This works on any server, including servers that hide other players' health (the death itself is seen). If the chat says someone else killed a player you hit (`Bob was slain by Carl`), or names you only as an assist, it is not your kill. Nicknames from the player list are understood. Leaving a fight with a command such as `/lobby` or `/hub` does not count your last hits as a win.
- **Streaks:** kills within the streak window (default 10 s) are grouped into one clip. Two or more kills count as a streak (`streakMinKills`).
- **Clips:** saved to `.minecraft/killclips/`. Each clip starts a few seconds before the first kill (`preRollMs`) and runs past the last one (`postRollMs`).
- **Clip library:** Clip Editor on the title screen lists every clip with a picture of the kill, its streak size, server and size. Search by name, server or tag (for example `3K` or `mcpvp`), show only favourites, streaks, replays or edits, star favourites, rename and delete clips (the replay and edit go with them). Storage sets a size cap and an age limit; the oldest clips go first, and favourites and edited videos are never deleted.
- **Editor extras:** undo and redo (Ctrl+Z / Ctrl+Y) for every change, freeze-frames (F, 1 to 3 seconds of a held picture with the sound paused), and text you write yourself (T): position, size, colour and how long it shows, dragged along its own track. Edits save as you make them.
- **Highlight reel:** in the clip editor (Clip Editor on the title screen), Highlight reel turns your best kills into one montage with a single click. It rates every clip (more kills, quick kills in a row, crystal and anchor kills, long shots and low-health wins score higher), skips repeats of the same fight, and picks about a minute of the best. The best clip opens the reel, the second best closes it in slow motion, and every cut lands on the beat: the two beats before a kill speed up into it and the kill hits on the bar. Your own music works too: pick any mp3, wav, ogg, m4a or flac and its tempo and bar lines are found automatically (fast songs such as drum and bass or phonk are read at their real speed). The cut pace follows the song: fast music cuts every half bar with short run-ups, slow music lets each clip play for whole bars. With your own song and "How many: Auto", the reel uses every different fight, best first, until the song is full (up to 3 minutes), and spreads the streaks through it. Loading screens, connecting screens and the switch back to the lobby after a win are never in the reel: clips note them while recording, and older clips are checked by picture. The reel has no text on it by default; **Text: On** adds kill popups (Double kill, Clutch, Long shot), an intro and a closing title. For the best kills with a fight replay an instant replay from the third-person camera is filmed and cut in. Before making it you can remove, move or swap clips. Options: 9:16 for phones, and getting a share link instead of opening the video. The reel is saved to `killclips/Edited/`.
- **Settings:** Mod Menu or `/killclip menu`. FPS, resolution, quality, encoder (CPU, NVIDIA, AMD, Intel), buffer length, audio, and streak options.
- **Quiet:** clipping happens silently, with no chat or hotbar messages. Problems (FFmpeg could not start, a clip failed) show once above the hotbar, never in chat. Turn on Notifications in the settings to see "Clip saved" again.
- **Report a missed or wrong kill:** `/killclip report` (a kill was not clipped; it also saves the last 15 seconds as a kill clip) or `/killclip report wrong` (a clip was saved for something that was not your kill). Both can be bound to keys in Controls → Kill Clipper. The report goes to `.minecraft/killclip-reports/` and holds the last 200 server chat lines with what detection decided about each one, plus recent hits, deaths and kills, so the rule can be fixed. It stays on your computer; read it before sharing, since chat can include private messages.
- **Commands:** `/killclip status`, `test`, `folder`, `on`, `off`, `reload`, `report`, `learn`.

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

- `DeathMessagesTest`: kill-message rules for many servers, and lines where you died that must never count.
- `DetectionTest`: kills credited to other players, deaths held until the chat confirms them, nicknames, and the detection report. Compile it with `KillRules`, `VanillaDeathMessages`, `ServerDefaults`, `PendingKills` and `DetectionLog`, and put `src/main/resources` on the classpath.
- `StreakTrackerTest`, `HitTrackerTest`: streak grouping, hit tracking, and unpacking the bundled FFmpeg.
- `HighlightReelTest`: rating and picking clips, beat detection (including fast songs), cut pace by tempo, filling your own song, keeping loading screens out, cutting on the beat, popups, and making reels with every transition, with and without music, replays and 9:16. Needs `ffmpeg` on PATH. Compile it with `src/main/java/dev/killclip/core/HighlightReel.java`, `ReelMusic.java`, `ReelText.java`, `BeatDetector.java`, `ClipMeta.java`, `LoadingScreens.java`, `VideoTools.java` and `ProcessPriority.java` (the last needs JNA on the classpath, or a stub).
- `ClipLibraryTest`: titles, tags, search, the storage limits, rename and delete, and the silent gap a freeze-frame puts in the sound. Compile it with `ClipLibrary.java`, `VideoTools.java` and a `ProcessPriority` stub; pass `ffmpeg` as a second argument to also run the FFmpeg audio graph.
- `ReplayBufferTest`, `GameAudioTest`: record synthetic frames and audio through FFmpeg and save clips. Both need `ffmpeg` on PATH.
- `EncoderProbeTest`: the first-run auto-tune picks, the bitrate ceiling, and a real encoder test. Compile it with `ReplayBuffer.java`, `EncoderProbe.java` and `ProcessPriority.java`; pass the FFmpeg to test.

To check the whole pipeline in the game, launch with `-Dkillclip.selfTestClipSeconds=40`, which saves a test clip 40 seconds after startup. On a machine with no speakers, `-Dkillclip.audioWithoutSpeakers=true` runs game audio capture without playback.

## Status

Tested in the game on a Linux virtual display: video, game audio from the title music, and clip saving. Not yet tested in a real PvP fight, on Windows or macOS, or with real speakers.

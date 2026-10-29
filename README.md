# Kill Clipper

A Fabric client mod for Minecraft 1.21.11 that keeps a rolling replay buffer and saves an mp4 clip when you get a kill or a multi-kill streak. It does not need Medal or any other recorder.

## Requirements

- Java 21
- FFmpeg on your PATH, or set `ffmpegPath` in `config/killclipper.json`

## Build

    ./gradlew build

The jar is written to `build/libs/`.

## Test

- `tests/StreakTrackerTest.java` covers streak grouping. Run with `javac` and `java`.
- `tests/ReplayBufferTest.java` records synthetic frames through FFmpeg and saves a clip. It needs `ffmpeg` on PATH.

## Status

Built and tested in the game on a virtual display. Kill detection still uses the server list in the config. Game audio is not recorded yet.

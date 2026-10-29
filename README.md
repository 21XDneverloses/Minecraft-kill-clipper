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

## Audio

Set `audioSource` to `system` to record system audio (off by default). It records everything playing on the machine, not only Minecraft. Set `audioDevice` to choose the input:

- **Windows:** the name of a DirectShow capture device, such as `Stereo Mix` (enable it in Sound settings) or `CABLE Output` (VB-Audio Cable). Audio stays off until a device is set.
- **Linux:** a PulseAudio source, or blank for `default`.
- **macOS:** an AVFoundation audio index, or blank for `0`. You need a virtual device such as BlackHole.

## Status

Built and tested in the game on a virtual display. Video and system audio were tested with synthetic inputs, not a real sound device. Kill detection still uses the server list in the config. Game-only audio is not supported.

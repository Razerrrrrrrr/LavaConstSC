# LavaConstSC

A Lavalink plugin that plays SoundCloud again. SoundCloud stopped serving MP3/Opus streams, and the lavaplayer bundled
with Lavalink 4.2.x can only play those, so every SoundCloud track fails with
"Did not detect any supported formats". This plugin registers its own SoundCloud source that understands SoundCloud's
current AAC-in-fragmented-MP4 HLS streams.

## Install

1. Put the plugin jar in Lavalink's `plugins/` folder (or use the JitPack dependency below).
2. In `application.yml`:

```yaml
lavalink:
    server:
        sources:
            soundcloud: false        # turn off the built-in (broken) SoundCloud source

plugins:
    soundcloudplus:
        enabled: true
        allowSearch: true            # enables scsearch:
```

JitPack form (after tagging a release): 

```yaml
lavalink:
    plugins:
        - dependency: com.github.<your-github-user>.LavaConstSC:lavaconst-sc-plugin:<tag>
          repository: https://jitpack.io
          snapshot: false
```

## What to look for in the log

- `SoundCloud Plus registered (search enabled).` at startup means the plugin is active.
- If SoundCloud offers nothing playable, a single warning lists the formats it offered
  (`Offered (protocol, mime type): ...`). Send that line when reporting a problem.

## How it works

The `dev.lavaconst.soundcloud` package is lavaplayer's SoundCloud source with these changes (see `NOTICE`):
AAC fMP4 HLS decoding, init-segment (`EXT-X-MAP`) handling, a synthetic `sidx` box so lavaplayer's MP4 reader does not
drop the first fragment, no `client_id` on signed `*.soundcloud.cloud` URLs, DRM tracks reported clearly, and unplayable
search results skipped instead of failing the whole search.

## Build

`./gradlew build` produces `build/libs/lavaconst-sc-plugin-<version>.jar` (needs JDK 17).

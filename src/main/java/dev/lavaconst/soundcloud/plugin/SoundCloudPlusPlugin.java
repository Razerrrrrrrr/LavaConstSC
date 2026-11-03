package dev.lavaconst.soundcloud.plugin;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import dev.arbjerg.lavalink.api.AudioPlayerManagerConfiguration;
import dev.lavaconst.soundcloud.SoundCloudAudioSourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Registers a SoundCloud source that can play SoundCloud's current AAC streams, replacing Lavalink's built-in one.
 * Turn the built-in source off with {@code lavalink.server.sources.soundcloud: false}.
 */
@Service
public class SoundCloudPlusPlugin implements AudioPlayerManagerConfiguration {
    private static final Logger log = LoggerFactory.getLogger(SoundCloudPlusPlugin.class);

    private final SoundCloudPlusConfig config;

    public SoundCloudPlusPlugin(SoundCloudPlusConfig config) {
        this.config = config;
    }

    @Override
    public AudioPlayerManager configure(AudioPlayerManager manager) {
        if (!config.isEnabled()) {
            log.info("SoundCloud Plus is disabled (plugins.soundcloudplus.enabled=false).");
            return manager;
        }

        if (manager.source(com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager.class) != null) {
            log.warn("Lavalink's built-in SoundCloud source is still enabled and will handle SoundCloud requests first. " +
                "Set lavalink.server.sources.soundcloud to false so SoundCloud Plus can take over.");
        }

        manager.registerSourceManager(
            SoundCloudAudioSourceManager.builder()
                .withAllowSearch(config.isAllowSearch())
                .build()
        );

        log.info("SoundCloud Plus registered (search {}).", config.isAllowSearch() ? "enabled" : "disabled");
        return manager;
    }
}

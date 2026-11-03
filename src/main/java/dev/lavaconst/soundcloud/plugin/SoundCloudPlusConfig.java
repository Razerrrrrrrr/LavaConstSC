package dev.lavaconst.soundcloud.plugin;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Settings under {@code plugins.soundcloudplus} in application.yml.
 */
@ConfigurationProperties(prefix = "plugins.soundcloudplus")
@Component
public class SoundCloudPlusConfig {
    private boolean enabled = true;
    private boolean allowSearch = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAllowSearch() {
        return allowSearch;
    }

    public void setAllowSearch(boolean allowSearch) {
        this.allowSearch = allowSearch;
    }
}

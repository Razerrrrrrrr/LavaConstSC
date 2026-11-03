package dev.lavaconst.soundcloud;

import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.COMMON;

/**
 * Chooses which of the transcodings SoundCloud offers for a track will be played.
 *
 * <p>Based on lavaplayer's DefaultSoundCloudFormatHandler, extended with AAC in fragmented-MP4 HLS (the format
 * SoundCloud serves now that MP3/Opus transcodings are gone) and a clear error for DRM-protected tracks.
 */
public class DefaultSoundCloudFormatHandler implements SoundCloudFormatHandler {
    private static final Logger log = LoggerFactory.getLogger(DefaultSoundCloudFormatHandler.class);
    private static final FormatType[] TYPES = FormatType.values();

    /** The offered-formats list is logged loudly once, then quietly, so one broken CDN cannot flood the log. */
    private static final AtomicBoolean loggedUnsupportedOnce = new AtomicBoolean(false);

    @Override
    public SoundCloudTrackFormat chooseBestFormat(List<SoundCloudTrackFormat> formats) {
        for (FormatType type : TYPES) {
            for (SoundCloudTrackFormat format : formats) {
                if (type.matches(format)) {
                    log.debug("Chose SoundCloud format {} {} for track {}", format.getProtocol(), format.getMimeType(), format.getTrackId());
                    return format;
                }
            }
        }

        if (!formats.isEmpty() && formats.stream().allMatch(DefaultSoundCloudFormatHandler::isProtected)) {
            throw new FriendlyException("This SoundCloud track is DRM-protected and cannot be played.", COMMON, null);
        }

        String trackId = formats.isEmpty() ? "?" : formats.get(0).getTrackId();
        String offered = describe(formats);

        if (loggedUnsupportedOnce.compareAndSet(false, true)) {
            log.warn("SoundCloud offered no playable format for track {}. Offered (protocol, mime type): {}", trackId, offered);
        } else {
            log.debug("SoundCloud offered no playable format for track {}. Offered: {}", trackId, offered);
        }

        throw new RuntimeException("Did not detect any supported formats");
    }

    @Override
    public String buildFormatIdentifier(SoundCloudTrackFormat format) {
        for (FormatType type : TYPES) {
            if (type.matches(format)) {
                return type.prefix + format.getLookupUrl();
            }
        }

        return "X:" + format.getLookupUrl();
    }

    @Override
    public SoundCloudM3uInfo getM3uInfo(String identifier) {
        if (identifier.startsWith(FormatType.TYPE_M3U_OPUS.prefix)) {
            return new SoundCloudM3uInfo(identifier.substring(2), SoundCloudOpusSegmentDecoder::new);
        } else if (identifier.startsWith(FormatType.TYPE_M3U_MP3.prefix)) {
            return new SoundCloudM3uInfo(identifier.substring(2), SoundCloudMp3SegmentDecoder::new);
        } else if (identifier.startsWith(FormatType.TYPE_M3U_AAC.prefix)) {
            return new SoundCloudM3uInfo(identifier.substring(2), SoundCloudAacSegmentDecoder::new);
        }

        return null;
    }

    @Override
    public String getMp3LookupUrl(String identifier) {
        if (identifier.startsWith(FormatType.TYPE_SIMPLE_MP3.prefix)) {
            return identifier.substring(2);
        }

        return null;
    }

    private static boolean isProtected(SoundCloudTrackFormat format) {
        String protocol = format.getProtocol();
        return protocol != null && protocol.toLowerCase(Locale.ROOT).contains("encrypted");
    }

    private static String describe(List<SoundCloudTrackFormat> formats) {
        if (formats.isEmpty()) {
            return "(none)";
        }

        return formats.stream()
            .map(f -> "(" + f.getProtocol() + ", " + f.getMimeType() + ")")
            .collect(Collectors.joining(", "));
    }

    /** The mime type without parameters, e.g. {@code audio/mp4; codecs="mp4a.40.2"} becomes {@code audio/mp4}. */
    private static String baseMimeType(String mimeType) {
        if (mimeType == null) {
            return "";
        }

        int semicolon = mimeType.indexOf(';');
        String base = semicolon >= 0 ? mimeType.substring(0, semicolon) : mimeType;
        return base.trim().toLowerCase(Locale.ROOT);
    }

    private enum FormatType {
        TYPE_M3U_OPUS("hls", "audio/ogg", "O:"),
        TYPE_M3U_MP3("hls", "audio/mpeg", "U:"),
        TYPE_SIMPLE_MP3("progressive", "audio/mpeg", "M:"),
        TYPE_M3U_AAC("hls", "audio/mp4", "A:");

        public final String protocol;
        public final String mimeType;
        public final String prefix;

        FormatType(String protocol, String mimeType, String prefix) {
            this.protocol = protocol;
            this.mimeType = mimeType;
            this.prefix = prefix;
        }

        public boolean matches(SoundCloudTrackFormat format) {
            return protocol.equals(format.getProtocol()) && mimeType.equals(baseMimeType(format.getMimeType()));
        }
    }
}

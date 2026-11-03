package dev.lavaconst.soundcloud;

import com.sedmelluq.discord.lavaplayer.container.playlists.HlsStreamSegment;
import com.sedmelluq.discord.lavaplayer.container.playlists.HlsStreamSegmentParser;
import com.sedmelluq.discord.lavaplayer.tools.http.HttpStreamTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.ChainedInputStream;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.NonSeekableInputStream;
import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import org.apache.http.client.methods.HttpGet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SoundCloudM3uAudioTrack extends DelegatedAudioTrack {
    private static final Logger log = LoggerFactory.getLogger(SoundCloudM3uAudioTrack.class);

    private static final long SEGMENT_UPDATE_INTERVAL = TimeUnit.MINUTES.toMillis(10);

    private static final Pattern MAP_URI = Pattern.compile("^#EXT-X-MAP:.*URI=\"([^\"]+)\"");

    private final HttpInterface httpInterface;
    private final SoundCloudM3uInfo m3uInfo;

    /** URL of the fMP4 initialization segment from EXT-X-MAP, or null for streams that have none. */
    private volatile String initSegmentUrl;

    public SoundCloudM3uAudioTrack(AudioTrackInfo trackInfo, HttpInterface httpInterface, SoundCloudM3uInfo m3uInfo) {
        super(trackInfo);
        this.httpInterface = httpInterface;
        this.m3uInfo = m3uInfo;
    }

    @Override
    public void process(LocalAudioTrackExecutor localExecutor) throws Exception {
        try (SegmentTracker segmentTracker = createSegmentTracker()) {
            segmentTracker.decoder.prepareStream(true);

            localExecutor.executeProcessingLoop(() -> segmentTracker.decoder.playStream(
                localExecutor.getProcessingContext(),
                segmentTracker.streamStartPosition,
                segmentTracker.desiredPosition
            ), segmentTracker::seekToTimecode, true);
        }
    }

    private List<HlsStreamSegment> loadSegments() throws IOException {
        String playbackUrl = SoundCloudHelper.loadPlaybackUrl(httpInterface, m3uInfo.lookupUrl);
        String[] lines = HttpClientTools.fetchResponseLines(httpInterface, new HttpGet(playbackUrl), "stream segments list");
        initSegmentUrl = findInitSegmentUrl(lines, playbackUrl);
        return HlsStreamSegmentParser.parseFromLines(lines);
    }

    private static String findInitSegmentUrl(String[] lines, String playbackUrl) {
        for (String line : lines) {
            Matcher matcher = MAP_URI.matcher(line.trim());

            if (matcher.find()) {
                // Resolve against the playlist URL in case the init segment is given as a relative path.
                return URI.create(playbackUrl).resolve(matcher.group(1)).toString();
            }
        }

        return null;
    }

    private SegmentTracker createSegmentTracker() throws IOException {
        List<HlsStreamSegment> initialSegments = loadSegments();
        SegmentTracker tracker = new SegmentTracker(initialSegments);
        tracker.setupDecoder(m3uInfo.decoderFactory);
        return tracker;
    }

    private class SegmentTracker implements AutoCloseable {
        private final List<HlsStreamSegment> segments;
        private long desiredPosition = 0;
        private long streamStartPosition = 0;
        private long lastUpdate;
        private SoundCloudSegmentDecoder decoder;
        private int segmentIndex = 0;
        private boolean initSegmentPending = false;

        private SegmentTracker(List<HlsStreamSegment> segments) {
            this.segments = segments;
            this.lastUpdate = System.currentTimeMillis();
        }

        private void setupDecoder(SoundCloudSegmentDecoder.Factory factory) {
            decoder = factory.create(this::createChainedStream);
        }

        private SeekableInputStream createChainedStream() {
            // Every new stream (including after a seek) must begin with the init segment to be decodable.
            initSegmentPending = initSegmentUrl != null;
            return new NonSeekableInputStream(new ChainedInputStream(this::getNextStream));
        }

        private void seekToTimecode(long timecode) throws IOException {
            long segmentTimecode = 0;

            for (int i = 0; i < segments.size(); i++) {
                Long duration = segments.get(i).duration;

                if (duration == null) {
                    break;
                }

                long nextTimecode = segmentTimecode + duration;

                if (timecode >= segmentTimecode && timecode < nextTimecode) {
                    seekToSegment(i, timecode, segmentTimecode);
                    return;
                }

                segmentTimecode = nextTimecode;
            }

            seekToEnd();
        }

        private void seekToSegment(int index, long requestedTimecode, long segmentTimecode) throws IOException {
            decoder.resetStream();

            segmentIndex = index;
            desiredPosition = requestedTimecode;
            streamStartPosition = segmentTimecode;

            decoder.prepareStream(streamStartPosition == 0);
        }

        private void seekToEnd() throws IOException {
            decoder.resetStream();

            segmentIndex = segments.size();
        }

        private InputStream getNextStream() {
            if (initSegmentPending) {
                initSegmentPending = false;
                return HttpStreamTools.streamContent(httpInterface, new HttpGet(initSegmentUrl));
            }

            HlsStreamSegment segment = getNextSegment();

            if (segment == null) {
                return null;
            }

            return HttpStreamTools.streamContent(httpInterface, new HttpGet(segment.url));
        }

        private void updateSegmentList() {
            try {
                List<HlsStreamSegment> newSegments = loadSegments();

                if (newSegments.size() != segments.size()) {
                    log.error("For {}, received different number of segments on update, skipping.", trackInfo.identifier);
                    return;
                }

                for (int i = 0; i < segments.size(); i++) {
                    if (!Objects.equals(newSegments.get(i).duration, segments.get(i).duration)) {
                        log.error("For {}, segment {} has different length than previously on update.", trackInfo.identifier, i);
                        return;
                    }
                }

                for (int i = 0; i < segments.size(); i++) {
                    segments.set(i, newSegments.get(i));
                }
            } catch (Exception e) {
                log.error("For {}, failed to update segment list, skipping.", trackInfo.identifier, e);
            }
        }

        private void checkSegmentListUpdate() {
            long now = System.currentTimeMillis();
            long delta = now - lastUpdate;

            if (delta > SEGMENT_UPDATE_INTERVAL) {
                log.debug("For {}, {}ms has passed since last segment update, updating", trackInfo.identifier, delta);

                updateSegmentList();
                lastUpdate = now;
            }
        }

        private HlsStreamSegment getNextSegment() {
            int current = segmentIndex++;

            if (current < segments.size()) {
                checkSegmentListUpdate();
                return segments.get(current);
            } else {
                return null;
            }
        }

        @Override
        public void close() throws Exception {
            decoder.resetStream();
        }
    }
}

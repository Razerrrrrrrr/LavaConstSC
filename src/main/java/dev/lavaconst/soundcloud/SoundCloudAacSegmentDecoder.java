package dev.lavaconst.soundcloud;

import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegAacTrackConsumer;
import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegFileLoader;
import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegTrackInfo;
import com.sedmelluq.discord.lavaplayer.container.mpeg.reader.MpegFileTrackProvider;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.io.NonSeekableInputStream;
import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioProcessingContext;

import java.io.IOException;
import java.io.InputStream;
import java.util.function.Supplier;

import static com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.SUSPICIOUS;

/**
 * Decodes HLS streams whose segments are AAC in fragmented MP4. The stream handed out by the segment provider must
 * begin with the initialization segment (EXT-X-MAP) followed by the media segments, which
 * {@link SoundCloudM3uAudioTrack} guarantees for every new stream, including after a seek.
 */
public class SoundCloudAacSegmentDecoder implements SoundCloudSegmentDecoder {
    private final Supplier<SeekableInputStream> nextStreamProvider;

    public SoundCloudAacSegmentDecoder(Supplier<SeekableInputStream> nextStreamProvider) {
        this.nextStreamProvider = nextStreamProvider;
    }

    @Override
    public void prepareStream(boolean beginning) {
        // Each playStream call opens its own stream, nothing to prepare.
    }

    @Override
    public void resetStream() {
        // Streams are closed by playStream.
    }

    @Override
    public void playStream(
        AudioProcessingContext context,
        long startPosition,
        long desiredPosition
    ) throws InterruptedException, IOException {
        try (SeekableInputStream stream = new NonSeekableInputStream(new SidxInjectingInputStream(nextStreamProvider.get()))) {
            MpegFileLoader file = new MpegFileLoader(stream);
            file.parseHeaders();

            MpegTrackInfo track = findAacTrack(file);
            MpegAacTrackConsumer consumer = new MpegAacTrackConsumer(context, track);

            try {
                consumer.initialise();

                MpegFileTrackProvider fileReader = file.loadReader(consumer);

                if (fileReader == null) {
                    throw new FriendlyException("Unknown MP4 layout in SoundCloud stream.", SUSPICIOUS, null);
                }

                // Tell the consumer where playback should begin inside what is decoded.
                consumer.seekPerformed(desiredPosition, startPosition);

                fileReader.provideFrames();
            } finally {
                consumer.close();
            }
        }
    }

    @Override
    public void close() {
        // Nothing held between calls.
    }

    private static MpegTrackInfo findAacTrack(MpegFileLoader file) {
        for (MpegTrackInfo track : file.getTrackList()) {
            if ("soun".equals(track.handler) && "mp4a".equals(track.codecName)) {
                return track;
            }
        }

        throw new FriendlyException("No AAC audio track found in the SoundCloud stream.", SUSPICIOUS, null);
    }

    /**
     * Lavaplayer's MP4 reader stops parsing headers at a {@code sidx} box (which YouTube's init segments contain) or
     * at the first {@code mdat}. SoundCloud's init segment has no {@code sidx}, so the reader would run past the
     * first fragment and silently drop it. This wrapper passes the stream through unchanged, except that it inserts a
     * minimal, empty {@code sidx} box right after the {@code moov} box so header parsing stops in the right place.
     */
    static final class SidxInjectingInputStream extends InputStream {
        /**
         * A minimal sidx box: size=44, "sidx", version/flags=0, referenceId=1, timescale=1000, earliest=0, firstOffset=0,
         * reserved=0, one reference entry. Lavaplayer's seek table needs at least one entry; the values are never used
         * because seeking is done by choosing HLS segments, not through this table.
         */
        private static final byte[] SIDX = {
            0, 0, 0, 44, 's', 'i', 'd', 'x',
            0, 0, 0, 0,
            0, 0, 0, 1,
            0, 0, 0x03, (byte) 0xE8,
            0, 0, 0, 0,
            0, 0, 0, 0,
            0, 0, 0, 1,
            0, 0, 0, 1,
            0, 0, 0x03, (byte) 0xE8,
            (byte) 0x80, 0, 0, 0
        };

        private final InputStream in;
        private final byte[] header = new byte[16];
        private int headerLength = 0;
        private int headerPosition = 0;
        private long remainingInBox = 0;
        private boolean inMoov = false;
        private int sidxPosition = -1;
        private boolean done = false;

        SidxInjectingInputStream(InputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : (one[0] & 0xff);
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }

            // Re-emit header bytes that were read to learn the next box's size and type.
            if (headerPosition < headerLength) {
                int count = Math.min(length, headerLength - headerPosition);
                System.arraycopy(header, headerPosition, buffer, offset, count);
                headerPosition += count;
                return count;
            }

            if (sidxPosition >= 0 && sidxPosition < SIDX.length) {
                int count = Math.min(length, SIDX.length - sidxPosition);
                System.arraycopy(SIDX, sidxPosition, buffer, offset, count);
                sidxPosition += count;

                if (sidxPosition >= SIDX.length) {
                    done = true;
                }

                return count;
            }

            if (done) {
                return in.read(buffer, offset, length);
            }

            if (remainingInBox == 0) {
                if (inMoov) {
                    // The moov box just ended, insert the sidx before whatever comes next.
                    sidxPosition = 0;
                    return read(buffer, offset, length);
                }

                if (!readBoxHeader()) {
                    return -1;
                }

                return read(buffer, offset, length);
            }

            int count = in.read(buffer, offset, (int) Math.min(length, remainingInBox));

            if (count < 0) {
                return -1;
            }

            remainingInBox -= count;
            return count;
        }

        /** Reads the next box header into {@link #header} and sets how many payload bytes follow it. */
        private boolean readBoxHeader() throws IOException {
            if (!readFully(header, 0, 8)) {
                return false;
            }

            long size = ((header[0] & 0xffL) << 24) | ((header[1] & 0xffL) << 16) | ((header[2] & 0xffL) << 8) | (header[3] & 0xffL);
            headerLength = 8;

            if (size == 1) {
                if (!readFully(header, 8, 8)) {
                    return false;
                }

                size = 0;
                for (int i = 8; i < 16; i++) {
                    size = (size << 8) | (header[i] & 0xffL);
                }

                headerLength = 16;
            }

            if (size < headerLength) {
                // Size 0 means "until the end of the stream" and anything else is malformed: stop interfering.
                done = true;
                headerPosition = 0;
                remainingInBox = Long.MAX_VALUE;
                return true;
            }

            inMoov = header[4] == 'm' && header[5] == 'o' && header[6] == 'o' && header[7] == 'v';
            headerPosition = 0;
            remainingInBox = size - headerLength;

            if (!inMoov && sidxAlreadyPresentOrMediaStarted(header)) {
                // Not an init-segment box we need to wait for: pass everything through from here on.
                done = true;
            }

            return true;
        }

        private static boolean sidxAlreadyPresentOrMediaStarted(byte[] h) {
            String type = new String(h, 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            return type.equals("sidx") || type.equals("moof") || type.equals("mdat");
        }

        private boolean readFully(byte[] target, int offset, int length) throws IOException {
            int total = 0;

            while (total < length) {
                int count = in.read(target, offset + total, length - total);

                if (count < 0) {
                    if (total == 0) {
                        return false;
                    }

                    throw new IOException("Truncated MP4 box header.");
                }

                total += count;
            }

            return true;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}

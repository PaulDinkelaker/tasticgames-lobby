package de.tasticgames.lobby.music;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads sample rate, channel count and playing time out of an Ogg Vorbis file without a decoder: the
 * identification header carries the sample rate, the last Ogg page carries the granule position (= the sample
 * index the file ends at), so {@code duration = granule / sampleRate}.
 * <p>
 * Used to build the music playlist from the files an operator dropped in – nobody should have to type track
 * lengths into a config by hand.
 *
 * @param sampleRate     samples per second (Minecraft plays 48 kHz files fine)
 * @param channels       1 = mono, 2 = stereo (stereo is fine for music played at the player)
 * @param durationSeconds playing time in seconds
 */
public record OggInfo(int sampleRate, int channels, double durationSeconds) {

    /** Vorbis identification header: {@code 0x01 "vorbis" version(4) channels(1) sampleRate(4)}. */
    private static final byte[] VORBIS_HEADER = {0x01, 'v', 'o', 'r', 'b', 'i', 's'};
    private static final byte[] OGG_PAGE = {'O', 'g', 'g', 'S'};
    private static final int HEAD_BYTES = 64 * 1024;
    private static final int TAIL_BYTES = 64 * 1024;

    public OggInfo {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive");
        }
    }

    public int roundedSeconds() {
        return (int) Math.max(1, Math.round(durationSeconds));
    }

    /** @return empty when the file is not a readable Ogg Vorbis stream */
    public static Optional<OggInfo> read(Path file) {
        Objects.requireNonNull(file, "file");
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            long size = channel.size();
            if (size < 64) {
                return Optional.empty();
            }
            byte[] head = read(channel, 0, (int) Math.min(HEAD_BYTES, size));
            int header = indexOf(head, VORBIS_HEADER, 0);
            if (header < 0 || header + 16 > head.length) {
                return Optional.empty();
            }
            ByteBuffer identification = ByteBuffer.wrap(head, header + 7, 9).order(ByteOrder.LITTLE_ENDIAN);
            identification.getInt(); // vorbis version
            int channels = Byte.toUnsignedInt(identification.get());
            int sampleRate = identification.getInt();
            if (sampleRate <= 0) {
                return Optional.empty();
            }
            long tailStart = Math.max(0, size - TAIL_BYTES);
            byte[] tail = read(channel, tailStart, (int) Math.min(TAIL_BYTES, size));
            int lastPage = lastIndexOf(tail, OGG_PAGE);
            if (lastPage < 0 || lastPage + 14 > tail.length) {
                return Optional.empty();
            }
            long granule = ByteBuffer.wrap(tail, lastPage + 6, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
            if (granule <= 0) {
                return Optional.empty();
            }
            return Optional.of(new OggInfo(sampleRate, channels, granule / (double) sampleRate));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static byte[] read(SeekableByteChannel channel, long position, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        channel.position(position);
        while (buffer.hasRemaining() && channel.read(buffer) > 0) {
            // read until the buffer is full or the stream ends
        }
        byte[] bytes = new byte[buffer.position()];
        buffer.flip();
        buffer.get(bytes);
        return bytes;
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = from; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static int lastIndexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = haystack.length - needle.length; i >= 0; i--) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}

package eu.mrogalski.saidit;

import java.io.IOException;
import java.util.Arrays;

public class OpusRingBuffer {

    public static final int FRAME_SIZE = 960;

    private static final int AVG_FRAME_OVERHEAD = 32;
    public static final int AVG_FRAME_BUDGET = 60 + AVG_FRAME_OVERHEAD;

    private byte[][] frames;
    private int head;
    private int count;
    private int capacity;
    private int totalFrameBytes;

    public synchronized void allocate(long byteBudget) {
        int maxFrames = (int) (byteBudget / AVG_FRAME_BUDGET);
        if (maxFrames < 1) maxFrames = 1;
        capacity = maxFrames;
        frames = new byte[capacity][];
        head = 0;
        count = 0;
        totalFrameBytes = 0;
    }

    public synchronized long getAllocatedSize() {
        return totalFrameBytes + (long) count * AVG_FRAME_OVERHEAD;
    }

    public synchronized void store(byte[] frame) {
        if (capacity == 0) return;
        if (count == capacity) {
            byte[] old = frames[head];
            if (old != null) totalFrameBytes -= old.length;
            frames[head] = frame;
            totalFrameBytes += frame.length;
            head = (head + 1) % capacity;
        } else {
            int idx = (head + count) % capacity;
            frames[idx] = frame;
            totalFrameBytes += frame.length;
            count++;
        }
    }

    public synchronized int getFrameCount() {
        return count;
    }

    public interface FrameConsumer {
        void consume(byte[] frame, int offset, int length) throws IOException;
    }

    public synchronized void read(int skipFrames, FrameConsumer consumer) throws IOException {
        int toRead = count - skipFrames;
        if (toRead <= 0) return;
        int start = (head + skipFrames) % capacity;
        for (int i = 0; i < toRead; i++) {
            int idx = (start + i) % capacity;
            byte[] frame = frames[idx];
            consumer.consume(frame, 0, frame.length);
        }
    }

    public synchronized void clear() {
        Arrays.fill(frames, null);
        head = 0;
        count = 0;
        totalFrameBytes = 0;
    }
}

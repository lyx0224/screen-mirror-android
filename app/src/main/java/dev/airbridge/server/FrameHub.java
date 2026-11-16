package dev.airbridge.server;

/** A bounded, latest-frame-only mailbox. Publishers must not mutate a published JPEG. */
public final class FrameHub {
    public static final class Frame {
        public final long sequence;
        public final byte[] jpeg;
        public final int width;
        public final int height;

        private Frame(long sequence, byte[] jpeg, int width, int height) {
            this.sequence = sequence;
            this.jpeg = jpeg;
            this.width = width;
            this.height = height;
        }
    }

    private long sequence;
    private Frame latest;

    public synchronized void publish(byte[] jpeg, int width, int height) {
        if (jpeg == null || jpeg.length == 0 || width <= 0 || height <= 0) return;
        latest = new Frame(++sequence, jpeg, width, height);
        notifyAll();
    }

    public synchronized void clear() {
        latest = null;
        ++sequence;
        notifyAll();
    }

    public synchronized int getWidth() { return latest == null ? 0 : latest.width; }
    public synchronized int getHeight() { return latest == null ? 0 : latest.height; }
    public synchronized Frame getFrame() { return latest; }

    public synchronized Frame awaitAfter(long after, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + Math.max(0, timeoutMs) * 1_000_000L;
        while (latest == null || latest.sequence <= after) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return null;
            wait(Math.max(1, remaining / 1_000_000L));
        }
        return latest;
    }
}

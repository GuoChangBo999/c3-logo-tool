package xyz.yuluoqc.c3mirror;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.view.Surface;

import java.nio.ByteBuffer;

/**
 * H264 hardware decoder built on MediaCodec, renders directly to a Surface.
 * Mirrors the original app's eb/a6 logic:
 *   - video/avc
 *   - C3: 1024x512, 4.5Mbps (we just decode; bitrate here is informational)
 *   - frame-rate 30, i-frame-interval 1, low-latency
 *   - max-input-size 4MB
 *   - config frame (SPS/PPS) is simply queued as an input buffer too
 */
public class H264Decoder {

    private MediaCodec codec;
    private final Object lock = new Object();
    private volatile boolean running = false;
    private int width = 1024;
    private int height = 512;

    public void start(Surface surface) throws Exception {
        stop();
        MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
        }
        MediaCodec c = MediaCodec.createDecoderByType("video/avc");
        c.configure(format, surface, null, 0);
        // Let the decoder scale the frame to fill the Surface without cropping.
        // Combined with MirrorActivity's letterbox touch mapping this keeps the
        // car picture correct even though the phone is now portrait-locked.
        try {
            c.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT);
        } catch (Exception ignored) {
        }
        c.start();
        synchronized (lock) {
            codec = c;
            running = true;
        }
    }

    /** Feed one H264 access unit (Annex-B or with length prefix handled by decoder). */
    public void feed(byte[] data, int offset, int len, long ptsUs, boolean config) {
        MediaCodec c;
        synchronized (lock) {
            c = codec;
            if (!running || c == null) return;
        }
        try {
            int inIndex = c.dequeueInputBuffer(10000);
            if (inIndex >= 0) {
                ByteBuffer buf = c.getInputBuffer(inIndex);
                if (buf != null) {
                    buf.clear();
                    buf.put(data, offset, len);
                    c.queueInputBuffer(inIndex, 0, len, ptsUs, config ? MediaCodec.BUFFER_FLAG_CODEC_CONFIG : 0);
                }
            }
            drain(c);
        } catch (Exception ignored) {
        }
    }

    private void drain(MediaCodec c) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int outIndex = c.dequeueOutputBuffer(info, 0);
            if (outIndex >= 0) {
                c.releaseOutputBuffer(outIndex, true);
            } else {
                break;
            }
        }
    }

    public void stop() {
        synchronized (lock) {
            running = false;
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) {}
                try { codec.release(); } catch (Exception ignored) {}
                codec = null;
            }
        }
    }
}
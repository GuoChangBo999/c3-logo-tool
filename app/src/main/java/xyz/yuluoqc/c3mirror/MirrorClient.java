package xyz.yuluoqc.c3mirror;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Client for the C3 mirror chain.
 *
 * Video: connects to a local TCP port (usually an SSH-forwarded tunnel to the
 *        car's :5000). The car pushes a raw H264 Annex-B elementary stream
 *        (v4l_h264_encoder "listening on tcp://0.0.0.0:5000").
 *
 * Touch: connects to a local TCP port (SSH-forwarded to car's :27184).
 *        Sends fixed 32-byte packets: >BBQIIHHHII
 *          byte   msg_type   (0x7F = ping/heartbeat, 2 = touch)
 *          byte   action     (0=down, 1=up, 2=move, 3=cancel)
 *          long   timestamp  (ignored by server)
 *          int    x
 *          int    y
 *          short  width      (source frame width)
 *          short  height     (source frame height)
 *          short  _pad1
 *          int    _pad2
 *          int    _pad3
 *        server replies to 0x7F with 8-byte long timestamp.
 *
 * NOTE: this is the reverse of the original app: in the original the phone
 * connected straight to the car; here the same endpoints are reached through
 * an SSH local-forward tunnel, so netcode is identical apart from host/port.
 */
public class MirrorClient {

    private static final String TAG = "C3Mirror";

    public interface VideoSink {
        void onVideo(byte[] data, int offset, int len, long ptsUs, boolean config);
        void onVideoEnded(String reason);
    }

    public interface TouchAckCallback {
        void onPing(long serverMicros);
    }

    private final String host;
    private final int videoPort;
    private final int touchPort;
    private final VideoSink sink;
    private final TouchAckCallback ack;

    private volatile boolean running = false;
    private Thread videoThread;
    private Thread touchThread;
    private Socket videoSocket;
    private Socket touchSocket;

    public MirrorClient(String host, int videoPort, int touchPort, VideoSink sink, TouchAckCallback ack) {
        this.host = host;
        this.videoPort = videoPort;
        this.touchPort = touchPort;
        this.sink = sink;
        this.ack = ack;
    }

    public void start() {
        running = true;

        videoThread = new Thread(this::videoLoop, "c3-video");
        videoThread.start();

        touchThread = new Thread(this::touchLoop, "c3-touch");
        touchThread.start();
    }

    // ---------- video ----------

    private void videoLoop() {
        try {
            Socket s = new Socket();
            s.setTcpNoDelay(true);
            s.setReceiveBufferSize(256 * 1024);
            s.connect(new InetSocketAddress(host, videoPort), 8000);
            videoSocket = s;
            Log.i(TAG, "video connected " + host + ":" + videoPort);

            InputStream in = new BufferedInputStream(s.getInputStream(), 512 * 1024);
            byte[] acc = new byte[512 * 1024];
            int accLen = 0;
            byte[] chunk = new byte[64 * 1024];
            long pts = 0;
            while (running) {
                int n = in.read(chunk);
                if (n < 0) break;
                if (n == 0) continue;
                if (accLen + n > acc.length) {
                    int newCap = acc.length;
                    while (newCap < accLen + n) newCap <<= 1;
                    byte[] bigger = new byte[newCap];
                    System.arraycopy(acc, 0, bigger, 0, accLen);
                    acc = bigger;
                }
                System.arraycopy(chunk, 0, acc, accLen, n);
                accLen += n;
                int start = findStartCode(acc, 0, accLen);
                while (start >= 0) {
                    int nextStart = findStartCode(acc, start + 3, accLen);
                    if (nextStart < 0) break;
                    int nalLen = nextStart - start;
                    if (nalLen > 0) {
                        boolean isSpsPps = isParameterSet(acc, start + 3, nextStart);
                        sink.onVideo(acc, start, nalLen, pts, isSpsPps);
                        pts += 33_000;
                    }
                    System.arraycopy(acc, nextStart, acc, 0, accLen - nextStart);
                    accLen -= nextStart;
                    start = findStartCode(acc, 0, accLen);
                }
                if (accLen > 4 * 1024 * 1024) {
                    sink.onVideo(acc, 0, accLen, pts, false);
                    accLen = 0;
                }
            }
            if (accLen > 0) sink.onVideo(acc, 0, accLen, pts, false);
            sink.onVideoEnded("视频流结束");
        } catch (Exception e) {
            if (running) sink.onVideoEnded("视频连接错误: " + e.getMessage());
        } finally {
            closeQuietly(videoSocket);
        }
    }

    /** index of next Annex-B start code (00 00 01) at/after from, or -1. */
    private static int findStartCode(byte[] b, int from, int len) {
        for (int i = Math.max(from, 0); i + 2 < len; i++) {
            if (b[i] == 0 && b[i + 1] == 0 && b[i + 2] == 1) return i;
        }
        return -1;
    }

    /** true if the NAL whose bytes start at p (just past the start code) is SPS(7)/PPS(8). */
    private static boolean isParameterSet(byte[] b, int p, int end) {
        while (p < end && b[p] == 0) p++;
        if (p >= end) return false;
        int nalType = b[p] & 0x1F;
        return nalType == 7 || nalType == 8;
    }

    // ---------- touch ----------

    private void touchLoop() {
        try {
            Socket s = new Socket();
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(host, touchPort), 8000);
            touchSocket = s;
            Log.i(TAG, "touch connected " + host + ":" + touchPort);

            // heartbeat thread reads server replies for 0x7F
            final InputStream in = s.getInputStream();
            Thread reader = new Thread(() -> {
                byte[] hb = new byte[8];
                try {
                    while (running) {
                        int got = 0;
                        while (got < 8) {
                            int r = in.read(hb, got, 8 - got);
                            if (r < 0) return;
                            got += r;
                        }
                        if (ack != null) {
                            long v = ByteBuffer.wrap(hb).order(ByteOrder.BIG_ENDIAN).getLong();
                            ack.onPing(v);
                        }
                    }
                } catch (Exception ignored) {
                }
            }, "c3-touch-rx");
            reader.start();

            // periodic heartbeat every 2s
            while (running) {
                sendTouch(s, 0x7F, 0, 0, 0, 0, 0);
                Thread.sleep(2000);
            }
        } catch (Exception ignored) {
        } finally {
            closeQuietly(touchSocket);
        }
    }

    /** Send a touch event. action: 0=down 1=up 2=move 3=cancel. */
    public void sendTouchEvent(int action, int x, int y, int width, int height) {
        Socket s = touchSocket;
        if (s == null || s.isClosed()) return;
        try {
            sendTouch(s, 2, action, x, y, width, height);
        } catch (Exception ignored) {
        }
    }

    private void sendTouch(Socket s, int msgType, int action, int x, int y, int width, int height) {
        try {
            ByteBuffer bb = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN);
            bb.put((byte) msgType);
            bb.put((byte) action);
            bb.putLong(System.currentTimeMillis() * 1000L); // ts us
            bb.putInt(x);
            bb.putInt(y);
            bb.putShort((short) width);
            bb.putShort((short) height);
            bb.putShort((short) 0);   // pad1
            bb.putInt(0);             // pad2
            bb.putInt(0);             // pad3  -> total = 1+1+8+4+4+2+2+2+4+4 = 32
            s.getOutputStream().write(bb.array());
            s.getOutputStream().flush();
        } catch (Exception ignored) {
        }
    }

    public void stop() {
        running = false;
        closeQuietly(videoSocket);
        closeQuietly(touchSocket);
        if (videoThread != null) videoThread.interrupt();
        if (touchThread != null) touchThread.interrupt();
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try { s.close(); } catch (Exception ignored) {}
        }
    }
}
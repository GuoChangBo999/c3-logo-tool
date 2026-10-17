package xyz.yuluoqc.c3mirror;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;
import java.net.ServerSocket;

public class MirrorActivity extends AppCompatActivity {

    // source (car screen) resolution used for touch coordinate mapping.
    // The car touch_proxy maps into a 1080x2160 panel with x mirrored; but the
    // phone-side protocol just reports the width/height of the *displayed*
    // frame's coordinate space, then the server scales. We report the frame size
    // we render into, and the server does its own 1080x2160 mapping.
    private static final int SRC_W = 1024;
    private static final int SRC_H = 512;

    private SurfaceView surfaceView;
    private TextView tvStatus;
    private H264Decoder decoder;
    private MirrorClient client;
    private SshDeployer ssh;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean ready = false;

    private String host, user, pass, key, keypass;
    private int vPort, tPort;
    private boolean deploy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mirror);

        surfaceView = findViewById(R.id.surface);
        tvStatus = findViewById(R.id.tvStatus);
        Button btnClose = findViewById(R.id.btnClose);

        host = getIntent().getStringExtra("host");
        user = getIntent().getStringExtra("user");
        pass = getIntent().getStringExtra("pass");
        key = getIntent().getStringExtra("key");
        keypass = getIntent().getStringExtra("keypass");
        vPort = getIntent().getIntExtra("vport", 5000);
        tPort = getIntent().getIntExtra("tport", 27184);
        deploy = getIntent().getBooleanExtra("deploy", true);

        btnClose.setOnClickListener(v -> finish());

        decoder = new H264Decoder();

        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                try {
                    decoder.start(holder.getSurface());
                    ready = true;
                    status("Surface 就绪，启动链路...");
                    startPipeline();
                } catch (Exception e) {
                    status("解码器初始化失败: " + e.getMessage());
                }
            }

            @Override public void surfaceChanged(SurfaceHolder holder, int f, int w, int h) {}
            @Override public void surfaceDestroyed(SurfaceHolder holder) {
                ready = false;
                stopPipeline();
            }
        });

        // touch -> send to car via tunnel
        surfaceView.setOnTouchListener((v, ev) -> {
            if (client == null) return true;
            int action;
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN:
                    action = 0; break;
                case MotionEvent.ACTION_MOVE:
                    action = 2; break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP:
                    action = 1; break;
                case MotionEvent.ACTION_CANCEL:
                    action = 3; break;
                default:
                    return true;
            }
            int x = (int) ev.getX();
            int y = (int) ev.getY();
            int vw = surfaceView.getWidth();
            int vh = surfaceView.getHeight();
            if (vw <= 0 || vh <= 0) return true;
            // map view coords -> source frame coords
            int sx = clamp((int) (x * (float) SRC_W / vw), 0, SRC_W - 1);
            int sy = clamp((int) (y * (float) SRC_H / vh), 0, SRC_H - 1);
            client.sendTouchEvent(action, sx, sy, SRC_W, SRC_H);
            return true;
        });
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private void status(String s) {
        ui.post(() -> tvStatus.setText(s));
    }

    private void startPipeline() {
        new Thread(() -> {
            try {
                ssh = new SshDeployer(this, host, user, pass, key, keypass, this::status);
                status("SSH 连接 " + host + " ...");
                ssh.connect();

                if (deploy) {
                    status("部署车机端...");
                    ssh.deploy();
                }

                // pick two free local ports for the tunnels
                int lv = freePort();
                int lt = freePort();
                ssh.addLocalForward(lv, "127.0.0.1", vPort);
                ssh.addLocalForward(lt, "127.0.0.1", tPort);
                Thread.sleep(600);

                status("连接视频流 127.0.0.1:" + lv);
                client = new MirrorClient("127.0.0.1", lv, lt,
                        new MirrorClient.VideoSink() {
                            @Override
                            public void onVideo(byte[] data, int offset, int len, long ptsUs, boolean config) {
                                if (ready) decoder.feed(data, offset, len, ptsUs, config);
                            }
                            @Override
                            public void onVideoEnded(String reason) {
                                status("视频结束: " + reason);
                            }
                        },
                        micros -> { /* heartbeat ack */ });
                client.start();
                status("投屏中");
            } catch (Exception e) {
                status("链路错误: " + e.getMessage());
            }
        }, "c3-pipeline").start();
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            s.setReuseAddress(true);
            return s.getLocalPort();
        }
    }

    private void stopPipeline() {
        if (client != null) { client.stop(); client = null; }
        if (ssh != null) { ssh.close(); ssh = null; }
        if (decoder != null) decoder.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPipeline();
    }
}
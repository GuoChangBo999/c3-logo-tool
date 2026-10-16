package xyz.yuluoqc.c3mirror;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private EditText etHost, etUser, etPass, etVideoPort, etTouchPort;
    private CheckBox cbDeploy;
    private TextView tvLog;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etHost = findViewById(R.id.etHost);
        etUser = findViewById(R.id.etSshUser);
        etPass = findViewById(R.id.etSshPass);
        etVideoPort = findViewById(R.id.etVideoPort);
        etTouchPort = findViewById(R.id.etTouchPort);
        cbDeploy = findViewById(R.id.cbDeploy);
        tvLog = findViewById(R.id.tvLog);

        Button btnDeploy = findViewById(R.id.btnDeploy);
        Button btnStart = findViewById(R.id.btnStart);

        btnDeploy.setOnClickListener(v -> doDeployOnly());
        btnStart.setOnClickListener(v -> doStart());
    }

    private void log(String s) {
        ui.post(() -> tvLog.append(s + "\n"));
    }

    private String host() { return etHost.getText().toString().trim(); }
    private String user() { return etUser.getText().toString().trim(); }
    private String pass() { return etPass.getText().toString(); }
    private int vPort() { return parse(etVideoPort, 5000); }
    private int tPort() { return parse(etTouchPort, 27184); }

    private int parse(EditText e, int def) {
        try { return Integer.parseInt(e.getText().toString().trim()); }
        catch (Exception ex) { return def; }
    }

    private void doDeployOnly() {
        tvLog.setText("");
        io.execute(() -> {
            SshDeployer d = new SshDeployer(this, host(), user(), pass(), this::log);
            try {
                d.connect();
                d.deploy();
                log("部署流程结束");
            } catch (Exception e) {
                log("部署失败: " + e.getMessage());
            } finally {
                d.close();
            }
        });
    }

    private void doStart() {
        tvLog.setText("");
        Intent i = new Intent(this, MirrorActivity.class);
        i.putExtra("host", host());
        i.putExtra("user", user());
        i.putExtra("pass", pass());
        i.putExtra("vport", vPort());
        i.putExtra("tport", tPort());
        i.putExtra("deploy", cbDeploy.isChecked());
        startActivity(i);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }
}
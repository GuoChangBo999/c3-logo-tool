package xyz.yuluoqc.c3mirror;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private EditText etHost, etUser, etPass, etVideoPort, etTouchPort, etKey, etKeyPass;
    private CheckBox cbDeploy, cbUseKey;
    private TextView tvLog;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ActivityResultLauncher<Intent> filePicker;

    private static final String PREFS = "c3mirror";
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        etHost = findViewById(R.id.etHost);
        etUser = findViewById(R.id.etSshUser);
        etPass = findViewById(R.id.etSshPass);
        etVideoPort = findViewById(R.id.etVideoPort);
        etTouchPort = findViewById(R.id.etTouchPort);
        etKey = findViewById(R.id.etKey);
        etKeyPass = findViewById(R.id.etKeyPass);
        cbDeploy = findViewById(R.id.cbDeploy);
        cbUseKey = findViewById(R.id.cbUseKey);
        tvLog = findViewById(R.id.tvLog);

        Button btnDeploy = findViewById(R.id.btnDeploy);
        Button btnStart = findViewById(R.id.btnStart);
        Button btnPickKey = findViewById(R.id.btnPickKey);

        // restore saved settings
        etHost.setText(prefs.getString("host", "10.90.179.222"));
        etUser.setText(prefs.getString("user", "comma"));
        etPass.setText(prefs.getString("pass", "comma"));
        etVideoPort.setText(prefs.getString("vport", "5000"));
        etTouchPort.setText(prefs.getString("tport", "27184"));
        cbUseKey.setChecked(prefs.getBoolean("useKey", false));
        etKey.setText(prefs.getString("key", ""));
        etKeyPass.setText(prefs.getString("keypass", ""));

        // file picker for the private key
        filePicker = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) {
                            readKeyFromUri(uri);
                        }
                    }
                });

        btnPickKey.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"*/*"});
            filePicker.launch(i);
        });

        btnDeploy.setOnClickListener(v -> doDeployOnly());
        btnStart.setOnClickListener(v -> doStart());
    }

    /** Read the chosen private key file into the editor. */
    private void readKeyFromUri(Uri uri) {
        io.execute(() -> {
            try (InputStream is = getContentResolver().openInputStream(uri);
                 BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                String content = sb.toString();
                ui.post(() -> {
                    etKey.setText(content);
                    cbUseKey.setChecked(true);
                    log("已载入密钥文件（" + content.length() + " 字符）");
                });
            } catch (Exception e) {
                ui.post(() -> log("读取密钥失败: " + e.getMessage()));
            }
        });
    }

    private void log(String s) {
        ui.post(() -> {
            tvLog.append(s + "\n");
            int scroll = tvLog.getLayout() == null ? 0 : tvLog.getLayout().getLineTop(tvLog.getLineCount()) - tvLog.getHeight();
            if (scroll > 0) tvLog.scrollTo(0, scroll);
        });
    }

    private String host() { return etHost.getText().toString().trim(); }
    private String user() { return etUser.getText().toString().trim(); }
    private String pass() { return etPass.getText().toString(); }
    private int vPort() { return parse(etVideoPort, 5000); }
    private int tPort() { return parse(etTouchPort, 27184); }

    /** The private key to use, or empty if password auth is wanted. */
    private String privateKey() {
        if (!cbUseKey.isChecked()) return "";
        return etKey.getText().toString();
    }
    private String keyPassphrase() {
        if (!cbUseKey.isChecked()) return "";
        return etKeyPass.getText().toString();
    }

    private int parse(EditText e, int def) {
        try { return Integer.parseInt(e.getText().toString().trim()); }
        catch (Exception ex) { return def; }
    }

    private void saveSettings() {
        prefs.edit()
                .putString("host", host())
                .putString("user", user())
                .putString("pass", pass())
                .putString("vport", String.valueOf(vPort()))
                .putString("tport", String.valueOf(tPort()))
                .putBoolean("useKey", cbUseKey.isChecked())
                .putString("key", etKey.getText().toString())
                .putString("keypass", etKeyPass.getText().toString())
                .apply();
    }

    private void doDeployOnly() {
        tvLog.setText("");
        saveSettings();
        final String h = host(), u = user(), p = pass(), k = privateKey(), kp = keyPassphrase();
        io.execute(() -> {
            SshDeployer d = new SshDeployer(this, h, u, p, k, kp, this::log);
            try {
                log("连接 " + u + "@" + h + " ...");
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
        saveSettings();
        Intent i = new Intent(this, MirrorActivity.class);
        i.putExtra("host", host());
        i.putExtra("user", user());
        i.putExtra("pass", pass());
        i.putExtra("key", privateKey());
        i.putExtra("keypass", keyPassphrase());
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
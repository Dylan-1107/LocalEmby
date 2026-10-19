package com.local.embylite;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import android.provider.DocumentsContract;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/** 主界面：启停服务器、显示连接地址、设置视频路径与端口、刷新媒体库、看日志。 */
public class MainActivity extends Activity {

    private static final int REQ_PERMS = 1;
    private static final int REQ_PICK_FOLDER = 2;

    private View dot;
    private TextView tvStatus;
    private TextView tvAddrLoopback;
    private TextView tvAddrLan;
    private TextView tvCount;
    private Button btnToggle;
    private Button btnPick;
    private Button btnSavePath;
    private Button btnSavePort;
    private Button btnRefresh;
    private Button btnLog;
    private EditText etPath;
    private EditText etPort;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean scanning = false;

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            updateUi();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        dot = findViewById(R.id.dot);
        tvStatus = (TextView) findViewById(R.id.tvStatus);
        tvAddrLoopback = (TextView) findViewById(R.id.tvAddrLoopback);
        tvAddrLan = (TextView) findViewById(R.id.tvAddrLan);
        tvCount = (TextView) findViewById(R.id.tvCount);
        btnToggle = (Button) findViewById(R.id.btnToggle);
        btnPick = (Button) findViewById(R.id.btnPick);
        btnSavePath = (Button) findViewById(R.id.btnSavePath);
        btnSavePort = (Button) findViewById(R.id.btnSavePort);
        btnRefresh = (Button) findViewById(R.id.btnRefresh);
        btnLog = (Button) findViewById(R.id.btnLog);
        etPath = (EditText) findViewById(R.id.etPath);
        etPort = (EditText) findViewById(R.id.etPort);

        etPath.setText(Settings.getFolderPath(this));
        etPort.setText(String.valueOf(Settings.getPort(this)));

        btnToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleServer();
            }
        });
        btnPick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickFolder();
            }
        });
        // 点一下地址即可复制
        tvAddrLoopback.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyToClipboard(tvAddrLoopback.getText().toString());
            }
        });
        tvAddrLan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyToClipboard(tvAddrLan.getText().toString().replace("局域网: ", ""));
            }
        });
        btnSavePath.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                savePathAndScan();
            }
        });
        btnSavePort.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                savePort();
            }
        });
        btnRefresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scanNow();
            }
        });
        btnLog.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, LogActivity.class));
            }
        });

        requestPermsIfNeeded();
        refreshCountQuietly();
        updateUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresher);
    }

    private void copyToClipboard(String text) {
        if (text == null || text.isEmpty()) return;
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("url", text));
            Toast.makeText(this, "已复制: " + text, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "复制失败", Toast.LENGTH_SHORT).show();
        }
    }

    /** 系统文件夹选择器（SAF），选完自动授权并刷新媒体库。 */
    private void pickFolder() {
        try {
            Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            startActivityForResult(it, REQ_PICK_FOLDER);
        } catch (Throwable t) {
            Toast.makeText(this, "无法打开系统文件夹选择器", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_FOLDER && resultCode == RESULT_OK && data != null
                && data.getData() != null) {
            final Uri tree = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Throwable ignored) {
            }
            Settings.setTreeUri(this, tree.toString());
            String display = treePathForDisplay(tree);
            etPath.setText(display);
            VideoLibrary.invalidate();
            Toast.makeText(this, "已选择: " + display, Toast.LENGTH_LONG).show();
            scanNow();
            updateUi();
        }
    }

    /** 从目录树 URI 解析出可读路径（primary:Download/x → /storage/emulated/0/Download/x）。 */
    private static String treePathForDisplay(Uri tree) {
        try {
            String docId = DocumentsContract.getTreeDocumentId(tree);
            String path = java.net.URLDecoder.decode(docId, "UTF-8");
            int colon = path.indexOf(':');
            String vol = colon >= 0 ? path.substring(0, colon) : "primary";
            String sub = colon >= 0 ? path.substring(colon + 1) : "";
            if ("primary".equals(vol)) {
                return "/storage/emulated/0" + (sub.isEmpty() ? "" : "/" + sub);
            }
            return "/storage/" + vol + (sub.isEmpty() ? "" : "/" + sub);
        } catch (Throwable t) {
            return tree.toString();
        }
    }

    private void requestPermsIfNeeded() {        if (Build.VERSION.SDK_INT >= 33) {
            String[] perms = {
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.POST_NOTIFICATIONS
            };
            boolean need = false;
            for (String p : perms) {
                if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                    need = true;
                }
            }
            if (need) {
                requestPermissions(perms, REQ_PERMS);
            }
        } else {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_PERMS);
            }
        }
    }

    private void toggleServer() {
        Intent it = new Intent(this, HttpService.class);
        if (HttpService.isServiceRunning) {
            Settings.setServerEnabled(this, false);
            stopService(it);
            Toast.makeText(this, "服务器已停止", Toast.LENGTH_SHORT).show();
        } else {
            Settings.setServerEnabled(this, true);            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    startForegroundService(it);
                } else {
                    startService(it);
                }
                Toast.makeText(this, "服务器启动中…", Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                EmbyLog.info("启动前台服务失败: " + t);
                Toast.makeText(this, "启动失败，请重试", Toast.LENGTH_SHORT).show();
            }
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                updateUi();
            }
        }, 300);
    }

    private void savePathAndScan() {
        String path = etPath.getText().toString().trim();
        // 手动输入路径时清除 SAF 授权，避免旧目录优先级覆盖新路径
        Settings.setTreeUri(this, "");
        Settings.setFolderPath(this, path);
        VideoLibrary.invalidate();
        scanNow();
    }

    private void scanNow() {
        if (scanning) {
            return;
        }
        scanning = true;
        tvCount.setText("扫描媒体库中…");
        final String path = Settings.getFolderPath(this);
        new Thread(new Runnable() {
            @Override
            public void run() {
                int n;
                try {
                    n = VideoLibrary.scanAuto(MainActivity.this, path).size();
                } catch (Throwable t) {
                    EmbyLog.info("扫描失败: " + t);
                    n = -1;
                }
                final int result = n;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        scanning = false;
                        if (result < 0) {
                            tvCount.setText("扫描失败，请查看请求日志");
                        } else if (path.length() == 0) {
                            tvCount.setText("已扫描全手机视频，共 " + result + " 部");
                        } else {
                            tvCount.setText("共 " + result + " 部视频");
                        }
                    }
                });
            }
        }).start();
    }

    private void refreshCountQuietly() {
        final String path = Settings.getFolderPath(this);
        new Thread(new Runnable() {
            @Override
            public void run() {
                int n;
                try {
                    n = VideoLibrary.get(MainActivity.this, path).size();
                } catch (Throwable t) {
                    n = -1;
                }
                final int result = n;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (result >= 0 && tvCount.getText().length() == 0) {
                            tvCount.setText("共 " + result + " 部视频");
                        }
                    }
                });
            }
        }).start();
    }

    private void savePort() {
        String s = etPort.getText().toString().trim();
        try {
            int v = Integer.parseInt(s);
            if (v < 1024 || v > 65535) {
                throw new NumberFormatException("out of range");
            }
            Settings.setPort(this, v);
            String msg = "端口已保存: " + v;
            if (HttpService.isServiceRunning) {
                msg += "（重启服务器后生效）";
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
            updateUi();
        } catch (Exception e) {
            Toast.makeText(this, "请输入 1024 - 65535 之间的端口号", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateUi() {
        boolean running = HttpService.isServiceRunning;
        int port = Settings.getPort(this);

        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(running ? 0xFF4ADE80 : 0xFF5A5A66);
        dot.setBackground(d);

        tvStatus.setText(running ? "运行中 · 端口 " + port : "已停止");
        tvAddrLoopback.setText("http://127.0.0.1:" + port);
        String ip = getLanIp();
        tvAddrLan.setText("局域网: " + (ip == null ? "未检测到" : "http://" + ip + ":" + port));
        btnToggle.setText(running ? "停止服务器" : "启动服务器");
    }

    /** 枚举非回环 IPv4，优先返回常见内网段地址，过滤 169.254 链路本地地址。 */
    private static String getLanIp() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            String fallback = null;
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a.isLoopbackAddress() || !(a instanceof Inet4Address)) {
                        continue;
                    }
                    String h = a.getHostAddress();
                    if (h == null || h.startsWith("169.254")) {
                        continue;
                    }
                    if (h.startsWith("192.168.") || h.startsWith("10.")
                            || h.startsWith("172.")) {
                        return h;
                    }
                    if (fallback == null) {
                        fallback = h;
                    }
                }
            }
            return fallback;
        } catch (Throwable t) {
            return null;
        }
    }
}

package com.local.embylite;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

/** 请求日志页：等宽字体展示 EmbyLog，每 2 秒刷新，可复制。 */
public class LogActivity extends Activity {

    private TextView tvLog;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            loadLog();
            handler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);
        tvLog = (TextView) findViewById(R.id.tvLog);
        Button btnCopy = (Button) findViewById(R.id.btnCopy);
        btnCopy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyLog();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadLog();
        handler.postDelayed(refresher, 2000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresher);
    }

    private void loadLog() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String text = EmbyLog.text();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        tvLog.setText(text);
                    }
                });
            }
        }).start();
    }

    private void copyLog() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("embylite", tvLog.getText().toString()));
            Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "复制失败", Toast.LENGTH_SHORT).show();
        }
    }
}

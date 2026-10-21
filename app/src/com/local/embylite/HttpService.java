package com.local.embylite;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/** 前台 Service：拉起并保持 Emby HTTP 服务器。 */
public class HttpService extends Service {

    public static volatile boolean isServiceRunning = false;
    /** 服务启动时间戳，供界面显示运行时长。 */
    public static volatile long startedAt = 0L;

    private static final String CHANNEL_ID = "embylite";
    private static final int NOTIFICATION_ID = 1;
    private android.os.PowerManager.WakeLock wakeLock;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        isServiceRunning = false;
        // 部分国产 ROM 后台限流严重，持有 partial wakelock 保证 CPU 不休眠
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,
                    "embylite:server");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        } catch (Throwable ignored) {
        }
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "服务器", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Embylite 服务器运行状态");
            nm.createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int port = Settings.getPort(this);
        startForeground(NOTIFICATION_ID, buildNotification(port));
        try {
            EmbyHttpServer.start(this, port);
            isServiceRunning = EmbyHttpServer.isRunning();
            if (!isServiceRunning) {
                EmbyLog.info("HTTP 服务未监听，端口可能被占用: " + port);
            }
        } catch (Throwable t) {
            EmbyLog.info("HttpService 启动服务器失败: " + t.getClass().getName() + ": " + t.getMessage());
        }
        isServiceRunning = EmbyHttpServer.isRunning();
        if (!isServiceRunning) {
            startedAt = 0L;
            android.widget.Toast.makeText(this, "服务器启动失败，请检查端口和请求日志",
                    android.widget.Toast.LENGTH_LONG).show();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (startedAt == 0L) startedAt = System.currentTimeMillis();
        startForeground(NOTIFICATION_ID, buildNotification(port));
        return START_STICKY;
    }

    private Notification buildNotification(int port) {
        int count = 0;
        try {
            count = VideoLibrary.get(this, Settings.getFolderPath(this)).size();
        } catch (Throwable t) {
            EmbyLog.info("统计视频数量失败: " + t);
        }
        String lanIp = NetworkUtils.getLanIp();
        String address = lanIp == null ? "http://127.0.0.1:" + port : "http://" + lanIp + ":" + port;
        String content = address + " · 视频 " + count + " 部";

        PendingIntent pi = PendingIntent.getActivity(
                this,
                0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle(isServiceRunning ? "Embylite 服务器运行中" : "Embylite 服务器启动中")
                .setContentText(content)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pi)
                .setOngoing(true);
        return b.build();
    }

    @Override
    public void onDestroy() {
        isServiceRunning = false;
        startedAt = 0L;
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignored) {
        }
        try {
            EmbyHttpServer.stop();
        } catch (Throwable t) {
            EmbyLog.info("HttpService 停止服务器失败: " + t);
        }
        super.onDestroy();
    }
}

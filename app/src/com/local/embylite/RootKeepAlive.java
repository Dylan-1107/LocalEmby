package com.local.embylite;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * Root 保活：在已 root 的设备上申请 su，把本应用加入电池优化白名单，
 * 并尝试解除后台限制。未 root 或用户拒绝授权时静默失败，不影响正常使用。
 *
 * 注意：只做「用户显式开启后才执行」的本地操作，不下载、不联网、不做任何提权外的事。
 */
public final class RootKeepAlive {

    public enum Status {
        /** 尚未检测 */
        UNKNOWN,
        /** 有 root 且已授权 */
        GRANTED,
        /** 有 su 但用户拒绝/未授权 */
        DENIED,
        /** 设备无 root */
        NO_ROOT
    }

    private RootKeepAlive() {
    }

    /** 检测 su 是否可用且已授权。会真正执行一次 `id`，可能需要用户在 root 管理器中确认。 */
    public static Status check() {
        String out = exec("id");
        if (out == null) return Status.NO_ROOT;
        return out.contains("uid=0") ? Status.GRANTED : Status.DENIED;
    }

    /**
     * 执行保活：白名单 + 解除后台限制 + 保持服务。
     * @return 是否成功执行（全部或主要步骤成功）
     */
    public static boolean apply(Context ctx) {
        String pkg = ctx.getPackageName();
        boolean ok = false;

        // 1) 电池优化白名单（root 下可直接写，无需用户点确认）
        String whitelist = exec("dumpsys deviceidle whitelist +" + pkg);
        if (whitelist != null && !whitelist.contains("Error")) ok = true;

        // 2) 解除后台/待机限制（Android 7+）
        exec("cmd appops set " + pkg + " RUN_IN_BACKGROUND allow");
        exec("cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow");

        // 3) 关闭该应用的电池优化限制（部分 ROM 的 am 子命令）
        exec("am set-inactive " + pkg + " false");

        // 4) 常驻：把服务拉成前台并防止被回收
        try {
            Intent i = new Intent(ctx, HttpService.class);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Throwable t) {
            EmbyLog.info("root 保活拉起服务失败: " + t);
        }

        // 5) 尝试把本应用加入最近任务白名单（HyperOS / MIUI）
        exec("settings put secure user_setup_complete 1");

        EmbyLog.info("root 保活执行: pkg=" + pkg + " ok=" + ok);
        return ok;
    }

    /** 非 root 兜底：跳系统电池优化设置页，让用户手动允许。 */
    public static void requestIgnoreBatteryOptimizations(Context ctx) {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName())) return;
            Intent i = new Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable t) {
            EmbyLog.info("请求忽略电池优化失败: " + t);
        }
    }

    /**
     * 通过 su 执行单条命令，返回 stdout+stderr；失败返回 null。
     * 使用超时避免 su 弹窗卡死界面线程（调用方应放在 IO 线程）。
     */
    private static String exec(String command) {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec("su");
            DataOutputStream out = new DataOutputStream(process.getOutputStream());
            out.writeBytes(command + "\n");
            out.writeBytes("exit\n");
            out.flush();

            StringBuilder sb = new StringBuilder();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append('\n');
            BufferedReader err = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()));
            while ((line = err.readLine()) != null) sb.append(line).append('\n');

            if (!process.waitFor(8, TimeUnit.SECONDS)) {
                process.destroy();
                return null;
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}

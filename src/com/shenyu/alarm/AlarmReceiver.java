package com.shenyu.alarm;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.util.Log;

import java.util.Calendar;

/**
 * AI 闹钟桥 v2 —— 静默设置闹钟
 * v2: 到点直接 PendingIntent.getForegroundService() 拉起响铃服务（绕过广播启动FGS限制）
 */
public class AlarmReceiver extends BroadcastReceiver {

    private static final String TAG = "ShenYuAlarm";
    private static final String PREFS = "shenyu_alarm";
    private static final String KEY_HOUR = "hour";
    private static final String KEY_MINUTE = "minute";
    private static final String KEY_DAYS = "days";
    private static final String KEY_LABEL = "label";
    private static final int REQ_CODE = 9001;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        if ("com.shenyu.alarm.ACTION_SET".equals(action)) {
            int hour = intent.getIntExtra("hour", -1);
            int minute = intent.getIntExtra("minute", -1);
            String label = intent.getStringExtra("label");
            int[] days = intent.getIntArrayExtra("days");

            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
                Log.e(TAG, "invalid time: " + hour + ":" + minute);
                return;
            }

            SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            sp.edit()
                    .putInt(KEY_HOUR, hour)
                    .putInt(KEY_MINUTE, minute)
                    .putString(KEY_DAYS, days == null ? "" : join(days))
                    .putString(KEY_LABEL, label == null ? "" : label)
                    .apply();

            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, hour);
            cal.set(Calendar.MINUTE, minute);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
                cal.add(Calendar.DAY_OF_MONTH, 1);
            }

            scheduleNext(context, cal.getTimeInMillis(), label);
            Log.i(TAG, "alarm set: " + hour + ":" + minute + " days=" + join(days) + " label=" + label);
        } else if ("com.shenyu.alarm.ACTION_CANCEL".equals(action)) {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(buildServicePending(context, null));
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
            Log.i(TAG, "alarm cancelled");
        } else if ("com.shenyu.alarm.ACTION_RING".equals(action)) {
            // 手动测试入口：尽量起前台服务；被限制时降级为一次性铃声
            String label = intent.getStringExtra("label");
            try {
                Intent svc = new Intent(context, AlarmRingService.class);
                svc.putExtra("label", label);
                context.startForegroundService(svc);
                Log.i(TAG, "alarm RING fired (fgs)");
            } catch (Exception e) {
                Log.w(TAG, "fgs blocked, fallback ring: " + e.getMessage());
                fallbackRing(context, label);
            }
        } else if ("com.shenyu.alarm.ACTION_STOP".equals(action)) {
            Intent svc = new Intent(context, AlarmRingService.class);
            context.stopService(svc);
            Log.i(TAG, "alarm stopped by user/auto");
        }
    }

    private void fallbackRing(Context context, String label) {
        try {
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            Ringtone r = RingtoneManager.getRingtone(context, uri);
            if (r != null) {
                r.play();
                Thread t = new Thread(() -> {
                    try { Thread.sleep(8000); } catch (InterruptedException ignored) {}
                    if (r.isPlaying()) r.stop();
                });
                t.setDaemon(true);
                t.start();
            }
        } catch (Exception e) {
            Log.e(TAG, "fallback ring error: " + e.getMessage());
        }
    }

    private void scheduleRepeatIfNeeded(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String daysStr = sp.getString(KEY_DAYS, "");
        if (daysStr == null || daysStr.isEmpty()) return; // 单次闹钟不重复

        int[] days = parseDays(daysStr);
        int hour = sp.getInt(KEY_HOUR, -1);
        int minute = sp.getInt(KEY_MINUTE, -1);
        if (hour < 0 || minute < 0) return;

        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, hour);
        cal.set(Calendar.MINUTE, minute);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        cal.add(Calendar.DAY_OF_MONTH, 1);

        for (int i = 0; i < 8; i++) {
            Calendar c = (Calendar) cal.clone();
            c.add(Calendar.DAY_OF_MONTH, i);
            int dow = c.get(Calendar.DAY_OF_WEEK);
            if (contains(days, dow) && c.getTimeInMillis() > System.currentTimeMillis()) {
                scheduleNext(context, c.getTimeInMillis(), sp.getString(KEY_LABEL, ""));
                break;
            }
        }
        Log.i(TAG, "next repeat scheduled");
    }

    private void scheduleNext(Context context, long triggerAt, String label) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = buildServicePending(context, label);
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        } else {
            am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        }
    }

    private PendingIntent buildServicePending(Context context, String label) {
        Intent it = new Intent(context, AlarmRingService.class);
        it.putExtra("label", label);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            return PendingIntent.getForegroundService(context, REQ_CODE, it,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }
        return PendingIntent.getService(context, REQ_CODE, it,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static String join(int[] arr) {
        if (arr == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(arr[i]);
        }
        return sb.toString();
    }

    private static int[] parseDays(String s) {
        String[] parts = s.split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Integer.parseInt(parts[i].trim());
        }
        return out;
    }

    private static boolean contains(int[] arr, int v) {
        for (int x : arr) if (x == v) return true;
        return false;
    }
}
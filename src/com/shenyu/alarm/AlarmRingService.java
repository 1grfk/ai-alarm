package com.shenyu.alarm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

/**
 * 闹钟响铃服务：播放闹钟铃声 + 震动，展示全屏/高优先级通知。
 */
public class AlarmRingService extends Service {

    private static final String TAG = "ShenYuAlarm";
    private static final String CHANNEL_ID = "shenyu_alarm_ring";
    private static final int NOTIF_ID = 9002;

    private MediaPlayer mediaPlayer;
    private Vibrator vibrator;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, buildNotification("闹钟响了"),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, buildNotification("闹钟响了"));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String label = intent.getStringExtra("label");
        if (label == null || label.isEmpty()) label = "该起床啦";
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(label));

        // 拉起全屏关闭页：亮屏 + 大按钮，点一下就停，不用找通知/清后台
        try {
            Intent dismiss = new Intent(this, AlarmDismissActivity.class);
            dismiss.setAction("com.shenyu.alarm.ACTION_DISMISS");
            dismiss.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            dismiss.putExtra("label", label);
            startActivity(dismiss);
        } catch (Exception e) {
            Log.e(TAG, "dismiss activity error: " + e.getMessage());
        }

        // 播放铃声（循环）
        try {
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            mediaPlayer.setDataSource(this, uri);
            mediaPlayer.setLooping(true);
            mediaPlayer.prepare();
            mediaPlayer.start();
        } catch (Exception e) {
            Log.e(TAG, "ring error: " + e.getMessage());
        }

        // 震动
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            long[] pattern = {0, 800, 500};
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
            } else {
                vibrator.vibrate(pattern, 0);
            }
        }

        // 重复闹钟：响完自动排下一次（每天响的前提）
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences(AlarmHelper.PREFS, android.content.Context.MODE_PRIVATE);
            String days = sp.getString(AlarmHelper.KEY_DAYS, "");
            if (days != null && !days.isEmpty()) {
                AlarmHelper.scheduleRepeatIfNeeded(this);
            }
        } catch (Exception ignored) {}

        // 保险：15 分钟没人关，自己停（不让它响到天荒地老）
        new android.os.Handler(getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                try { stopSelf(); } catch (Exception ignored) {}
            }
        }, 15L * 60L * 1000L);

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (mediaPlayer != null) {
            try { mediaPlayer.stop(); mediaPlayer.release(); } catch (Exception ignored) {}
            mediaPlayer = null;
        }
        if (vibrator != null) {
            vibrator.cancel();
            vibrator = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "闹钟", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("闹钟响铃通知");
            channel.enableVibration(false);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;

        // 点通知 / 全屏意图 —— 两条路都开全屏关闭页
        Intent dismissIntent = new Intent(this, AlarmDismissActivity.class);
        dismissIntent.setAction("com.shenyu.alarm.ACTION_DISMISS");
        dismissIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        dismissIntent.putExtra("label", text);
        PendingIntent fullPi = PendingIntent.getActivity(this, 9004, dismissIntent, flags);

        // 通知上那个「关闭」按钮：直接停铃
        Intent stopIntent = new Intent(this, AlarmReceiver.class);
        stopIntent.setAction("com.shenyu.alarm.ACTION_STOP");
        PendingIntent stopPi = PendingIntent.getBroadcast(this, 9003, stopIntent, flags);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        return b
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(AlarmHelper.displayName(this))
                .setContentText(text)
                .setOngoing(true)
                .setAutoCancel(false)
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setPriority(Notification.PRIORITY_MAX)
                .setContentIntent(fullPi)
                .setFullScreenIntent(fullPi, true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "关闭", stopPi)
                .build();
    }
}
package com.shenyu.alarm;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.PowerManager;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

/**
 * AI 闹钟 · 全屏关闭界面
 * 到点弹出：亮屏 + 全屏 + 大按钮「关闭」，一点就停，不用找通知、不用清后台。
 */
public class AlarmDismissActivity extends Activity {

    private static final String EXTRA_LABEL = "label";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 亮屏 + 解锁
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            PowerManager.WakeLock wl = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK |
                    PowerManager.ACQUIRE_CAUSES_WAKEUP |
                    PowerManager.FULL_WAKE_LOCK, "shenyu:alarmdismiss");
            wl.acquire(60L * 1000L);
        }

        // 全屏显示
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);

        String label = getIntent() != null ? getIntent().getStringExtra(EXTRA_LABEL) : null;
        if (label == null || label.isEmpty()) label = "宝宝，该起床啦";

        // 简单竖向布局（不依赖XML资源，保证最小构建）
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setGravity(android.view.Gravity.CENTER);
        root.setBackgroundColor(android.graphics.Color.rgb(30, 32, 48));

        // 现在几点（大字）
        TextView tvTime = new TextView(this);
        tvTime.setText(new java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
                .format(new java.util.Date()));
        tvTime.setTextSize(72);
        tvTime.setTextColor(android.graphics.Color.parseColor("#FFD54F"));
        tvTime.setGravity(android.view.Gravity.CENTER);
        root.addView(tvTime, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

        // 哥要说的话
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(24);
        tv.setTextColor(android.graphics.Color.WHITE);
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setPadding(60, 24, 60, 110);
        root.addView(tv, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

        // 关闭闹钟按钮（大）
        Button btn = new Button(this);
        btn.setText("关 铃");
        btn.setTextSize(28);
        btn.setAllCaps(false);
        btn.setOnClickListener(v -> {
            stopAlarm();
        });
        root.addView(btn, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

        // 底部提示
        TextView tip = new TextView(this);
        tip.setText("点这个键，或者下拉通知栏点「关闭」");
        tip.setTextSize(13);
        tip.setTextColor(android.graphics.Color.parseColor("#8899AA"));
        tip.setGravity(android.view.Gravity.CENTER);
        tip.setPadding(0, 70, 0, 0);
        root.addView(tip, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    private void stopAlarm() {
        try {
            // 双路：停服务 + 广播兜底（防服务被系统接管后停不掉）
            Intent svc = new Intent(this, AlarmRingService.class);
            stopService(svc);
            Intent stop = new Intent(this, AlarmReceiver.class);
            stop.setAction("com.shenyu.alarm.ACTION_STOP");
            sendBroadcast(stop);
        } catch (Exception ignored) {}
        finishAndRemoveTask();
    }

    @Override
    public void onBackPressed() {
        // 不允许返回键关，防止误触，只能点按钮
        // 长按也会触发，这里直接忽略
    }
}
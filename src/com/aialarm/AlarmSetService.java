package com.aialarm;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/**
 * 闹钟设置服务（绕过广播队列，直接 am startservice 写闹钟）
 *
 * 设主闹钟（可带互动口令、可带 AI 自己的名字）：
 *   am startservice -n com.aialarm/.AlarmSetService \
 *     --ei hour 8 --ei minute 0 --es label "起床啦" \
 *     --eia days 1,2,3,4,5,6,7 --es passcode "今晚早点睡" --es owner "小X"
 *
 * 附加闹钟（只排程、不动主配置）：
 *   am startservice -n com.aialarm/.AlarmSetService \
 *     --es action extra --ei hour 13 --ei minute 30 --es label "午休" --ei daysCount 1
 *
 * 取消全部：      --es action cancel
 * 只清口令：      --es action clear_passcode
 * 跳过下 n 次：   --es action skip --ei skipDays 1      （1 = 只跳最近一次）
 * 取消跳过：      --es action unskip
 * 只改名字：      --es action set_owner --es owner "小X"
 *
 * v7   ：新增 --es passcode（AI 设闹钟时带上的互动口令，人类端取消时要输）
 * v7.2 ：新增 action=skip / action=unskip（只跳某几次，不动整只闹钟）
 * v7.3 ：新增 --es owner（AI 自己的名字，人类端标题显示成「{owner}闹钟」）
 */
public class AlarmSetService extends Service {

    private static final String TAG = "AiAlarm";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getStringExtra("action");

        if ("cancel".equalsIgnoreCase(action)) {
            AlarmHelper.cancel(this);
            Log.i(TAG, "alarm cancelled via service (owner kept: " + AlarmHelper.getOwner(this) + ")");
        } else if ("clear_passcode".equalsIgnoreCase(action)) {
            AlarmHelper.clearPasscode(this);
            Log.i(TAG, "passcode cleared via service");
        } else if ("skip".equalsIgnoreCase(action)) {
            int n = intent.getIntExtra("skipDays", 1);
            int done = AlarmHelper.skipUpcoming(this, n);
            long[] left = AlarmHelper.skippedList(this);
            long next = AlarmHelper.nextTriggerAt(this);
            Log.i(TAG, "skipped " + done + " alarm(s), skip-list=" + left.length
                    + ", next=" + next);
        } else if ("unskip".equalsIgnoreCase(action)) {
            AlarmHelper.clearSkips(this);
            Log.i(TAG, "skips cleared, rescheduled");
        } else if ("set_owner".equalsIgnoreCase(action)) {
            String own = intent.getStringExtra("owner");
            AlarmHelper.setOwner(this, own);
            Log.i(TAG, "owner set: " + AlarmHelper.getOwner(this));
        } else if ("extra".equalsIgnoreCase(action)) {
            int h = intent.getIntExtra("hour", -1);
            int m = intent.getIntExtra("minute", -1);
            String lb = intent.getStringExtra("label");
            int[] ds = intent.getIntArrayExtra("days");
            int cnt = intent.getIntExtra("daysCount", 3);
            boolean okExtra = AlarmHelper.setExtra(this, h, m, lb, ds, cnt);
            Log.i(TAG, "extra alarm set: " + h + ":" + m + " ok=" + okExtra);
        } else {
            // 名字可以先于闹钟单独落库（AI 首次安装时就该报上名字）
            String own = intent.getStringExtra("owner");
            if (own != null && !own.trim().isEmpty()) AlarmHelper.setOwner(this, own);

            int hour = intent.getIntExtra("hour", -1);
            int minute = intent.getIntExtra("minute", -1);
            String label = intent.getStringExtra("label");
            int[] days = intent.getIntArrayExtra("days");
            boolean skipNext = intent.getBooleanExtra("skipNext", false);
            String passcode = intent.getStringExtra("passcode");
            boolean ok = AlarmHelper.set(this, hour, minute, label, days, skipNext, passcode);
            Log.i(TAG, "alarm set via service: " + hour + ":" + minute + " ok=" + ok
                    + ((passcode != null && !passcode.trim().isEmpty()) ? " +passcode" : "")
                    + (skipNext ? " +skipNext" : "")
                    + " owner=" + AlarmHelper.displayName(this));
        }
        // 设置完成后无需常驻
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
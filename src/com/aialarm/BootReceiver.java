package com.aialarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机 / 应用被替换后自动重排闹钟（v7 新增）
 *
 * 背景：AlarmManager 里的排程重启后会全部丢失，App 自己不会醒。
 * 收到 BOOT_COMPLETED / MY_PACKAGE_REPLACED，就用上次保存的配置重排 60 天。
 * 幂等：相同 requestCode 的 PendingIntent 会被覆盖，不会重复堆积。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "AiAlarm";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        AlarmHelper.rescheduleFromPrefs(context);
        Log.i(TAG, "rescheduled after " + action);
    }
}

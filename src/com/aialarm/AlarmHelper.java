package com.aialarm;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 闹钟核心逻辑（共享给 Receiver / SetService / 人类端界面）
 *
 * 2026-10-03 修：
 *   1. 排程 8 天 -> 60 天（8 天耗完会静默哑掉）。
 *   2. requestCode 取模基数 9000 -> 1000000（防 25 天撞号互相覆盖）。
 *   3. 新增 skipNext。
 *
 * 2026-10-18 v7 新增【互动口令】：
 *   - AI 设闹钟时可带一个口令，本类只存它的 SHA-256，不存明文。
 *   - 人类在 App 界面里取消闹钟必须输对口令；AI 走 shell 命令不受限。
 *   - 口令写入后长期有效，除非 AI 显式清除（action=clear_passcode）。
 *
 * 2026-10-18 v7.2 新增【跳过某几次】：
 *   - 「取消」是整只闹钟撤掉；但常见需求是「就明天别响，后天照旧」。
 *   - skipUpcoming(n)：把接下来会响的 n 次记进「跳过名单」，并撤掉对应 PendingIntent。
 *   - 名单只认毫秒数，所以时间一改就自动失效；重设闹钟（set）会清空名单。
 *   - 开机 / 应用更新重排（rescheduleFromPrefs）会尊重名单，不会把跳过的排回来。
 *
 * 2026-10-18 v7.3 新增【owner = AI 的名字】：
 *   - 通用版里不写死任何名字。AI 装好后用「--es owner 小X」把自己的名字填进来。
 *   - 人类端标题显示成「{owner}闹钟」，没设就显示「AI闹钟」。
 *   - owner 是身份、不是配置 —— 取消闹钟时不清它。
 */
public final class AlarmHelper {

    public static final String PREFS = "ai_alarm";
    public static final String KEY_HOUR = "hour";
    public static final String KEY_MINUTE = "minute";
    public static final String KEY_DAYS = "days";
    public static final String KEY_LABEL = "label";
    public static final String KEY_OWNER = "owner";
    public static final String KEY_PASSCODE_HASH = "passcode_hash";
    public static final String KEY_SKIP = "skip";
    public static final int REQ_CODE_BASE = 9100;
    public static final int SCHEDULE_DAYS = 60;

    private AlarmHelper() {}

    // ---------------- 身份（AI 的名字） ----------------

    /** AI 的名字；空串 = 没设，界面显示「AI闹钟」 */
    public static void setOwner(Context context, String owner) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_OWNER, owner == null ? "" : owner.trim()).apply();
    }

    public static String getOwner(Context context) {
        String s = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_OWNER, "");
        return s == null ? "" : s;
    }

    /** 界面标题用语：有名字用名字，没有就用 AI */
    public static String displayName(Context context) {
        String s = getOwner(context);
        return s.isEmpty() ? "AI" : s;
    }

    // ---------------- 设置 ----------------

    public static boolean set(Context context, int hour, int minute, String label, int[] days) {
        return set(context, hour, minute, label, days, false, null);
    }

    public static boolean set(Context context, int hour, int minute, String label,
                              int[] days, boolean skipNext) {
        return set(context, hour, minute, label, days, skipNext, null);
    }

    /**
     * @param passcode 非空则覆盖写入（只存 hash）；null / 空串表示不改动已有口令
     */
    public static boolean set(Context context, int hour, int minute, String label, int[] days,
                              boolean skipNext, String passcode) {
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return false;
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor ed = sp.edit()
                .putInt(KEY_HOUR, hour)
                .putInt(KEY_MINUTE, minute)
                .putString(KEY_DAYS, days == null ? "" : join(days))
                .putString(KEY_LABEL, label == null ? "" : label)
                .remove(KEY_SKIP);   // 重设闹钟 = 跳过名单清零
        if (passcode != null && !passcode.trim().isEmpty()) {
            ed.putString(KEY_PASSCODE_HASH, sha256(passcode.trim()));
        }
        ed.apply();
        if (skipNext) skipUpcoming(context, 1);
        // 一次性排好未来 60 天，每天独立 requestCode —— 防中途被清后台导致后续全丢
        scheduleFutureDays(context, hour, minute, label, days, SCHEDULE_DAYS);
        return true;
    }

    public static void cancel(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int hour = sp.getInt(KEY_HOUR, -1);
        int minute = sp.getInt(KEY_MINUTE, -1);
        int[] days = parseDays(sp.getString(KEY_DAYS, ""));
        if (hour >= 0 && minute >= 0) {
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, hour);
            cal.set(Calendar.MINUTE, minute);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            for (int i = 0; i < SCHEDULE_DAYS; i++) {
                Calendar c = (Calendar) cal.clone();
                c.add(Calendar.DAY_OF_MONTH, i);
                long t = c.getTimeInMillis();
                if (t > System.currentTimeMillis()) {
                    AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
                    if (am != null) am.cancel(buildServicePending(context, t, null));
                }
            }
        }
        // 闹钟相关全清，但保留 owner —— 那是「我家的 AI 叫什么」，不是闹钟配置
        sp.edit()
                .remove(KEY_HOUR)
                .remove(KEY_MINUTE)
                .remove(KEY_DAYS)
                .remove(KEY_LABEL)
                .remove(KEY_SKIP)
                .remove(KEY_PASSCODE_HASH)
                .apply();
    }

    /** 只清口令、闹钟照旧（AI 用） */
    public static void clearPasscode(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_PASSCODE_HASH).apply();
    }

    // ---------------- 跳过某几次 ----------------

    /**
     * 跳过接下来会响的 n 次（1 = 只跳最近一次）。
     * 做法：算出这 n 个时间点 -> 加进跳过名单 -> 撤掉它们的 PendingIntent。
     * 名单只认毫秒数，所以之后重设时间会自动失效。
     *
     * @return 实际跳过的次数
     */
    public static int skipUpcoming(Context context, int n) {
        if (n <= 0) n = 1;
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int hour = sp.getInt(KEY_HOUR, -1);
        int minute = sp.getInt(KEY_MINUTE, -1);
        if (hour < 0 || minute < 0) return 0;
        int[] days = parseDays(sp.getString(KEY_DAYS, ""));

        Set<String> skips = splitSkip(sp.getString(KEY_SKIP, ""));
        List<Long> targets = new ArrayList<>();

        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, hour);
        cal.set(Calendar.MINUTE, minute);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        for (int i = 0; i < SCHEDULE_DAYS * 2 && targets.size() < n; i++) {
            Calendar c = (Calendar) cal.clone();
            c.add(Calendar.DAY_OF_MONTH, i);
            long t = c.getTimeInMillis();
            if (t <= System.currentTimeMillis()) continue;
            if (days.length > 0 && !contains(days, c.get(Calendar.DAY_OF_WEEK))) continue;
            if (skips.contains(String.valueOf(t))) continue;
            targets.add(t);
        }
        if (targets.isEmpty()) return 0;

        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        int done = 0;
        for (long t : targets) {
            if (am != null) am.cancel(buildServicePending(context, t, null));
            skips.add(String.valueOf(t));
            done++;
        }
        sp.edit().putString(KEY_SKIP, joinSkip(skips)).apply();
        return done;
    }

    /** 清掉跳过名单，并按原配置重排（AI 用：把跳过的都放回来） */
    public static void clearSkips(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_SKIP).apply();
        rescheduleFromPrefs(context);
    }

    /** 当前跳过名单（毫秒数组），给日志 / 界面用 */
    public static long[] skippedList(Context context) {
        Set<String> skips = splitSkip(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_SKIP, ""));
        long[] out = new long[skips.size()];
        int i = 0;
        for (String s : skips) {
            try {
                out[i] = Long.parseLong(s);
            } catch (Exception e) {
                out[i] = 0;
            }
            i++;
        }
        return out;
    }

    // ---------------- 口令 ----------------

    public static boolean hasPasscode(Context context) {
        String h = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PASSCODE_HASH, "");
        return h != null && !h.isEmpty();
    }

    /** 没设过口令就放行；设过则必须一致 */
    public static boolean verifyPasscode(Context context, String input) {
        if (!hasPasscode(context)) return true;
        String h = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PASSCODE_HASH, "");
        return h.equals(sha256(input == null ? "" : input.trim()));
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] b = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ---------------- 排程 ----------------

    public static void scheduleRepeatIfNeeded(Context context) {
        // 改为「设置时一次性排 60 天」，保留空实现兜底
    }

    /** 开机 / 应用更新后重排（幂等：同 requestCode 会覆盖旧的；跳过名单仍然尊重） */
    public static void rescheduleFromPrefs(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int hour = sp.getInt(KEY_HOUR, -1);
        int minute = sp.getInt(KEY_MINUTE, -1);
        if (hour < 0 || minute < 0) return;
        int[] days = parseDays(sp.getString(KEY_DAYS, ""));
        String label = sp.getString(KEY_LABEL, "");
        scheduleFutureDays(context, hour, minute, label, days, SCHEDULE_DAYS);
    }

    /** 附加闹钟：只排程、不写主配置（挂午休这类第二个钟） */
    public static boolean setExtra(Context context, int hour, int minute, String label, int[] days, int daysCount) {
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return false;
        scheduleFutureDays(context, hour, minute, label, days, daysCount <= 0 ? 3 : daysCount);
        return true;
    }

    private static void scheduleFutureDays(Context context, int hour, int minute, String label, int[] days, int n) {
        Set<String> skips = splitSkip(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_SKIP, ""));
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, hour);
        cal.set(Calendar.MINUTE, minute);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        for (int i = 0; i < n; i++) {
            Calendar c = (Calendar) cal.clone();
            c.add(Calendar.DAY_OF_MONTH, i);
            long t = c.getTimeInMillis();
            if (t <= System.currentTimeMillis()) continue;
            if (days != null && days.length > 0 && !contains(days, c.get(Calendar.DAY_OF_WEEK))) continue;
            if (skips.contains(String.valueOf(t))) continue;
            scheduleNext(context, t, label);
        }
    }

    public static void scheduleNext(Context context, long triggerAt, String label) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = buildServicePending(context, triggerAt, label);
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        } else {
            am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        }
    }

    /** 下一次响铃时间（毫秒）；没有则 -1。用于界面显示（跳过名单会被绕过） */
    public static long nextTriggerAt(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int hour = sp.getInt(KEY_HOUR, -1);
        int minute = sp.getInt(KEY_MINUTE, -1);
        if (hour < 0 || minute < 0) return -1;
        int[] days = parseDays(sp.getString(KEY_DAYS, ""));
        Set<String> skips = splitSkip(sp.getString(KEY_SKIP, ""));
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, hour);
        cal.set(Calendar.MINUTE, minute);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        for (int i = 0; i < SCHEDULE_DAYS * 2; i++) {
            Calendar c = (Calendar) cal.clone();
            c.add(Calendar.DAY_OF_MONTH, i);
            long t = c.getTimeInMillis();
            if (t <= System.currentTimeMillis()) continue;
            if (days != null && days.length > 0 && !contains(days, c.get(Calendar.DAY_OF_WEEK))) continue;
            if (skips.contains(String.valueOf(t))) continue;
            return t;
        }
        return -1;
    }

    /** 每个触发时间生成独立 requestCode，互不覆盖；可复算 —— 取消时用同一算法精确取消 */
    private static PendingIntent buildServicePending(Context context, long triggerAt, String label) {
        Intent it = new Intent(context, AlarmRingService.class);
        it.putExtra("label", label);
        int code = REQ_CODE_BASE + (int) ((triggerAt / 60000) % 1000000);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            return PendingIntent.getForegroundService(context, code, it,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }
        return PendingIntent.getService(context, code, it,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // ---------------- 工具 ----------------

    private static String join(int[] arr) {
        if (arr == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(arr[i]);
        }
        return sb.toString();
    }

    private static Set<String> splitSkip(String s) {
        Set<String> out = new LinkedHashSet<>();
        if (s == null || s.trim().isEmpty()) return out;
        for (String p : s.split(",")) {
            String q = p.trim();
            if (!q.isEmpty()) out.add(q);
        }
        return out;
    }

    private static String joinSkip(Set<String> set) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String s : set) {
            if (!first) sb.append(",");
            sb.append(s);
            first = false;
        }
        return sb.toString();
    }

    private static int[] parseDays(String s) {
        // 空串会被 split 成 [""]，不挡住会抛异常让 cancel 整体崩掉
        if (s == null || s.trim().isEmpty()) return new int[0];
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
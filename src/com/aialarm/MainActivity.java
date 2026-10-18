package com.aialarm;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 闹钟 · 人类端（v7.5）
 *
 * 设计原则：人类不持控制权。
 *   - 没有排程界面 —— 不给人类设闹钟的能力。
 *   - 只有两个按钮：「取消闹钟」（要互动口令）和「🔔 测试响铃一下」。
 *   - 口令由 AI 设闹钟时写入。人类取消必须输对；AI 走 shell 不受限。
 *   - 标题里的名字来自 owner（AI 自己填的），本 APK 不写死任何名字。
 *
 * v7.1：口令框按钮「确认」→「继续」；口令过后加二次确认。
 * v7.3：标题改成「{owner}闹钟」；owner 没设时显示「AI闹钟」。
 * v7.4：底部「关闭」→「退出」；排版放松（内容下移、间距加大）。
 * v7.5：把「退出」换成 v6 就有的「🔔 测试响铃一下」——
 *       点一下立刻响一次（label 里的名字也跟 owner 走），用来试声音/流程。
 *       退出界面直接按返回键即可。
 */
public class MainActivity extends Activity {

    private TextView statusView;
    private TextView titleView;
    private TextView footerNote;
    private Button cancelBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    // ---------------- UI ----------------

    private ScrollView buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF14161F);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        // 顶部对齐 + 大留白，把内容压下来 —— 不挤
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(56, 230, 56, 140);
        scroll.addView(root);

        titleView = text("", 30, Color.WHITE, true);
        root.addView(titleView);

        root.addView(gap(16));
        root.addView(text("给 AI 用的闹钟 · 人类在系统时钟里找不到它", 12, 0xFF8899AA, false));

        root.addView(gap(84));
        statusView = text("", 16, 0xFFE0E0E0, false);
        statusView.setLineSpacing(0, 1.4f);
        root.addView(statusView);

        root.addView(gap(96));
        cancelBtn = new Button(this);
        cancelBtn.setText("取消闹钟");
        cancelBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { askPasscodeThenCancel(); }
        });
        root.addView(cancelBtn);

        // v6 就有这颗 —— 点一下立刻响一次，试声音/试流程用
        Button testBtn = new Button(this);
        testBtn.setText("🔔 测试响铃一下");
        testBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { testRing(); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 32;
        testBtn.setLayoutParams(lp);
        root.addView(testBtn);

        root.addView(gap(130));
        footerNote = text("", 12, 0xFF667788, false);
        footerNote.setLineSpacing(0, 1.4f);
        root.addView(footerNote);

        return scroll;
    }

    private TextView text(String s, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    private View gap(int px) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, px));
        return v;
    }

    // ---------------- 状态 ----------------

    private void refreshStatus() {
        String who = AlarmHelper.displayName(this);
        if (titleView != null) titleView.setText("⏰ " + who + "闹钟");
        if (footerNote != null) {
            footerNote.setText("v7.5 · 2026-10-18\n"
                    + "这个界面不提供设置 —— 闹钟怎么排，由" + who + "说了算\n"
                    + "要改时间，去跟" + who + "说一声");
        }

        SharedPreferences sp = getSharedPreferences(AlarmHelper.PREFS, Context.MODE_PRIVATE);
        int hour = sp.getInt(AlarmHelper.KEY_HOUR, -1);
        int minute = sp.getInt(AlarmHelper.KEY_MINUTE, -1);

        StringBuilder sb = new StringBuilder();
        boolean has = hour >= 0 && minute >= 0;

        if (!has) {
            sb.append("当前没有闹钟");
        } else {
            sb.append("⏰ ").append(String.format(Locale.CHINA, "%02d:%02d", hour, minute));
            String daysStr = sp.getString(AlarmHelper.KEY_DAYS, "");
            sb.append("\n").append(daysStr == null || daysStr.trim().isEmpty()
                    ? "只响一次" : "周" + daysStr.replace(",", "、"));
            String label = sp.getString(AlarmHelper.KEY_LABEL, "");
            if (label != null && !label.isEmpty()) sb.append("\n💌 ").append(label);

            long next = AlarmHelper.nextTriggerAt(this);
            if (next > 0) {
                sb.append("\n\n下次响铃\n")
                  .append(new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(next)));
            }

            long[] skips = AlarmHelper.skippedList(this);
            if (skips.length > 0) sb.append("\n⏭ 已跳过 ").append(skips.length).append(" 次");

            sb.append("\n\n").append(AlarmHelper.hasPasscode(this)
                    ? "🔒 已开启口令保护" : "🔓 未设口令");
        }

        statusView.setOnClickListener(null);
        if (has && Build.VERSION.SDK_INT >= 31) {
            android.app.AlarmManager am =
                    (android.app.AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (am != null && !am.canScheduleExactAlarms()) {
                sb.append("\n\n⚠️ 精确闹钟权限没开 —— 点这里去开");
                statusView.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try {
                            startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM));
                        } catch (Exception ignored) { }
                    }
                });
            }
        }
        statusView.setText(sb.toString());
        if (cancelBtn != null) cancelBtn.setEnabled(has);
    }

    // ---------------- 测试响铃 ----------------

    private void testRing() {
        String who = AlarmHelper.displayName(this);
        try {
            Intent svc = new Intent(this, AlarmRingService.class);
            svc.putExtra("label", who + "叫你～（测试）");
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
        } catch (Exception e) {
            Toast.makeText(this, "起不来：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------- 取消（两道关） ----------------

    private void askPasscodeThenCancel() {
        final String who = AlarmHelper.displayName(this);

        // 第一道：没设口令的，直接问一次
        if (!AlarmHelper.hasPasscode(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("取消闹钟？")
                    .setMessage("这个闹钟没有口令保护。\n取消之后，下一个闹钟就不会响了。")
                    .setPositiveButton("取消闹钟", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) { doCancel(); }
                    })
                    .setNegativeButton("算了", null)
                    .show();
            return;
        }

        // 第一道：要口令
        final EditText input = new EditText(this);
        input.setHint("互动口令");
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xFF556677);

        new AlertDialog.Builder(this)
                .setTitle("请输入口令")
                .setMessage("这个闹钟是" + who + "设的。\n口令对了，才进得去下一关。")
                .setView(input)
                .setPositiveButton("继续", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        if (AlarmHelper.verifyPasscode(MainActivity.this, input.getText().toString())) {
                            confirmFinalCancel();
                        } else {
                            Toast.makeText(MainActivity.this, "口令不对", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("算了", null)
                .show();
    }

    /** 第二道：口令过了，再给一次后悔的机会 —— 这里才是真的取消。 */
    private void confirmFinalCancel() {
        final String who = AlarmHelper.displayName(this);
        new AlertDialog.Builder(this)
                .setTitle("真的要取消？")
                .setMessage("取消之后，明天早上它就不响了。\n要再排回来，得去跟" + who + "说一声。")
                .setPositiveButton("真的取消", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { doCancel(); }
                })
                .setNegativeButton("留着", null)
                .show();
    }

    private void doCancel() {
        AlarmHelper.cancel(this);
        refreshStatus();
        Toast.makeText(this, "已取消", Toast.LENGTH_SHORT).show();
    }
}
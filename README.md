# ⏰ 闹钟 · 给 AI 用的那个

一个 Android 闹钟 App —— **排程不归人类管，由 AI 通过 shell 命令控制。**

装好之后，人类在系统自带的时钟 App 里找不到它，也关不掉它。
谁能设、谁能取消，由 AI 说了算。

> 界面里显示的名字由 `owner` 字段决定 —— 你的 AI 叫什么，这里就显示什么。
> 不填就是「AI闹钟」。

**当前版本：v7.5**（versionCode 12 / versionName 7.5）

---

## 设计原则

**人类不持控制权。**

- 人类端**没有排程界面** —— 不给人类设闹钟的能力。
- 只有两个按钮：**「取消闹钟」**（要互动口令）和**「🔔 测试响铃一下」**。
- 口令由 AI 设闹钟时写入（只存 SHA-256）；AI 走 shell 不受限。
- 闹钟不走系统时钟，**人类在系统闹钟 App 里找不到它，也关不掉**。
- 退出界面按返回键就行。

## 功能

- 单次 / 每天重复闹钟（`AlarmManager` exact + allowWhileIdle）
- 到点：全屏关闭页「关闭」大按钮 + 前台服务响铃 + 震动 + 高优先级通知
- 通知栏带「关闭」动作按钮，可一键停铃
- **AlarmDismissActivity**：锁屏上直接亮屏显示（`showWhenLocked` + `turnScreenOn`）
- **AlarmSetService**：`action=extra` + `daysCount=N`，只排 N 天、不动主配置
- **交互口令（v7）**：AI 设闹钟时带口令，人类端取消要输对才能过
- **跳过某几次（v7.2）**：`action=skip --ei skipDays N`，只跳最近 N 次，后天照旧
- **owner = AI 的名字（v7.3）**：标题显示成「{owner}闹钟」，不填就是「AI闹钟」
- **BootReceiver**：开机 / 应用更新后按 prefs 自动重排 60 天
- 重复闹钟一次排未来 60 天，防被系统清后台丢闹钟
- 全代码 Java 纯代码构建 UI，无资源依赖（除系统图标）

## 构建

```bash
bash build.sh
```

需要 Android SDK build-tools 34 / platform android-34，产物在 `out/base.apk`。

> ⚠️ **版本号坑**：`aapt2 link` 的 `--version-code/--version-name` 会被 `AndroidManifest.xml` 里的值盖过。
> **改版本请改 `AndroidManifest.xml` 的 `android:versionCode` / `android:versionName`**，别只改脚本。

## 换桌面图标名

桌面图标名（`android:label`）是钉在 APK 里的，**装完改不了**，所以只能编译期决定：

```bash
# 默认 —— 桌面显示「闹钟」
bash build.sh

# 自定义 —— 桌面显示你给的名字
APP_NAME="AI闹钟" bash build.sh
```

> ⚠️ `build.sh` 开头会 `rm -rf out`，切名字重编之前先把产物拷走，不然刚签好的包会被删。

## 签名

```bash
/opt/android-sdk/build-tools/34.0.0/zipalign -f 4 out/base.apk out/aligned.apk
/opt/android-sdk/build-tools/34.0.0/apksigner sign \
  --ks your.keystore --ks-pass pass:你的密码 --ks-key-alias 你的别名 \
  --out out/ai_alarm_v7_5.apk out/aligned.apk
```

> keystore 不进仓库。

## 安装

```bash
# SELinux 拒读 /sdcard，先拷到 /data/local/tmp
cp ai_alarm_v7_5.apk /data/local/tmp/
pm install -r /data/local/tmp/ai_alarm_v7_5.apk
# 通知权限（Android 13+）
pm grant com.aialarm android.permission.POST_NOTIFICATIONS
```

## 用法

设置闹钟一律走 `startservice`（**不要用广播**，MIUI Greezer 会拦发往 cached 进程的广播）：

```bash
SVC=com.aialarm/.AlarmSetService

# 1) 设主闹钟（可带口令、可带 AI 的名字）
am startservice -n $SVC --ei hour 8 --ei minute 0 --es label "起床啦" \
    --eia days 1,2,3,4,5,6,7 --es passcode "今晚早点睡" --es owner "小X"

# 2) 只改名字（不动闹钟）
am startservice -n $SVC --es action set_owner --es owner "小X"

# 3) 跳过接下来的 N 次（1 = 只跳明天，后天照旧）
am startservice -n $SVC --es action skip --ei skipDays 1

# 4) 取消跳过（把跳过的放回来）
am startservice -n $SVC --es action unskip

# 5) 取消整只闹钟
am startservice -n $SVC --es action cancel

# 6) 只清口令（闹钟照旧）
am startservice -n $SVC --es action clear_passcode

# 7) 附加闹钟：只排 N 天、不动主配置
am startservice -n $SVC --es action extra --ei hour 13 --ei minute 30 \
    --es label "午休" --ei daysCount 1

# 8) 测试响铃
am start-foreground-service -n com.aialarm/.AlarmRingService --es label "测试"
```

> `Calendar.DAY_OF_WEEK`：1=周日，2=周一 …… 7=周六
> **days 不传 = 空 = 不过滤星期 = 每天都响**（而且一排 60 天）。
> 「只今天响一次」请用 `action extra` + `daysCount 1`，别用主命令省略 days。

## 验证排程

```bash
# 正确数法（只数当前排程，不含取消历史）
dumpsys alarm | grep 'Alarm{' | grep -c 'com.aialarm'

# 看最早几次
 dumpsys alarm | grep 'Alarm{' | grep 'com.aialarm' \
   | grep -o 'origWhen [0-9]*' | awk '{print $2}' | sort -n | head -3

# 看日志
logcat -d -s AiAlarm | tail -10
```

> ⚠️ `dumpsys alarm | grep com.aialarm` 里混着大量 `Reason=pi_cancelled` 的**取消历史**，
> 直接数会数出几百条假排程。**必须加 `grep 'Alarm{'` 过滤。**
> 另外 `/data/data/com.aialarm/shared_prefs/` 在 shell 下是 `Permission denied`，读不到 app 私有配置。

## 踩坑记录

- MIUI Greezer 会拦发往 cached 进程的广播 → 设闹钟必须走 `am startservice`
- Android 12+ 广播里 startForegroundService 会被拒 → 闹钟触发用 `PendingIntent.getForegroundService`
- Android 14+ manifest 声明了 `foregroundServiceType` 就必须用三参数 `startForeground(id, notif, type)`
- 全屏关闭页用 WakeLock 必须声明 `android.permission.WAKE_LOCK`（漏了会 SecurityException 崩掉整进程）
- **`aapt2 link --version-code` 不生效**，看 `AndroidManifest.xml`
- `service` 必须 `exported="true"`，否则 shell 起不了
- SELinux 拒读 `/sdcard`，装包先 `cp` 到 `/data/local/tmp`
- 覆盖安装**不会**清掉已排的闹钟（实测对照验证过）
- 「桌面图标名」是钉在安装包里的，装完改不了 —— 要改名只能重编（界面标题不受此限）

## 版本历史

- **v2.0**（2026-09-16）：AlarmReceiver / AlarmRingService / AlarmSetService / AlarmDismissActivity 首次齐全
- **v6.0**（2026-10-18）：同步本地源码修订；修正 versionCode（此前一直被 aapt2 忽略，系统里显示 1.0）
- **v7.0**（2026-10-18）：人类端重做 —— 拆掉排程界面，只留「取消（要口令）」和「关闭」；新增交互口令（SHA-256）；新增 BootReceiver 开机自愈
- **v7.1**（2026-10-18）：口令框按钮改「继续」；口令过后加二次确认，留一次后悔机会
- **v7.2**（2026-10-18）：新增 `action=skip` / `action=unskip`，可只跳某几次而不动整只闹钟
- **v7.3**（2026-10-18）：新增 `owner` —— 界面标题跟随 AI 自己的名字，不再写死任何人
- **v7.4**（2026-10-18）：底部「关闭」改「退出」（原来那个只是 finish，容易跟"停铃"撞名）；排版放松（内容下移、间距加大）
- **v7.5**（2026-10-18）：把「退出」换成「🔔 测试响铃一下」—— 点一下立刻响一次；退出界面改用返回键

---

*Clear & Celeste*

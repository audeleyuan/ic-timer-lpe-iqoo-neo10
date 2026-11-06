package dev.ic.timerlpe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import rikka.shizuku.Shizuku;

/**
 * UI side.  It never touches /data/local/tmp itself: Shizuku gives us a user
 * service that runs as the adb shell (uid 2000 / u:r:shell:s0), and that
 * service relays the binary, runs run_lpe.sh and streams the log back.
 */
public class MainActivity extends Activity {
    private static final int REQ = 1001;
    private static final int REQ_PICK = 2001;
    private static final String TMP = "/data/local/tmp/";
    private static final int CHUNK = 128 * 1024;
    private static final String DEFAULT_CMD =
            "id; cat /proc/self/attr/current; getenforce; echo LPE-PROOF";

    /* The launcher asset bundled in the APK.  The other geometry variants
     * (gate / no-early / simple) were kept only as an experiment ladder while
     * the device geometry was still being pinned down; on 2026-10-27 the
     * device configuration below was the one that completed the full chain
     * (drop -> setresuid(0,0,0)) end to end, so it is the only one shipped.
     * Keep the name in sync with assets/. */
    private static final String SCHEME_ASSET = "run_core.sh";

    /* Root handoff: after the chain reaches uid 0 the payload can hand that
     * root to a KernelSU-family manager with the manager's own ksud
     * (assets/ksu_handoff.sh).  All four ship ksud as libksud.so inside the
     * manager APK and speak the same "late-load" CLI.  BakaSU is the renamed
     * ReSukiSU; auto-detect also accepts the old com.resukisu.resukisu id. */
    private static final String[] KSU_NAMES = {
            "关闭", "自动检测", "KernelSU", "SukiSU Ultra",
            "BakaSU", "ReSukiSU (旧包名)", "SakiSU", "7kimisu" };
    private static final String[] KSU_PKGS = {
            null, "",
            "me.weishu.kernelsu", "com.sukisu.ultra",
            "org.bakasu.bakasu", "com.resukisu.resukisu",
            "com.sakisu.sakisu", "com.sevenk.core" };

    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView status;
    private TextView log;
    private ScrollView scroll;
    private Spinner ksuView;
    private CheckBox safeView;
    private Button connectBtn;
    private Button runBtn;
    private Button parseBtn;
    private Button cleanBtn;

    private IIcService svc;
    private boolean bound;
    private Shizuku.UserServiceArgs args;
    private int logOff;
    private String logPath;
    private boolean polling;

    private final Shizuku.OnBinderReceivedListener onRecv =
            () -> ui.post(this::refresh);
    private final Shizuku.OnBinderDeadListener onDead =
            () -> ui.post(() -> { bound = false; svc = null; refresh(); });
    private final Shizuku.OnRequestPermissionResultListener onPerm =
            (code, res) -> ui.post(() -> {
                append("Shizuku 权限: " +
                       (res == PackageManager.PERMISSION_GRANTED
                        ? "已允许" : "被拒绝(" + res + ")"));
                if (res == PackageManager.PERMISSION_GRANTED)
                    bindService();
                refresh();
            });

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            svc = IIcService.Stub.asInterface(binder);
            bound = true;
            new Thread(() -> {
                String id;
                try {
                    id = svc.identity();
                } catch (Throwable t) {
                    id = "读取身份失败: " + t;
                }
                final String fid = id;
                ui.post(() -> { append("[service] " + fid); refresh(); });
            }, "svc-identity").start();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            svc = null;
            bound = false;
            ui.post(MainActivity.this::refresh);
        }
    };

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!polling || svc == null || logPath == null) {
                polling = false;
                return;
            }
            try {
                int len = svc.length(logPath);
                while (logOff < len) {
                    int want = Math.min(64 * 1024, len - logOff);
                    byte[] b = svc.read(logPath, logOff, want);
                    if (b == null || b.length == 0)
                        break;
                    logOff += b.length;
                    raw(new String(b, StandardCharsets.UTF_8));
                }
            } catch (Throwable ignored) { }
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        int p = (int) (12 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p, p, p, p);

        TextView title = new TextView(this);
        title.setText("IC Timer LPE  (vivo/iQOO 6.1.145)");
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(12);
        status.setPadding(0, p / 2, 0, p / 2);
        root.addView(status);

        TextView warn = new TextView(this);
        warn.setTextSize(12);
        warn.setText("规则：每次完整运行前重启手机；运行后残留的 ic-timer 进程"
                + "不能 kill -9，只能重启清理。接管 KSU 需要先装好对应管理器"
                + "的 APK（管理器自身的内核模块由 late-load 加载）。\n"
                + "已内置偏移（机型 + 版本匹配）：\n"
                + "· PD2506 / V2506（Y500）：16.1.15.0、16.1.13.2\n"
                + "· PD2516 / V2516（Y500 Pro）：16.1.11.0\n"
                + "· PD2307 / PD2329 / V2307（iQOO 12）：16.2.11、16.2.20.3、16.2.20.7、16.2.21.1\n"
                + "· PD2417 / V2417（X100s Pro）：16.2.14.1\n"
                + "· PD2324 / V2324A / V2324HA / V2324H / PD2324J（X100 系列）：16.2.12.0、16.2.13.1\n"
                + "其中 16.2.11 / 16.2.20.x / 16.2.21.1 自带 KSU 交接所需的对象地址；\n"
                + "其余机型跑 KSU 请先点“解析偏移”选对应 boot.img。\n"
                + "其他 build：点“解析偏移”用系统文件选择器选 boot.img。\n"
                + "本软件免费开源，作者酷安@散花神木");
        root.addView(warn);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        connectBtn = new Button(this);
        connectBtn.setText("连接 Shizuku");
        connectBtn.setOnClickListener(v -> connect());
        runBtn = new Button(this);
        runBtn.setText("推送并运行");
        runBtn.setOnClickListener(v -> run());
        Button diagBtn = new Button(this);
        diagBtn.setText("诊断");
        diagBtn.setOnClickListener(v -> diag());
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(connectBtn, rp);
        row.addView(runBtn, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(diagBtn, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row);

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        parseBtn = new Button(this);
        parseBtn.setText("解析偏移");
        parseBtn.setOnClickListener(v -> parseOffsets());
        cleanBtn = new Button(this);
        cleanBtn.setText("清理 KSU");
        cleanBtn.setOnClickListener(v -> cleanup());
        tools.addView(parseBtn, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tools.addView(cleanBtn, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(tools);

        LinearLayout ksuRow = new LinearLayout(this);
        ksuRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView ksuLabel = new TextView(this);
        ksuLabel.setTextSize(12);
        ksuLabel.setText("Root 交接(KSU late-load):");
        ksuLabel.setPadding(0, p / 2, p / 2, p / 2);
        ksuView = new Spinner(this);
        ArrayAdapter<String> ksuAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, KSU_NAMES);
        ksuAdapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        ksuView.setAdapter(ksuAdapter);
        ksuView.setSelection(1);
        ksuRow.addView(ksuLabel);
        ksuRow.addView(ksuView, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(ksuRow);

        safeView = new CheckBox(this);
        safeView.setTextSize(12);
        safeView.setText("安全模式：提权时禁用全部 KSU 模块");
        root.addView(safeView);

        scroll = new ScrollView(this);
        log = new TextView(this);
        log.setTextSize(10);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextIsSelectable(true);
        scroll.addView(log);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        Shizuku.addBinderReceivedListenerSticky(onRecv);
        Shizuku.addBinderDeadListener(onDead);
        Shizuku.addRequestPermissionResultListener(onPerm);
        refresh();
    }

    @Override
    protected void onDestroy() {
        polling = false;
        ui.removeCallbacks(poll);
        Shizuku.removeRequestPermissionResultListener(onPerm);
        Shizuku.removeBinderDeadListener(onDead);
        Shizuku.removeBinderReceivedListener(onRecv);
        if (bound && args != null) {
            try {
                Shizuku.unbindUserService(args, conn, false);
            } catch (Throwable ignored) { }
        }
        super.onDestroy();
    }

    private void connect() {
        try {
            if (!Shizuku.pingBinder()) {
                append("Shizuku 未运行：请在 Shizuku 应用里通过无线调试或 ADB 启动服务");
                refresh();
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                append("已弹出 Shizuku 授权请求，请允许");
                Shizuku.requestPermission(REQ);
                return;
            }
            bindService();
        } catch (Throwable t) {
            append("连接失败: " + t);
        }
        refresh();
    }

    private void bindService() {
        try {
            ComponentName cn = new ComponentName(this, IcService.class);
            args = new Shizuku.UserServiceArgs(cn)
                    .daemon(true)
                    .debuggable(false)
                    .version(1)
                    .tag("ic-lpe")
                    .processNameSuffix(":icsvc");
            Shizuku.bindUserService(args, conn);
            append("正在绑定 shell 用户服务…");
        } catch (Throwable t) {
            append("绑定失败: " + t);
        }
    }

    private void diag() {
        final IIcService s = svc;
        if (s == null) {
            append("服务未连接：先点“连接 Shizuku”");
            return;
        }
        new Thread(() -> {
            String out;
            try {
                out = s.runCmd("id; cat /proc/self/attr/current; getenforce; "
                        + "echo ---; uname -r; "
                        + "getprop ro.product.device; getprop ro.product.model; "
                        + "echo ---; ls -l /data/local/tmp/ | head -20; "
                        + "echo ---; head -n 2 /data/local/tmp/ic-syms.txt "
                        + "2>/dev/null; ls -d /data/adb/ksu 2>/dev/null; "
                        + "echo ---; pm list packages 2>/dev/null | "
                        + "grep -Ei 'kernelsu|sukisu|resukisu|bakasu|sakisu|"
                        + "sevenk|vivoksu|ksunext|fina'");
            } catch (Throwable t) {
                out = "诊断失败: " + t;
            }
            final String f = out;
            ui.post(() -> append("[诊断]\n" + f));
        }, "diag").start();
    }

    private void run() {
        final IIcService s = svc;
        if (s == null) {
            append("服务未连接：先点“连接 Shizuku”");
            return;
        }
        /* Fixed ladder: the run loop stops by itself as soon as one attempt
         * reaches euid 0, so the count is only an upper bound.  16 is the
         * documented ceiling for parked attempts (~10 MB each). */
        final int att = 16;
        final String cmd = DEFAULT_CMD;
        final String scheme = SCHEME_ASSET;
        final int ksuIdx = ksuView.getSelectedItemPosition();
        final String ksuPkg = KSU_PKGS[ksuIdx];
        final boolean safe = safeView.isChecked();

        // A new copy every run: truncating the ELF that parked attempts still
        // execute would SIGBUS them, and a dying attempt walks a rewritten
        // timer node (kernel oops with panic_on_oops=1).
        final String stamp = Long.toString(System.currentTimeMillis() / 1000);
        final String bin = TMP + "ic-timer-a" + stamp;
        final String script = TMP + "ic-lpe-a" + stamp + ".sh";
        final String logFile = TMP + "ic-app-a" + stamp + ".log";
        final String handoff = ksuPkg == null
                ? null : TMP + "ksu-h" + stamp + ".sh";
        // With KSU handoff on, the handoff script applies safe mode itself as
        // root.  With handoff off, run safemode.sh in the payload instead: the
        // helper is the only process that has root before KSU.
        final String safeScript = (safe && handoff == null)
                ? TMP + "ic-safe-h" + stamp + ".sh" : null;
        // The handoff runs first as root, then the user's proof command; ';'
        // keeps the proof running even when the handoff fails.  An empty
        // package means "let the script auto-detect the manager".
        String pl;
        if (handoff != null)
            pl = "sh " + handoff + " '" + ksuPkg + "' '' '"
                    + (safe ? "1" : "0") + "' ; " + cmd;
        else if (safeScript != null)
            pl = "sh " + safeScript + " ; " + cmd;
        else
            pl = cmd;
        // The launcher asset owns the whole plan: it computes this boot's
        // KASLR slide with the binary's own `slide` mode, always disarms vr.ko
        // with a zero-write when the module is loaded *before* the chain
        // releases the fake-cred winner (the X300/t28 ordering), and makes
        // SELinux permissive only on a stock device.
        final String payload = pl;
        final String extraEnv = "IC_SYMS_FILE=/data/local/tmp/ic-syms.txt"
                + (safe ? "\nIC_SAFE_MODE=1" : "")
                + (handoff != null ? "\nIC_KSU=1" : "");

        runBtn.setEnabled(false);
        new Thread(() -> {
            try {
                // The chain is only valid from the adb shell identity.  A
                // Shizuku server started as root hands out a root/ksu service,
                // and starting the exploit from there is both meaningless and
                // unsafe -- refuse instead of silently running it.
                String id0 = s.identity();
                if (!id0.startsWith("uid=2000 ") || !id0.contains("ctx=u:r:shell")) {
                    ui.post(() -> {
                        append("[run] 拒绝执行：用户服务身份不是 shell\n" + id0
                                + "\n请用 ADB / 无线调试启动 Shizuku（不要用 root 启动），"
                                + "再点“连接 Shizuku”。");
                        runBtn.setEnabled(true);
                    });
                    return;
                }
                s.truncate(script);
                push(s, scheme, script);
                s.makeExec(script);

                s.truncate(bin);
                push(s, "ic-timer", bin);
                s.makeExec(bin);

                if (handoff != null) {
                    s.truncate(handoff);
                    push(s, "ksu_handoff.sh", handoff);
                    s.makeExec(handoff);
                }
                if (safeScript != null) {
                    s.truncate(safeScript);
                    push(s, "safemode.sh", safeScript);
                    s.makeExec(safeScript);
                }

                s.truncate(logFile);
                String r = s.start(script, bin, logFile, att, payload,
                        extraEnv);
                ui.post(() -> {
                    append("[run] 方案: " + scheme
                            + "  KSU: " + KSU_NAMES[ksuIdx]
                            + "\n" + r);
                    logPath = logFile;
                    logOff = 0;
                    polling = true;
                    ui.postDelayed(poll, 1000);
                    runBtn.setEnabled(true);
                });
            } catch (Throwable t) {
                ui.post(() -> {
                    append("[run] 失败: " + t);
                    runBtn.setEnabled(true);
                });
            }
        }, "runner").start();
    }

    /* "解析偏移" opens the system document picker (SAF), copies the chosen
     * boot.img into /data/local/tmp through the Shizuku service, and runs
     * `ic-timer syms` on it.  This is optional: Y500, iQOO 12 and the
     * gf506252 PD2506 build already carry their tables in the binary.  The
     * PC-side tool/parse_offsets.py also accepts a full OTA zip. */
    private void parseOffsets() {
        if (svc == null) {
            append("服务未连接：先点“连接 Shizuku”");
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Throwable t) {
            append("[解析偏移] 无法打开文件选择器: " + t);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK)
            return;
        if (res != RESULT_OK || data == null || data.getData() == null) {
            append("[解析偏移] 已取消");
            return;
        }
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) { }
        doParseSelected(uri);
    }

    private void doParseSelected(final Uri uri) {
        final IIcService s = svc;
        if (s == null) {
            append("服务未连接：先点“连接 Shizuku”");
            return;
        }
        parseBtn.setEnabled(false);
        new Thread(() -> {
            String out;
            try {
                String stamp = Long.toString(System.currentTimeMillis() / 1000);
                String bin = TMP + "ic-syms-a" + stamp;
                String img = TMP + "ic-boot-a" + stamp + ".img";
                String syms = TMP + "ic-syms.txt";
                s.truncate(bin);
                push(s, "ic-timer", bin);
                s.makeExec(bin);
                s.truncate(img);
                InputStream in = getContentResolver().openInputStream(uri);
                if (in == null)
                    throw new IllegalStateException("openInputStream returned null");
                int off = 0;
                byte[] buf = new byte[CHUNK];
                try {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        byte[] chunk = (n == buf.length)
                                ? buf : Arrays.copyOf(buf, n);
                        s.put(img, off, chunk);
                        off += n;
                    }
                } finally {
                    in.close();
                }
                out = "已选择: " + uri.getLastPathSegment()
                        + " (" + off + " bytes)\n";
                out += s.runCmd("ls -l " + img + " 2>&1; "
                        + bin + " syms " + img + " " + syms + " 2>&1; "
                        + "echo parse_rc=$?; "
                        + "sed -n '1,3p' " + syms + " 2>/dev/null; "
                        + "rm -f " + img);
            } catch (Throwable t) {
                out = "解析失败: " + t;
            }
            final String f = out;
            ui.post(() -> {
                append("[解析偏移]\n" + f);
                parseBtn.setEnabled(true);
            });
        }, "syms").start();
    }

    private void cleanup() {
        final IIcService s = svc;
        if (s == null) {
            append("服务未连接：先点“连接 Shizuku”");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("清理 KernelSU")
                .setMessage("删除 /data/adb/ksu、/data/adb/modules、"
                        + "/data/adb/modules_update 等 KSU 文件，并清理本工具的"
                        + "临时文件。管理器 APK 默认保留。\n\n"
                        + "已加载的内核模块要重启后才消失；清理后请重启再提权。")
                .setPositiveButton("清理(保留管理器)",
                        (d, w) -> doCleanup(s, false))
                .setNeutralButton("连管理器一起卸载",
                        (d, w) -> doCleanup(s, true))
                .setNegativeButton("取消", null)
                .show();
    }

    private void doCleanup(final IIcService s, final boolean apk) {
        new Thread(() -> {
            String out;
            try {
                String stamp = Long.toString(System.currentTimeMillis() / 1000);
                String script = TMP + "ic-clean-a" + stamp + ".sh";
                s.truncate(script);
                push(s, "cleanup.sh", script);
                s.makeExec(script);
                out = s.runCmd("sh " + script + " --logs"
                        + (apk ? " --apk" : "") + " 2>&1");
            } catch (Throwable t) {
                out = "清理失败: " + t;
            }
            final String f = out;
            ui.post(() -> append("[清理]\n" + f));
        }, "cleanup").start();
    }

    private void push(IIcService s, String asset, String dst) throws Exception {
        InputStream in = getAssets().open(asset);
        try {
            byte[] buf = new byte[CHUNK];
            int off = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                byte[] chunk = (n == buf.length) ? buf : Arrays.copyOf(buf, n);
                s.put(dst, off, chunk);
                off += n;
            }
        } finally {
            in.close();
        }
    }

    private void refresh() {
        StringBuilder sb = new StringBuilder();
        try {
            if (Shizuku.pingBinder()) {
                sb.append("Shizuku: 运行中  uid=").append(Shizuku.getUid())
                  .append("  版本=").append(Shizuku.getVersion())
                  .append("  ctx=").append(Shizuku.getSELinuxContext())
                  .append(Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                          ? "  权限: 已授权" : "  权限: 未授权");
            } else {
                sb.append("Shizuku: 未运行（在 Shizuku 里用无线调试或 ADB 启动）");
            }
        } catch (Throwable t) {
            sb.append("Shizuku 状态读取失败: ").append(t);
        }
        sb.append("\n用户服务: ").append(bound ? "已连接" : "未连接");
        if (svc != null) {
            try {
                sb.append("  ").append(svc.identity());
            } catch (Throwable ignored) { }
        }
        status.setText(sb.toString());
    }

    private void append(String s) {
        raw(s + "\n");
    }

    private void raw(String s) {
        log.append(s);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }
}

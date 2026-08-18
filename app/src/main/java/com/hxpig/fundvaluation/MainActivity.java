package com.hxpig.fundvaluation;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String UPDATE_MANIFEST_URL = "https://raw.githubusercontent.com/HxPigGroup/fund_valuation_android/main/updates/latest.json";
    private static final int COLOR_BG = Color.rgb(245, 247, 251);
    private static final int COLOR_CARD = Color.WHITE;
    private static final int COLOR_INK = Color.rgb(16, 24, 40);
    private static final int COLOR_MUTED = Color.rgb(71, 84, 103);
    private static final int COLOR_LINE = Color.rgb(208, 213, 221);
    private static final int COLOR_BRAND = Color.rgb(21, 112, 239);
    private static final int COLOR_DANGER = Color.rgb(217, 45, 32);
    private static final int COLOR_UP = Color.rgb(217, 45, 32);
    private static final int COLOR_DOWN = Color.rgb(2, 122, 72);
    private static final int NAME_COL_WIDTH_DP = 168;
    private static final int ROW_MIN_HEIGHT_DP = 72;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final FundService fundService = new FundService();

    private Handler mainHandler;
    private FundStorage storage;
    private AlertDialog checkingUpdateDialog;
    private long updateDownloadId = -1L;
    private EditText entryPhoneInput;
    private TextView pageText;
    private TextView statusText;
    private Button refreshButton;
    private Button toggleExtraButton;
    private Button importantButton;
    private TextView importantBadge;
    private TableLayout fixedTable;
    private TableLayout scrollTable;
    private boolean refreshing;
    private boolean showExtraColumns;
    private boolean onEntryScreen = true;
    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, Intent intent) {
            if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
                return;
            }
            long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
            if (downloadId != updateDownloadId) {
                return;
            }
            updateDownloadId = -1L;
            handleDownloadedUpdate(downloadId);
        }
    };

    @Override
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mainHandler = new Handler(Looper.getMainLooper());
        storage = new FundStorage(this);
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(downloadReceiver, filter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(downloadReceiver, filter);
        }
        showEntryScreen();
    }

    @Override
    public void onBackPressed() {
        if (!onEntryScreen) {
            showEntryScreen();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(downloadReceiver);
        executor.shutdownNow();
        super.onDestroy();
    }

    private void showEntryScreen() {
        onEntryScreen = true;
        refreshing = false;

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(COLOR_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(18), dp(36), dp(18), dp(36));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = text("基金估值跟踪", 28, COLOR_INK, Typeface.BOLD);
        root.addView(title, blockParams(0, 0, 0, 10));

        TextView subtitle = text("选择要进入的页面", 15, COLOR_MUTED, Typeface.NORMAL);
        root.addView(subtitle, blockParams(0, 0, 0, 18));

        LinearLayout card = card();
        root.addView(card, blockParams(0, 0, 0, 0));

        Button publicButton = button("进入公共界面", COLOR_BRAND, Color.WHITE);
        publicButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                storage.setCurrentProfile("");
                showFundScreen();
            }
        });
        card.addView(publicButton, blockParams(0, 0, 0, 12));

        TextView divider = text("或进入个人界面", 14, COLOR_MUTED, Typeface.NORMAL);
        card.addView(divider, blockParams(0, 0, 0, 8));

        entryPhoneInput = input("输入 11 位手机号", InputType.TYPE_CLASS_NUMBER);
        card.addView(entryPhoneInput, blockParams(0, 0, 0, 10));

        Button personalButton = button("进入个人界面", Color.rgb(234, 242, 255), Color.rgb(23, 92, 211));
        personalButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                enterPersonalProfile();
            }
        });
        card.addView(personalButton, blockParams(0, 0, 0, 12));

        List<String> recentProfiles = storage.getRecentProfiles();
        if (!recentProfiles.isEmpty()) {
            final String lastProfile = recentProfiles.get(0);
            Button lastButton = button("进入上次个人界面 " + maskPhone(lastProfile), Color.rgb(255, 241, 243), COLOR_DANGER);
            lastButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    enterStoredProfile(lastProfile);
                }
            });
            card.addView(lastButton, blockParams(0, 0, 0, 8));

            TextView recentTitle = text("本机最近使用", 13, COLOR_MUTED, Typeface.NORMAL);
            card.addView(recentTitle, blockParams(0, 0, 0, 6));
            int count = Math.min(3, recentProfiles.size());
            for (int i = 0; i < count; i++) {
                final String phone = recentProfiles.get(i);
                Button recentButton = button(maskPhone(phone), Color.WHITE, COLOR_INK);
                recentButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        enterStoredProfile(phone);
                    }
                });
                card.addView(recentButton, blockParams(0, 0, 0, 6));
            }
        }

        Button aboutButton = button("关于", Color.WHITE, COLOR_INK);
        aboutButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showAboutDialog();
            }
        });
        card.addView(aboutButton, blockParams(0, 0, 0, 8));

        Button updateButton = button("查找更新", Color.rgb(234, 242, 255), Color.rgb(23, 92, 211));
        updateButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                checkForUpdates();
            }
        });
        card.addView(updateButton);

        setContentView(scrollView);
    }

    private void showFundScreen() {
        onEntryScreen = false;

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(COLOR_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), topSafePadding(), dp(10), dp(28));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout toolbar = row();
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(toolbar, blockParams(0, 0, 0, 8));

        Button backButton = iconButton("‹", Color.rgb(234, 242, 255), Color.rgb(23, 92, 211));
        backButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        backButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showEntryScreen();
            }
        });
        toolbar.addView(backButton, compactParams(0, 0, 4, 0));

        pageText = text("", 15, COLOR_INK, Typeface.BOLD);
        pageText.setSingleLine(true);
        pageText.setMaxLines(1);
        pageText.setMinWidth(0);
        pageText.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1.0f);
        toolbar.addView(pageText, titleParams);

        String profile = storage.getCurrentProfile();
        if (profile.length() > 0) {
            Button copyButton = iconButton("复制", Color.rgb(234, 242, 255), Color.rgb(23, 92, 211));
            copyButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    showCopyDialog();
                }
            });
            toolbar.addView(copyButton, compactParams(4, 0, 4, 0));

            LinearLayout importantWrap = row();
            importantWrap.setGravity(Gravity.CENTER_VERTICAL);
            importantButton = iconButton("🔔", Color.rgb(234, 242, 255), Color.rgb(23, 92, 211));
            importantButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    showImportantAlertsDialog();
                }
            });
            importantWrap.addView(importantButton);
            importantBadge = text("", 11, Color.WHITE, Typeface.BOLD);
            importantBadge.setGravity(Gravity.CENTER);
            importantBadge.setMinWidth(dp(20));
            importantBadge.setMinHeight(dp(20));
            importantBadge.setPadding(dp(5), 0, dp(5), 0);
            importantBadge.setBackground(rounded(COLOR_DANGER, COLOR_DANGER));
            importantBadge.setVisibility(View.GONE);
            importantWrap.addView(importantBadge, compactParams(3, 0, 0, 0));
            toolbar.addView(importantWrap, compactParams(0, 0, 4, 0));
        } else {
            importantButton = null;
            importantBadge = null;
        }

        Button addButton = iconButton("⊕", COLOR_BRAND, Color.WHITE);
        addButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        addButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showAddDialog();
            }
        });
        toolbar.addView(addButton, compactParams(4, 0, 4, 0));

        refreshButton = iconButton("↻", COLOR_BRAND, Color.WHITE);
        refreshButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        refreshButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                refreshNow();
            }
        });
        toolbar.addView(refreshButton, compactParams(0, 0, 4, 0));

        toggleExtraButton = iconButton("扩展", Color.rgb(234, 242, 255), Color.rgb(23, 92, 211));
        toggleExtraButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showExtraColumns = !showExtraColumns;
                render();
                setDefaultStatus();
            }
        });
        toolbar.addView(toggleExtraButton, compactParams(0, 0, 0, 0));

        statusText = text("", 13, COLOR_BRAND, Typeface.NORMAL);
        statusText.setLineSpacing(dp(2), 1.0f);
        root.addView(statusText, blockParams(2, 0, 2, 8));

        LinearLayout tableCard = card();
        tableCard.setPadding(0, 0, 0, 0);
        root.addView(tableCard, blockParams(0, 0, 0, 0));

        LinearLayout splitTable = row();
        tableCard.addView(splitTable);

        fixedTable = new TableLayout(this);
        fixedTable.setShrinkAllColumns(false);
        fixedTable.setStretchAllColumns(false);
        splitTable.addView(fixedTable, new LinearLayout.LayoutParams(
                dp(NAME_COL_WIDTH_DP),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        HorizontalScrollView horizontalScrollView = new HorizontalScrollView(this);
        horizontalScrollView.setFillViewport(false);
        horizontalScrollView.setHorizontalScrollBarEnabled(true);
        scrollTable = new TableLayout(this);
        scrollTable.setShrinkAllColumns(false);
        scrollTable.setStretchAllColumns(false);
        horizontalScrollView.addView(scrollTable, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        splitTable.addView(horizontalScrollView, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1.0f));

        setContentView(scrollView);
        render();
        setDefaultStatus();
    }

    private void showAboutDialog() {
        new AlertDialog.Builder(this)
                .setTitle("关于")
                .setItems(new CharSequence[]{"作者与仓库", "版本更新记录", "主要功能"}, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int which) {
                        if (which == 0) {
                            showAboutSection("作者与仓库", "作者：Facico\n仓库：https://github.com/HxPigGroup/fund_valuation_android");
                        } else if (which == 1) {
                            showAboutSection("版本更新记录",
                                    "0.4.7：新增新浪盘中估值，与东方财富估值同时展示。\n\n"
                                            + "0.4.6：修复自算估值算法的归一化问题。\n\n"
                                            + "0.4.5：停用已下线的逐基金估值接口并改进自算估值。\n\n"
                                            + "0.4.4：同步网页端的自算估值逻辑，使用历史净值和持仓日线校准自算值。\n\n"
                                            + "0.4.2：官方估值主接口与网页端对齐，旧逐基金接口仅作为缺失数据的兜底。\n\n"
                                            + "0.4.1：增加主页查找更新，可与服务端版本对齐并下载最新 APK。\n\n"
                                            + "0.3.2：新增近 5 个交易日涨跌超 10% 的名称标记，并确保升级后本地个人列表和最近账号继续保留。\n\n"
                                            + "0.3.1：压缩顶部工具栏，重要提醒改为铃铛，本机记录最近个人页面，关于页改为分板块查看。\n\n"
                                            + "0.3.0：新增首页关于、个人页复制跟踪列表、长按基金操作、个人提醒规则和重要提醒记录。\n\n"
                                            + "0.2.0：优化移动端列表，固定基金名称列，加入紧凑顶部工具栏。\n\n"
                                            + "0.1.0：创建 Android 原生项目，支持基金跟踪、刷新和缓存。");
                        } else {
                            showAboutSection("主要功能",
                                    "公共页面和个人页面分开管理。\n\n"
                                            + "本地保存基金跟踪列表，并可从本机最近个人页面快速进入。\n\n"
                                            + "刷新东方财富估值、新浪估值、自算估值、昨日增长和近一月增长。\n\n"
                                            + "个人页面可复制公共页面或其他个人页面的跟踪列表。\n\n"
                                            + "个人页面可设置涨跌提醒，并在铃铛入口查看重要提醒。");
                        }
                    }
                })
                .show();
    }

    private void checkForUpdates() {
        if (checkingUpdateDialog != null && checkingUpdateDialog.isShowing()) {
            return;
        }
        checkingUpdateDialog = new AlertDialog.Builder(this)
                .setTitle("查找更新")
                .setMessage("正在检查最新版本...")
                .setCancelable(false)
                .create();
        checkingUpdateDialog.show();

        executor.execute(new Runnable() {
            @Override
            public void run() {
                final AppUpdateInfo[] info = new AppUpdateInfo[]{null};
                final String[] error = new String[]{""};
                try {
                    info[0] = fetchUpdateInfo();
                } catch (Exception exception) {
                    error[0] = exception.getMessage() == null ? "检查失败" : exception.getMessage();
                }
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (checkingUpdateDialog != null) {
                            checkingUpdateDialog.dismiss();
                            checkingUpdateDialog = null;
                        }
                        if (FundFormat.hasValue(error[0])) {
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("查找更新")
                                    .setMessage("更新检查失败：" + error[0])
                                    .setPositiveButton("知道了", null)
                                    .show();
                            return;
                        }
                        showUpdateResult(info[0]);
                    }
                });
            }
        });
    }

    private AppUpdateInfo fetchUpdateInfo() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(UPDATE_MANIFEST_URL + "?t=" + System.currentTimeMillis()).openConnection();
        connection.setConnectTimeout(9000);
        connection.setReadTimeout(9000);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) FundValuationAndroid/1.0");
        try {
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String body = readText(stream);
            if (status >= 400) {
                throw new IOException("HTTP " + status);
            }
            return AppUpdateInfo.fromJson(body);
        } finally {
            connection.disconnect();
        }
    }

    private String readText(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }
        return builder.toString();
    }

    private void showUpdateResult(final AppUpdateInfo updateInfo) {
        if (updateInfo == null || !FundFormat.hasValue(updateInfo.versionName) || !FundFormat.hasValue(updateInfo.apkUrl)) {
            new AlertDialog.Builder(this)
                    .setTitle("查找更新")
                    .setMessage("服务端没有返回有效的更新信息。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        if (updateInfo.versionCode <= currentVersionCode()) {
            new AlertDialog.Builder(this)
                    .setTitle("查找更新")
                    .setMessage("当前已是最新版本 " + currentVersionName() + "。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        String message = "检测到有最新版本 " + updateInfo.versionName + "，是否要进行更新？";
        if (FundFormat.hasValue(updateInfo.notes)) {
            message += "\n\n" + updateInfo.notes;
        }
        new AlertDialog.Builder(this)
                .setTitle("查找更新")
                .setMessage(message)
                .setNegativeButton("暂不更新", null)
                .setPositiveButton("立即更新", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int which) {
                        downloadLatestUpdate(updateInfo);
                    }
                })
                .show();
    }

    private void downloadLatestUpdate(AppUpdateInfo updateInfo) {
        DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        if (manager == null) {
            new AlertDialog.Builder(this)
                    .setTitle("查找更新")
                    .setMessage("当前设备不支持下载管理器。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        Uri uri = Uri.parse(updateInfo.apkUrl);
        DownloadManager.Request request = new DownloadManager.Request(uri);
        request.setTitle("基金估值跟踪 " + updateInfo.versionName);
        request.setDescription("正在下载最新更新");
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "fund-valuation-android-" + updateInfo.versionName + ".apk");
        updateDownloadId = manager.enqueue(request);
        new AlertDialog.Builder(this)
                .setTitle("查找更新")
                .setMessage("已开始下载最新版本，下载完成后会提示安装。")
                .setPositiveButton("知道了", null)
                .show();
    }

    private void handleDownloadedUpdate(long downloadId) {
        DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        if (manager == null) {
            return;
        }
        DownloadManager.Query query = new DownloadManager.Query().setFilterById(downloadId);
        Cursor cursor = manager.query(query);
        if (cursor == null) {
            return;
        }
        try {
            if (!cursor.moveToFirst()) {
                return;
            }
            int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                new AlertDialog.Builder(this)
                        .setTitle("查找更新")
                        .setMessage("更新下载失败，请稍后重试。")
                        .setPositiveButton("知道了", null)
                        .show();
                return;
            }
            Uri uri = manager.getUriForDownloadedFile(downloadId);
            if (uri == null) {
                new AlertDialog.Builder(this)
                        .setTitle("查找更新")
                        .setMessage("更新已下载完成，但无法自动打开安装包。")
                        .setPositiveButton("知道了", null)
                        .show();
                return;
            }
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } finally {
            cursor.close();
        }
    }

    private int currentVersionCode() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionCode;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private String currentVersionName() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName == null ? "未知版本" : info.versionName;
        } catch (Exception ignored) {
            return "未知版本";
        }
    }

    private void showAboutSection(String title, String message) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("返回", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int which) {
                        showAboutDialog();
                    }
                })
                .setPositiveButton("关闭", null)
                .show();
    }

    private void showCopyDialog() {
        final String currentProfile = storage.getCurrentProfile();
        if (currentProfile.length() == 0) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("复制跟踪列表")
                .setItems(new CharSequence[]{"从公共页面复制", "从其他个人页面复制"}, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int which) {
                        if (which == 0) {
                            confirmCopyFrom("");
                        } else {
                            showCopyPhoneDialog();
                        }
                    }
                })
                .show();
    }

    private void showCopyPhoneDialog() {
        final EditText input = input("输入要复制的 11 位手机号", InputType.TYPE_CLASS_NUMBER);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(18), dp(10), dp(18), 0);
        box.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("从个人页面复制")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("下一步", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int which) {
                        String sourceProfile = normalizePhone(input.getText().toString());
                        if (!FundFormat.hasValue(sourceProfile)) {
                            setStatus("请输入 11 位手机号。", true);
                            return;
                        }
                        if (sourceProfile.equals(storage.getCurrentProfile())) {
                            setStatus("不能复制当前个人页面。", true);
                            return;
                        }
                        confirmCopyFrom(sourceProfile);
                    }
                })
                .show();
    }

    private void confirmCopyFrom(final String sourceProfile) {
        List<String> sourceCodes = storage.getCodes(sourceProfile);
        if (sourceCodes.isEmpty()) {
            setStatus(sourceProfile.length() == 0 ? "公共页面没有可复制的基金。" : "目标个人页面没有可复制的基金。", true);
            return;
        }
        String sourceName = sourceProfile.length() == 0 ? "公共页面" : "个人页面 " + maskPhone(sourceProfile);
        new AlertDialog.Builder(this)
                .setTitle("确认复制")
                .setMessage("将用“" + sourceName + "”的 " + sourceCodes.size() + " 只基金覆盖当前个人页面。")
                .setNegativeButton("取消", null)
                .setPositiveButton("复制", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int which) {
                        copyTrackingList(sourceProfile);
                    }
                })
                .show();
    }

    private void copyTrackingList(String sourceProfile) {
        String currentProfile = storage.getCurrentProfile();
        if (currentProfile.length() == 0) {
            return;
        }
        List<String> sourceCodes = storage.getCodes(sourceProfile);
        storage.saveCodes(currentProfile, sourceCodes);
        storage.saveRows(currentProfile, new ArrayList<FundRow>());
        storage.saveAlerts(currentProfile, new ArrayList<FundAlert>());
        storage.saveUpdatedAt(currentProfile, "");
        render();
        setStatus("已复制 " + sourceCodes.size() + " 只基金，点 ↻ 刷新估值。", false);
    }

    private void render() {
        if (onEntryScreen || fixedTable == null || scrollTable == null) {
            return;
        }

        String profile = storage.getCurrentProfile();
        List<String> codes = storage.getCodes(profile);
        Map<String, FundRow> cachedRows = storage.getCachedRows(profile);
        if (profile.length() > 0) {
            storage.evaluateAlerts(profile, cachedRows);
        }
        List<FundRow> rows = new ArrayList<>();
        for (String code : codes) {
            FundRow row = cachedRows.get(code);
            rows.add(row == null ? new FundRow(code) : row);
        }
        sortRows(rows);

        pageText.setText(pageTitle(profile));
        refreshButton.setText(refreshing ? "…" : "↻");
        refreshButton.setEnabled(!refreshing);
        refreshButton.setBackground(rounded(refreshing ? COLOR_MUTED : COLOR_BRAND, refreshing ? COLOR_MUTED : COLOR_BRAND));
        toggleExtraButton.setText(showExtraColumns ? "收起" : "扩展");
        updateImportantButton(profile);
        renderTable(rows);
    }

    private void updateImportantButton(String profile) {
        if (importantButton == null) {
            return;
        }
        int count = storage.getTriggeredAlertCount(profile);
        if (count > 0) {
            importantButton.setText("🔔");
            importantButton.setTextColor(Color.rgb(23, 92, 211));
            importantButton.setBackground(rounded(Color.rgb(234, 242, 255), Color.rgb(234, 242, 255)));
            if (importantBadge != null) {
                importantBadge.setText(String.valueOf(count));
                importantBadge.setVisibility(View.VISIBLE);
            }
        } else {
            importantButton.setText("🔔");
            importantButton.setTextColor(Color.rgb(23, 92, 211));
            importantButton.setBackground(rounded(Color.rgb(234, 242, 255), Color.rgb(234, 242, 255)));
            if (importantBadge != null) {
                importantBadge.setVisibility(View.GONE);
            }
        }
    }

    private void renderTable(List<FundRow> rows) {
        fixedTable.removeAllViews();
        scrollTable.removeAllViews();

        TableRow fixedHeader = new TableRow(this);
        fixedHeader.addView(headerCell("基金名称", NAME_COL_WIDTH_DP, Gravity.CENTER_VERTICAL));
        fixedTable.addView(fixedHeader);

        TableRow scrollHeader = new TableRow(this);
        addHeader(scrollHeader, "东方财富\n涨跌", 104);
        addHeader(scrollHeader, "东方财富\n估算值", 104);
        addHeader(scrollHeader, "新浪估算\n涨跌", 104);
        addHeader(scrollHeader, "新浪估算值", 104);
        if (showExtraColumns) {
            addHeader(scrollHeader, "自算估值", 96);
            addHeader(scrollHeader, "自算涨跌", 96);
        }
        addHeader(scrollHeader, "昨日增长", 96);
        addHeader(scrollHeader, "近一月增长", 106);
        scrollTable.addView(scrollHeader);

        if (rows.isEmpty()) {
            TableRow fixedEmpty = new TableRow(this);
            View nameCell = nameCell("当前没有跟踪的基金。", "", FundFormat.BLANK);
            fixedEmpty.addView(nameCell);
            fixedTable.addView(fixedEmpty);

            TableRow scrollEmpty = new TableRow(this);
            TextView cell = cell("点 ⊕ 加入", 280, COLOR_MUTED, Typeface.NORMAL, Gravity.CENTER_VERTICAL);
            scrollEmpty.addView(cell);
            scrollTable.addView(scrollEmpty);
            return;
        }

        for (final FundRow row : rows) {
            TableRow fixedRow = new TableRow(this);
            String name = FundFormat.hasValue(row.name) ? row.name : row.code;
            View fixedNameCell = nameCell(name, row.code, row.fiveDayGrowth);
            attachFundLongPress(fixedNameCell, row);
            fixedRow.setOnLongClickListener(fundLongClickListener(row));
            fixedRow.addView(fixedNameCell);
            fixedTable.addView(fixedRow);

            TableRow scrollRow = new TableRow(this);
            scrollRow.setOnLongClickListener(fundLongClickListener(row));
            attachFundLongPress(addEstimateGrowthCell(scrollRow, row), row);
            attachFundLongPress(addEstimateValueCell(scrollRow, row), row);
            attachFundLongPress(addSinaEstimateGrowthCell(scrollRow, row), row);
            attachFundLongPress(addSinaEstimateValueCell(scrollRow, row), row);
            if (showExtraColumns) {
                attachFundLongPress(addCell(scrollRow, row.selfEstimateValue, 96, COLOR_MUTED, Typeface.NORMAL), row);
                attachFundLongPress(addCell(scrollRow, row.selfEstimateGrowth, 96, toneColor(row.selfEstimateGrowth), Typeface.NORMAL), row);
            }
            attachFundLongPress(addCell(scrollRow, row.publishedGrowth, 96, toneColor(row.publishedGrowth), Typeface.NORMAL), row);
            attachFundLongPress(addCell(scrollRow, row.monthGrowth, 106, toneColor(row.monthGrowth), Typeface.NORMAL), row);
            scrollTable.addView(scrollRow);
        }
    }

    private View nameCell(String name, String code, String fiveDayGrowth) {
        String safeName = FundFormat.orBlank(name);
        String safeCode = FundFormat.hasValue(code) ? code : "";
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setLayoutParams(new TableRow.LayoutParams(dp(NAME_COL_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT));
        layout.setMinimumWidth(dp(NAME_COL_WIDTH_DP));
        layout.setMinimumHeight(dp(ROW_MIN_HEIGHT_DP));
        layout.setGravity(Gravity.CENTER_VERTICAL);
        layout.setPadding(dp(10), dp(8), dp(10), dp(8));
        layout.setBackground(rounded(Color.WHITE, Color.rgb(234, 236, 240)));

        TextView nameView = text(safeName, 13, COLOR_INK, Typeface.BOLD);
        nameView.setSingleLine(false);
        layout.addView(nameView);

        String badgeText = fiveDayBadgeText(fiveDayGrowth);
        if (safeCode.length() > 0 || FundFormat.hasValue(badgeText)) {
            LinearLayout metaRow = row();
            metaRow.setGravity(Gravity.CENTER_VERTICAL);
            layout.addView(metaRow, compactParams(0, 3, 0, 0));

            if (safeCode.length() > 0) {
                TextView codeView = text(safeCode, 11, COLOR_MUTED, Typeface.NORMAL);
                metaRow.addView(codeView, compactParams(0, 0, 0, 0));
            }

            if (FundFormat.hasValue(badgeText)) {
                int tone = fiveDayBadgeColor(fiveDayGrowth);
                TextView badgeView = text(badgeText, 10, tone, Typeface.BOLD);
                badgeView.setPadding(dp(5), dp(2), dp(5), dp(2));
                badgeView.setBackground(rounded(Color.WHITE, tone));
                metaRow.addView(badgeView, compactParams(safeCode.length() > 0 ? 6 : 0, 0, 0, 0));
            }
        }
        return layout;
    }

    private TextView headerCell(String text, int widthDp, int gravity) {
        TextView view = cell(text, widthDp, COLOR_MUTED, Typeface.BOLD, gravity);
        view.setBackgroundColor(Color.rgb(248, 250, 252));
        return view;
    }

    private TextView addEstimateValueCell(TableRow row, FundRow fundRow) {
        String value = FundFormat.orBlank(fundRow.estimateValue);
        TextView view = cell(value, 104, COLOR_INK, Typeface.NORMAL, Gravity.CENTER);
        row.addView(view);
        return view;
    }

    private TextView addEstimateGrowthCell(TableRow row, FundRow fundRow) {
        String value = FundFormat.orBlank(fundRow.estimateGrowth);
        TextView view = cell(value, 104, toneColor(fundRow.estimateGrowth), Typeface.BOLD, Gravity.CENTER);
        row.addView(view);
        return view;
    }

    private TextView addSinaEstimateValueCell(TableRow row, FundRow fundRow) {
        TextView view = cell(
                FundFormat.orBlank(fundRow.sinaEstimateValue),
                104,
                COLOR_INK,
                Typeface.NORMAL,
                Gravity.CENTER
        );
        row.addView(view);
        return view;
    }

    private TextView addSinaEstimateGrowthCell(TableRow row, FundRow fundRow) {
        TextView view = cell(
                FundFormat.orBlank(fundRow.sinaEstimateGrowth),
                104,
                toneColor(fundRow.sinaEstimateGrowth),
                Typeface.BOLD,
                Gravity.CENTER
        );
        row.addView(view);
        return view;
    }

    private void addHeader(TableRow row, String text, int widthDp) {
        row.addView(headerCell(text, widthDp, Gravity.CENTER));
    }

    private TextView addCell(TableRow row, String text, int widthDp, int color, int style) {
        TextView view = cell(text, widthDp, color, style, Gravity.CENTER);
        row.addView(view);
        return view;
    }

    private TextView cell(String text, int widthDp, int color, int style, int gravity) {
        TextView view = text(FundFormat.orBlank(text), 14, color, style);
        view.setWidth(dp(widthDp));
        view.setMinHeight(dp(ROW_MIN_HEIGHT_DP));
        view.setGravity(gravity);
        view.setPadding(dp(8), dp(8), dp(8), dp(8));
        view.setSingleLine(false);
        view.setBackground(rounded(Color.WHITE, Color.rgb(234, 236, 240)));
        return view;
    }

    private void attachFundLongPress(View view, final FundRow row) {
        view.setOnLongClickListener(fundLongClickListener(row));
    }

    private View.OnLongClickListener fundLongClickListener(final FundRow row) {
        return new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                showFundActionDialog(row);
                return true;
            }
        };
    }

    private void showFundActionDialog(final FundRow row) {
        final String profile = storage.getCurrentProfile();
        CharSequence[] items = profile.length() == 0
                ? new CharSequence[]{"删除"}
                : new CharSequence[]{"删除", "提醒"};
        new AlertDialog.Builder(this)
                .setTitle((FundFormat.hasValue(row.name) ? row.name : row.code) + "\n" + row.code)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int which) {
                        if (which == 0) {
                            deleteCode(row.code);
                        } else {
                            showAlertRuleDialog(row);
                        }
                    }
                })
                .show();
    }

    private void showAlertRuleDialog(final FundRow row) {
        final String profile = storage.getCurrentProfile();
        if (profile.length() == 0) {
            return;
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(8), dp(18), 0);

        TextView current = text(
                "东方财富涨跌：" + FundFormat.orBlank(row.estimateGrowth)
                        + "    新浪涨跌：" + FundFormat.orBlank(row.sinaEstimateGrowth)
                        + "\n提醒优先使用东方财富，缺失时使用新浪。",
                14,
                COLOR_MUTED,
                Typeface.NORMAL
        );
        box.addView(current, blockParams(0, 0, 0, 10));

        final EditText upInput = input("收益率涨百分之多少，例如 2.5", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        box.addView(upInput, blockParams(0, 0, 0, 8));

        final EditText downInput = input("收益率跌百分之多少，例如 2.5", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        box.addView(downInput);

        new AlertDialog.Builder(this)
                .setTitle("设置提醒")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int which) {
                        saveAlertRules(profile, row, upInput.getText().toString(), downInput.getText().toString());
                    }
                })
                .show();
    }

    private void saveAlertRules(String profile, FundRow row, String upText, String downText) {
        Double upThreshold = parseThreshold(upText);
        Double downThreshold = parseThreshold(downText);
        if (upThreshold == null && downThreshold == null) {
            setStatus("请输入上涨或下跌提醒线。", true);
            return;
        }
        if (upThreshold != null) {
            FundAlert alert = new FundAlert(row.code, FundAlert.DIRECTION_UP, upThreshold);
            alert.fundName = FundFormat.hasValue(row.name) ? row.name : row.code;
            storage.upsertAlert(profile, alert);
        }
        if (downThreshold != null) {
            FundAlert alert = new FundAlert(row.code, FundAlert.DIRECTION_DOWN, downThreshold);
            alert.fundName = FundFormat.hasValue(row.name) ? row.name : row.code;
            storage.upsertAlert(profile, alert);
        }
        storage.evaluateAlerts(profile, storage.getCachedRows(profile));
        render();
        setStatus("已保存提醒，点铃铛查看触发情况。", false);
    }

    private Double parseThreshold(String value) {
        Double number = FundFormat.parseNumber(value);
        if (number == null) {
            return null;
        }
        number = Math.abs(number);
        return number > 0 ? number : null;
    }

    private void showImportantAlertsDialog() {
        String profile = storage.getCurrentProfile();
        if (profile.length() == 0) {
            return;
        }
        storage.evaluateAlerts(profile, storage.getCachedRows(profile));
        List<FundAlert> alerts = storage.getAlerts(profile);
        if (alerts.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("重要提醒")
                    .setMessage("还没有设置提醒。长按基金后点“提醒”即可添加。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(14), dp(8), dp(14), dp(8));
        Collections.sort(alerts, new Comparator<FundAlert>() {
            @Override
            public int compare(FundAlert left, FundAlert right) {
                if (left.triggered != right.triggered) {
                    return left.triggered ? -1 : 1;
                }
                return left.code.compareTo(right.code);
            }
        });
        for (FundAlert alert : alerts) {
            list.addView(alertView(alert), blockParams(0, 0, 0, 8));
        }

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(list, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("重要提醒")
                .setView(scrollView)
                .setPositiveButton("关闭", null)
                .show();
    }

    private TextView alertView(FundAlert alert) {
        String latest = Double.isNaN(alert.latestGrowth) ? "---" : FundFormat.percentFromNumber(alert.latestGrowth);
        String status = alert.triggered ? "已触发" : "未触发";
        String time = alert.triggered ? alert.triggeredAt : alert.evaluatedAt;
        String text = status + " | " + FundFormat.orBlank(alert.fundName) + "\n"
                + alert.code + " | 目标：" + alert.targetText() + " | 当前：" + latest
                + (FundFormat.hasValue(time) ? "\n" + time : "");
        TextView view = text(text, 14, alert.triggered ? COLOR_DANGER : COLOR_INK, alert.triggered ? Typeface.BOLD : Typeface.NORMAL);
        view.setPadding(dp(10), dp(10), dp(10), dp(10));
        view.setSingleLine(false);
        view.setBackground(rounded(alert.triggered ? Color.rgb(255, 241, 243) : Color.WHITE, alert.triggered ? COLOR_DANGER : COLOR_LINE));
        return view;
    }

    private void showAddDialog() {
        final EditText input = input("输入基金代码，例如 161725", InputType.TYPE_CLASS_NUMBER);
        input.setSelectAllOnFocus(true);
        int padding = dp(18);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(padding, dp(10), padding, 0);
        box.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("加入跟踪")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("加入", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int which) {
                        addCode(input.getText().toString());
                    }
                })
                .show();
    }

    private void addCode(String rawCode) {
        String profile = storage.getCurrentProfile();
        String code = FundStorage.normalizeCode(rawCode);
        if (!FundFormat.hasValue(code)) {
            setStatus("请输入 1 到 6 位基金代码。", true);
            return;
        }
        List<String> codes = storage.getCodes(profile);
        if (!codes.contains(code)) {
            codes.add(code);
            storage.saveCodes(profile, codes);
        }
        render();
        setStatus("已加入 " + code + "，可点 ↻ 刷新。", false);
    }

    private void deleteCode(String code) {
        String profile = storage.getCurrentProfile();
        List<String> codes = storage.getCodes(profile);
        codes.remove(code);
        storage.saveCodes(profile, codes);
        storage.deleteAlertsForCode(profile, code);
        render();
        setStatus("已删除 " + code + "。", false);
    }

    private void enterPersonalProfile() {
        String phone = normalizePhone(entryPhoneInput.getText().toString());
        if (!FundFormat.hasValue(phone)) {
            entryPhoneInput.setError("请输入 11 位手机号");
            return;
        }
        storage.rememberProfile(phone);
        showFundScreen();
    }

    private void enterStoredProfile(String phone) {
        String normalized = normalizePhone(phone);
        if (!FundFormat.hasValue(normalized)) {
            return;
        }
        storage.rememberProfile(normalized);
        showFundScreen();
    }

    private void refreshNow() {
        if (refreshing) {
            return;
        }
        final String targetProfile = storage.getCurrentProfile();
        final List<String> codes = storage.getCodes(targetProfile);
        if (codes.isEmpty()) {
            setStatus("当前列表为空，先点 ⊕ 加入基金。", true);
            return;
        }

        refreshing = true;
        render();
        setStatus("正在刷新 " + codes.size() + " 只基金，请稍等。", false);
        executor.execute(new Runnable() {
            @Override
            public void run() {
                final List<FundRow> rows = new ArrayList<>();
                final String[] error = new String[]{""};
                try {
                    rows.addAll(fundService.fetchRows(codes));
                } catch (RuntimeException exception) {
                    error[0] = exception.getMessage() == null ? "刷新失败" : exception.getMessage();
                }
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        refreshing = false;
                        if (FundFormat.hasValue(error[0])) {
                            render();
                            setStatus("刷新失败：" + error[0], true);
                            return;
                        }
                        storage.saveRows(targetProfile, rows);
                        storage.saveUpdatedAt(targetProfile, FundFormat.nowText());
                        render();
                        int eastmoneyCount = 0;
                        int sinaCount = 0;
                        for (FundRow row : rows) {
                            if (FundFormat.hasValue(row.estimateGrowth)) {
                                eastmoneyCount++;
                            }
                            if (FundFormat.hasValue(row.sinaEstimateGrowth)) {
                                sinaCount++;
                            }
                        }
                        setStatus(
                                "已刷新 " + rows.size() + " 只基金，东方财富 "
                                        + eastmoneyCount + " 只，新浪 " + sinaCount + " 只。",
                                false
                        );
                    }
                });
            }
        });
    }

    private void setDefaultStatus() {
        String profile = storage.getCurrentProfile();
        List<String> codes = storage.getCodes(profile);
        String updatedAt = storage.getUpdatedAt(profile);
        if (codes.isEmpty()) {
            setStatus(profile.length() == 0
                    ? "公共页面暂无跟踪基金，点 ⊕ 添加。"
                    : "个人页面暂无跟踪基金，点 ⊕ 添加。", false);
        } else if (FundFormat.hasValue(updatedAt)) {
            setStatus("最近缓存：" + updatedAt + "。", false);
        } else {
            setStatus("已加载基金列表，点 ↻ 获取最新估值。", false);
        }
    }

    private void setStatus(String message, boolean error) {
        if (statusText == null) {
            return;
        }
        statusText.setText(message);
        statusText.setTextColor(error ? COLOR_DANGER : COLOR_BRAND);
    }

    private String pageTitle(String profile) {
        if (profile.length() == 0) {
            return "公共界面";
        }
        return "个人界面 " + maskPhone(profile);
    }

    private void sortRows(List<FundRow> rows) {
        Collections.sort(rows, new Comparator<FundRow>() {
            @Override
            public int compare(FundRow left, FundRow right) {
                Double leftValue = FundFormat.parseNumber(left.preferredEstimateGrowth());
                Double rightValue = FundFormat.parseNumber(right.preferredEstimateGrowth());
                if (leftValue == null && rightValue == null) {
                    return left.code.compareTo(right.code);
                }
                if (leftValue == null) {
                    return 1;
                }
                if (rightValue == null) {
                    return -1;
                }
                return Double.compare(rightValue, leftValue);
            }
        });
    }

    private int toneColor(String value) {
        Double number = FundFormat.parseNumber(value);
        if (number == null) {
            return COLOR_INK;
        }
        if (number > 0) {
            return COLOR_UP;
        }
        if (number < 0) {
            return COLOR_DOWN;
        }
        return COLOR_INK;
    }

    private String fiveDayBadgeText(String value) {
        Double number = FundFormat.parseNumber(value);
        if (number == null) {
            return "";
        }
        if (number > 10.0) {
            return "近5天涨>10%";
        }
        if (number < -10.0) {
            return "近5天下跌>10%";
        }
        return "";
    }

    private int fiveDayBadgeColor(String value) {
        Double number = FundFormat.parseNumber(value);
        if (number == null) {
            return COLOR_MUTED;
        }
        return number > 0 ? COLOR_UP : COLOR_DOWN;
    }

    private String normalizePhone(String value) {
        return FundStorage.normalizePhone(value);
    }

    private String maskPhone(String phone) {
        if (phone.length() != 11) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }

    private LinearLayout card() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(14), dp(14), dp(14), dp(14));
        layout.setBackground(rounded(COLOR_CARD, COLOR_LINE));
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setBaselineAligned(false);
        return layout;
    }

    private EditText input(String hint, int inputType) {
        EditText editText = new EditText(this);
        editText.setHint(hint);
        editText.setSingleLine(true);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        editText.setTextColor(COLOR_INK);
        editText.setHintTextColor(COLOR_MUTED);
        editText.setInputType(inputType);
        editText.setPadding(dp(12), 0, dp(12), 0);
        editText.setMinHeight(dp(46));
        editText.setBackground(rounded(Color.WHITE, COLOR_LINE));
        return editText;
    }

    private Button button(String label, int background, int foreground) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        button.setTextColor(foreground);
        button.setMinHeight(dp(46));
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setBackground(rounded(background, background));
        return button;
    }

    private Button iconButton(String label, int background, int foreground) {
        Button button = button(label, background, foreground);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        button.setMinWidth(dp(34));
        button.setMinHeight(dp(34));
        button.setMinimumWidth(dp(34));
        button.setMinimumHeight(dp(34));
        button.setPadding(dp(4), 0, dp(4), 0);
        return button;
    }

    private TextView text(String value, int sp, int color, int style) {
        TextView textView = new TextView(this);
        textView.setText(value);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        textView.setTextColor(color);
        textView.setTypeface(Typeface.DEFAULT, style);
        return textView;
    }

    private GradientDrawable rounded(int fill, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(8));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private LinearLayout.LayoutParams blockParams(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private LinearLayout.LayoutParams compactParams(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private int topSafePadding() {
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        int statusBarHeight = resourceId > 0 ? getResources().getDimensionPixelSize(resourceId) : 0;
        return statusBarHeight + dp(12);
    }

    private int dp(float value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                getResources().getDisplayMetrics());
    }
}

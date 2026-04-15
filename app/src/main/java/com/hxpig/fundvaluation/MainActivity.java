package com.hxpig.fundvaluation;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int COLOR_BG = Color.rgb(245, 247, 251);
    private static final int COLOR_CARD = Color.WHITE;
    private static final int COLOR_INK = Color.rgb(16, 24, 40);
    private static final int COLOR_MUTED = Color.rgb(71, 84, 103);
    private static final int COLOR_LINE = Color.rgb(208, 213, 221);
    private static final int COLOR_BRAND = Color.rgb(21, 112, 239);
    private static final int COLOR_DANGER = Color.rgb(217, 45, 32);
    private static final int COLOR_UP = Color.rgb(217, 45, 32);
    private static final int COLOR_DOWN = Color.rgb(2, 122, 72);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final FundService fundService = new FundService();

    private Handler mainHandler;
    private FundStorage storage;
    private TextView pageText;
    private TextView statusText;
    private EditText phoneInput;
    private EditText codeInput;
    private Button refreshButton;
    private Button toggleExtraButton;
    private TableLayout table;
    private boolean refreshing;
    private boolean showExtraColumns;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mainHandler = new Handler(Looper.getMainLooper());
        storage = new FundStorage(this);
        buildLayout();
        render();
        setDefaultStatus();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void buildLayout() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(COLOR_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(24), dp(16), dp(36));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("基金估值跟踪", 26, COLOR_INK, Typeface.BOLD);
        root.addView(title, blockParams(0, 0, 0, 12));

        LinearLayout toolsCard = card();
        root.addView(toolsCard, blockParams(0, 0, 0, 14));

        pageText = text("", 14, COLOR_MUTED, Typeface.NORMAL);
        pageText.setLineSpacing(dp(3), 1.0f);
        toolsCard.addView(pageText, blockParams(0, 0, 0, 12));

        phoneInput = input("输入 11 位手机号进入个人页面", InputType.TYPE_CLASS_NUMBER);
        toolsCard.addView(phoneInput, blockParams(0, 0, 0, 8));

        LinearLayout profileButtons = row();
        Button switchButton = button("切换个人页面", COLOR_BRAND, Color.WHITE);
        switchButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                switchProfile();
            }
        });
        Button publicButton = button("回到公共页面", Color.rgb(234, 242, 255), Color.rgb(23, 92, 211));
        publicButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                storage.setCurrentProfile("");
                render();
                setDefaultStatus();
            }
        });
        addWeighted(profileButtons, switchButton, 0, 0, 6, 0);
        addWeighted(profileButtons, publicButton, 6, 0, 0, 0);
        toolsCard.addView(profileButtons, blockParams(0, 0, 0, 12));

        codeInput = input("输入基金代码，例如 161725", InputType.TYPE_CLASS_NUMBER);
        toolsCard.addView(codeInput, blockParams(0, 0, 0, 8));

        LinearLayout actionButtons = row();
        Button addButton = button("加入跟踪", COLOR_BRAND, Color.WHITE);
        addButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                addCode();
            }
        });
        refreshButton = button("立即刷新估值", COLOR_BRAND, Color.WHITE);
        refreshButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                refreshNow();
            }
        });
        addWeighted(actionButtons, addButton, 0, 0, 6, 0);
        addWeighted(actionButtons, refreshButton, 6, 0, 0, 0);
        toolsCard.addView(actionButtons, blockParams(0, 0, 0, 10));

        toggleExtraButton = button("显示扩展列", Color.rgb(234, 242, 255), Color.rgb(23, 92, 211));
        toggleExtraButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showExtraColumns = !showExtraColumns;
                render();
                setDefaultStatus();
            }
        });
        toolsCard.addView(toggleExtraButton, blockParams(0, 0, 0, 10));

        statusText = text("", 14, COLOR_BRAND, Typeface.NORMAL);
        statusText.setLineSpacing(dp(3), 1.0f);
        toolsCard.addView(statusText, blockParams(0, 0, 0, 10));

        TextView hint = text("默认按官方估算涨跌从大到小排序。估算值来自天天基金，昨日增长和近一月增长来自东方财富历史净值。自算估值字段保留给后续接入持仓算法。", 13, COLOR_MUTED, Typeface.NORMAL);
        hint.setLineSpacing(dp(3), 1.0f);
        toolsCard.addView(hint);

        LinearLayout tableCard = card();
        root.addView(tableCard, blockParams(0, 0, 0, 0));
        HorizontalScrollView horizontalScrollView = new HorizontalScrollView(this);
        horizontalScrollView.setFillViewport(true);
        table = new TableLayout(this);
        table.setShrinkAllColumns(false);
        table.setStretchAllColumns(false);
        horizontalScrollView.addView(table, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        tableCard.addView(horizontalScrollView);

        setContentView(scrollView);
    }

    private void render() {
        String profile = storage.getCurrentProfile();
        List<String> codes = storage.getCodes(profile);
        Map<String, FundRow> cachedRows = storage.getCachedRows(profile);
        List<FundRow> rows = new ArrayList<>();
        for (String code : codes) {
            FundRow row = cachedRows.get(code);
            rows.add(row == null ? new FundRow(code) : row);
        }
        sortRows(rows);

        phoneInput.setText(profile);
        pageText.setText(pageDescription(profile));
        refreshButton.setText(refreshing ? "刷新中..." : "立即刷新估值");
        refreshButton.setEnabled(!refreshing);
        refreshButton.setBackground(rounded(refreshing ? COLOR_MUTED : COLOR_BRAND, refreshing ? COLOR_MUTED : COLOR_BRAND));
        toggleExtraButton.setText(showExtraColumns ? "隐藏扩展列" : "显示扩展列");
        renderTable(rows);
    }

    private void renderTable(List<FundRow> rows) {
        table.removeAllViews();
        TableRow header = new TableRow(this);
        addHeader(header, "基金代码", 88);
        addHeader(header, "基金名称", 188);
        addHeader(header, "官方估算值", 112);
        addHeader(header, "官方估算涨跌", 122);
        if (showExtraColumns) {
            addHeader(header, "自算估值", 106);
            addHeader(header, "自算涨跌", 106);
        }
        addHeader(header, "昨日增长", 102);
        addHeader(header, "近一月增长", 112);
        addHeader(header, "操作", 82);
        table.addView(header);

        if (rows.isEmpty()) {
            TableRow empty = new TableRow(this);
            TextView cell = cell("当前没有跟踪的基金。", 340, COLOR_MUTED, Typeface.NORMAL);
            empty.addView(cell);
            table.addView(empty);
            return;
        }

        for (final FundRow row : rows) {
            TableRow tableRow = new TableRow(this);
            addCell(tableRow, row.code, 88, COLOR_INK, Typeface.NORMAL);
            String nameText = row.name;
            if (FundFormat.hasValue(row.message)) {
                nameText = nameText + "\n" + row.message;
            }
            addCell(tableRow, nameText, 188, COLOR_INK, Typeface.NORMAL);
            addCell(tableRow, row.estimateValue, 112, COLOR_INK, Typeface.NORMAL);
            addCell(tableRow, row.estimateGrowth, 122, toneColor(row.estimateGrowth), Typeface.BOLD);
            if (showExtraColumns) {
                addCell(tableRow, row.selfEstimateValue, 106, COLOR_MUTED, Typeface.NORMAL);
                addCell(tableRow, row.selfEstimateGrowth, 106, toneColor(row.selfEstimateGrowth), Typeface.NORMAL);
            }
            addCell(tableRow, row.publishedGrowth, 102, toneColor(row.publishedGrowth), Typeface.NORMAL);
            addCell(tableRow, row.monthGrowth, 112, toneColor(row.monthGrowth), Typeface.NORMAL);

            Button deleteButton = button("删除", COLOR_DANGER, Color.WHITE);
            deleteButton.setMinWidth(dp(64));
            deleteButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    deleteCode(row.code);
                }
            });
            TableRow.LayoutParams params = new TableRow.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(dp(8), dp(6), dp(8), dp(6));
            tableRow.addView(deleteButton, params);
            table.addView(tableRow);
        }
    }

    private void addHeader(TableRow row, String text, int widthDp) {
        TextView view = cell(text, widthDp, COLOR_MUTED, Typeface.BOLD);
        view.setBackgroundColor(Color.rgb(248, 250, 252));
        row.addView(view);
    }

    private void addCell(TableRow row, String text, int widthDp, int color, int style) {
        row.addView(cell(text, widthDp, color, style));
    }

    private TextView cell(String text, int widthDp, int color, int style) {
        TextView view = text(FundFormat.orBlank(text), 14, color, style);
        view.setMinWidth(dp(widthDp));
        view.setMaxWidth(dp(widthDp + 60));
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(dp(10), dp(10), dp(10), dp(10));
        view.setSingleLine(false);
        view.setBackground(rounded(Color.WHITE, Color.rgb(234, 236, 240)));
        return view;
    }

    private void addCode() {
        String profile = storage.getCurrentProfile();
        String code = FundStorage.normalizeCode(codeInput.getText().toString());
        if (!FundFormat.hasValue(code)) {
            setStatus("请输入 1 到 6 位基金代码。", true);
            return;
        }
        List<String> codes = storage.getCodes(profile);
        if (!codes.contains(code)) {
            codes.add(code);
            storage.saveCodes(profile, codes);
        }
        codeInput.setText("");
        render();
        setStatus("已加入 " + code + "，可以立即刷新估值。", false);
    }

    private void deleteCode(String code) {
        String profile = storage.getCurrentProfile();
        List<String> codes = storage.getCodes(profile);
        codes.remove(code);
        storage.saveCodes(profile, codes);
        render();
        setStatus("已删除 " + code + "。", false);
    }

    private void switchProfile() {
        String phone = normalizePhone(phoneInput.getText().toString());
        if (!FundFormat.hasValue(phone)) {
            setStatus("请输入 11 位手机号后再进入个人页面。", true);
            return;
        }
        storage.setCurrentProfile(phone);
        render();
        setDefaultStatus();
    }

    private void refreshNow() {
        if (refreshing) {
            return;
        }
        final String targetProfile = storage.getCurrentProfile();
        final List<String> codes = storage.getCodes(targetProfile);
        if (codes.isEmpty()) {
            setStatus("当前列表为空，先加入基金代码。", true);
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
                        render();
                        if (FundFormat.hasValue(error[0])) {
                            setStatus("刷新失败：" + error[0], true);
                            return;
                        }
                        storage.saveRows(targetProfile, rows);
                        storage.saveUpdatedAt(targetProfile, FundFormat.nowText());
                        render();
                        setStatus("已刷新 " + rows.size() + " 只基金。", false);
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
                    ? "公共页面暂时没有跟踪基金。"
                    : "这是一个空白个人页，先加入基金代码。", false);
        } else if (FundFormat.hasValue(updatedAt)) {
            setStatus("最近缓存时间：" + updatedAt + "。", false);
        } else {
            setStatus("已加载默认基金列表，点击“立即刷新估值”获取数据。", false);
        }
    }

    private void setStatus(String message, boolean error) {
        statusText.setText(message);
        statusText.setTextColor(error ? COLOR_DANGER : COLOR_BRAND);
    }

    private String pageDescription(String profile) {
        String updatedAt = storage.getUpdatedAt(profile);
        String timeText = FundFormat.hasValue(updatedAt) ? updatedAt : "暂无";
        if (profile.length() == 0) {
            return "公共页面 | 最近缓存时间：" + timeText;
        }
        return "个人页：" + maskPhone(profile) + " | 页面标识：" + profile + " | 最近缓存时间：" + timeText;
    }

    private void sortRows(List<FundRow> rows) {
        Collections.sort(rows, new Comparator<FundRow>() {
            @Override
            public int compare(FundRow left, FundRow right) {
                Double leftValue = FundFormat.parseNumber(left.estimateGrowth);
                Double rightValue = FundFormat.parseNumber(right.estimateGrowth);
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

    private String normalizePhone(String value) {
        if (value == null) {
            return "";
        }
        String digits = value.replaceAll("\\D", "");
        return digits.length() == 11 ? digits : "";
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
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setTextColor(foreground);
        button.setMinHeight(dp(44));
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setBackground(rounded(background, background));
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

    private void addWeighted(LinearLayout parent, View child, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1.0f);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        parent.addView(child, params);
    }

    private int dp(float value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                getResources().getDisplayMetrics());
    }
}

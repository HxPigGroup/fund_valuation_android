package com.hxpig.fundvaluation;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
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
    private static final int NAME_COL_WIDTH_DP = 136;
    private static final int ROW_MIN_HEIGHT_DP = 72;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final FundService fundService = new FundService();

    private Handler mainHandler;
    private FundStorage storage;
    private EditText entryPhoneInput;
    private TextView pageText;
    private TextView statusText;
    private Button refreshButton;
    private Button toggleExtraButton;
    private TableLayout fixedTable;
    private TableLayout scrollTable;
    private boolean refreshing;
    private boolean showExtraColumns;
    private boolean onEntryScreen = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mainHandler = new Handler(Looper.getMainLooper());
        storage = new FundStorage(this);
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
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(18), dp(36), dp(18), dp(36));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

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
        card.addView(personalButton);

        setContentView(scrollView);
    }

    private void showFundScreen() {
        onEntryScreen = false;

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(COLOR_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(16), dp(10), dp(28));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout toolbar = row();
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(toolbar, blockParams(0, 0, 0, 8));

        Button backButton = iconButton("返回", Color.rgb(234, 242, 255), Color.rgb(23, 92, 211));
        backButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showEntryScreen();
            }
        });
        toolbar.addView(backButton, compactParams(0, 0, 8, 0));

        pageText = text("", 15, COLOR_INK, Typeface.BOLD);
        pageText.setSingleLine(false);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1.0f);
        toolbar.addView(pageText, titleParams);

        Button addButton = iconButton("⊕", COLOR_BRAND, Color.WHITE);
        addButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        addButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showAddDialog();
            }
        });
        toolbar.addView(addButton, compactParams(8, 0, 6, 0));

        refreshButton = iconButton("↻", COLOR_BRAND, Color.WHITE);
        refreshButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        refreshButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                refreshNow();
            }
        });
        toolbar.addView(refreshButton, compactParams(0, 0, 6, 0));

        toggleExtraButton = iconButton("列", Color.rgb(234, 242, 255), Color.rgb(23, 92, 211));
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

    private void render() {
        if (onEntryScreen || fixedTable == null || scrollTable == null) {
            return;
        }

        String profile = storage.getCurrentProfile();
        List<String> codes = storage.getCodes(profile);
        Map<String, FundRow> cachedRows = storage.getCachedRows(profile);
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
        toggleExtraButton.setText(showExtraColumns ? "简" : "列");
        renderTable(rows);
    }

    private void renderTable(List<FundRow> rows) {
        fixedTable.removeAllViews();
        scrollTable.removeAllViews();

        TableRow fixedHeader = new TableRow(this);
        fixedHeader.addView(headerCell("基金名称", NAME_COL_WIDTH_DP, Gravity.CENTER_VERTICAL));
        fixedTable.addView(fixedHeader);

        TableRow scrollHeader = new TableRow(this);
        addHeader(scrollHeader, "今日估值\n涨跌", 104);
        addHeader(scrollHeader, "官方估算值", 104);
        if (showExtraColumns) {
            addHeader(scrollHeader, "自算估值", 96);
            addHeader(scrollHeader, "自算涨跌", 96);
        }
        addHeader(scrollHeader, "昨日增长", 96);
        addHeader(scrollHeader, "近一月增长", 106);
        addHeader(scrollHeader, "操作", 76);
        scrollTable.addView(scrollHeader);

        if (rows.isEmpty()) {
            TableRow fixedEmpty = new TableRow(this);
            TextView nameCell = nameCell("当前没有跟踪的基金。", "");
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
            fixedRow.addView(nameCell(name, row.code));
            fixedTable.addView(fixedRow);

            TableRow scrollRow = new TableRow(this);
            addCell(scrollRow, row.estimateGrowth, 104, toneColor(row.estimateGrowth), Typeface.BOLD);
            addCell(scrollRow, row.estimateValue, 104, COLOR_INK, Typeface.NORMAL);
            if (showExtraColumns) {
                addCell(scrollRow, row.selfEstimateValue, 96, COLOR_MUTED, Typeface.NORMAL);
                addCell(scrollRow, row.selfEstimateGrowth, 96, toneColor(row.selfEstimateGrowth), Typeface.NORMAL);
            }
            addCell(scrollRow, row.publishedGrowth, 96, toneColor(row.publishedGrowth), Typeface.NORMAL);
            addCell(scrollRow, row.monthGrowth, 106, toneColor(row.monthGrowth), Typeface.NORMAL);

            Button deleteButton = iconButton("删", COLOR_DANGER, Color.WHITE);
            deleteButton.setMinWidth(dp(54));
            deleteButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    deleteCode(row.code);
                }
            });
            TableRow.LayoutParams params = new TableRow.LayoutParams(
                    dp(76),
                    dp(ROW_MIN_HEIGHT_DP));
            params.setMargins(0, 0, 0, 0);
            scrollRow.addView(deleteButton, params);
            scrollTable.addView(scrollRow);
        }
    }

    private TextView nameCell(String name, String code) {
        String safeName = FundFormat.orBlank(name);
        String safeCode = FundFormat.hasValue(code) ? code : "";
        String text = safeCode.length() == 0 ? safeName : safeName + "\n" + safeCode;
        SpannableString span = new SpannableString(text);
        if (safeCode.length() > 0) {
            int start = text.length() - safeCode.length();
            span.setSpan(new RelativeSizeSpan(0.78f), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            span.setSpan(new ForegroundColorSpan(COLOR_MUTED), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        TextView view = text("", 13, COLOR_INK, Typeface.BOLD);
        view.setText(span);
        view.setWidth(dp(NAME_COL_WIDTH_DP));
        view.setMaxWidth(dp(NAME_COL_WIDTH_DP));
        view.setMinHeight(dp(ROW_MIN_HEIGHT_DP));
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(dp(10), dp(8), dp(10), dp(8));
        view.setSingleLine(false);
        view.setBackground(rounded(Color.WHITE, Color.rgb(234, 236, 240)));
        return view;
    }

    private TextView headerCell(String text, int widthDp, int gravity) {
        TextView view = cell(text, widthDp, COLOR_MUTED, Typeface.BOLD, gravity);
        view.setBackgroundColor(Color.rgb(248, 250, 252));
        return view;
    }

    private void addHeader(TableRow row, String text, int widthDp) {
        row.addView(headerCell(text, widthDp, Gravity.CENTER));
    }

    private void addCell(TableRow row, String text, int widthDp, int color, int style) {
        row.addView(cell(text, widthDp, color, style, Gravity.CENTER));
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
        render();
        setStatus("已删除 " + code + "。", false);
    }

    private void enterPersonalProfile() {
        String phone = normalizePhone(entryPhoneInput.getText().toString());
        if (!FundFormat.hasValue(phone)) {
            entryPhoneInput.setError("请输入 11 位手机号");
            return;
        }
        storage.setCurrentProfile(phone);
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
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        button.setTextColor(foreground);
        button.setMinHeight(dp(46));
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setBackground(rounded(background, background));
        return button;
    }

    private Button iconButton(String label, int background, int foreground) {
        Button button = button(label, background, foreground);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setMinWidth(dp(44));
        button.setMinHeight(dp(40));
        button.setPadding(dp(6), 0, dp(6), 0);
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

    private int dp(float value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                getResources().getDisplayMetrics());
    }
}

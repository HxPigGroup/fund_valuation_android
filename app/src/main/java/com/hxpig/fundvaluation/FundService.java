package com.hxpig.fundvaluation;

import android.text.Html;
import android.util.JsonReader;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class FundService {
    private static final int TIMEOUT_MS = 9000;
    private static final int ESTIMATION_PAGE_SIZE = 20000;
    private static final int MAX_ESTIMATION_PAGES = 5;
    private static final int SELF_ESTIMATE_HISTORY_DAYS = 5;
    private static final int STOCK_LOOKBACK_DAYS = 20;
    private static final long STOCK_QUOTE_CACHE_MS = 5L * 60L * 1000L;
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android) FundValuationAndroid/1.0";
    private static final long THIRTY_DAYS_MS = 30L * 24L * 60L * 60L * 1000L;
    private static final String ESTIMATION_LIST_URL =
            "https://api.fund.eastmoney.com/FundGuZhi/GetFundGZList";
    private static final String FUND_HOLDINGS_PAGE_URL = "https://fundf10.eastmoney.com/ccmx_%s.html";
    private static final String FUND_HOLDINGS_DATA_URL = "https://fundf10.eastmoney.com/FundArchivesDatas.aspx";
    private static final String STOCK_KLINE_URL = "https://push2his.eastmoney.com/api/qt/stock/kline/get";
    private static final String STOCK_KLINE_REFERER = "https://quote.eastmoney.com/";
    private static final Pattern HOLDINGS_CONTENT_PATTERN =
            Pattern.compile("content:\"((?:\\\\.|[^\\\\\"])*)\"", Pattern.DOTALL);
    private static final Pattern HOLDINGS_ROW_PATTERN =
            Pattern.compile("(?is)<tr[^>]*>(.*?)</tr>");
    private static final Pattern HOLDINGS_CELL_PATTERN =
            Pattern.compile("(?is)<t[dh][^>]*>(.*?)</t[dh]>");

    private final Map<String, StockQuote> stockQuoteCache = new ConcurrentHashMap<>();
    private volatile long stockQuoteCacheUpdatedAt;

    List<FundRow> fetchRows(List<String> codes) {
        List<FundRow> rows = new ArrayList<>();
        Map<String, FundRow> officialEstimates = fetchOfficialEstimateMap(codes);
        int workerCount = Math.min(4, Math.max(1, codes.size()));
        ExecutorService pool = Executors.newFixedThreadPool(workerCount);
        List<Future<FundRow>> futures = new ArrayList<>();
        for (final String code : codes) {
            futures.add(pool.submit(new Callable<FundRow>() {
                @Override
                public FundRow call() {
                    return fetchRow(code, officialEstimates.get(code));
                }
            }));
        }
        for (int i = 0; i < futures.size(); i++) {
            try {
                rows.add(futures.get(i).get());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                rows.add(failedRow(codes.get(i), "刷新被中断"));
            } catch (ExecutionException exception) {
                rows.add(failedRow(codes.get(i), "刷新失败"));
            }
        }
        pool.shutdown();
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
        return rows;
    }

    private FundRow failedRow(String code, String message) {
        FundRow row = new FundRow(code);
        row.name = code;
        row.message = message;
        return row;
    }

    private FundRow fetchRow(String code, FundRow officialEstimate) {
        FundRow row = officialEstimate == null ? new FundRow(code) : officialEstimate;
        List<String> warnings = new ArrayList<>();
        if (!FundFormat.hasValue(row.estimateValue)) {
            try {
                fetchOfficialEstimateFallback(row);
            } catch (IOException | JSONException exception) {
                // Keep going. A missing官方估值 can still be补成自算值.
            }
        }
        NavInfo navInfo = null;
        try {
            navInfo = fetchHistory(row);
        } catch (IOException | JSONException exception) {
            warnings.add("历史净值接口失败");
        }
        if (!FundFormat.hasValue(row.estimateValue) && navInfo != null) {
            try {
                List<HoldingItem> holdings = fetchHoldings(code);
                Map<String, StockQuote> quotes = fetchStockQuotes(holdings);
                SelfEstimate selfEstimate = estimateByHoldings(navInfo, holdings, quotes);
                if (selfEstimate != null) {
                    row.selfEstimateValue = FundFormat.value4(String.valueOf(selfEstimate.value));
                    row.selfEstimateGrowth = FundFormat.percentFromNumber(selfEstimate.growth);
                }
            } catch (RuntimeException exception) {
                warnings.add("自算估值接口失败");
            }
        }
        if (!FundFormat.hasValue(row.name)) {
            row.name = code;
        }
        if (!warnings.isEmpty()) {
            row.message = joinWarnings(warnings);
        }
        return row;
    }

    private Map<String, FundRow> fetchOfficialEstimateMap(List<String> codes) {
        Map<String, FundRow> estimates = new HashMap<>();
        Set<String> missingCodes = new HashSet<>(codes);
        for (int page = 1; page <= MAX_ESTIMATION_PAGES && !missingCodes.isEmpty(); page++) {
            try {
                int itemCount = fetchOfficialEstimatePage(page, missingCodes, estimates);
                if (itemCount < ESTIMATION_PAGE_SIZE) {
                    break;
                }
            } catch (IOException | IllegalStateException ignored) {
                // A list failure should not prevent the per-fund fallback below.
                break;
            }
        }
        return estimates;
    }

    private int fetchOfficialEstimatePage(
            int page,
            Set<String> missingCodes,
            Map<String, FundRow> estimates
    ) throws IOException {
        String url = ESTIMATION_LIST_URL
                + "?type=1&sort=3&orderType=desc&canbuy=0&pageIndex=" + page
                + "&pageSize=" + ESTIMATION_PAGE_SIZE + "&_="
                + System.currentTimeMillis();
        HttpURLConnection connection = openConnection(url, "https://fund.eastmoney.com/");
        try {
            int status = connection.getResponseCode();
            if (status >= 400) {
                throw new IOException("HTTP " + status);
            }
            try (InputStream stream = connection.getInputStream();
                 JsonReader reader = new JsonReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                return readEstimationResponse(reader, missingCodes, estimates);
            }
        } finally {
            connection.disconnect();
        }
    }

    private int readEstimationResponse(
            JsonReader reader,
            Set<String> missingCodes,
            Map<String, FundRow> estimates
    ) throws IOException {
        int itemCount = 0;
        reader.beginObject();
        while (reader.hasNext()) {
            if ("Data".equals(reader.nextName())) {
                itemCount = readEstimationData(reader, missingCodes, estimates);
            } else {
                reader.skipValue();
            }
        }
        reader.endObject();
        return itemCount;
    }

    private int readEstimationData(
            JsonReader reader,
            Set<String> missingCodes,
            Map<String, FundRow> estimates
    ) throws IOException {
        int itemCount = 0;
        reader.beginObject();
        while (reader.hasNext()) {
            if ("list".equals(reader.nextName())) {
                reader.beginArray();
                while (reader.hasNext()) {
                    itemCount++;
                    FundRow row = readEstimationItem(reader, missingCodes);
                    if (row != null) {
                        estimates.put(row.code, row);
                        missingCodes.remove(row.code);
                    }
                }
                reader.endArray();
            } else {
                reader.skipValue();
            }
        }
        reader.endObject();
        return itemCount;
    }

    private FundRow readEstimationItem(JsonReader reader, Set<String> missingCodes) throws IOException {
        String code = "";
        String name = "";
        String estimateValue = "";
        String estimateGrowth = "";
        String publishedNav = "";
        String publishedGrowth = "";
        String estimateDate = "";
        String navDate = "";

        reader.beginObject();
        while (reader.hasNext()) {
            String field = reader.nextName();
            if ("bzdm".equals(field)) {
                code = readString(reader);
            } else if ("jjjc".equals(field)) {
                name = readString(reader);
            } else if ("gsz".equals(field)) {
                estimateValue = readString(reader);
            } else if ("gszzl".equals(field)) {
                estimateGrowth = readString(reader);
            } else if ("dwjz".equals(field)) {
                publishedNav = readString(reader);
            } else if ("jzzzl".equals(field)) {
                publishedGrowth = readString(reader);
            } else if ("gxrq".equals(field)) {
                estimateDate = readString(reader);
            } else if ("gzrq".equals(field)) {
                navDate = readString(reader);
            } else {
                reader.skipValue();
            }
        }
        reader.endObject();

        if (!missingCodes.contains(code) || !FundFormat.hasValue(estimateValue)) {
            return null;
        }
        FundRow row = new FundRow(code);
        row.name = FundFormat.orBlank(name);
        row.estimateValue = FundFormat.value4(estimateValue);
        row.estimateGrowth = FundFormat.percent(estimateGrowth);
        row.publishedNav = FundFormat.value4(publishedNav);
        row.publishedGrowth = FundFormat.percent(publishedGrowth);
        row.estimateTime = estimateDate;
        row.navDate = navDate;
        return row;
    }

    private String readString(JsonReader reader) throws IOException {
        switch (reader.peek()) {
            case NULL:
                reader.nextNull();
                return "";
            case STRING:
                return reader.nextString();
            case NUMBER:
                return reader.nextString();
            default:
                reader.skipValue();
                return "";
        }
    }

    private void fetchOfficialEstimateFallback(FundRow row) throws IOException, JSONException {
        String url = "https://fundgz.1234567.com.cn/js/" + row.code + ".js?rt=" + System.currentTimeMillis();
        String text = get(url, "https://fund.eastmoney.com/");
        JSONObject payload = parseJsonp(text);
        row.name = FundFormat.orBlank(payload.optString("name", row.name));
        row.estimateValue = FundFormat.value4(payload.optString("gsz", ""));
        row.estimateGrowth = FundFormat.percent(payload.optString("gszzl", ""));
        row.publishedNav = FundFormat.value4(payload.optString("dwjz", ""));
        row.estimateTime = payload.optString("gztime", "");
        row.navDate = payload.optString("jzrq", "");
    }

    private NavInfo fetchHistory(FundRow row) throws IOException, JSONException {
        String url = "https://api.fund.eastmoney.com/f10/lsjz?fundCode="
                + row.code
                + "&pageIndex=1&pageSize=30";
        JSONObject root = new JSONObject(get(url, "https://fundf10.eastmoney.com/"));
        JSONObject data = root.optJSONObject("Data");
        JSONArray list = data == null ? null : data.optJSONArray("LSJZList");
        if (list == null || list.length() == 0) {
            return null;
        }

        JSONObject latest = list.getJSONObject(0);
        NavInfo navInfo = new NavInfo();
        String latestNav = latest.optString("DWJZ", "");
        if (FundFormat.hasValue(latestNav)) {
            row.publishedNav = FundFormat.value4(latestNav);
            navInfo.publishedNav = FundFormat.parseNumber(latestNav);
        }
        row.publishedGrowth = FundFormat.percent(latest.optString("JZZZL", ""));
        navInfo.publishedGrowth = FundFormat.parseNumber(latest.optString("JZZZL", ""));
        if (!FundFormat.hasValue(row.navDate)) {
            row.navDate = latest.optString("FSRQ", "");
        }

        Double latestValue = FundFormat.parseNumber(latestNav);
        Double fiveDayStartValue = findFiveDayStartValue(list);
        if (latestValue != null && fiveDayStartValue != null && fiveDayStartValue != 0.0) {
            row.fiveDayGrowth = FundFormat.percentFromNumber((latestValue / fiveDayStartValue - 1.0) * 100.0);
        }
        Double startValue = findMonthStartValue(list, latest.optString("FSRQ", ""));
        if (latestValue != null && startValue != null && startValue != 0.0) {
            row.monthGrowth = FundFormat.percentFromNumber((latestValue / startValue - 1.0) * 100.0);
        }
        navInfo.navHistory = extractNavHistory(list);
        return navInfo;
    }

    private Double findFiveDayStartValue(JSONArray list) {
        if (list == null || list.length() <= 5) {
            return null;
        }
        JSONObject item = list.optJSONObject(5);
        return item == null ? null : FundFormat.parseNumber(item.optString("DWJZ", ""));
    }

    private Double findMonthStartValue(JSONArray list, String latestDateText) {
        Date latestDate = parseDate(latestDateText);
        if (latestDate != null) {
            long cutoff = latestDate.getTime() - THIRTY_DAYS_MS;
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                Date itemDate = parseDate(item.optString("FSRQ", ""));
                if (itemDate != null && itemDate.getTime() <= cutoff) {
                    return FundFormat.parseNumber(item.optString("DWJZ", ""));
                }
            }
        }
        JSONObject fallback = list.optJSONObject(list.length() - 1);
        return fallback == null ? null : FundFormat.parseNumber(fallback.optString("DWJZ", ""));
    }

    private List<Double> extractNavHistory(JSONArray list) {
        List<Double> values = new ArrayList<>();
        if (list == null || list.length() == 0) {
            return values;
        }
        for (int i = list.length() - 1; i >= 0; i--) {
            JSONObject item = list.optJSONObject(i);
            if (item == null) {
                continue;
            }
            Double nav = FundFormat.parseNumber(item.optString("DWJZ", ""));
            if (nav != null) {
                values.add(nav);
            }
        }
        return values;
    }

    private Date parseDate(String value) {
        if (!FundFormat.hasValue(value)) {
            return null;
        }
        try {
            return new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).parse(value);
        } catch (ParseException ignored) {
            return null;
        }
    }

    private Double historyWindowReturn(List<Double> history, int days) {
        if (history == null || history.size() <= days) {
            return null;
        }
        Double latest = history.get(history.size() - 1);
        Double start = history.get(history.size() - (days + 1));
        if (latest == null || start == null || start == 0.0) {
            return null;
        }
        return (latest / start - 1.0) * 100.0;
    }

    private SelfEstimate estimateByHoldings(
            NavInfo navInfo,
            List<HoldingItem> holdings,
            Map<String, StockQuote> quotes
    ) {
        if (navInfo == null || navInfo.publishedNav == null || holdings == null || holdings.isEmpty() || quotes == null || quotes.isEmpty()) {
            return null;
        }

        double currentWeightedReturn = 0.0;
        double historicalWeightedReturn = 0.0;
        double coveredWeight = 0.0;
        int holdingCount = 0;
        int historicalCount = 0;
        Set<String> seen = new HashSet<>();

        for (HoldingItem holding : holdings) {
            if (holdingCount >= 10) {
                break;
            }
            if (holding == null || !FundFormat.hasValue(holding.stockCode) || seen.contains(holding.stockCode)) {
                continue;
            }
            seen.add(holding.stockCode);
            StockQuote quote = quotes.get(holding.stockCode);
            if (quote == null) {
                continue;
            }
            if (holding.weight == null || holding.weight <= 0.0 || quote.pctChange == null) {
                continue;
            }

            currentWeightedReturn += (holding.weight / 100.0) * (quote.pctChange / 100.0);
            coveredWeight += holding.weight;
            holdingCount++;
            if (quote.windowPctChange != null) {
                historicalWeightedReturn += (holding.weight / 100.0) * (quote.windowPctChange / 100.0);
                historicalCount++;
            }
        }

        if (holdingCount == 0 || coveredWeight <= 0.0) {
            return null;
        }

        double scale = 1.0;
        Double fundWindowReturn = historyWindowReturn(navInfo.navHistory, SELF_ESTIMATE_HISTORY_DAYS);
        if (
                fundWindowReturn != null
                        && historicalCount > 0
                        && Math.abs(historicalWeightedReturn) > 1e-6
        ) {
            double rawScale = (fundWindowReturn / 100.0) / historicalWeightedReturn;
            scale = clamp(rawScale, 0.3, 1.7);
            double confidence = Math.min(1.0, coveredWeight / 100.0);
            scale = 1.0 + (scale - 1.0) * confidence;
        }

        SelfEstimate estimate = new SelfEstimate();
        estimate.value = navInfo.publishedNav * (1.0 + currentWeightedReturn * scale);
        estimate.growth = currentWeightedReturn * scale * 100.0;
        estimate.coverage = coveredWeight;
        estimate.holdingCount = holdingCount;
        return estimate;
    }

    private List<HoldingItem> fetchHoldings(String code) {
        int currentYear = Calendar.getInstance().get(Calendar.YEAR);
        String[] years = new String[]{
                String.valueOf(currentYear),
                String.valueOf(currentYear - 1),
                String.valueOf(currentYear - 2)
        };
        for (String year : years) {
            List<HoldingItem> items = fetchHoldingsYear(code, year);
            if (!items.isEmpty()) {
                return items;
            }
        }
        return new ArrayList<>();
    }

    private List<HoldingItem> fetchHoldingsYear(String code, String year) {
        HttpURLConnection pageConnection = null;
        HttpURLConnection dataConnection = null;
        try {
            String pageUrl = String.format(Locale.US, FUND_HOLDINGS_PAGE_URL, code);
            pageConnection = openConnection(
                    pageUrl,
                    "https://fundf10.eastmoney.com/",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            );
            pageConnection.getResponseCode();
            String cookie = extractCookieHeader(pageConnection);
            if (!FundFormat.hasValue(cookie)) {
                return new ArrayList<>();
            }

            String dataUrl = FUND_HOLDINGS_DATA_URL
                    + "?type=jjcc&code=" + code
                    + "&topline=10000&year=" + year
                    + "&month=&rt=" + System.currentTimeMillis();
            dataConnection = openConnection(dataUrl, pageUrl);
            dataConnection.setRequestProperty("Cookie", cookie);
            int status = dataConnection.getResponseCode();
            InputStream stream = status >= 400 ? dataConnection.getErrorStream() : dataConnection.getInputStream();
            String text = readStream(stream);
            if (status >= 400 || !FundFormat.hasValue(text)) {
                return new ArrayList<>();
            }
            String content = extractHoldingsContent(text);
            if (!FundFormat.hasValue(content)) {
                return new ArrayList<>();
            }
            return parseHoldingsTable(content);
        } catch (IOException ignored) {
            return new ArrayList<>();
        } finally {
            if (dataConnection != null) {
                dataConnection.disconnect();
            }
            if (pageConnection != null) {
                pageConnection.disconnect();
            }
        }
    }

    private String extractHoldingsContent(String text) {
        Matcher matcher = HOLDINGS_CONTENT_PATTERN.matcher(text);
        if (!matcher.find()) {
            return "";
        }
        return decodeJsString(matcher.group(1));
    }

    private List<HoldingItem> parseHoldingsTable(String html) {
        List<List<String>> rows = extractHoldingsRows(html);
        List<HoldingItem> items = new ArrayList<>();
        List<String> headers = null;
        for (List<String> cells : rows) {
            if (cells.isEmpty()) {
                continue;
            }
            if (headers == null) {
                if (isHoldingsHeader(cells)) {
                    headers = cells;
                }
                continue;
            }
            HoldingItem item = parseHoldingRow(headers, cells);
            if (item != null) {
                items.add(item);
            }
        }
        Collections.sort(items, new Comparator<HoldingItem>() {
            @Override
            public int compare(HoldingItem left, HoldingItem right) {
                return Double.compare(right.weight, left.weight);
            }
        });
        return items;
    }

    private List<List<String>> extractHoldingsRows(String html) {
        List<List<String>> rows = new ArrayList<>();
        Matcher rowMatcher = HOLDINGS_ROW_PATTERN.matcher(html);
        while (rowMatcher.find()) {
            rows.add(extractHoldingsCells(rowMatcher.group(1)));
        }
        return rows;
    }

    private List<String> extractHoldingsCells(String rowHtml) {
        List<String> cells = new ArrayList<>();
        Matcher cellMatcher = HOLDINGS_CELL_PATTERN.matcher(rowHtml);
        while (cellMatcher.find()) {
            cells.add(normalizeHoldingsText(cellMatcher.group(1)));
        }
        return cells;
    }

    private boolean isHoldingsHeader(List<String> cells) {
        return findColumnIndex(cells, "股票代码", "代码") >= 0 && findColumnIndex(cells, "占净值比例", "占净值比") >= 0;
    }

    private HoldingItem parseHoldingRow(List<String> headers, List<String> cells) {
        int codeIndex = findColumnIndex(headers, "股票代码", "代码");
        int nameIndex = findColumnIndex(headers, "股票名称", "名称");
        int weightIndex = findColumnIndex(headers, "占净值比例", "占净值比");
        if (codeIndex < 0 || weightIndex < 0) {
            return null;
        }
        String stockCode = FundStorage.normalizeCode(cellAt(cells, codeIndex));
        Double weight = FundFormat.parseNumber(cellAt(cells, weightIndex));
        if (!FundFormat.hasValue(stockCode) || weight == null || weight <= 0.0) {
            return null;
        }
        HoldingItem item = new HoldingItem();
        item.stockCode = stockCode;
        item.stockName = cellAt(cells, nameIndex);
        item.weight = weight;
        return item;
    }

    private int findColumnIndex(List<String> headers, String... keywords) {
        if (headers == null || headers.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < headers.size(); i++) {
            String normalized = normalizeHeaderText(headers.get(i));
            for (String keyword : keywords) {
                if (normalized.contains(normalizeHeaderText(keyword))) {
                    return i;
                }
            }
        }
        return -1;
    }

    private Map<String, StockQuote> fetchStockQuotes(List<HoldingItem> holdings) {
        Map<String, StockQuote> quotes = new HashMap<>();
        if (holdings == null || holdings.isEmpty()) {
            return quotes;
        }

        Set<String> needed = new HashSet<>();
        for (HoldingItem holding : holdings) {
            if (holding == null || !FundFormat.hasValue(holding.stockCode)) {
                continue;
            }
            needed.add(holding.stockCode);
            if (needed.size() >= 10) {
                break;
            }
        }

        long now = System.currentTimeMillis();
        boolean cacheFresh = now - stockQuoteCacheUpdatedAt < STOCK_QUOTE_CACHE_MS;
        for (String code : needed) {
            if (cacheFresh) {
                StockQuote cached = stockQuoteCache.get(code);
                if (cached != null) {
                    quotes.put(code, cached);
                }
            }
        }
        for (String code : needed) {
            if (quotes.containsKey(code)) {
                continue;
            }
            StockQuote quote = fetchStockQuote(code);
            if (quote != null) {
                quotes.put(code, quote);
                stockQuoteCache.put(code, quote);
                stockQuoteCacheUpdatedAt = System.currentTimeMillis();
                continue;
            }
            StockQuote cached = stockQuoteCache.get(code);
            if (cached != null) {
                quotes.put(code, cached);
            }
        }
        return quotes;
    }

    private StockQuote fetchStockQuote(String code) {
        String secid = stockSecid(code);
        if (!FundFormat.hasValue(secid)) {
            return null;
        }
        String url = buildStockKlineUrl(secid);
        try {
            String body = get(url, STOCK_KLINE_REFERER);
            JSONObject root = new JSONObject(body);
            JSONObject data = root.optJSONObject("data");
            JSONArray klines = data == null ? null : data.optJSONArray("klines");
            if (klines == null || klines.length() == 0) {
                return null;
            }
            String latestLine = klines.optString(klines.length() - 1, "");
            String[] latestParts = latestLine.split(",");
            if (latestParts.length < 9) {
                return null;
            }

            StockQuote quote = new StockQuote();
            quote.pctChange = FundFormat.parseNumber(latestParts[8]);
            if (klines.length() > SELF_ESTIMATE_HISTORY_DAYS) {
                String startLine = klines.optString(klines.length() - 1 - SELF_ESTIMATE_HISTORY_DAYS, "");
                String[] startParts = startLine.split(",");
                if (latestParts.length > 2 && startParts.length > 2) {
                    Double latestClose = FundFormat.parseNumber(latestParts[2]);
                    Double startClose = FundFormat.parseNumber(startParts[2]);
                    if (latestClose != null && startClose != null && startClose != 0.0) {
                        quote.windowPctChange = (latestClose / startClose - 1.0) * 100.0;
                    }
                }
            }
            quote.tradeDate = latestParts[0];
            return quote;
        } catch (IOException | JSONException exception) {
            return null;
        }
    }

    private String buildStockKlineUrl(String secid) {
        String end = todayText();
        String beg = daysAgoText(STOCK_LOOKBACK_DAYS);
        return STOCK_KLINE_URL
                + "?fields1=f1,f2,f3,f4,f5,f6"
                + "&fields2=f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61"
                + "&ut=7eea3edcaed734bea9cbfc24409ed989"
                + "&klt=101&fqt=1"
                + "&beg=" + beg
                + "&end=" + end
                + "&secid=" + secid;
    }

    private String stockSecid(String code) {
        if (!FundFormat.hasValue(code)) {
            return "";
        }
        char first = code.charAt(0);
        int market = (first == '5' || first == '6' || first == '9') ? 1 : 0;
        return market + "." + code;
    }

    private String todayText() {
        return new SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(new Date());
    }

    private String daysAgoText(int days) {
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_YEAR, -days);
        return new SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(calendar.getTime());
    }

    private String extractCookieHeader(HttpURLConnection connection) {
        Map<String, List<String>> headers = connection.getHeaderFields();
        if (headers == null || headers.isEmpty()) {
            return "";
        }
        List<String> cookies = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() == null || !"Set-Cookie".equalsIgnoreCase(entry.getKey())) {
                continue;
            }
            List<String> values = entry.getValue();
            if (values == null) {
                continue;
            }
            for (String value : values) {
                if (!FundFormat.hasValue(value)) {
                    continue;
                }
                int end = value.indexOf(';');
                cookies.add(end >= 0 ? value.substring(0, end) : value);
            }
        }
        if (cookies.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String cookie : cookies) {
            if (builder.length() > 0) {
                builder.append("; ");
            }
            builder.append(cookie.trim());
        }
        return builder.toString();
    }

    private String decodeJsString(String value) {
        if (value == null || value.length() == 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder(value.length());
        boolean escaping = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (escaping) {
                if (ch == 'u' && i + 4 < value.length()) {
                    String hex = value.substring(i + 1, i + 5);
                    try {
                        builder.append((char) Integer.parseInt(hex, 16));
                        i += 4;
                    } catch (NumberFormatException exception) {
                        builder.append(ch);
                    }
                } else if (ch == 'n') {
                    builder.append('\n');
                } else if (ch == 'r') {
                    builder.append('\r');
                } else if (ch == 't') {
                    builder.append('\t');
                } else if (ch == '\\' || ch == '"' || ch == '/') {
                    builder.append(ch);
                } else {
                    builder.append(ch);
                }
                escaping = false;
                continue;
            }
            if (ch == '\\') {
                escaping = true;
            } else {
                builder.append(ch);
            }
        }
        if (escaping) {
            builder.append('\\');
        }
        return builder.toString();
    }

    private String htmlText(String value) {
        return Html.fromHtml(value == null ? "" : value).toString();
    }

    private String normalizeHoldingsText(String value) {
        return htmlText(value)
                .replace('\u00a0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String normalizeHeaderText(String value) {
        return normalizeHoldingsText(value).replaceAll("\\s+", "");
    }

    private String cellAt(List<String> cells, int index) {
        if (cells == null || index < 0 || index >= cells.size()) {
            return "";
        }
        return cells.get(index);
    }

    private double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }

    private JSONObject parseJsonp(String text) throws JSONException {
        int start = text.indexOf('(');
        int end = text.lastIndexOf(')');
        if (start < 0 || end <= start) {
            throw new JSONException("Invalid JSONP payload");
        }
        return new JSONObject(text.substring(start + 1, end));
    }

    private String get(String urlText, String referer) throws IOException {
        HttpURLConnection connection = openConnection(urlText, referer);
        try {
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String body = readStream(stream);
            if (status >= 400) {
                throw new IOException("HTTP " + status + ": " + body);
            }
            return body;
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection openConnection(String urlText, String referer) throws IOException {
        return openConnection(urlText, referer, "application/json,text/javascript,*/*;q=0.8");
    }

    private HttpURLConnection openConnection(String urlText, String referer, String accept) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlText).openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", accept);
        connection.setRequestProperty("Referer", referer);
        return connection;
    }

    private String readStream(InputStream stream) throws IOException {
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

    private static final class NavInfo {
        Double publishedNav;
        Double publishedGrowth;
        List<Double> navHistory = new ArrayList<>();
    }

    private static final class HoldingItem {
        String stockCode = "";
        String stockName = "";
        Double weight;
    }

    private static final class StockQuote {
        Double pctChange;
        Double windowPctChange;
        String tradeDate = "";
    }

    private static final class SelfEstimate {
        Double value;
        Double growth;
        Double coverage;
        int holdingCount;
    }

    private String joinWarnings(List<String> warnings) {
        StringBuilder builder = new StringBuilder();
        for (String warning : warnings) {
            if (builder.length() > 0) {
                builder.append("；");
            }
            builder.append(warning);
        }
        return builder.toString();
    }
}

package com.hxpig.fundvaluation;

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
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

final class FundService {
    private static final int TIMEOUT_MS = 9000;
    private static final int ESTIMATION_PAGE_SIZE = 20000;
    private static final int MAX_ESTIMATION_PAGES = 5;
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android) FundValuationAndroid/1.0";
    private static final long THIRTY_DAYS_MS = 30L * 24L * 60L * 60L * 1000L;
    private static final String ESTIMATION_LIST_URL =
            "https://api.fund.eastmoney.com/FundGuZhi/GetFundGZList";

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
                warnings.add("官方估算暂无数据");
            }
        }
        try {
            fetchHistory(row);
        } catch (IOException | JSONException exception) {
            warnings.add("历史净值接口失败");
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

    private void fetchHistory(FundRow row) throws IOException, JSONException {
        String url = "https://api.fund.eastmoney.com/f10/lsjz?fundCode="
                + row.code
                + "&pageIndex=1&pageSize=30";
        JSONObject root = new JSONObject(get(url, "https://fundf10.eastmoney.com/"));
        JSONObject data = root.optJSONObject("Data");
        JSONArray list = data == null ? null : data.optJSONArray("LSJZList");
        if (list == null || list.length() == 0) {
            return;
        }

        JSONObject latest = list.getJSONObject(0);
        String latestNav = latest.optString("DWJZ", "");
        if (FundFormat.hasValue(latestNav)) {
            row.publishedNav = FundFormat.value4(latestNav);
        }
        row.publishedGrowth = FundFormat.percent(latest.optString("JZZZL", ""));
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
        HttpURLConnection connection = (HttpURLConnection) new URL(urlText).openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", "application/json,text/javascript,*/*;q=0.8");
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

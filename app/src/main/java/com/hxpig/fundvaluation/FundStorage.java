package com.hxpig.fundvaluation;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class FundStorage {
    private static final String PREFS_NAME = "fund_valuation";
    private static final String KEY_PROFILE = "active_profile";
    private static final String KEY_RECENT_PROFILES = "recent_profiles";
    private static final String PUBLIC_PROFILE = "";

    private final Context context;
    private final SharedPreferences prefs;

    FundStorage(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        seedPublicProfileIfNeeded();
    }

    String getCurrentProfile() {
        return prefs.getString(KEY_PROFILE, PUBLIC_PROFILE);
    }

    void setCurrentProfile(String profile) {
        prefs.edit().putString(KEY_PROFILE, profile == null ? PUBLIC_PROFILE : profile).apply();
    }

    void rememberProfile(String profile) {
        if (!FundFormat.hasValue(profile)) {
            return;
        }
        List<String> recent = getRecentProfiles();
        recent.remove(profile);
        recent.add(0, profile);
        while (recent.size() > 5) {
            recent.remove(recent.size() - 1);
        }
        StringBuilder builder = new StringBuilder();
        for (String item : recent) {
            builder.append(item).append('\n');
        }
        prefs.edit()
                .putString(KEY_RECENT_PROFILES, builder.toString())
                .putString(KEY_PROFILE, profile)
                .apply();
    }

    List<String> getRecentProfiles() {
        return parsePhones(prefs.getString(KEY_RECENT_PROFILES, ""));
    }

    List<String> getCodes(String profile) {
        String raw = prefs.getString(codesKey(profile), "");
        return parseCodes(raw);
    }

    void saveCodes(String profile, List<String> codes) {
        StringBuilder builder = new StringBuilder();
        for (String code : uniqueCodes(codes)) {
            builder.append(code).append('\n');
        }
        prefs.edit().putString(codesKey(profile), builder.toString()).apply();
    }

    Map<String, FundRow> getCachedRows(String profile) {
        Map<String, FundRow> rows = new LinkedHashMap<>();
        String raw = prefs.getString(rowsKey(profile), "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) {
                    continue;
                }
                FundRow row = FundRow.fromJson(object);
                if (FundFormat.hasValue(row.code)) {
                    rows.put(row.code, row);
                }
            }
        } catch (JSONException ignored) {
        }
        return rows;
    }

    void saveRows(String profile, List<FundRow> rows) {
        JSONArray array = new JSONArray();
        for (FundRow row : rows) {
            try {
                array.put(row.toJson());
            } catch (JSONException ignored) {
            }
        }
        prefs.edit().putString(rowsKey(profile), array.toString()).apply();
    }

    String getUpdatedAt(String profile) {
        return prefs.getString(updatedKey(profile), "");
    }

    void saveUpdatedAt(String profile, String value) {
        prefs.edit().putString(updatedKey(profile), value).apply();
    }

    List<FundAlert> getAlerts(String profile) {
        List<FundAlert> alerts = new ArrayList<>();
        String raw = prefs.getString(alertsKey(profile), "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) {
                    continue;
                }
                FundAlert alert = FundAlert.fromJson(object);
                if (FundFormat.hasValue(alert.code) && alert.threshold > 0) {
                    alerts.add(alert);
                }
            }
        } catch (JSONException ignored) {
        }
        return alerts;
    }

    void saveAlerts(String profile, List<FundAlert> alerts) {
        JSONArray array = new JSONArray();
        for (FundAlert alert : alerts) {
            try {
                array.put(alert.toJson());
            } catch (JSONException ignored) {
            }
        }
        prefs.edit().putString(alertsKey(profile), array.toString()).apply();
    }

    void upsertAlert(String profile, FundAlert nextAlert) {
        List<FundAlert> alerts = getAlerts(profile);
        boolean updated = false;
        for (int i = 0; i < alerts.size(); i++) {
            FundAlert item = alerts.get(i);
            if (item.key().equals(nextAlert.key())) {
                alerts.set(i, nextAlert);
                updated = true;
                break;
            }
        }
        if (!updated) {
            alerts.add(nextAlert);
        }
        saveAlerts(profile, alerts);
    }

    void deleteAlertsForCode(String profile, String code) {
        List<FundAlert> alerts = getAlerts(profile);
        List<FundAlert> kept = new ArrayList<>();
        for (FundAlert alert : alerts) {
            if (!code.equals(alert.code)) {
                kept.add(alert);
            }
        }
        saveAlerts(profile, kept);
    }

    int getTriggeredAlertCount(String profile) {
        int count = 0;
        for (FundAlert alert : getAlerts(profile)) {
            if (alert.triggered) {
                count++;
            }
        }
        return count;
    }

    void evaluateAlerts(String profile, Map<String, FundRow> rows) {
        List<FundAlert> alerts = getAlerts(profile);
        if (alerts.isEmpty()) {
            return;
        }
        String now = FundFormat.nowText();
        for (FundAlert alert : alerts) {
            FundRow row = rows.get(alert.code);
            Double growth = row == null ? null : FundFormat.parseNumber(row.estimateGrowth);
            if (row != null && FundFormat.hasValue(row.name)) {
                alert.fundName = row.name;
            }
            alert.evaluatedAt = now;
            if (growth == null) {
                alert.latestGrowth = Double.NaN;
                alert.triggered = false;
                continue;
            }
            alert.latestGrowth = growth;
            boolean triggered = FundAlert.DIRECTION_UP.equals(alert.direction)
                    ? growth >= alert.threshold
                    : growth <= -alert.threshold;
            alert.triggered = triggered;
            alert.triggeredAt = triggered ? now : "";
        }
        saveAlerts(profile, alerts);
    }

    private void seedPublicProfileIfNeeded() {
        String key = codesKey(PUBLIC_PROFILE);
        if (prefs.contains(key)) {
            return;
        }
        prefs.edit().putString(key, readDefaultCodes()).apply();
    }

    private String readDefaultCodes() {
        StringBuilder builder = new StringBuilder();
        try (InputStream stream = context.getResources().openRawResource(R.raw.default_tracked_funds);
             BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String code = normalizeCode(line);
                if (FundFormat.hasValue(code)) {
                    builder.append(code).append('\n');
                }
            }
        } catch (IOException ignored) {
        }
        return builder.toString();
    }

    private static List<String> parseCodes(String raw) {
        List<String> codes = new ArrayList<>();
        if (raw == null) {
            return codes;
        }
        String[] lines = raw.split("[\\r\\n]+");
        for (String line : lines) {
            String code = normalizeCode(line);
            if (FundFormat.hasValue(code) && !codes.contains(code)) {
                codes.add(code);
            }
        }
        return codes;
    }

    private static List<String> uniqueCodes(List<String> input) {
        List<String> codes = new ArrayList<>();
        for (String item : input) {
            String code = normalizeCode(item);
            if (FundFormat.hasValue(code) && !codes.contains(code)) {
                codes.add(code);
            }
        }
        return codes;
    }

    static String normalizeCode(String value) {
        if (value == null) {
            return "";
        }
        String digits = value.replaceAll("\\D", "");
        if (digits.length() == 0 || digits.length() > 6) {
            return "";
        }
        while (digits.length() < 6) {
            digits = "0" + digits;
        }
        return digits;
    }

    static String normalizePhone(String value) {
        if (value == null) {
            return "";
        }
        String digits = value.replaceAll("\\D", "");
        return digits.length() == 11 ? digits : "";
    }

    private static List<String> parsePhones(String raw) {
        List<String> phones = new ArrayList<>();
        if (raw == null) {
            return phones;
        }
        String[] lines = raw.split("[\\r\\n]+");
        for (String line : lines) {
            String phone = normalizePhone(line);
            if (FundFormat.hasValue(phone) && !phones.contains(phone)) {
                phones.add(phone);
            }
        }
        return phones;
    }

    private static String profileKey(String profile) {
        if (!FundFormat.hasValue(profile)) {
            return "public";
        }
        return "phone_" + profile;
    }

    private static String codesKey(String profile) {
        return "codes_" + profileKey(profile);
    }

    private static String rowsKey(String profile) {
        return "rows_" + profileKey(profile);
    }

    private static String updatedKey(String profile) {
        return "updated_" + profileKey(profile);
    }

    private static String alertsKey(String profile) {
        return "alerts_" + profileKey(profile);
    }
}

package com.hxpig.fundvaluation;

import org.json.JSONException;
import org.json.JSONObject;

final class FundAlert {
    static final String DIRECTION_UP = "up";
    static final String DIRECTION_DOWN = "down";

    final String code;
    final String direction;
    double threshold;
    String fundName = FundFormat.BLANK;
    double latestGrowth = Double.NaN;
    boolean triggered;
    String evaluatedAt = "";
    String triggeredAt = "";

    FundAlert(String code, String direction, double threshold) {
        this.code = code;
        this.direction = direction;
        this.threshold = threshold;
    }

    String key() {
        return code + ":" + direction;
    }

    String directionText() {
        return DIRECTION_UP.equals(direction) ? "涨" : "跌";
    }

    String targetText() {
        return String.format(java.util.Locale.CHINA, "%s %.2f%%", directionText(), threshold);
    }

    JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("code", code);
        object.put("direction", direction);
        object.put("threshold", threshold);
        object.put("fundName", fundName);
        object.put("latestGrowth", Double.isNaN(latestGrowth) ? JSONObject.NULL : latestGrowth);
        object.put("triggered", triggered);
        object.put("evaluatedAt", evaluatedAt);
        object.put("triggeredAt", triggeredAt);
        return object;
    }

    static FundAlert fromJson(JSONObject object) {
        FundAlert alert = new FundAlert(
                object.optString("code", ""),
                object.optString("direction", DIRECTION_UP),
                object.optDouble("threshold", 0.0));
        alert.fundName = object.optString("fundName", FundFormat.BLANK);
        alert.latestGrowth = object.has("latestGrowth") && !object.isNull("latestGrowth")
                ? object.optDouble("latestGrowth", Double.NaN)
                : Double.NaN;
        alert.triggered = object.optBoolean("triggered", false);
        alert.evaluatedAt = object.optString("evaluatedAt", "");
        alert.triggeredAt = object.optString("triggeredAt", "");
        return alert;
    }
}

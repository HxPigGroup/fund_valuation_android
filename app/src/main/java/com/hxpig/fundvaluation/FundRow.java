package com.hxpig.fundvaluation;

import org.json.JSONException;
import org.json.JSONObject;

final class FundRow {
    final String code;
    String name = FundFormat.BLANK;
    String estimateValue = FundFormat.BLANK;
    String estimateGrowth = FundFormat.BLANK;
    String publishedNav = FundFormat.BLANK;
    String publishedGrowth = FundFormat.BLANK;
    String fiveDayGrowth = FundFormat.BLANK;
    String monthGrowth = FundFormat.BLANK;
    String selfEstimateValue = FundFormat.BLANK;
    String selfEstimateGrowth = FundFormat.BLANK;
    String estimateTime = "";
    String navDate = "";
    String message = "";

    FundRow(String code) {
        this.code = code;
    }

    JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("code", code);
        object.put("name", name);
        object.put("estimateValue", estimateValue);
        object.put("estimateGrowth", estimateGrowth);
        object.put("publishedNav", publishedNav);
        object.put("publishedGrowth", publishedGrowth);
        object.put("fiveDayGrowth", fiveDayGrowth);
        object.put("monthGrowth", monthGrowth);
        object.put("selfEstimateValue", selfEstimateValue);
        object.put("selfEstimateGrowth", selfEstimateGrowth);
        object.put("estimateTime", estimateTime);
        object.put("navDate", navDate);
        object.put("message", message);
        return object;
    }

    static FundRow fromJson(JSONObject object) {
        FundRow row = new FundRow(object.optString("code", ""));
        row.name = object.optString("name", FundFormat.BLANK);
        row.estimateValue = object.optString("estimateValue", FundFormat.BLANK);
        row.estimateGrowth = object.optString("estimateGrowth", FundFormat.BLANK);
        row.publishedNav = object.optString("publishedNav", FundFormat.BLANK);
        row.publishedGrowth = object.optString("publishedGrowth", FundFormat.BLANK);
        row.fiveDayGrowth = object.optString("fiveDayGrowth", FundFormat.BLANK);
        row.monthGrowth = object.optString("monthGrowth", FundFormat.BLANK);
        row.selfEstimateValue = object.optString("selfEstimateValue", FundFormat.BLANK);
        row.selfEstimateGrowth = object.optString("selfEstimateGrowth", FundFormat.BLANK);
        row.estimateTime = object.optString("estimateTime", "");
        row.navDate = object.optString("navDate", "");
        row.message = object.optString("message", "");
        return row;
    }
}

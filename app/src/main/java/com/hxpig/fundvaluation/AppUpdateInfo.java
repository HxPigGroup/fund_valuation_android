package com.hxpig.fundvaluation;

import org.json.JSONException;
import org.json.JSONObject;

final class AppUpdateInfo {
    final int versionCode;
    final String versionName;
    final String apkUrl;
    final String notes;

    AppUpdateInfo(int versionCode, String versionName, String apkUrl, String notes) {
        this.versionCode = versionCode;
        this.versionName = versionName;
        this.apkUrl = apkUrl;
        this.notes = notes;
    }

    static AppUpdateInfo fromJson(String raw) throws JSONException {
        JSONObject object = new JSONObject(raw);
        return new AppUpdateInfo(
                object.optInt("versionCode", 0),
                object.optString("versionName", ""),
                object.optString("apkUrl", ""),
                object.optString("notes", ""));
    }
}

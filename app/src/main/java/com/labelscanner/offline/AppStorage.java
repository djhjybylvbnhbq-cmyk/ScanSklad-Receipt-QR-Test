package com.labelscanner.offline;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

public final class AppStorage {
    private static final String PREFS = "label_scanner_state";
    private static final String KEY_RESULTS = "results";
    private static final String KEY_SCREEN = "screen";
    private static final String KEY_ARRIVAL = "arrival";
    private static final String KEY_CODE = "code";
    private static final String KEY_BOXES = "boxes";
    private static final String KEY_QUANTITY_UNIT = "quantity_unit";

    private final SharedPreferences prefs;

    public AppStorage(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public List<String> loadResults() {
        List<String> out = new ArrayList<>();
        String raw = prefs.getString(KEY_RESULTS, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                String value = array.optString(i, "").trim();
                if (!value.isEmpty()) out.add(value);
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    public void saveResults(List<String> results) {
        JSONArray array = new JSONArray();
        for (String result : results) array.put(result);
        prefs.edit().putString(KEY_RESULTS, array.toString()).apply();
    }

    public String loadScreen(String fallback) {
        return prefs.getString(KEY_SCREEN, fallback);
    }

    public void saveProgress(String screen, String arrival, String code, String boxes, String unit) {
        prefs.edit()
                .putString(KEY_SCREEN, screen)
                .putString(KEY_ARRIVAL, arrival == null ? "" : arrival)
                .putString(KEY_CODE, code == null ? "" : code)
                .putString(KEY_BOXES, boxes == null ? "" : boxes)
                .putString(KEY_QUANTITY_UNIT, ResultLine.normalizeUnit(unit))
                .apply();
    }

    public String loadArrival() { return prefs.getString(KEY_ARRIVAL, ""); }
    public String loadCode() { return prefs.getString(KEY_CODE, ""); }
    public String loadBoxes() { return prefs.getString(KEY_BOXES, ""); }
    public String loadQuantityUnit() { return prefs.getString(KEY_QUANTITY_UNIT, ResultLine.UNIT_PIECES); }

    public void clearAll() {
        prefs.edit().clear().apply();
    }
}

package com.gdrecorder.android;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class RunRecord {
    String id = UUID.randomUUID().toString();
    String level;
    String uri;
    String filePath;
    int startPercent;
    int best;
    boolean startpos;
    long durationMs;
    long recordedAt;
    List<RunLibrary.Point> markers = new ArrayList<>();

    JSONObject toJson() throws Exception {
        JSONObject object = new JSONObject();
        object.put("id", id); object.put("level", level); object.put("uri", uri);
        object.put("filePath", filePath); object.put("startPercent", startPercent);
        object.put("best", best); object.put("startpos", startpos);
        object.put("durationMs", durationMs); object.put("recordedAt", recordedAt);
        JSONArray points = new JSONArray();
        for (RunLibrary.Point point : markers) {
            JSONObject value = new JSONObject(); value.put("timeMs", point.timeMs); value.put("percent", point.percent); points.put(value);
        }
        object.put("markers", points);
        return object;
    }

    static RunRecord fromJson(JSONObject object) throws Exception {
        RunRecord run = new RunRecord();
        run.id = object.optString("id", UUID.randomUUID().toString());
        run.level = object.optString("level", "Unknown level");
        run.uri = object.optString("uri", ""); run.filePath = object.optString("filePath", "");
        run.startPercent = object.optInt("startPercent", 0); run.best = object.optInt("best", 0);
        run.startpos = object.optBoolean("startpos", false); run.durationMs = object.optLong("durationMs", 0);
        run.recordedAt = object.optLong("recordedAt", 0);
        JSONArray points = object.optJSONArray("markers");
        if (points != null) for (int i = 0; i < points.length(); i++) {
            JSONObject point = points.getJSONObject(i);
            run.markers.add(new RunLibrary.Point(point.optLong("timeMs"), point.optInt("percent")));
        }
        return run;
    }
}

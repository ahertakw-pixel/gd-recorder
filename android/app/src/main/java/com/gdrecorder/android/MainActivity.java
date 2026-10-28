package com.gdrecorder.android;

import android.annotation.SuppressLint;
import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressLint("SetTextI18n")
public final class MainActivity extends Activity {
    private static final int REQUEST_CAPTURE = 721;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<RunRecord> runs = new ArrayList<>();
    private EditText levelInput, startInput, queryInput;
    private CheckBox startposInput;
    private TextView status, runList;
    private Button startButton, stopButton;
    private String pendingLevel;
    private int pendingStart;
    private boolean pendingStartpos;
    private boolean receiverRegistered;
    private boolean analysisInProgress;

    private final BroadcastReceiver captureReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String type = intent.getStringExtra(ScreenRecorderService.EVENT_TYPE);
            String message = intent.getStringExtra("message");
            if ("started".equals(type)) {
                status.setText(message);
                startButton.setEnabled(false);
                stopButton.setEnabled(true);
            } else if ("saved".equals(type)) {
                status.setText("Recording saved · detecting percentages and splitting runs…");
                startButton.setEnabled(true);
                stopButton.setEnabled(false);
                consumePendingRecording();
            } else if ("error".equals(type)) {
                status.setText("Capture error · " + message);
                startButton.setEnabled(true);
                stopButton.setEnabled(false);
                new AlertDialog.Builder(MainActivity.this).setTitle("Recorder").setMessage(message).setPositiveButton("OK", null).show();
            }
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xFF10141F);
        getWindow().setNavigationBarColor(0xFF10141F);
        buildUi();
        loadRuns();
        renderRuns();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 731);
        }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(22), dp(20), dp(28));
        page.setBackgroundColor(0xFF10141F);
        scroll.addView(page);
        if (Build.VERSION.SDK_INT >= 35) {
            scroll.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets systemBars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(dp(20), dp(22) + systemBars.top, dp(20), dp(28) + systemBars.bottom);
                return insets;
            });
        }

        TextView title = text("GD Recorder", 28, 0xFFFFFFFF, true);
        page.addView(title);
        page.addView(text("Android screen capture · run library", 14, 0xFFADB6C8, false));
        page.addView(gap(18));
        page.addView(text("RECORD A RUN", 13, 0xFF52D6A0, true));
        levelInput = input("Level name");
        page.addView(levelInput, matchWrap());
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        startInput = input("Starting %");
        startInput.setText("0");
        row.addView(startInput, weight(1));
        startposInput = new CheckBox(this);
        startposInput.setText("Startpos");
        startposInput.setTextColor(0xFFFFFFFF);
        row.addView(startposInput);
        page.addView(row);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        startButton = button("Start screen capture");
        stopButton = button("Stop + split runs");
        stopButton.setEnabled(false);
        buttons.addView(startButton, weight(1));
        buttons.addView(stopButton, weight(1));
        page.addView(buttons);
        status = text("Android will ask for screen-capture permission each session. After starting, switch to Geometry Dash; stop from here or the recording notification.", 13, 0xFFADB6C8, false);
        page.addView(status);
        page.addView(gap(22));

        page.addView(text("FIND A PERCENT OR PLAY A RANGE", 13, 0xFF52D6A0, true));
        queryInput = input("For example: 23 or 5-23");
        LinearLayout queryRow = new LinearLayout(this);
        queryRow.addView(queryInput, weight(1));
        Button find = button("Find / play");
        queryRow.addView(find);
        page.addView(queryRow);
        page.addView(text("Ranges require Startpos attempts. If multiple runs match, choose a run number.", 12, 0xFFADB6C8, false));
        page.addView(gap(20));
        page.addView(text("RUNS & BESTS", 13, 0xFF52D6A0, true));
        runList = text("No runs saved yet.", 14, 0xFFFFFFFF, false);
        page.addView(runList);
        setContentView(scroll);

        startButton.setOnClickListener(v -> requestCapture());
        stopButton.setOnClickListener(v -> stopCapture());
        find.setOnClickListener(v -> findAndPlay());
    }

    private void requestCapture() {
        pendingLevel = levelInput.getText().toString().trim();
        if (pendingLevel.isEmpty()) { levelInput.setError("Enter a level name"); return; }
        Integer value = RunLibrary.parsePercent(startInput.getText().toString());
        if (value == null) { startInput.setError("Enter 0–100"); return; }
        pendingStart = value;
        pendingStartpos = startposInput.isChecked() || value > 0;
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE);
    }

    @Override @SuppressWarnings("deprecation") protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) { status.setText("Screen capture permission cancelled."); return; }
        Intent service = new Intent(this, ScreenRecorderService.class)
                .setAction(ScreenRecorderService.ACTION_START)
                .putExtra(ScreenRecorderService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(ScreenRecorderService.EXTRA_RESULT_DATA, data)
                .putExtra(ScreenRecorderService.EXTRA_LEVEL, pendingLevel)
                .putExtra(ScreenRecorderService.EXTRA_START, pendingStart)
                .putExtra(ScreenRecorderService.EXTRA_STARTPOS, pendingStartpos);
        startForegroundService(service);
        status.setText("Starting secure screen capture…");
    }

    private void stopCapture() {
        Intent service = new Intent(this, ScreenRecorderService.class).setAction(ScreenRecorderService.ACTION_STOP);
        startService(service);
        status.setText("Finalizing MP4…");
        stopButton.setEnabled(false);
    }

    private void analyzeRecording(File source, String level, int start, boolean startpos) {
        worker.execute(() -> {
            try {
                List<RunAnalyzer.Result> results = RunAnalyzer.analyze(this, source, start,
                        message -> main.post(() -> status.setText(message)));
                List<RunRecord> added = new ArrayList<>();
                int index = 1;
                for (RunAnalyzer.Result result : results) {
                    String filename = safeName(level) + "_run_" + System.currentTimeMillis() + "_" + index + ".mp4";
                    Uri uri = RunAnalyzer.publish(this, result.file, filename);
                    RunRecord record = new RunRecord();
                    record.level = level; record.uri = uri.toString(); record.filePath = result.file.getAbsolutePath();
                    record.startPercent = start; record.startpos = startpos; record.best = result.best;
                    record.durationMs = result.durationMs; record.recordedAt = System.currentTimeMillis();
                    record.markers = result.markers; added.add(record); index++;
                }
                source.delete();
                synchronized (runs) { runs.addAll(0, added); }
                saveRuns();
                main.post(() -> {
                    clearPendingRecording();
                    analysisInProgress = false;
                    status.setText("Saved " + added.size() + " run clip(s) to Movies/GD Recorder.");
                    startButton.setEnabled(true);
                    renderRuns();
                });
            } catch (Exception error) {
                main.post(() -> {
                    clearPendingRecording();
                    analysisInProgress = false;
                    status.setText("Analysis failed; the original MP4 is kept in app storage.");
                    new AlertDialog.Builder(this).setTitle("Run analysis failed")
                            .setMessage(error.toString()).setPositiveButton("OK", null).show();
                });
            }
        });
    }

    private void findAndPlay() {
        String query = queryInput.getText().toString().trim().replace("%", "");
        if (query.isEmpty()) { queryInput.setError("Enter a percentage or range"); return; }
        String[] range = query.split("-", -1);
        int from, to;
        boolean isRange = range.length == 2;
        if (range.length > 2) { queryInput.setError("Use one dash, like 5-23"); return; }
        try {
            from = Integer.parseInt(range[0]);
            to = isRange ? Integer.parseInt(range[1]) : from;
        } catch (NumberFormatException error) { queryInput.setError("Use 23 or 5-23"); return; }
        if (from < 0 || to > 100 || from > to) { queryInput.setError("Percentages must be 0–100 in ascending order"); return; }
        if (isRange && from == to) { queryInput.setError("Range endpoints must differ"); return; }
        List<RunRecord> matches = new ArrayList<>();
        for (RunRecord run : runs) {
            if (RunLibrary.matchesRun(run.startPercent, run.best, run.startpos, from, to, isRange)) matches.add(run);
        }
        if (matches.isEmpty()) {
            status.setText(isRange ? "No matching Startpos run. Record a startpos run covering both percentages." : "No recorded run reached " + from + "%.");
            return;
        }
        chooseRun(matches, from, to);
    }

    private void chooseRun(List<RunRecord> matches, int fromPercent, int toPercent) {
        if (matches.size() == 1) { playRun(matches.get(0), fromPercent, toPercent); return; }
        String[] labels = new String[matches.size()];
        for (int i = 0; i < matches.size(); i++) {
            RunRecord r = matches.get(i);
            labels[i] = (i + 1) + " · " + r.level + " · " + r.best + "% · " + DateFormat.getDateTimeInstance().format(new Date(r.recordedAt));
        }
        new AlertDialog.Builder(this).setTitle("Choose run number").setItems(labels, (dialog, which) -> playRun(matches.get(which), fromPercent, toPercent)).show();
    }

    private void playRun(RunRecord run, int fromPercent, int toPercent) {
        worker.execute(() -> {
            try {
                long startMs = RunLibrary.timeAtPercent(run.markers, fromPercent, run.durationMs);
                long endMs = toPercent == fromPercent ? run.durationMs : RunLibrary.timeAtPercent(run.markers, toPercent, run.durationMs);
                if (toPercent != fromPercent) endMs = Math.max(startMs + 1, endMs);
                File source = new File(run.filePath);
                File clip = RunAnalyzer.exportSegment(source, startMs, endMs, "play-" + System.currentTimeMillis());
                Uri uri = RunAnalyzer.publish(this, clip, safeName(run.level) + "_" + fromPercent + "-" + toPercent + "_" + System.currentTimeMillis() + ".mp4");
                main.post(() -> {
                    Intent play = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try { startActivity(play); } catch (Exception error) { status.setText("No video player is installed."); }
                });
            } catch (Exception error) {
                main.post(() -> status.setText("Could not export this run segment: " + error.getMessage()));
            }
        });
    }

    private void renderRuns() {
        if (runList == null) return;
        if (runs.isEmpty()) { runList.setText("No runs saved yet."); return; }
        Map<String, List<RunRecord>> groups = new LinkedHashMap<>();
        for (RunRecord run : runs) {
            String key = run.level + (run.startpos ? " · Startpos from " + run.startPercent + "%" : " · Normal");
            groups.computeIfAbsent(key, unused -> new ArrayList<>()).add(run);
        }
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, List<RunRecord>> group : groups.entrySet()) {
            int best = group.getValue().stream().mapToInt(r -> r.best).max().orElse(0);
            text.append("\n").append(group.getKey()).append("  ×").append(group.getValue().size()).append("  ·  best ").append(best).append("%\n");
            for (int i = 0; i < group.getValue().size(); i++) {
                RunRecord run = group.getValue().get(i);
                text.append("  ").append(i + 1).append(". ").append(run.best).append("% · ").append(run.markers.size()).append(" OCR marks · ")
                        .append(DateFormat.getDateTimeInstance().format(new Date(run.recordedAt))).append("\n");
            }
        }
        runList.setText(text.toString());
    }

    private void loadRuns() {
        try {
            String json = getPreferences(MODE_PRIVATE).getString("runs", "[]");
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) runs.add(RunRecord.fromJson(array.getJSONObject(i)));
        } catch (Exception ignored) { runs.clear(); }
    }

    private void saveRuns() {
        try {
            JSONArray array = new JSONArray();
            synchronized (runs) { for (RunRecord run : runs) array.put(run.toJson()); }
            getPreferences(MODE_PRIVATE).edit().putString("runs", array.toString()).apply();
        } catch (Exception error) { main.post(() -> status.setText("Could not save run history: " + error.getMessage())); }
    }

    private static String safeName(String value) {
        String safe = value == null ? "Geometry-Dash" : value.replaceAll("[^A-Za-z0-9._-]+", "_");
        return safe.isEmpty() ? "Geometry-Dash" : safe;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(sp); view.setTextColor(color);
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        view.setPadding(0, dp(5), 0, dp(5)); return view;
    }
    private EditText input(String hint) {
        EditText input = new EditText(this); input.setSingleLine(true); input.setHint(hint); input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0xFF8C96AA); input.setPadding(dp(12), dp(8), dp(12), dp(8));
        input.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF52D6A0)); return input;
    }
    private Button button(String label) {
        Button button = new Button(this); button.setText(label); button.setAllCaps(false); button.setTextColor(0xFF10141F);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF52D6A0)); return button;
    }
    private View gap(int height) { View view = new View(this); view.setLayoutParams(new LinearLayout.LayoutParams(1, dp(height))); return view; }
    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(-1, -2); }
    private LinearLayout.LayoutParams weight(int weight) { return new LinearLayout.LayoutParams(0, -2, weight); }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(ScreenRecorderService.ACTION_EVENT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(captureReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(captureReceiver, filter);
        receiverRegistered = true;
        consumePendingRecording();
    }

    private void consumePendingRecording() {
        android.content.SharedPreferences prefs = getSharedPreferences("capture", MODE_PRIVATE);
        if (!prefs.getBoolean("pending", false) || analysisInProgress) return;
        String path = prefs.getString("pendingPath", "");
        String level = prefs.getString("pendingLevel", "Geometry Dash");
        int start = prefs.getInt("pendingStart", 0);
        boolean startpos = prefs.getBoolean("pendingStartpos", false);
        analysisInProgress = true;
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        if (!path.isEmpty()) analyzeRecording(new File(path), level, start, startpos);
        else { clearPendingRecording(); analysisInProgress = false; }
    }

    @SuppressLint("ApplySharedPref")
    private void clearPendingRecording() {
        getSharedPreferences("capture", MODE_PRIVATE).edit().putBoolean("pending", false).commit();
    }

    @Override protected void onStop() {
        if (receiverRegistered) { unregisterReceiver(captureReceiver); receiverRegistered = false; }
        super.onStop();
    }

    @Override protected void onDestroy() { worker.shutdown(); super.onDestroy(); }
}

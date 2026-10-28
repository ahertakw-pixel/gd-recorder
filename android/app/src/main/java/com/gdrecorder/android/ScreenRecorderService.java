package com.gdrecorder.android;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.Display;

import java.io.File;

/** Screen capture service. It does no OCR or image analysis while the game is running. */
public final class ScreenRecorderService extends Service {
    public static final String ACTION_START = "com.gdrecorder.android.START";
    public static final String ACTION_STOP = "com.gdrecorder.android.STOP";
    public static final String ACTION_EVENT = "com.gdrecorder.android.EVENT";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";
    public static final String EXTRA_LEVEL = "level";
    public static final String EXTRA_START = "startPercent";
    public static final String EXTRA_STARTPOS = "startpos";
    public static final String EVENT_TYPE = "eventType";

    private static final String CHANNEL_ID = "gd_recording";
    private static final int NOTIFICATION_ID = 4201;
    private MediaProjection projection;
    private MediaProjection.Callback projectionCallback;
    private MediaRecorder recorder;
    private VirtualDisplay virtualDisplay;
    private File outputFile;
    private boolean ending;
    private String level;
    private int startPercent;
    private boolean startpos;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        if (ACTION_STOP.equals(intent.getAction())) { finishRecording(); return START_NOT_STICKY; }
        if (ACTION_START.equals(intent.getAction())) beginRecording(intent);
        return START_NOT_STICKY;
    }

    private void beginRecording(Intent intent) {
        try {
            level = intent.getStringExtra(EXTRA_LEVEL);
            startPercent = intent.getIntExtra(EXTRA_START, 0);
            startpos = intent.getBooleanExtra(EXTRA_STARTPOS, false);
            createChannel();
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);

            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent resultData = getResultData(intent);
            projection = manager.getMediaProjection(resultCode, resultData);
            if (projection == null) throw new IllegalStateException("Android did not grant screen capture permission");

            Display display = ((DisplayManager) getSystemService(DISPLAY_SERVICE)).getDisplay(Display.DEFAULT_DISPLAY);
            DisplayMetrics metrics = new DisplayMetrics();
            display.getRealMetrics(metrics);
            int width = Math.max(metrics.widthPixels, metrics.heightPixels);
            int height = Math.min(metrics.widthPixels, metrics.heightPixels);
            float scale = Math.min(1f, 1920f / Math.max(width, height));
            int videoWidth = even((int) (width * scale));
            int videoHeight = even((int) (height * scale));
            int density = metrics.densityDpi;

            File folder = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
            if (folder == null) folder = getFilesDir();
            if (!folder.exists() && !folder.mkdirs()) throw new IllegalStateException("Cannot create the recordings folder");
            outputFile = new File(folder, "session-" + System.currentTimeMillis() + ".mp4");

            recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            recorder.setVideoSize(videoWidth, videoHeight);
            recorder.setVideoFrameRate(30);
            recorder.setVideoEncodingBitRate(6_000_000);
            recorder.setOutputFile(outputFile.getAbsolutePath());
            recorder.prepare();
            projectionCallback = new MediaProjection.Callback() {
                @Override public void onStop() { finishRecording(); }
                @Override public void onCapturedContentResize(int width, int height) {
                    if (virtualDisplay != null && width > 0 && height > 0) {
                        virtualDisplay.resize(videoWidth, videoHeight, density);
                    }
                }
            };
            projection.registerCallback(projectionCallback, null);
            virtualDisplay = projection.createVirtualDisplay("GD Recorder", videoWidth, videoHeight, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, recorder.getSurface(), null, null);
            if (virtualDisplay == null) throw new IllegalStateException("Android could not create the screen capture display");
            recorder.start();
            sendEvent("started", "Recording screen to MP4 · switch to Geometry Dash");
        } catch (Exception error) {
            sendEvent("error", error.getMessage() == null ? error.toString() : error.getMessage());
            finishRecording();
        }
    }

    @SuppressWarnings("deprecation")
    private Intent getResultData(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) return intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        return intent.getParcelableExtra(EXTRA_RESULT_DATA);
    }

    private static int even(int value) { return Math.max(2, value & ~1); }

    @SuppressLint("ApplySharedPref")
    private void finishRecording() {
        if (ending) return;
        ending = true;
        boolean usable = false;
        try {
            if (recorder != null) {
                try { recorder.stop(); usable = outputFile != null && outputFile.length() > 1024; }
                catch (RuntimeException ignored) { usable = false; }
                recorder.reset(); recorder.release(); recorder = null;
            }
            if (virtualDisplay != null) { virtualDisplay.release(); virtualDisplay = null; }
            if (projection != null) {
                if (projectionCallback != null) {
                    try { projection.unregisterCallback(projectionCallback); } catch (Exception ignored) { }
                    projectionCallback = null;
                }
                try { projection.stop(); } catch (Exception ignored) { }
                projection = null;
            }
            if (usable) {
                getSharedPreferences("capture", MODE_PRIVATE).edit()
                        .putString("pendingPath", outputFile.getAbsolutePath())
                        .putString("pendingLevel", level)
                        .putInt("pendingStart", startPercent)
                        .putBoolean("pendingStartpos", startpos)
                        .putBoolean("pending", true)
                        .commit();
                Intent event = eventIntent("saved", outputFile.getAbsolutePath());
                event.putExtra(EXTRA_LEVEL, level);
                event.putExtra(EXTRA_START, startPercent);
                event.putExtra(EXTRA_STARTPOS, startpos);
                sendBroadcast(event);
            } else if (outputFile != null) {
                outputFile.delete();
                sendEvent("error", "The screen recording was too short or Android could not finalize its MP4.");
            }
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private void sendEvent(String type, String message) { sendBroadcast(eventIntent(type, message)); }
    private Intent eventIntent(String type, String message) {
        Intent event = new Intent(ACTION_EVENT).setPackage(getPackageName());
        event.putExtra(EVENT_TYPE, type); event.putExtra("message", message);
        return event;
    }

    private void createChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Screen recording", NotificationManager.IMPORTANCE_LOW));
        }
    }

    private Notification buildNotification() {
        Intent stop = new Intent(this, ScreenRecorderService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID);
        return builder.setSmallIcon(android.R.drawable.presence_video_online)
                .setContentTitle("GD Recorder is recording")
                .setContentText("Tap Stop to finish and find attempts")
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stopPending).build())
                .build();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}

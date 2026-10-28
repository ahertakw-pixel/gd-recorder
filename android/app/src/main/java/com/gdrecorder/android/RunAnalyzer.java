package com.gdrecorder.android;

import android.content.ContentValues;
import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.media.MediaCodec;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Offline OCR and MP4 segment export; no frame analysis runs during gameplay. */
public final class RunAnalyzer {
    private RunAnalyzer() {}

    public interface Progress { void onProgress(String message); }

    public static final class Result {
        public final File file;
        public final int best;
        public final long durationMs;
        public final List<RunLibrary.Point> markers;
        Result(File file, int best, long durationMs, List<RunLibrary.Point> markers) {
            this.file = file; this.best = best; this.durationMs = durationMs; this.markers = markers;
        }
    }

    public static List<Result> analyze(Context context, File source, int startingPercent, Progress progress) throws Exception {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        List<RunLibrary.Sample> samples = new ArrayList<>();
        long durationMs;
        try {
            retriever.setDataSource(source.getAbsolutePath());
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            durationMs = duration == null ? 0 : Long.parseLong(duration);
            for (long t = 0; t < durationMs; t += 500) {
                Bitmap frame = retriever.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST);
                if (frame != null) {
                    Integer percent = detectPercent(recognizer, frame);
                    if (percent != null) samples.add(new RunLibrary.Sample(t, percent));
                    frame.recycle();
                }
                if (progress != null && (t == 0 || t % 5000 == 0)) {
                    progress.onProgress("Analyzing screen recording · " + t / 1000 + " / " + Math.max(1, durationMs / 1000) + " sec");
                }
            }
        } finally {
            retriever.release();
            recognizer.close();
        }

        List<RunLibrary.Segment> segments = RunLibrary.splitAttempts(samples, durationMs, startingPercent);
        if (segments.isEmpty()) {
            File copy = new File(source.getParentFile(), "unparsed-" + System.currentTimeMillis() + ".mp4");
            java.nio.file.Files.copy(source.toPath(), copy.toPath());
            List<RunLibrary.Point> noMarkers = new ArrayList<>();
            return List.of(new Result(copy, 0, durationMs, noMarkers));
        }
        List<Result> results = new ArrayList<>();
        int index = 1;
        for (RunLibrary.Segment segment : segments) {
            File clip = exportSegment(source, segment.startMs, segment.endMs, "attempt-" + index + "-" + System.currentTimeMillis());
            List<RunLibrary.Point> normalized = new ArrayList<>();
            for (RunLibrary.Point point : segment.markers) normalized.add(new RunLibrary.Point(point.timeMs, point.percent));
            results.add(new Result(clip, segment.best, Math.max(1, segment.endMs - segment.startMs), normalized));
            index++;
        }
        return results;
    }

    private static Integer detectPercent(TextRecognizer recognizer, Bitmap frame) throws Exception {
        int width = frame.getWidth();
        int height = frame.getHeight();
        int left = width / 10;
        int right = width - left;
        int bottom = Math.max(1, height * 35 / 100);
        Bitmap crop = Bitmap.createBitmap(frame, left, 0, right - left, bottom);
        Bitmap enlarged = Bitmap.createScaledBitmap(crop, crop.getWidth() * 2, crop.getHeight() * 2, true);
        crop.recycle();
        int scanWidth = enlarged.getWidth();
        int scanHeight = enlarged.getHeight();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Text> result = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        recognizer.process(InputImage.fromBitmap(enlarged, 0))
                .addOnSuccessListener(result::set)
                .addOnFailureListener(error::set)
                .addOnCompleteListener(task -> latch.countDown());
        if (!latch.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("Text recognition timed out");
        enlarged.recycle();
        if (error.get() != null) throw error.get();
        Text text = result.get();
        if (text == null) return null;
        Integer best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                Integer value = RunLibrary.findPercent(line.getText());
                Rect box = line.getBoundingBox();
                if (value == null || box == null) continue;
                float centerX = (box.left + box.right) / 2f;
                float centerY = (box.top + box.bottom) / 2f;
                double score = 1.0 - Math.abs(centerX - scanWidth / 2f) / scanWidth - centerY / (double) scanHeight;
                if (score > bestScore) { bestScore = score; best = value; }
            }
        }
        return best;
    }

    public static File exportSegment(File source, long startMs, long endMs, String name) throws Exception {
        File output = new File(source.getParentFile(), name + ".mp4");
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean muxerStarted = false;
        try {
            extractor.setDataSource(source.getAbsolutePath());
            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int[] trackMap = new int[extractor.getTrackCount()];
            for (int i = 0; i < trackMap.length; i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                trackMap[i] = muxer.addTrack(format);
                extractor.selectTrack(i);
            }
            if (trackMap.length == 0) throw new IllegalStateException("Recording has no media tracks");
            muxer.start();
            muxerStarted = true;
            extractor.seekTo(Math.max(0, startMs) * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            ByteBuffer buffer = ByteBuffer.allocateDirect(8 * 1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long endUs = Math.max(startMs + 1, endMs) * 1000;
            long baseTimeUs = -1;
            while (true) {
                int track = extractor.getSampleTrackIndex();
                if (track < 0) break;
                long timeUs = extractor.getSampleTime();
                if (timeUs < 0 || timeUs >= endUs) break;
                buffer.clear();
                int size = extractor.readSampleData(buffer, 0);
                if (size < 0) break;
                if (baseTimeUs < 0) baseTimeUs = timeUs;
                info.offset = 0;
                info.size = size;
                info.presentationTimeUs = Math.max(0, timeUs - baseTimeUs);
                int sampleFlags = extractor.getSampleFlags();
                info.flags = 0;
                if ((sampleFlags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0) info.flags |= MediaCodec.BUFFER_FLAG_KEY_FRAME;
                if ((sampleFlags & MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) info.flags |= MediaCodec.BUFFER_FLAG_PARTIAL_FRAME;
                muxer.writeSampleData(trackMap[track], buffer, info);
                extractor.advance();
            }
            return output;
        } finally {
            extractor.release();
            if (muxer != null) {
                if (muxerStarted) muxer.stop();
                muxer.release();
            }
        }
    }

    public static Uri publish(Context context, File source, String displayName) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/GD Recorder");
        values.put(MediaStore.Video.Media.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IllegalStateException("Could not create video in Movies/GD Recorder");
        try (OutputStream output = resolver.openOutputStream(uri); InputStream input = java.nio.file.Files.newInputStream(source.toPath())) {
            if (output == null) throw new IllegalStateException("Could not open exported video");
            byte[] bytes = new byte[1024 * 256];
            int count;
            while ((count = input.read(bytes)) >= 0) output.write(bytes, 0, count);
        } catch (Exception error) {
            resolver.delete(uri, null, null);
            throw error;
        }
        ContentValues ready = new ContentValues();
        ready.put(MediaStore.Video.Media.IS_PENDING, 0);
        resolver.update(uri, ready, null, null);
        return uri;
    }
}

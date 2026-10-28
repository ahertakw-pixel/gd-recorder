package com.gdrecorder.android;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure run parsing and attempt-boundary logic shared by OCR and unit tests. */
public final class RunLibrary {
    private static final Pattern PERCENT = Pattern.compile("(?<![\\d.])(100|\\d{1,2})\\s*%(?![\\d.])");

    private RunLibrary() {}

    public static Integer findPercent(String text) {
        if (text == null) return null;
        String normalized = text.replace('O', '0').replace('o', '0');
        Matcher matcher = PERCENT.matcher(normalized);
        if (!matcher.find()) return null;
        int value = Integer.parseInt(matcher.group(1));
        return value <= 100 ? value : null;
    }

    public static Integer parsePercent(String text) {
        if (text == null) return null;
        String value = text.trim().replace("%", "");
        try {
            int percent = Integer.parseInt(value);
            return percent >= 0 && percent <= 100 ? percent : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public static boolean matchesRun(int runStart, int best, boolean startpos, int from, int to, boolean isRange) {
        return runStart <= from && best >= to && (!isRange || startpos);
    }

    public static List<Segment> splitAttempts(List<Sample> samples, long totalDurationMs, int startPercent) {
        List<Sample> valid = new ArrayList<>();
        for (Sample sample : samples) if (sample.percent >= 0 && sample.percent <= 100) valid.add(sample);
        List<Segment> output = new ArrayList<>();
        if (valid.isEmpty()) return output;

        long runStart = valid.get(0).timeMs;
        long firstReset = -1;
        int resetHits = 0;
        int max = valid.get(0).percent;
        List<Point> markers = new ArrayList<>();
        markers.add(new Point(0, valid.get(0).percent));

        for (int i = 1; i < valid.size(); i++) {
            Sample sample = valid.get(i);
            boolean reset = sample.percent <= startPercent + 2 && max >= startPercent + 5;
            if (reset) {
                if (resetHits == 0) firstReset = sample.timeMs;
                resetHits++;
            } else {
                resetHits = 0;
                firstReset = -1;
            }
            if (resetHits >= 2) {
                long end = Math.max(runStart + 1, firstReset);
                output.add(new Segment(runStart, end, max, markers));
                runStart = firstReset;
                max = sample.percent;
                markers = new ArrayList<>();
                markers.add(new Point(0, sample.percent));
                resetHits = 0;
                firstReset = -1;
                continue;
            }
            if (sample.percent > max) {
                max = sample.percent;
                long relative = Math.max(0, sample.timeMs - runStart);
                if (markers.isEmpty() || sample.percent > markers.get(markers.size() - 1).percent) {
                    markers.add(new Point(relative, sample.percent));
                }
            }
        }
        long end = Math.max(runStart + 1, totalDurationMs);
        output.add(new Segment(runStart, end, max, markers));
        return output;
    }

    public static long timeAtPercent(List<Point> points, int target, long durationMs) {
        if (points == null || points.isEmpty()) return 0;
        Point previous = points.get(0);
        if (target <= previous.percent) return previous.timeMs;
        for (int i = 1; i < points.size(); i++) {
            Point next = points.get(i);
            if (target <= next.percent) {
                int span = next.percent - previous.percent;
                if (span <= 0) return next.timeMs;
                double fraction = (target - previous.percent) / (double) span;
                return previous.timeMs + Math.round((next.timeMs - previous.timeMs) * fraction);
            }
            previous = next;
        }
        return Math.min(durationMs, previous.timeMs);
    }

    public static final class Sample {
        public final long timeMs;
        public final int percent;
        public Sample(long timeMs, int percent) { this.timeMs = timeMs; this.percent = percent; }
    }

    public static final class Point {
        public final long timeMs;
        public final int percent;
        public Point(long timeMs, int percent) { this.timeMs = timeMs; this.percent = percent; }
    }

    public static final class Segment {
        public final long startMs;
        public final long endMs;
        public final int best;
        public final List<Point> markers;
        public Segment(long startMs, long endMs, int best, List<Point> markers) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.best = best;
            this.markers = new ArrayList<>(markers);
        }
    }
}

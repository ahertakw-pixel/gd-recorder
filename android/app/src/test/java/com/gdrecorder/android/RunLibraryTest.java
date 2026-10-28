package com.gdrecorder.android;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class RunLibraryTest {
    @Test public void recognizesPercentFromOcrText() {
        assertEquals(Integer.valueOf(73), RunLibrary.findPercent("73%"));
        assertEquals(Integer.valueOf(0), RunLibrary.findPercent("O%"));
        assertNull(RunLibrary.findPercent("level 75"));
        assertNull(RunLibrary.findPercent("3.5%"));
    }

    @Test public void parsesInputBounds() {
        assertEquals(Integer.valueOf(100), RunLibrary.parsePercent("100%"));
        assertNull(RunLibrary.parsePercent("101"));
        assertNull(RunLibrary.parsePercent("-1"));
    }

    @Test public void splitsRepeatedAttemptsOnTwoResetSamples() {
        List<RunLibrary.Sample> samples = Arrays.asList(
                new RunLibrary.Sample(0, 0), new RunLibrary.Sample(500, 4),
                new RunLibrary.Sample(1000, 8), new RunLibrary.Sample(1500, 1),
                new RunLibrary.Sample(2000, 0), new RunLibrary.Sample(2500, 5),
                new RunLibrary.Sample(3000, 9));
        List<RunLibrary.Segment> segments = RunLibrary.splitAttempts(samples, 3500, 0);
        assertEquals(2, segments.size());
        assertEquals(8, segments.get(0).best);
        assertEquals(9, segments.get(1).best);
        assertEquals(1500, segments.get(0).endMs);
    }

    @Test public void interpolatesPercentTime() {
        List<RunLibrary.Point> points = Arrays.asList(
                new RunLibrary.Point(0, 0), new RunLibrary.Point(1000, 20));
        assertEquals(500, RunLibrary.timeAtPercent(points, 10, 1000));
    }

    @Test public void rangeQueriesRequireStartposAndActualCoverage() {
        assertTrue(RunLibrary.matchesRun(5, 40, true, 5, 23, true));
        assertFalse(RunLibrary.matchesRun(5, 40, false, 5, 23, true));
        assertFalse(RunLibrary.matchesRun(25, 40, true, 5, 23, true));
        assertTrue(RunLibrary.matchesRun(0, 40, false, 23, 23, false));
    }
}

package com.firepad.app;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class AxisAccumulatorTest {
    @Test public void slowDeltasAccumulateUntilTheyCrossOneStep() {
        AxisAccumulator axis = new AxisAccumulator();
        assertEquals(0, axis.consume(0.4f, 12).length);
        assertEquals(0, axis.consume(0.4f, 12).length);
        assertArrayEquals(new int[] {1}, axis.consume(0.4f, 12));
    }

    @Test public void negativeFractionIsNotTruncatedAway() {
        AxisAccumulator axis = new AxisAccumulator();
        assertEquals(0, axis.consume(-0.9f, 12).length);
        assertArrayEquals(new int[] {-1}, axis.consume(-0.2f, 12));
    }

    @Test public void largeDeltaIsSplitIntoCappedSteps() {
        AxisAccumulator axis = new AxisAccumulator();
        assertArrayEquals(new int[] {12, 12, 6}, axis.consume(30f, 12));
    }

    @Test public void resetDropsTheStoredFraction() {
        AxisAccumulator axis = new AxisAccumulator();
        axis.consume(0.8f, 12);
        axis.reset();
        assertEquals(0, axis.consume(0.1f, 12).length);
    }
}

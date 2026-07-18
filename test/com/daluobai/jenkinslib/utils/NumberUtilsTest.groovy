package com.daluobai.jenkinslib.utils

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertThrows

class NumberUtilsTest {

    @Test
    void maxAndMinIgnoreNullValuesIncludingTheFirstItem() {
        assertEquals(3, NumberUtils.max(null, 1, 3, 2))
        assertEquals(1, NumberUtils.min(null, 1, 3, 2))
        assertNull(NumberUtils.max(null, null))
        assertNull(NumberUtils.min(null, null))
    }

    @Test
    void averageDividesByNonNullValueCount() {
        assertEquals(new BigDecimal('2.00'), NumberUtils.average([2, null] as Number[]))
        assertEquals(BigDecimal.ZERO, NumberUtils.average([null, null] as Number[]))
    }

    @Test
    void percentWithZeroScaleHasNoTrailingDecimalPoint() {
        assertEquals('12%', NumberUtils.formatPercent(0.125, 0))
        assertThrows(IllegalArgumentException.class) {
            NumberUtils.formatPercent(0.125, -1)
        }
    }
}

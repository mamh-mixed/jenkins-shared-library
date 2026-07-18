package com.daluobai.jenkinslib.utils

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals

class ConfigMergeUtilsTest {

    @Test
    void getNestedValuePreservesFalseZeroAndEmptyString() {
        Map config = [feature: [enabled: false, retries: 0, suffix: '']]

        assertEquals(false, ConfigMergeUtils.getNestedValue(config, 'feature.enabled', true))
        assertEquals(0, ConfigMergeUtils.getNestedValue(config, 'feature.retries', 3))
        assertEquals('', ConfigMergeUtils.getNestedValue(config, 'feature.suffix', 'default'))
    }
}

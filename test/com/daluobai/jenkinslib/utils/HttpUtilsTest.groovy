package com.daluobai.jenkinslib.utils

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

class HttpUtilsTest {

    @Test
    void requestErrorsDoNotExposeTokensFromUrls() {
        RuntimeException error = assertThrows(RuntimeException.class) {
            HttpUtils.get('not-a-valid-url?token=super-secret')
        }

        assertTrue(error.message.contains('<redacted-url>'))
        assertFalse(error.message.contains('super-secret'))
    }
}

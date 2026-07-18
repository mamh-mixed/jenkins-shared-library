package com.daluobai.jenkinslib.utils

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

class MessageUtilsTest {

    @Test
    void notificationFailureDoesNotThrowOrSkipOtherChannels() {
        MessageUtils messageUtils = new MessageUtils(new FakeSteps())
        messageUtils.wecomApi = new FakeWecomApi(false, true)
        messageUtils.feishuApi = new FakeFeishuApi(true)

        boolean result = messageUtils.sendMessage([
                wecom : [key: 'wecom-key'],
                feishu: [token: 'feishu-token', version: 'v2']
        ], 'title', 'content')

        assertFalse(result)
        assertTrue(messageUtils.feishuApi.called)
    }

    static class FakeSteps {
        void echo(String ignored) { }
    }

    static class FakeWecomApi {
        boolean result
        boolean shouldThrow
        FakeWecomApi(boolean result, boolean shouldThrow = false) {
            this.result = result
            this.shouldThrow = shouldThrow
        }
        boolean sendMsg(String key, String content) {
            if (shouldThrow) {
                throw new RuntimeException('boom')
            }
            return result
        }
    }

    static class FakeFeishuApi {
        boolean result
        boolean called
        FakeFeishuApi(boolean result) { this.result = result }
        boolean sendMsg(String token, String title, String content, String version) {
            called = true
            return result
        }
    }
}

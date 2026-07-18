package com.daluobai.jenkinslib.api

import com.daluobai.jenkinslib.utils.HttpUtils
import groovy.lang.ExpandoMetaClass
import groovy.lang.GroovySystem
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

class WecomApiTest {

    @AfterEach
    void cleanup() {
        GroovySystem.metaClassRegistry.removeMetaClass(HttpUtils.HttpRequest)
    }

    @Test
    void sendMsgUsesErrcodeAsSuccessSignal() {
        stubPostResponse('{"errcode":0,"errmsg":"ok"}')
        assertTrue(new WecomApi(new FakeSteps()).sendMsg('secret-key', 'hello'))
    }

    @Test
    void sendMsgReturnsFalseForNonZeroErrcode() {
        stubPostResponse('{"errcode":93000,"errmsg":"invalid webhook"}')
        assertFalse(new WecomApi(new FakeSteps()).sendMsg('secret-key', 'hello'))
    }

    private static void stubPostResponse(String body) {
        ExpandoMetaClass emc = new ExpandoMetaClass(HttpUtils.HttpRequest, false, true)
        emc.'static'.post = { String url -> new FakeHttpRequest(body) }
        emc.initialize()
        GroovySystem.metaClassRegistry.setMetaClass(HttpUtils.HttpRequest, emc)
    }

    static class FakeHttpRequest {
        String responseBody

        FakeHttpRequest(String responseBody) {
            this.responseBody = responseBody
        }

        FakeHttpRequest contentType(String ignored) { return this }
        FakeHttpRequest body(String ignored) { return this }
        HttpUtils.HttpResponse execute() { return new HttpUtils.HttpResponse(200, responseBody) }
    }

    static class FakeSteps {
        List<String> messages = []
        void echo(String message) { messages.add(message) }
    }
}

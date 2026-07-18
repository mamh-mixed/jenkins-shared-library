package com.daluobai.jenkinslib.utils

import org.jenkinsci.plugins.workflow.steps.ExceededTimeout
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException
import org.jenkinsci.plugins.workflow.steps.UserInterruption
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertSame
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

class EndpointUtilsTest {

    @Test
    void localTcpProbeUsesExactSsPortFilter() {
        FakeSteps steps = new FakeSteps(shellOutput: 'LISTEN 0 4096 0.0.0.0:80 0.0.0.0:*')

        boolean result = new EndpointUtils(steps).healthCheckWithLocalTCPPort(80, 0, 1)

        assertTrue(result)
        assertEquals(1, steps.scripts.size())
        assertTrue(steps.scripts[0].contains("sport = :80"))
        assertFalse(steps.scripts[0].contains('egrep'))
    }

    @Test
    void httpProbeLimitsBothConnectionAndWholeTransfer() {
        FakeSteps steps = new FakeSteps(shellOutput: '200')

        boolean result = new EndpointUtils(steps).healthCheckWithHttp('http://localhost:8080/health', 5, 0, 1)

        assertTrue(result)
        assertEquals(1, steps.scripts.size())
        assertTrue(steps.scripts[0].contains('--connect-timeout 5'))
        assertTrue(steps.scripts[0].contains('--max-time 5'))
    }

    @Test
    void commandProbeRetriesJenkinsTimeouts() {
        FlowInterruptedException timeout = new FlowInterruptedException([new ExceededTimeout()])
        FakeSteps steps = new FakeSteps(timeoutException: timeout)

        boolean result = new EndpointUtils(steps).healthCheckWithCMD('check-service', 5, 0, 2)

        assertFalse(result)
        assertEquals(2, steps.timeoutCalls)
    }

    @Test
    void commandProbeStillPropagatesManualAbort() {
        FlowInterruptedException abort = new FlowInterruptedException([new UserInterruption()])
        FakeSteps steps = new FakeSteps(timeoutException: abort)

        FlowInterruptedException thrown = assertThrows(FlowInterruptedException.class) {
            new EndpointUtils(steps).healthCheckWithCMD('check-service', 5, 0, 2)
        }

        assertSame(abort, thrown)
        assertEquals(1, steps.timeoutCalls)
    }

    static class FakeSteps {
        String shellOutput = ''
        Exception timeoutException
        int timeoutCalls
        List<String> scripts = []

        void echo(Object ignored) { }

        void sleep(Map ignored) { }

        Object sh(Map arguments) {
            scripts.add(arguments.script.toString())
            return arguments.returnStatus ? 0 : shellOutput
        }

        void timeout(Map ignored, Closure body) {
            timeoutCalls++
            if (timeoutException != null) {
                throw timeoutException
            }
            body.call()
        }
    }
}

package com.daluobai.jenkinslib.utils

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test

import java.net.InetAddress
import java.net.InetSocketAddress

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

class ConfigUtilsTest {

    @Test
    void optionalResourceMissingReturnsEmptyMap() {
        FakeSteps steps = new FakeSteps()

        Map result = new ConfigUtils(steps)
                .readOptionalConfigFromFullPath('RESOURCES:config/missing.json') as Map

        assertEquals([:], result)
        assertEquals(['config/missing.json'], steps.resourceReads)
    }

    @Test
    void strictResourceReadStillThrowsWhenMissing() {
        FakeSteps steps = new FakeSteps()

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class) {
            new ConfigUtils(steps).readConfigFromFullPath('RESOURCES:config/missing.json')
        }

        assertTrue(error.message.contains('No such library resource'))
    }

    @Test
    void optionalHostPathMissingReturnsEmptyMapWithoutReadingFile() {
        FakeSteps steps = new FakeSteps(hostFileExists: false)

        Map result = new ConfigUtils(steps)
                .readOptionalConfigFromFullPath('HOST_PATH:/agent/missing.json') as Map

        assertEquals([:], result)
        assertEquals(['/agent/missing.json'], steps.hostPathChecks)
        assertFalse(steps.hostFileRead)
    }

    @Test
    void optionalResourceInvalidJsonStillThrows() {
        FakeSteps steps = new FakeSteps(resources: ['config/broken.json': '{'])

        assertThrows(Exception.class) {
            new ConfigUtils(steps).readOptionalConfigFromFullPath('RESOURCES:config/broken.json')
        }
    }

    @Test
    void optionalUnknownTypeStillThrows() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class) {
            new ConfigUtils(new FakeSteps())
                    .readOptionalConfigFromFullPath('UNKNOWN:config/extend.json')
        }

        assertTrue(error.message.contains('配置类型为空'))
    }

    @Test
    void optionalUrl404ReturnsEmptyMap() {
        withHttpStatus(404) { String url ->
            Map result = new ConfigUtils(new FakeSteps()).readOptionalConfigFromFullPath("URL:${url}") as Map
            assertEquals([:], result)
        }
    }

    @Test
    void optionalUrl500StillThrows() {
        withHttpStatus(500) { String url ->
            RuntimeException error = assertThrows(RuntimeException.class) {
                new ConfigUtils(new FakeSteps())
                        .readOptionalConfigFromFullPath("URL:${url}")
            }
            assertTrue(error.cause.message.contains('500'))
        }
    }

    private static void withHttpStatus(int status, Closure assertion) {
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.loopbackAddress, 0),
                0
        )
        server.createContext('/config') { exchange ->
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        server.start()
        try {
            assertion("http://127.0.0.1:${server.address.port}/config")
        } finally {
            server.stop(0)
        }
    }

    static class FakeSteps {
        Map<String, String> resources = [:]
        boolean hostFileExists = true
        boolean hostFileRead = false
        List<String> resourceReads = []
        List<String> hostPathChecks = []

        String libraryResource(String path) {
            resourceReads.add(path)
            if (!resources.containsKey(path)) {
                throw new IllegalArgumentException(
                        'No such library resource ' + path + ' could be found.'
                )
            }
            return resources[path]
        }

        boolean fileExists(String path) {
            hostPathChecks.add(path)
            return hostFileExists
        }

        String readFile(Map ignoredArgs) {
            hostFileRead = true
            return '{"host":true}'
        }
    }
}

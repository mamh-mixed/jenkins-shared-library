package com.daluobai.jenkinslib.steps

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

class StepsJavaWebTest {

    @Test
    void systemctlRestartRemovesUnitFromCorrectDirectory() {
        FakeSteps steps = new FakeSteps()

        new StepsJavaWeb(steps).reStartBySystemctl([
                pathRoot: '/apps/application',
                javaPath: '/usr/bin/java',
                runOptions: '',
                runArgs: ''
        ])

        assertTrue(steps.scripts.contains('rm -f /etc/systemd/system/demo.service || true'))
        assertFalse(steps.scripts.any { String script -> script.contains('/etc/systemd/systemO/') })
        assertEquals('/etc/systemd/system/demo.service', steps.writtenFile)
    }

    static class FakeSteps {
        Map globalParameterMap = [
                SHARE_PARAM: [appName: 'demo', archiveName: 'app.jar']
        ]
        List<String> scripts = []
        String writtenFile

        void echo(Object ignored) { }

        Object sh(Map arguments) {
            return arguments.returnStdout ? 'jenkins' : 0
        }

        void sh(String script) {
            scripts.add(script)
        }

        String libraryResource(String ignored) {
            return 'ExecStart=${javaPath} -jar ${pathRoot}/${appName}/${archiveName}'
        }

        void writeFile(Map arguments) {
            writtenFile = arguments.file.toString()
        }

        void dir(String ignored, Closure body) {
            body.call()
        }
    }
}

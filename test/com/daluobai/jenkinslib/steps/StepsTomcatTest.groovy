package com.daluobai.jenkinslib.steps

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

class StepsTomcatTest {

    @Test
    void deployBacksUpArchiveToAFileInsteadOfMissingDirectory() {
        FakeSteps steps = new FakeSteps()

        new StepsTomcat(steps).deploy([
                enable: true,
                tomcatHome: '/tomcat',
                deployPath: '/tomcat/webapps',
                command: ''
        ])

        String backupCommand = steps.scripts.find { it.startsWith('mv /tomcat/webapps/app.war ') }
        assertTrue(backupCommand.contains('/tomcat/backup/demo/app-'))
        assertTrue(backupCommand.endsWith('.war || true'))
        assertFalse(backupCommand.contains('.war/ || true'))
    }

    static class FakeSteps {
        Map globalParameterMap = [SHARE_PARAM: [appName: 'demo', archiveName: 'app.war']]
        List<String> scripts = []

        void echo(String ignored) { }
        void unstash(String ignored) { }
        void sh(String script) { scripts.add(script) }
        void dir(String ignored, Closure body) { body.call() }
    }
}

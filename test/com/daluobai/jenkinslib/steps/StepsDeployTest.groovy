package com.daluobai.jenkinslib.steps

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertThrows

class StepsDeployTest {

    @Test
    void deployHonorsDisabledJavaConfigAndUsesEnabledTomcatConfig() {
        FakeSteps steps = new FakeSteps()
        StepsDeploy deploy = new StepsDeploy(steps)
        deploy.stepsJenkins = new FakeStepsJenkins()
        deploy.stepsJavaWeb = new FakeDeployer()
        deploy.stepsTomcat = new FakeDeployer()

        deploy.deploy([
                labels: ['deploy-node'],
                stepsJavaWebDeployToService: [enable: false],
                stepsTomcatDeploy: [enable: true]
        ])

        assertEquals(0, deploy.stepsJavaWeb.calls)
        assertEquals(1, deploy.stepsTomcat.calls)
    }

    @Test
    void deployRejectsTwoEnabledDeploymentModes() {
        StepsDeploy deploy = new StepsDeploy(new FakeSteps())

        assertThrows(IllegalStateException.class) {
            deploy.deploy([
                    labels: ['deploy-node'],
                    stepsJavaWebDeployToService: [enable: true],
                    stepsTomcatDeploy: [enable: true]
            ])
        }
    }

    static class FakeSteps {
        Map globalParameterMap = [
                SHARE_PARAM: [appName: 'app', archiveName: 'app.jar'],
                DEPLOY_PIPELINE: [stepsStorage: [jenkinsStash: [enable: true]]]
        ]

        void echo(String ignored) { }
        void error(String message) { throw new IllegalStateException(message) }
        void node(String ignored, Closure body) { body.call() }
        void sh(String ignored) { }
    }

    static class FakeStepsJenkins {
        List<String> getNodeByLabel(String ignored) { return ['agent-1'] }
    }

    static class FakeDeployer {
        int calls
        void deploy(Map ignored) { calls++ }
    }
}

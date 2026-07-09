package com.daluobai.jenkinslib.steps

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

class StepsBuildMavenTest {

    @Test
    void buildChecksOutSourceWithJenkinsChangelogBeforeRunningMavenBuild() {
        FakeSteps steps = new FakeSteps()
        StepsBuildMaven stepsBuildMaven = new StepsBuildMaven(steps)
        stepsBuildMaven.stepsGit = new FakeStepsGit()

        stepsBuildMaven.build([
                DEFAULT_CONFIG : [
                        docker: [
                                registry: [
                                        domain: 'registry.example.com'
                                ]
                        ]
                ],
                SHARE_PARAM    : [:],
                DEPLOY_PIPELINE: [
                        stepsBuild: [
                                stepsBuildMaven: [
                                        gitUrl          : 'git@example.com:team/service.git',
                                        gitBranch       : 'main',
                                        subModule       : 'service-web',
                                        lifecycle       : 'package',
                                        skipTest        : true,
                                        activeProfile   : 'prod',
                                        dockerBuildImage: 'registry.example.com/build-maven:3-jdk17'
                                ]
                        ]
                ]
        ])

        assertEquals(1, steps.checkoutCalls.size())
        Map checkoutCall = steps.checkoutCalls[0]
        assertTrue(checkoutCall.changelog)
        assertFalse(checkoutCall.poll)
        assertEquals([[$class: 'CleanBeforeCheckout']], checkoutCall.scm.extensions)
        assertEquals([[name: 'main']], checkoutCall.scm.branches)
        assertEquals([[url: 'git@example.com:team/service.git', credentialsId: 'ssh-git']], checkoutCall.scm.userRemoteConfigs)
        assertTrue(steps.dirCalls.contains('/workspace/code/code'))
        assertTrue(steps.shScripts.any { it.contains('mvn -Dmaven.test.skip=true package') })
        assertFalse(steps.shScripts.any { it.contains('git clone') })
    }

    static class FakeSteps {
        Map env = [WORKSPACE: '/workspace']
        FakeDocker docker = new FakeDocker()
        List<String> shScripts = []
        List<Map> checkoutCalls = []
        List<String> dirCalls = []

        void sh(String script) {
            shScripts.add(script)
        }

        void withDockerRegistry(Map args, Closure body) {
            body.call()
        }

        void dir(String path, Closure body) {
            dirCalls.add(path)
            body.call()
        }

        Map checkout(Map args) {
            checkoutCalls.add(args)
            return [:]
        }
    }

    static class FakeDocker {
        FakeDockerImage image(String imageName) {
            return new FakeDockerImage(imageName)
        }
    }

    static class FakeDockerImage {
        String imageName

        FakeDockerImage(String imageName) {
            this.imageName = imageName
        }

        void pull() {
        }

        void inside(String args, Closure body) {
            body.call()
        }
    }

    static class FakeStepsGit {
        void saveJenkinsSSHKey(String credentialsId, String path) {
        }

        void sshKeyscan(String gitUrl, String filePath) {
        }
    }
}

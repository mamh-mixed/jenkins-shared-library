package com.daluobai.jenkinslib.steps

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals

class StepsJenkinsTest {

    @Test
    void dockerPublishingKeepsFileArchiveNameForHostDeployment() {
        FakeSteps steps = new FakeSteps()
        new StepsJenkins(steps).stash([
                archiveType: 'JAR',
                jenkinsStash: [enable: true],
                dockerRegistry: [
                        enable: true,
                        imagePrefix: 'registry.example.com/team',
                        imageName: 'service',
                        imageVersion: 'v1',
                        dockerfile: [url: 'git@example.com:dockerfiles.git', gitBranch: 'main', path: 'java'],
                        buildArgs: [:]
                ]
        ])

        assertEquals('app.jar', steps.globalParameterMap.SHARE_PARAM.archiveName)
        assertEquals('registry.example.com/team/service:v1', steps.globalParameterMap.SHARE_PARAM.dockerImageName)
    }

    static class FakeSteps {
        Map globalParameterMap = [SHARE_PARAM: [appName: 'service']]
        Map currentBuild = [projectName: 'job']
        List<String> scripts = []

        void sh(String script) { scripts.add(script) }
        void dir(String ignored, Closure body) { body.call() }
        void git(Map ignored) { }
        void stash(Map ignored) { }
        void archiveArtifacts(Map ignored) { }
    }
}

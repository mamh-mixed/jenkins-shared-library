package com.daluobai.jenkinslib.steps

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

class StepsGitTest {

    @Test
    void syncSupportsExplicitMirrorModeWithoutChangingDefaultMode() {
        FakeSteps mirrorSteps = new FakeSteps()
        new TestableStepsGit(mirrorSteps).syncGit2Git('git@source:repo.git', 'source-key', 'git@target:repo.git', 'target-key', true)
        assertTrue(mirrorSteps.script.contains('git clone --quiet --mirror'))
        assertTrue(mirrorSteps.script.contains('push --quiet --mirror origin_target'))

        FakeSteps defaultSteps = new FakeSteps()
        new TestableStepsGit(defaultSteps).syncGit2Git('git@source:repo.git', 'source-key', 'git@target:repo.git', 'target-key')
        assertFalse(defaultSteps.script.contains('git clone --mirror'))
        assertTrue(defaultSteps.script.contains('push --quiet origin_target HEAD'))
    }

    static class TestableStepsGit extends StepsGit {
        TestableStepsGit(steps) { super(steps) }
        @Override
        def saveJenkinsSSHKey(String credentialsId, String path = '~/.ssh') { }
        @Override
        def sshKeyscan(String gitUrl, String filePath) { }
    }

    static class FakeSteps {
        Map env = [WORKSPACE: '/workspace']
        String script
        void sh(String script) { this.script = script }
    }
}

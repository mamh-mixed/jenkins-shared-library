package com.daluobai.jenkinslib.steps

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

class StepsWebTest {

    @Test
    void zipDeploymentExtractsAndValidatesBeforeSwitchingLiveApp() {
        FakeSteps steps = new FakeSteps('ZIP', 'app.zip')
        StepsWeb deployer = deployerFor(steps)

        deployer.deploy([labels: ['web-node'], pathRoot: '/srv/www'])

        int extractIndex = steps.scripts.findIndexOf { it.contains("unzip 'app.zip' -d .app-next/") }
        int validateIndex = steps.scripts.findIndexOf { it.contains('find .app-next -mindepth 1') }
        int switchIndex = steps.scripts.findIndexOf { it.contains('mv app .app-previous') }
        assertTrue(extractIndex >= 0)
        assertTrue(extractIndex < validateIndex)
        assertTrue(validateIndex < switchIndex)
        assertFalse(steps.scripts.take(switchIndex).any { it.contains('rm -rf app') })

        String switchScript = steps.scripts[switchIndex]
        assertTrue(switchScript.contains('mv .app-next app'))
        assertTrue(switchScript.contains('mv .app-previous app'))
        assertTrue(switchScript.contains('旧版本保留在 $(pwd)/.app-previous'))
        assertTrue(switchScript.contains('rm -rf .app-previous'))

        assertTrue(steps.scripts.any { it.contains("mkdir -p '/srv/www/demo' '/srv/www/demo/backup'") })
        assertTrue(steps.scripts.any { it.contains("'/srv/www/demo/backup/") })
        assertTrue(steps.scripts.contains('find . -mtime +3 -delete'))
    }

    @Test
    void tarDeploymentUsesSameStagingAndRollbackFlow() {
        FakeSteps steps = new FakeSteps('TAR', 'app.tar.gz')
        StepsWeb deployer = deployerFor(steps)

        deployer.deploy([labels: ['web-node'], pathRoot: '/srv/www'])

        int extractIndex = steps.scripts.findIndexOf { it.contains("tar -zxvf 'app.tar.gz' -C .app-next/") }
        int validateIndex = steps.scripts.findIndexOf { it.contains('find .app-next -mindepth 1') }
        int switchIndex = steps.scripts.findIndexOf { it.contains('mv app .app-previous') }
        assertTrue(extractIndex >= 0)
        assertTrue(extractIndex < validateIndex)
        assertTrue(validateIndex < switchIndex)
        assertFalse(steps.scripts.take(switchIndex).any { it.contains('rm -rf app') })
        assertTrue(steps.scripts[switchIndex].contains('mv .app-next app'))
        assertTrue(steps.scripts[switchIndex].contains('mv .app-previous app'))
    }

    @Test
    void staleRollbackWithoutLiveAppFailsAndPreservesRollbackDirectory() {
        FakeSteps steps = new FakeSteps('ZIP', 'app.zip')
        StepsWeb deployer = deployerFor(steps)

        deployer.deploy([labels: ['web-node'], pathRoot: '/srv/www'])

        String stagingScript = steps.scripts.find { it.contains('[ -e .app-previous ]') }
        assertTrue(stagingScript.contains('[ ! -e app ]'))
        assertTrue(stagingScript.contains('检测到未恢复的回滚目录: $(pwd)/.app-previous'))
        int missingAppCheckIndex = stagingScript.indexOf('[ ! -e app ]')
        int failIndex = stagingScript.indexOf('exit 1', missingAppCheckIndex)
        int cleanupIndex = stagingScript.indexOf('rm -rf .app-previous')
        assertTrue(missingAppCheckIndex >= 0)
        assertTrue(failIndex > missingAppCheckIndex)
        assertTrue(cleanupIndex > failIndex)
    }

    @Test
    void deploymentRejectsInvalidInputsBeforeTouchingFilesystem() {
        List<Map> cases = [
                [parameterMap: null, steps: new FakeSteps('ZIP', 'app.zip')],
                [parameterMap: [:], steps: new FakeSteps('ZIP', 'app.zip')],
                [parameterMap: [labels: [], pathRoot: '/srv/www'], steps: new FakeSteps('ZIP', 'app.zip')],
                [parameterMap: [labels: ['web-node'], pathRoot: ''], steps: new FakeSteps('ZIP', 'app.zip')],
                [parameterMap: [labels: ['web-node'], pathRoot: '/srv/www'], steps: new FakeSteps('ZIP', 'app.zip', '')],
                [parameterMap: [labels: ['web-node'], pathRoot: '/srv/www'], steps: new FakeSteps('ZIP', '')],
                [parameterMap: [labels: ['web-node'], pathRoot: '/srv/www'], steps: new FakeSteps(null, 'app.zip')]
        ]

        cases.each { Map testCase ->
            FakeSteps steps = testCase.steps as FakeSteps
            assertThrows(IllegalArgumentException.class) {
                deployerFor(steps).deploy(testCase.parameterMap as Map)
            }
            assertTrue(steps.scripts.isEmpty())
        }
    }

    @Test
    void deploymentRejectsUnsupportedArchiveBeforeTouchingFilesystem() {
        FakeSteps steps = new FakeSteps('JAR', 'app.jar')
        StepsWeb deployer = deployerFor(steps)

        assertThrows(IllegalArgumentException.class) {
            deployer.deploy([labels: ['web-node'], pathRoot: '/srv/www'])
        }
        assertTrue(steps.scripts.isEmpty())
    }

    private static StepsWeb deployerFor(FakeSteps steps) {
        StepsWeb deployer = new StepsWeb(steps)
        deployer.stepsJenkins = new FakeStepsJenkins()
        return deployer
    }

    static class FakeSteps {
        Map globalParameterMap
        List<String> scripts = []
        List<String> directories = []

        FakeSteps(String archiveType, String archiveName, String appName = 'demo') {
            globalParameterMap = [
                    SHARE_PARAM    : [appName: appName, archiveName: archiveName],
                    DEPLOY_PIPELINE: [stepsStorage: [archiveType: archiveType]]
            ]
        }

        void echo(Object ignored) { }
        void unstash(String ignored) { }
        void node(String ignored, Closure body) { body.call() }
        void sh(String script) { scripts.add(script) }
        void dir(String path, Closure body) {
            directories.add(path)
            body.call()
        }
    }

    static class FakeStepsJenkins {
        List<String> getNodeByLabel(String ignored) { ['agent-1'] }
    }
}

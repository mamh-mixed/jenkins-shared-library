package com.daluobai.jenkinslib.vars

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals

class SyncGit2GitVarTest extends DeployPipelineVarTestSupport {

    @Test
    void mergeConfigLoadsExistingExtensionFromFullPath() {
        List<String> reads = []
        Script script = loadPipelineScript('vars/syncGit2Git.groovy', [
                'config/config.json': [SYNC: [defaultKey: 'default-value']],
                'config/extend.json': [SYNC: [extendKey: 'extend-value']]
        ], [:], reads)

        Map result = script.invokeMethod('mergeConfig', [[
                CONFIG_EXTEND: [configFullPath: 'RESOURCES:config/extend.json'],
                SYNC         : [customKey: 'custom-value']
        ]] as Object[]) as Map

        assertEquals(['config/config.json', 'config/extend.json'], reads)
        assertEquals('default-value', result.SYNC.defaultKey)
        assertEquals('extend-value', result.SYNC.extendKey)
        assertEquals('custom-value', result.SYNC.customKey)
    }

    @Test
    void mergeConfigSkipsMissingExtensionResource() {
        List<String> reads = []
        Script script = loadPipelineScript('vars/syncGit2Git.groovy', [
                'config/config.json': [SYNC: [defaultKey: 'default-value']]
        ], [:], reads)

        Map result = script.invokeMethod('mergeConfig', [[
                CONFIG_EXTEND: [configFullPath: 'RESOURCES:config/missing.json'],
                SYNC         : [customKey: 'custom-value']
        ]] as Object[]) as Map

        assertEquals(['config/config.json', 'config/missing.json'], reads)
        assertEquals('default-value', result.SYNC.defaultKey)
        assertEquals('custom-value', result.SYNC.customKey)
    }
}

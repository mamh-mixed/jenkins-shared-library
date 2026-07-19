package com.daluobai.jenkinslib.vars

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

class DeployWebVarTest extends DeployPipelineVarTestSupport {

    @Test
    void mergeConfigLoadsExtensionAndKeepsParameterPrecedence() {
        List<String> reads = []
        Script script = loadPipelineScript('vars/deployWeb.groovy', [
                'config/config.json': disabledWebConfig([appName: 'default-app']),
                'config/extend.json': [SHARE_PARAM: [appName: 'extend-app', fromExtend: true]]
        ], ['SHARE_PARAM.appName': 'parameter-app'], reads)

        Map result = script.invokeMethod('mergeConfig', [[
                CONFIG_EXTEND: [configFullPath: 'RESOURCES:config/extend.json'],
                SHARE_PARAM : [appName: 'custom-app']
        ]] as Object[]) as Map

        assertEquals(['config/config.json', 'config/extend.json'], reads)
        assertTrue(result.SHARE_PARAM.fromExtend)
        assertEquals('parameter-app', result.SHARE_PARAM.appName)
    }

    @Test
    void mergeConfigRejectsInvalidExtensionType() {
        Script script = loadPipelineScript('vars/deployWeb.groovy', [
                'config/config.json': disabledWebConfig()
        ])

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class) {
            script.invokeMethod('mergeConfig', [[
                    CONFIG_EXTEND: [configFullPath: 'UNKNOWN:config/extend.json']
            ]] as Object[])
        }

        assertTrue(error.message.contains('配置类型为空'))
    }

    @Test
    void callUsesShareParamProvidedOnlyByExtensionConfig() {
        Script script = loadPipelineScript('vars/deployWeb.groovy', [
                'config/config.json': disabledWebConfig(),
                'config/extend.json': [SHARE_PARAM: [
                        appName: 'extension-web',
                        message: [wecom: [key: '']]
                ]]
        ])

        script.invokeMethod('call', [[
                CONFIG_EXTEND: [configFullPath: 'RESOURCES:config/extend.json']
        ]] as Object[])

        Map effectiveConfig = script.getProperty('globalParameterMap') as Map
        assertEquals('extension-web', effectiveConfig.SHARE_PARAM.appName)
    }

    @Test
    void callFallsBackToJenkinsProjectNameAfterMerge() {
        Script script = loadPipelineScript('vars/deployWeb.groovy', [
                'config/config.json': disabledWebConfig()
        ])

        script.invokeMethod('call', [[:]] as Object[])

        Map effectiveConfig = script.getProperty('globalParameterMap') as Map
        assertEquals('jenkins-project', effectiveConfig.SHARE_PARAM.appName)
    }
}

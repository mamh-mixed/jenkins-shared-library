package com.daluobai.jenkinslib.vars

import com.daluobai.jenkinslib.utils.MessageUtils
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

class DeployWebVarTest extends DeployPipelineVarTestSupport {

    @Test
    void mergeConfigLoadsExtensionAndKeepsParameterPrecedence() {
        List<String> reads = []
        Script script = loadPipelineScript('vars/deployWeb.groovy', [
                'config/config.json': disabledWebConfig([
                        appName: 'default-app',
                        extendWins: 'default-value',
                        customWins: 'default-value'
                ]),
                'config/extend.json': [SHARE_PARAM: [
                        appName: 'extend-app',
                        extendWins: 'extend-value',
                        customWins: 'extend-value'
                ]]
        ], ['SHARE_PARAM.appName': 'parameter-app'], reads)

        Map result = script.invokeMethod('mergeConfig', [[
                CONFIG_EXTEND: [configFullPath: 'RESOURCES:config/extend.json'],
                SHARE_PARAM : [appName: 'custom-app', customWins: 'custom-value']
        ]] as Object[]) as Map

        assertEquals(['config/config.json', 'config/extend.json'], reads)
        assertEquals('extend-value', result.SHARE_PARAM.extendWins)
        assertEquals('custom-value', result.SHARE_PARAM.customWins)
        assertEquals('parameter-app', result.SHARE_PARAM.appName)
    }

    @Test
    void mergeConfigSkipsMissingExtensionResource() {
        List<String> reads = []
        Script script = loadPipelineScript('vars/deployWeb.groovy', [
                'config/config.json': disabledWebConfig([
                        appName   : 'default-app',
                        defaultKey: 'default-value'
                ])
        ], ['SHARE_PARAM.appName': 'parameter-app'], reads)

        Map result = script.invokeMethod('mergeConfig', [[
                CONFIG_EXTEND: [configFullPath: 'RESOURCES:config/missing.json'],
                SHARE_PARAM : [customKey: 'custom-value']
        ]] as Object[]) as Map

        assertEquals(['config/config.json', 'config/missing.json'], reads)
        assertEquals('default-value', result.SHARE_PARAM.defaultKey)
        assertEquals('custom-value', result.SHARE_PARAM.customKey)
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
    void callPreservesInvalidExtensionErrorWhenFinallyHasNoNotificationConfig() {
        Script script = loadPipelineScript('vars/deployWeb.groovy', [
                'config/config.json': disabledWebConfig()
        ])

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class) {
            script.invokeMethod('call', [[
                    CONFIG_EXTEND: [configFullPath: 'UNKNOWN:config/extend.json']
            ]] as Object[])
        }

        assertTrue(error.message.contains('配置类型为空'))
    }

    @Test
    void callPreservesInvalidExtensionErrorWhenFailureNotificationThrowsAndStillDeletesWorkspace() {
        Script script = loadPipelineScript('vars/deployWeb.groovy', [
                'config/config.json': disabledWebConfig()
        ])
        List<String> cleanupCalls = []
        script.metaClass.deleteDir = { -> cleanupCalls.add('deleteDir') }
        MessageUtils.metaClass.sendMessage = { Object ignoredConfig, String ignoredTitle, String ignoredContent ->
            throw new IllegalStateException('notification failed')
        }

        try {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class) {
                script.invokeMethod('call', [[
                        CONFIG_EXTEND: [configFullPath: 'UNKNOWN:config/extend.json'],
                        SHARE_PARAM : [appName: 'web-app', message: [wecom: [key: 'test']]]
                ]] as Object[])
            }

            assertTrue(error.message.contains('配置类型为空'))
            assertEquals(['deleteDir'], cleanupCalls)
        } finally {
            GroovySystem.metaClassRegistry.removeMetaClass(MessageUtils)
        }
    }

    @Test
    void callPreservesInvalidExtensionErrorWhenDeleteDirThrows() {
        Script script = loadPipelineScript('vars/deployWeb.groovy', [
                'config/config.json': disabledWebConfig()
        ])
        script.metaClass.deleteDir = { -> throw new IllegalStateException('deleteDir failed') }

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class) {
            script.invokeMethod('call', [[
                    CONFIG_EXTEND: [configFullPath: 'UNKNOWN:config/extend.json']
            ]] as Object[])
        }

        assertTrue(error.message.contains('配置类型为空'))
    }

    @Test
    void callUsesExtensionShareParamForStartAndCompletionNotifications() {
        Map extensionMessage = [wecom: [key: '']]
        Script script = loadPipelineScript('vars/deployWeb.groovy', [
                'config/config.json': disabledWebConfig(),
                'config/extend.json': [SHARE_PARAM: [
                        appName: 'extension-web',
                        message: extensionMessage
                ]]
        ])
        List<List<Object>> sentMessages = []
        MessageUtils.metaClass.sendMessage = { boolean simpleMessage, Object messageConfig, String title, String content ->
            sentMessages.add([simpleMessage, messageConfig, title, content])
            return true
        }
        MessageUtils.metaClass.sendMessage = { Object messageConfig, String title, String content ->
            sentMessages.add([messageConfig, title, content])
            return true
        }

        try {
            script.invokeMethod('call', [[
                    CONFIG_EXTEND: [configFullPath: 'RESOURCES:config/extend.json']
            ]] as Object[])
        } finally {
            GroovySystem.metaClassRegistry.removeMetaClass(MessageUtils)
        }

        Map effectiveConfig = script.getProperty('globalParameterMap') as Map
        assertEquals('extension-web', effectiveConfig.SHARE_PARAM.appName)
        assertEquals(2, sentMessages.size())
        assertEquals([false, extensionMessage, '发布开始：extension-web', '发布开始: job #1'], sentMessages[0])
        assertEquals([extensionMessage, '成功:extension-web', '发布成功: job #1'], sentMessages[1])
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

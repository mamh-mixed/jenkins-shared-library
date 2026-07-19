package com.daluobai.jenkinslib.vars

import com.daluobai.jenkinslib.utils.MessageUtils
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

class DeployJavaWebVarTest extends DeployPipelineVarTestSupport {

    @Test
    void mergeConfigKeepsCompleteSourceAndParameterPrecedence() {
        List<String> reads = []
        Script script = loadPipelineScript('vars/deployJavaWeb.groovy', [
                'config/config.json': disabledJavaConfig([
                        appName    : 'default-app',
                        defaultOnly: 'default-value',
                        extendWins: 'default-value',
                        customWins: 'default-value'
                ]),
                'config/extend.json': [SHARE_PARAM: [
                        appName    : 'extend-app',
                        extendWins: 'extend-value',
                        customWins: 'extend-value'
                ]]
        ], ['SHARE_PARAM.appName': 'parameter-app'], reads)

        Map result = script.invokeMethod('mergeConfig', [[
                CONFIG_EXTEND: [configFullPath: 'RESOURCES:config/extend.json'],
                SHARE_PARAM : [appName: 'custom-app', customWins: 'custom-value']
        ]] as Object[]) as Map

        assertEquals(['config/config.json', 'config/extend.json'], reads)
        assertEquals('default-value', result.SHARE_PARAM.defaultOnly)
        assertEquals('extend-value', result.SHARE_PARAM.extendWins)
        assertEquals('custom-value', result.SHARE_PARAM.customWins)
        assertEquals('parameter-app', result.SHARE_PARAM.appName)
    }

    @Test
    void callPreservesInvalidExtensionErrorWhenFinallyHasNoNotificationConfig() {
        Script script = loadPipelineScript('vars/deployJavaWeb.groovy', [
                'config/config.json': disabledJavaConfig()
        ])

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class) {
            script.invokeMethod('call', [[
                    CONFIG_EXTEND: [configFullPath: 'UNKNOWN:config/extend.json']
            ]] as Object[])
        }

        assertTrue(error.message.contains('配置类型为空'))
    }

    @Test
    void mergeConfigNormalizesLegacyCustomBeforeApplyingSourcePrecedence() {
        Map defaultConfig = disabledJavaConfig()
        defaultConfig.DEPLOY_PIPELINE.stepsBuild = [
                enable         : false,
                stepsBuildEnv  : [FROM_DEFAULT: 'true'],
                stepsBuildMaven: [gitUrl: 'default.git', defaultOnly: 'kept']
        ]
        Script script = loadPipelineScript('vars/deployJavaWeb.groovy', [
                'config/config.json': defaultConfig
        ])

        Map result = script.invokeMethod('mergeConfig', [[
                DEPLOY_PIPELINE: [
                        stepsBuildMaven: [enable: true, gitUrl: 'legacy-custom.git']
                ]
        ]] as Object[]) as Map

        assertEquals(true, result.DEPLOY_PIPELINE.stepsBuild.enable)
        assertEquals('legacy-custom.git', result.DEPLOY_PIPELINE.stepsBuild.stepsBuildMaven.gitUrl)
        assertEquals('kept', result.DEPLOY_PIPELINE.stepsBuild.stepsBuildMaven.defaultOnly)
        assertEquals('true', result.DEPLOY_PIPELINE.stepsBuild.stepsBuildEnv.FROM_DEFAULT)
        assertFalse(result.DEPLOY_PIPELINE.containsKey('stepsBuildMaven'))
    }

    @Test
    void compatibleConfigKeepsCurrentStructureWhenBothLayoutsExist() {
        Script script = loadPipelineScript('vars/deployJavaWeb.groovy', [
                'config/config.json': disabledJavaConfig()
        ])
        Map source = [
                DEPLOY_PIPELINE: [
                        stepsBuildMaven: [enable: true, gitUrl: 'legacy.git'],
                        stepsBuild     : [
                                enable         : false,
                                stepsBuildEnv  : [PROFILE: 'prod'],
                                stepsBuildMaven: [gitUrl: 'current.git']
                        ]
                ]
        ]

        Map result = script.invokeMethod('compatibleConfig', [source] as Object[]) as Map

        assertEquals(false, result.DEPLOY_PIPELINE.stepsBuild.enable)
        assertEquals('current.git', result.DEPLOY_PIPELINE.stepsBuild.stepsBuildMaven.gitUrl)
        assertEquals('prod', result.DEPLOY_PIPELINE.stepsBuild.stepsBuildEnv.PROFILE)
        assertFalse(result.DEPLOY_PIPELINE.containsKey('stepsBuildMaven'))
        assertTrue(source.DEPLOY_PIPELINE.containsKey('stepsBuildMaven'))
    }

    @Test
    void compatibleConfigUsesLegacyEnableWithoutDroppingCurrentAdjacentFields() {
        Script script = loadPipelineScript('vars/deployJavaWeb.groovy', [
                'config/config.json': disabledJavaConfig()
        ])

        Map result = script.invokeMethod('compatibleConfig', [[
                DEPLOY_PIPELINE: [
                        stepsBuildMaven: [enable: false, gitUrl: 'legacy.git'],
                        stepsBuild     : [stepsBuildEnv: [PROFILE: 'test']]
                ]
        ]] as Object[]) as Map

        assertEquals(false, result.DEPLOY_PIPELINE.stepsBuild.enable)
        assertEquals('legacy.git', result.DEPLOY_PIPELINE.stepsBuild.stepsBuildMaven.gitUrl)
        assertEquals('test', result.DEPLOY_PIPELINE.stepsBuild.stepsBuildEnv.PROFILE)
    }

    @Test
    void callUsesExtensionShareParamForStartAndCompletionNotifications() {
        Map extensionMessage = [wecom: [key: '']]
        Script script = loadPipelineScript('vars/deployJavaWeb.groovy', [
                'config/config.json': disabledJavaConfig(),
                'config/extend.json': [SHARE_PARAM: [
                        appName: 'extension-java',
                        message: extensionMessage
                ]]
        ])
        List<List<Object>> sentMessages = []
        MessageUtils.metaClass.sendMessage = { boolean simpleMessage, Object messageConfig, String title, String content ->
            sentMessages.add([simpleMessage, messageConfig, title, content])
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
        assertEquals('extension-java', effectiveConfig.SHARE_PARAM.appName)
        assertEquals(2, sentMessages.size())
        assertEquals([false, extensionMessage, '发布开始：extension-java', '发布开始: job #1'], sentMessages[0])
        assertEquals([true, extensionMessage, '成功:extension-java', '发布成功: job #1'], sentMessages[1])
    }

    @Test
    void callFallsBackToJenkinsProjectNameAfterMerge() {
        Script script = loadPipelineScript('vars/deployJavaWeb.groovy', [
                'config/config.json': disabledJavaConfig()
        ])

        script.invokeMethod('call', [[:]] as Object[])

        Map effectiveConfig = script.getProperty('globalParameterMap') as Map
        assertEquals('jenkins-project', effectiveConfig.SHARE_PARAM.appName)
    }
}

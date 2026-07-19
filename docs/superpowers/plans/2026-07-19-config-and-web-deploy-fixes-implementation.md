# Jenkins Config and Safe Web Deploy Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 #2、#4、#6、#7，使扩展配置和旧版 Java 配置可靠归一化，Pipeline 全程使用合并后的配置，Web 发布在暂存验证后切换并可回滚，同时修正 PID 服务脚本目录。

**Architecture:** 配置层在每个来源进入合并前完成旧结构归一化，再按“默认 < 扩展 < 自定义 < Jenkins 参数”生成唯一 `fullConfig`；两个 Pipeline 入口及通知只消费该对象。Web 部署保留现有物理 `app` 目录，在同一父目录使用 `.app-next` 解压验证、`.app-previous` 回滚，最后通过同文件系统重命名完成近原子切换。

**Tech Stack:** Groovy 4、Jenkins Shared Library Pipeline DSL、JUnit Jupiter 5、Gradle Wrapper、Bash、GitNexus

## Global Constraints

- 以 `docs/superpowers/specs/2026-07-19-config-and-web-deploy-fixes-design.md` 为唯一设计依据。
- 旧版配置继续可用；兼容转换集中在配置读取/合并入口，构建与部署实现只消费统一结构。
- 配置优先级固定为 Jenkins 参数 > `customConfig` > 扩展配置 > 默认配置。
- 同一配置来源内新 Java 构建结构优先；跨来源仍遵守配置来源优先级。
- 不新增必填配置项，不修改现有 Jenkinsfile 调用方式、部署根目录、归档备份目录和 Web 服务器站点根目录。
- Web 解压或校验失败时不得移动、删除线上 `app`；切换失败时必须尝试恢复 `.app-previous`。
- 不将物理 `app` 改成符号链接，不增加部署后业务健康检查。
- 所有 Java/Gradle 命令在同一 PowerShell 命令中设置 `JAVA_HOME=C:\workspace\env\jdk\jdk21` 和 `PATH`。
- 修改任何代码符号前必须运行 GitNexus `impact`；若风险为 HIGH 或 CRITICAL，先向用户警告并停止编辑。
- 每次提交前只暂存本任务文件，并运行 GitNexus `detect_changes({scope: "staged"})`。
- 保留工作区现有 `AGENTS.md` 修改，不读取、覆盖、暂存或提交该修改。

---

### Task 0: 变更前影响分析

**Files:**
- Inspect: `vars/deployWeb.groovy`
- Inspect: `vars/deployJavaWeb.groovy`
- Inspect: `src/com/daluobai/jenkinslib/steps/StepsWeb.groovy`
- Inspect: `resources/template/shell/javaWeb/serviceByPID.sh`

**Interfaces:**
- Consumes: GitNexus 仓库索引 `jenkins-shared-library`。
- Produces: `call`、`mergeConfig`、`compatibleConfig`、`StepsWeb.deploy` 和服务模板的上游爆炸半径及风险结论。

- [ ] **Step 1: 对两个 Pipeline 入口和配置函数运行上游影响分析**

依次调用：

```text
impact({repo: "jenkins-shared-library", target: "call", file_path: "vars/deployWeb.groovy", direction: "upstream", includeTests: true})
impact({repo: "jenkins-shared-library", target: "mergeConfig", file_path: "vars/deployWeb.groovy", direction: "upstream", includeTests: true})
impact({repo: "jenkins-shared-library", target: "call", file_path: "vars/deployJavaWeb.groovy", direction: "upstream", includeTests: true})
impact({repo: "jenkins-shared-library", target: "mergeConfig", file_path: "vars/deployJavaWeb.groovy", direction: "upstream", includeTests: true})
impact({repo: "jenkins-shared-library", target: "compatibleConfig", file_path: "vars/deployJavaWeb.groovy", direction: "upstream", includeTests: true})
```

Expected: 返回每个符号的直接调用者、受影响流程和风险；若 Groovy 符号未被索引，结果应明确记录为 UNKNOWN，而不能当作零风险。

- [ ] **Step 2: 对 Web 部署方法和模板文件运行影响分析**

```text
impact({repo: "jenkins-shared-library", target: "deploy", kind: "Method", file_path: "src/com/daluobai/jenkinslib/steps/StepsWeb.groovy", direction: "upstream", includeTests: true})
impact({repo: "jenkins-shared-library", target: "resources/template/shell/javaWeb/serviceByPID.sh", direction: "upstream", includeTests: true})
```

Expected: `StepsWeb.deploy` 的直接调用面包含 Web Pipeline；模板若没有符号节点则记录 UNKNOWN。

- [ ] **Step 3: 向用户报告爆炸半径后再继续**

报告必须包含：每个目标的风险级别、直接调用者数量、受影响流程，以及 GitNexus 对 Groovy/资源文件的索引局限。HIGH 或 CRITICAL 时停止；LOW、MEDIUM 或 UNKNOWN 时说明回归测试补偿方案后进入 Task 1。

---

### Task 1: Web Pipeline 扩展配置与统一运行时配置

**Files:**
- Create: `test/com/daluobai/jenkinslib/vars/DeployPipelineVarTestSupport.groovy`
- Create: `test/com/daluobai/jenkinslib/vars/DeployWebVarTest.groovy`
- Modify: `vars/deployWeb.groovy:25-109`
- Modify: `vars/deployWeb.groovy:125-145`

**Interfaces:**
- Consumes: `ConfigUtils.readConfigFromFullPath(String)`、`MapUtils.merge(List<Map>)`、`ConfigMergeUtils.mergeParams(Map, Map)`。
- Produces: `mergeConfig(Map): Map`，返回默认、扩展、自定义及参数合并后的深拷贝；`call(Map)` 从合并开始到通知结束只使用 `fullConfig`。

- [ ] **Step 1: 创建 Pipeline 变量测试支撑类**

创建 `test/com/daluobai/jenkinslib/vars/DeployPipelineVarTestSupport.groovy`：

```groovy
package com.daluobai.jenkinslib.vars

import com.daluobai.jenkinslib.utils.ConfigUtils
import groovy.json.JsonOutput
import groovy.lang.Binding
import groovy.lang.GroovyShell

abstract class DeployPipelineVarTestSupport {

    protected static Script loadPipelineScript(String scriptPath,
                                               Map<String, Map> resources,
                                               Map params = [:],
                                               List<String> resourceReads = []) {
        Binding binding = new Binding([
                params      : params,
                currentBuild: [projectName: 'jenkins-project', fullDisplayName: 'job #1'],
                BUILD_URL   : 'https://jenkins.example/job/1/'
        ])
        GroovyShell shell = new GroovyShell(ConfigUtils.class.classLoader, binding)
        Script script = shell.parse(new File(scriptPath))

        script.metaClass.libraryResource = { String path ->
            resourceReads.add(path)
            if (!resources.containsKey(path)) {
                throw new IllegalArgumentException("测试资源不存在: ${path}")
            }
            return JsonOutput.toJson(resources[path])
        }
        script.metaClass.nodesByLabel = { Map ignored -> ['agent-1'] }
        script.metaClass.echo = { Object ignored -> }
        script.metaClass.error = { Object message -> throw new IllegalStateException(message.toString()) }
        script.metaClass.node = { String ignored, Closure body -> body.call() }
        script.metaClass.stage = { String ignored, Closure body -> body.call() }
        script.metaClass.withEnv = { List ignored, Closure body -> body.call() }
        script.metaClass.deleteDir = { -> }
        return script
    }

    protected static Map disabledWebConfig(Map shareParam = [:]) {
        return [
                SHARE_PARAM    : shareParam,
                DEPLOY_PIPELINE: [
                        stepsBuildNpm                   : [enable: false],
                        stepsStorage                    : [enable: false],
                        stepsJavaWebDeployToWebServer    : [enable: false]
                ]
        ]
    }

    protected static Map disabledJavaConfig(Map shareParam = [:]) {
        return [
                SHARE_PARAM    : shareParam,
                DEPLOY_PIPELINE: [
                        stepsBuild  : [enable: false, stepsBuildMaven: [:]],
                        stepsStorage: [enable: false],
                        stepsDeploy : [enable: false]
                ]
        ]
    }
}
```

- [ ] **Step 2: 编写 Web Pipeline 失败测试**

创建 `test/com/daluobai/jenkinslib/vars/DeployWebVarTest.groovy`：

```groovy
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
```

- [ ] **Step 3: 运行测试并确认当前实现失败**

Run:

```powershell
$env:JAVA_HOME='C:\workspace\env\jdk\jdk21'; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"; .\gradlew.bat test --tests "com.daluobai.jenkinslib.vars.DeployWebVarTest" --rerun-tasks --no-daemon
```

Expected: FAIL；至少包含扩展资源未读取、非法类型未抛错或 `customConfig.SHARE_PARAM` 空指针中的一项，不允许因测试夹具编译错误而失败。

- [ ] **Step 4: 修复 `deployWeb.mergeConfig` 的扩展读取**

将扩展读取和合并主体替换为：

```groovy
def mergeConfig(Map customConfig) {
    Map defaultConfig = new ConfigUtils(this).readConfig(
            EFileReadType.RESOURCES,
            defaultConfigPath(EFileReadType.RESOURCES)
    )
    Map extendConfig = [:]
    String configFullPath = customConfig?.CONFIG_EXTEND?.configFullPath?.toString()
    if (StrUtils.isNotBlank(configFullPath)) {
        extendConfig = new ConfigUtils(this).readConfigFromFullPath(configFullPath)
    }

    Map fullConfig = MapUtils.merge([defaultConfig, extendConfig, customConfig ?: [:]])
    fullConfig = ConfigMergeUtils.mergeParams(fullConfig, params)
    return MapUtils.deepCopy(fullConfig)
}
```

- [ ] **Step 5: 让 `deployWeb.call` 全程使用合并配置**

在进入 `node` 前声明：

```groovy
Map fullConfig = [:]
```

删除合并前对 `customConfig.SHARE_PARAM` 的默认值设置和局部别名。在 `try` 开头替换为：

```groovy
fullConfig = mergeConfig(customConfig ?: [:])
if (!(fullConfig.SHARE_PARAM instanceof Map)) {
    fullConfig.SHARE_PARAM = [:]
}
if (StrUtils.isBlank(fullConfig.SHARE_PARAM.appName)) {
    fullConfig.SHARE_PARAM.appName = currentBuild.projectName
}
echo "配置加载完成，appName=${fullConfig.SHARE_PARAM.appName}"
globalParameterMap = fullConfig

messageUtils.sendMessage(
        false,
        fullConfig.SHARE_PARAM.message,
        "发布开始：${fullConfig.SHARE_PARAM.appName}",
        "发布开始: ${currentBuild.fullDisplayName}"
)
```

保留 Pipeline 阶段逻辑，但阶段内只使用 `fullConfig`。将 `finally` 中通知配置解析为：

```groovy
Map notificationShareParam = fullConfig?.SHARE_PARAM instanceof Map
        ? fullConfig.SHARE_PARAM as Map
        : (customConfig?.SHARE_PARAM instanceof Map ? customConfig.SHARE_PARAM as Map : [:])
if (ObjUtils.isNotEmpty(notificationShareParam.message)) {
    String messageTitle = ''
    String messageContent = ''
    if (eBuildStatusType == EBuildStatusType.SUCCESS) {
        messageTitle = "成功:${notificationShareParam.appName}"
        messageContent = "发布成功: ${currentBuild.fullDisplayName}"
    } else if (eBuildStatusType == EBuildStatusType.FAILED) {
        messageTitle = "失败:${notificationShareParam.appName}"
        messageContent = "发布失败: ${currentBuild.fullDisplayName},异常信息: ${errMessage},构建日志:(${BUILD_URL}console)"
    }
    if (StrUtils.isNotBlank(messageTitle) && StrUtils.isNotBlank(messageContent)) {
        messageUtils.sendMessage(notificationShareParam.message, messageTitle, messageContent)
    }
}
deleteDir()
```

完成后运行：

```powershell
$env:JAVA_HOME='C:\workspace\env\jdk\jdk21'; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"; .\gradlew.bat test --tests "com.daluobai.jenkinslib.vars.DeployWebVarTest" --rerun-tasks --no-daemon
```

Expected: PASS，4 tests completed，0 failed。

- [ ] **Step 6: 暂存、检查影响并提交 Web 配置修复**

```powershell
git add -- 'test/com/daluobai/jenkinslib/vars/DeployPipelineVarTestSupport.groovy' 'test/com/daluobai/jenkinslib/vars/DeployWebVarTest.groovy' 'vars/deployWeb.groovy'
```

调用：

```text
detect_changes({repo: "jenkins-shared-library", scope: "staged", worktree: "C:\workspace\code\github\daluobai-devops\jenkins-shared-library"})
```

确认仅包含上述文件后提交：

```powershell
git commit -m "fix: 统一 Web Pipeline 配置来源"
```

---

### Task 2: Java Pipeline 扩展配置与旧版结构归一化

**Files:**
- Create: `test/com/daluobai/jenkinslib/vars/DeployJavaWebVarTest.groovy`
- Modify: `vars/deployJavaWeb.groovy:22-123`
- Modify: `vars/deployJavaWeb.groovy:139-169`
- Reuse: `test/com/daluobai/jenkinslib/vars/DeployPipelineVarTestSupport.groovy`

**Interfaces:**
- Consumes: Task 1 的 `loadPipelineScript` 和 `disabledJavaConfig` 测试夹具。
- Produces: `compatibleConfig(Map): Map`，把单个配置来源的旧 `stepsBuildMaven` 归一化为当前结构且不修改输入；`mergeConfig(Map): Map` 在来源归一化后合并并应用参数。

- [ ] **Step 1: 编写 Java Pipeline 失败测试**

创建 `test/com/daluobai/jenkinslib/vars/DeployJavaWebVarTest.groovy`：

```groovy
package com.daluobai.jenkinslib.vars

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

class DeployJavaWebVarTest extends DeployPipelineVarTestSupport {

    @Test
    void mergeConfigLoadsExtensionAndKeepsParameterPrecedence() {
        List<String> reads = []
        Script script = loadPipelineScript('vars/deployJavaWeb.groovy', [
                'config/config.json': disabledJavaConfig([appName: 'default-app']),
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
        Script script = loadPipelineScript('vars/deployJavaWeb.groovy', [
                'config/config.json': disabledJavaConfig()
        ])

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class) {
            script.invokeMethod('mergeConfig', [[
                    CONFIG_EXTEND: [configFullPath: 'UNKNOWN:config/extend.json']
            ]] as Object[])
        }

        assertTrue(error.message.contains('配置类型为空'))
    }

    @Test
    void mergeConfigNormalizesEachSourceBeforeApplyingPrecedence() {
        Map defaultConfig = disabledJavaConfig()
        defaultConfig.DEPLOY_PIPELINE.stepsBuild = [
                enable         : false,
                stepsBuildEnv  : [FROM_DEFAULT: 'true'],
                stepsBuildMaven: [gitUrl: 'default.git']
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
        assertEquals('true', result.DEPLOY_PIPELINE.stepsBuild.stepsBuildEnv.FROM_DEFAULT)
        assertFalse(result.DEPLOY_PIPELINE.containsKey('stepsBuildMaven'))
    }

    @Test
    void compatibleConfigKeepsCurrentStructureWhenBothLayoutsExist() {
        Script script = loadPipelineScript('vars/deployJavaWeb.groovy', [
                'config/config.json': disabledJavaConfig()
        ])

        Map result = script.invokeMethod('compatibleConfig', [[
                DEPLOY_PIPELINE: [
                        stepsBuildMaven: [enable: true, gitUrl: 'legacy.git'],
                        stepsBuild     : [
                                enable         : false,
                                stepsBuildEnv  : [PROFILE: 'prod'],
                                stepsBuildMaven: [gitUrl: 'current.git']
                        ]
                ]
        ]] as Object[]) as Map

        assertEquals(false, result.DEPLOY_PIPELINE.stepsBuild.enable)
        assertEquals('current.git', result.DEPLOY_PIPELINE.stepsBuild.stepsBuildMaven.gitUrl)
        assertEquals('prod', result.DEPLOY_PIPELINE.stepsBuild.stepsBuildEnv.PROFILE)
        assertFalse(result.DEPLOY_PIPELINE.containsKey('stepsBuildMaven'))
    }

    @Test
    void callUsesShareParamProvidedOnlyByExtensionConfig() {
        Script script = loadPipelineScript('vars/deployJavaWeb.groovy', [
                'config/config.json': disabledJavaConfig(),
                'config/extend.json': [SHARE_PARAM: [
                        appName: 'extension-java',
                        message: [wecom: [key: '']]
                ]]
        ])

        script.invokeMethod('call', [[
                CONFIG_EXTEND: [configFullPath: 'RESOURCES:config/extend.json']
        ]] as Object[])

        Map effectiveConfig = script.getProperty('globalParameterMap') as Map
        assertEquals('extension-java', effectiveConfig.SHARE_PARAM.appName)
    }
}
```

- [ ] **Step 2: 运行测试并确认当前实现失败**

```powershell
$env:JAVA_HOME='C:\workspace\env\jdk\jdk21'; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"; .\gradlew.bat test --tests "com.daluobai.jenkinslib.vars.DeployJavaWebVarTest" --rerun-tasks --no-daemon
```

Expected: FAIL；扩展配置未读取、旧兼容逻辑覆盖当前结构或合并前访问 `SHARE_PARAM`。测试夹具本身必须成功编译。

- [ ] **Step 3: 将旧配置转换改成按来源归一化**

用以下完整实现替换 `compatibleConfig`：

```groovy
def compatibleConfig(Map config) {
    Map normalizedConfig = MapUtils.deepCopy(config ?: [:])
    if (!(normalizedConfig.DEPLOY_PIPELINE instanceof Map)) {
        return normalizedConfig
    }

    Map deployPipeline = normalizedConfig.DEPLOY_PIPELINE as Map
    if (!deployPipeline.containsKey('stepsBuildMaven')) {
        return normalizedConfig
    }

    def legacyStepsBuildMaven = deployPipeline.remove('stepsBuildMaven')
    Map stepsBuild = deployPipeline.stepsBuild instanceof Map
            ? MapUtils.deepCopy(deployPipeline.stepsBuild as Map)
            : [:]

    if (!stepsBuild.containsKey('stepsBuildMaven')) {
        stepsBuild.stepsBuildMaven = MapUtils.deepCopy(legacyStepsBuildMaven)
        if (!stepsBuild.containsKey('enable')) {
            stepsBuild.enable = legacyStepsBuildMaven instanceof Map && legacyStepsBuildMaven.enable != null
                    ? legacyStepsBuildMaven.enable
                    : true
        }
    }
    deployPipeline.stepsBuild = stepsBuild
    return normalizedConfig
}
```

用以下完整实现替换 `mergeConfig`：

```groovy
def mergeConfig(Map customConfig) {
    Map defaultConfig = compatibleConfig(new ConfigUtils(this).readConfig(
            EFileReadType.RESOURCES,
            defaultConfigPath(EFileReadType.RESOURCES)
    ))
    Map extendConfig = [:]
    String configFullPath = customConfig?.CONFIG_EXTEND?.configFullPath?.toString()
    if (StrUtils.isNotBlank(configFullPath)) {
        extendConfig = compatibleConfig(new ConfigUtils(this).readConfigFromFullPath(configFullPath))
    }
    Map normalizedCustomConfig = compatibleConfig(customConfig ?: [:])

    Map fullConfig = MapUtils.merge([defaultConfig, extendConfig, normalizedCustomConfig])
    fullConfig = ConfigMergeUtils.mergeParams(fullConfig, params)
    return MapUtils.deepCopy(fullConfig)
}
```

- [ ] **Step 4: 让 `deployJavaWeb.call` 全程使用合并配置**

按 Task 1 的入口模式，在 `node` 前声明 `Map fullConfig = [:]`，删除合并前的 `customConfig.SHARE_PARAM` 读取，并在 `try` 开头使用：

```groovy
fullConfig = mergeConfig(customConfig ?: [:])
if (!(fullConfig.SHARE_PARAM instanceof Map)) {
    fullConfig.SHARE_PARAM = [:]
}
if (StrUtils.isBlank(fullConfig.SHARE_PARAM.appName)) {
    fullConfig.SHARE_PARAM.appName = currentBuild.projectName
}
echo "配置加载完成，appName=${fullConfig.SHARE_PARAM.appName}"
globalParameterMap = fullConfig

messageUtils.sendMessage(
        false,
        fullConfig.SHARE_PARAM.message,
        "发布开始：${fullConfig.SHARE_PARAM.appName}",
        "发布开始: ${currentBuild.fullDisplayName}"
)
```

把重启前通知替换为：

```groovy
messageUtils.sendMessage(
        false,
        fullConfig.SHARE_PARAM.message,
        "准备重启：${fullConfig.SHARE_PARAM.appName}",
        "准备重启: ${currentBuild.fullDisplayName}"
)
```

把 `finally` 通知替换为：

```groovy
Map notificationShareParam = fullConfig?.SHARE_PARAM instanceof Map
        ? fullConfig.SHARE_PARAM as Map
        : (customConfig?.SHARE_PARAM instanceof Map ? customConfig.SHARE_PARAM as Map : [:])
if (ObjUtils.isNotEmpty(notificationShareParam.message)) {
    String messageTitle = ''
    String messageContent = ''
    if (eBuildStatusType == EBuildStatusType.SUCCESS) {
        messageTitle = "成功:${notificationShareParam.appName}"
        messageContent = "发布成功: ${currentBuild.fullDisplayName}"
    } else if (eBuildStatusType == EBuildStatusType.FAILED) {
        messageTitle = "失败:${notificationShareParam.appName}"
        messageContent = "发布失败: ${currentBuild.fullDisplayName},异常信息: ${errMessage},构建日志:(${BUILD_URL}console)"
    }
    if (StrUtils.isNotBlank(messageTitle) && StrUtils.isNotBlank(messageContent)) {
        messageUtils.sendMessage(true, notificationShareParam.message, messageTitle, messageContent)
    }
}
deleteDir()
```

- [ ] **Step 5: 运行 Java 及全部变量测试**

```powershell
$env:JAVA_HOME='C:\workspace\env\jdk\jdk21'; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"; .\gradlew.bat test --tests "com.daluobai.jenkinslib.vars.DeployJavaWebVarTest" --tests "com.daluobai.jenkinslib.vars.DeployWebVarTest" --rerun-tasks --no-daemon
```

Expected: PASS，9 tests completed，0 failed。

- [ ] **Step 6: 暂存、检查影响并提交 Java 配置修复**

```powershell
git add -- 'test/com/daluobai/jenkinslib/vars/DeployJavaWebVarTest.groovy' 'vars/deployJavaWeb.groovy'
```

```text
detect_changes({repo: "jenkins-shared-library", scope: "staged", worktree: "C:\workspace\code\github\daluobai-devops\jenkins-shared-library"})
```

```powershell
git commit -m "fix: 兼容并统一 Java Pipeline 配置"
```

---

### Task 3: Web 暂存解压、切换与回滚

**Files:**
- Create: `test/com/daluobai/jenkinslib/steps/StepsWebTest.groovy`
- Modify: `src/com/daluobai/jenkinslib/steps/StepsWeb.groovy:26-83`

**Interfaces:**
- Consumes: `parameterMap.labels`、`parameterMap.pathRoot`、`globalParameterMap.SHARE_PARAM.appName/archiveName`、`DEPLOY_PIPELINE.stepsStorage.archiveType`。
- Produces: `deploy(Map)`；ZIP/TAR 均先写 `.app-next`，校验后把旧 `app` 移到 `.app-previous`，切换失败恢复旧目录。

- [ ] **Step 1: 编写 Web 发布失败测试**

创建 `test/com/daluobai/jenkinslib/steps/StepsWebTest.groovy`：

```groovy
package com.daluobai.jenkinslib.steps

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

class StepsWebTest {

    @Test
    void zipDeploymentExtractsAndValidatesBeforeSwitchingLiveApp() {
        FakeSteps steps = new FakeSteps('ZIP', 'app.zip')
        StepsWeb deployer = new StepsWeb(steps)
        deployer.stepsJenkins = new FakeStepsJenkins()

        deployer.deploy([labels: ['web-node'], pathRoot: '/srv/www'])

        int extractIndex = steps.scripts.findIndexOf { it.contains("unzip 'app.zip' -d .app-next/") }
        int validateIndex = steps.scripts.findIndexOf { it.contains('find .app-next -mindepth 1') }
        int switchIndex = steps.scripts.findIndexOf { it.contains('mv app .app-previous') }
        assertTrue(extractIndex >= 0)
        assertTrue(extractIndex < validateIndex)
        assertTrue(validateIndex < switchIndex)
        assertFalse(steps.scripts.take(switchIndex).any { it.contains('rm -rf app') })
        assertTrue(steps.scripts[switchIndex].contains('mv .app-previous app'))
        assertTrue(steps.scripts[switchIndex].contains('rm -rf .app-previous'))
        assertTrue(steps.scripts.any { it.contains('/srv/www/demo/backup/') })
    }

    @Test
    void tarDeploymentUsesSameStagingAndRollbackFlow() {
        FakeSteps steps = new FakeSteps('TAR', 'app.tar.gz')
        StepsWeb deployer = new StepsWeb(steps)
        deployer.stepsJenkins = new FakeStepsJenkins()

        deployer.deploy([labels: ['web-node'], pathRoot: '/srv/www'])

        int extractIndex = steps.scripts.findIndexOf { it.contains("tar -zxvf 'app.tar.gz' -C .app-next/") }
        int switchIndex = steps.scripts.findIndexOf { it.contains('mv app .app-previous') }
        assertTrue(extractIndex >= 0)
        assertTrue(extractIndex < switchIndex)
        assertTrue(steps.scripts[switchIndex].contains('mv .app-previous app'))
    }

    @Test
    void deploymentRejectsUnsupportedArchiveBeforeTouchingFilesystem() {
        FakeSteps steps = new FakeSteps('JAR', 'app.jar')
        StepsWeb deployer = new StepsWeb(steps)
        deployer.stepsJenkins = new FakeStepsJenkins()

        assertThrows(IllegalArgumentException.class) {
            deployer.deploy([labels: ['web-node'], pathRoot: '/srv/www'])
        }
        assertTrue(steps.scripts.isEmpty())
    }

    static class FakeSteps {
        Map globalParameterMap
        List<String> scripts = []
        List<String> directories = []

        FakeSteps(String archiveType, String archiveName) {
            globalParameterMap = [
                    SHARE_PARAM    : [appName: 'demo', archiveName: archiveName],
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
```

- [ ] **Step 2: 运行测试并确认旧实现在安全顺序断言上失败**

```powershell
$env:JAVA_HOME='C:\workspace\env\jdk\jdk21'; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"; .\gradlew.bat test --tests "com.daluobai.jenkinslib.steps.StepsWebTest" --rerun-tasks --no-daemon
```

Expected: FAIL；旧实现没有 `.app-next`、`.app-previous` 或回滚命令，并在解压前执行 `rm -rf .../app`。

- [ ] **Step 3: 用暂存和回滚流程替换 `StepsWeb.deploy`**

保留类定义和 `stepsJenkins` 字段，使用以下完整方法：

```groovy
def deploy(Map parameterMap) {
    steps.echo '开始部署Web静态资源'
    AssertUtils.notEmpty(parameterMap, '参数为空')
    def labels = parameterMap.labels
    String pathRoot = parameterMap.pathRoot?.toString()
    Map globalParameterMap = steps.globalParameterMap as Map
    String appName = globalParameterMap?.SHARE_PARAM?.appName?.toString()
    String archiveName = globalParameterMap?.SHARE_PARAM?.archiveName?.toString()
    Map configStepsStorage = globalParameterMap?.DEPLOY_PIPELINE?.stepsStorage as Map
    String archiveType = configStepsStorage?.archiveType?.toString()

    AssertUtils.notEmpty(labels, 'labels为空')
    AssertUtils.notBlank(pathRoot, 'pathRoot为空')
    AssertUtils.notBlank(appName, 'appName为空')
    AssertUtils.notBlank(archiveName, 'archiveName为空')
    AssertUtils.isTrue(archiveType in ['ZIP', 'TAR'], 'archiveType仅支持ZIP或TAR')

    String archiveSuffix = StrUtils.subAfter(archiveName, '.', true)
    String backAppName = "app-${DateUtils.format(new Date(), 'yyyyMMddHHmmss')}.${archiveSuffix}"
    String appRoot = "${pathRoot}/${appName}"

    labels.each { label ->
        steps.echo "发布第一个标签:${label}"
        def nodeDeployNodeList = stepsJenkins.getNodeByLabel(label)
        steps.echo "获取到发布节点:${nodeDeployNodeList}"
        if (ObjUtils.isEmpty(nodeDeployNodeList)) {
            steps.error '没有可用的发布节点'
        }
        nodeDeployNodeList.each { nodeDeployNode ->
            steps.echo "开始发布:${nodeDeployNode}"
            steps.node(nodeDeployNode) {
                steps.unstash('appPackage')
                steps.sh 'hostname'
                steps.sh 'ls -l package'
                steps.sh "mkdir -p '${appRoot}' '${appRoot}/backup'"
                steps.sh "mv -f '${appRoot}/${archiveName}' '${appRoot}/backup/${backAppName}' || true"
                steps.dir("${appRoot}/backup/") {
                    steps.sh 'find . -mtime +3 -delete'
                }
                steps.sh "cp 'package/${archiveName}' '${appRoot}/${archiveName}'"

                steps.dir("${appRoot}/") {
                    steps.sh '''
set -eu
rm -rf .app-next
if [ -e .app-previous ]; then
  if [ ! -e app ]; then
    echo "检测到未恢复的回滚目录: $(pwd)/.app-previous" >&2
    exit 1
  fi
  rm -rf .app-previous
fi
mkdir -p .app-next
'''
                    if (archiveType == 'ZIP') {
                        steps.sh "unzip '${archiveName}' -d .app-next/"
                    } else {
                        steps.sh "tar -zxvf '${archiveName}' -C .app-next/"
                    }
                    steps.sh '''
set -eu
if [ -z "$(find .app-next -mindepth 1 -print -quit)" ]; then
  echo 'Web发布暂存目录为空: .app-next' >&2
  exit 1
fi
'''
                    steps.sh '''
set -eu
had_previous=0
if [ -e app ]; then
  mv app .app-previous
  had_previous=1
fi
if mv .app-next app; then
  rm -rf .app-previous
else
  switch_status=$?
  if [ "$had_previous" -eq 1 ]; then
    if ! mv .app-previous app; then
      echo "发布切换失败，自动恢复也失败；旧版本保留在 $(pwd)/.app-previous" >&2
    fi
  fi
  exit "$switch_status"
fi
'''
                    steps.sh 'ls -l app'
                }
            }
        }
    }
}
```

- [ ] **Step 4: 运行 Web 部署及相邻步骤测试**

```powershell
$env:JAVA_HOME='C:\workspace\env\jdk\jdk21'; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"; .\gradlew.bat test --tests "com.daluobai.jenkinslib.steps.StepsWebTest" --tests "com.daluobai.jenkinslib.steps.StepsJenkinsTest" --rerun-tasks --no-daemon
```

Expected: PASS，新增 3 个 Web 发布测试全部通过，现有 `StepsJenkinsTest` 无回归。

- [ ] **Step 5: 暂存、检查影响并提交安全发布修复**

```powershell
git add -- 'test/com/daluobai/jenkinslib/steps/StepsWebTest.groovy' 'src/com/daluobai/jenkinslib/steps/StepsWeb.groovy'
```

```text
detect_changes({repo: "jenkins-shared-library", scope: "staged", worktree: "C:\workspace\code\github\daluobai-devops\jenkins-shared-library"})
```

```powershell
git commit -m "fix: Web 发布改用暂存切换和回滚"
```

---

### Task 4: PID 服务脚本目录修复

**Files:**
- Create: `test/com/daluobai/jenkinslib/resources/ServiceByPidTemplateTest.groovy`
- Modify: `resources/template/shell/javaWeb/serviceByPID.sh:5`

**Interfaces:**
- Consumes: `TemplateUtils.makeTemplate(String, Map)` 及服务模板变量 `appName`、`javaPath`、`runOptions`、`archiveName`、`runArgs`。
- Produces: 渲染后的 Bash 脚本用 `SHELL_PATH` 计算 `SERVICE_DIR`。

- [ ] **Step 1: 编写模板失败测试**

创建 `test/com/daluobai/jenkinslib/resources/ServiceByPidTemplateTest.groovy`：

```groovy
package com.daluobai.jenkinslib.resources

import com.daluobai.jenkinslib.utils.TemplateUtils
import org.junit.jupiter.api.Test

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

class ServiceByPidTemplateTest {

    @Test
    void renderedScriptDerivesServiceDirectoryFromScriptPath() {
        String template = Files.readString(
                Path.of('resources/template/shell/javaWeb/serviceByPID.sh'),
                StandardCharsets.UTF_8
        )
        String rendered = TemplateUtils.makeTemplate(template, [
                appName   : 'demo',
                javaPath  : '/usr/bin/java',
                runOptions: '',
                archiveName: 'app.jar',
                runArgs   : ''
        ])

        assertTrue(rendered.contains('SHELL_DIR=$(dirname "$SHELL_PATH")'))
        assertFalse(rendered.contains('SHELL_DIR=$(dirname "$path")'))
    }
}
```

- [ ] **Step 2: 运行测试并确认旧模板失败**

```powershell
$env:JAVA_HOME='C:\workspace\env\jdk\jdk21'; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"; .\gradlew.bat test --tests "com.daluobai.jenkinslib.resources.ServiceByPidTemplateTest" --rerun-tasks --no-daemon
```

Expected: FAIL，渲染结果仍包含 `dirname "$path"`。

- [ ] **Step 3: 修复模板变量并重新运行测试**

把模板第 5 行替换为：

```bash
SHELL_DIR=\$(dirname "\$SHELL_PATH")
```

Run:

```powershell
$env:JAVA_HOME='C:\workspace\env\jdk\jdk21'; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"; .\gradlew.bat test --tests "com.daluobai.jenkinslib.resources.ServiceByPidTemplateTest" --rerun-tasks --no-daemon
```

Expected: PASS，1 test completed，0 failed。

- [ ] **Step 4: 暂存、检查影响并提交模板修复**

```powershell
git add -- 'test/com/daluobai/jenkinslib/resources/ServiceByPidTemplateTest.groovy' 'resources/template/shell/javaWeb/serviceByPID.sh'
```

```text
detect_changes({repo: "jenkins-shared-library", scope: "staged", worktree: "C:\workspace\code\github\daluobai-devops\jenkins-shared-library"})
```

```powershell
git commit -m "fix: 修正 PID 服务脚本目录变量"
```

---

### Task 5: 全量回归与范围审计

**Files:**
- Verify: `vars/deployWeb.groovy`
- Verify: `vars/deployJavaWeb.groovy`
- Verify: `src/com/daluobai/jenkinslib/steps/StepsWeb.groovy`
- Verify: `resources/template/shell/javaWeb/serviceByPID.sh`
- Verify: all files under `test/`

**Interfaces:**
- Consumes: Tasks 1-4 的提交结果。
- Produces: 完整 Gradle 回归证据、GitNexus 变更范围报告和干净的任务暂存区。

- [ ] **Step 1: 搜索原始缺陷模式**

```powershell
rg -n --fixed-strings 'EFileReadType.get(customConfig.CONFIG_EXTEND.configFullPath)' vars/deployWeb.groovy vars/deployJavaWeb.groovy
rg -n --fixed-strings 'customConfig.SHARE_PARAM' vars/deployWeb.groovy vars/deployJavaWeb.groovy
rg -n --fixed-strings 'rm -rf ${pathRoot}/${appName}/app' src/com/daluobai/jenkinslib/steps/StepsWeb.groovy
rg -n --fixed-strings 'dirname "\$path"' resources/template/shell/javaWeb/serviceByPID.sh
```

Expected: 四条命令均无匹配。`finally` 中只允许安全的 `customConfig?.SHARE_PARAM instanceof Map` 回退，不允许直接解引用。

- [ ] **Step 2: 运行全部测试且强制重新执行**

```powershell
$env:JAVA_HOME='C:\workspace\env\jdk\jdk21'; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"; .\gradlew.bat test --rerun-tasks --no-daemon
```

Expected: BUILD SUCCESSFUL；所有测试 0 failed、0 skipped。记录实际测试总数，不沿用计划中的估计数。

- [ ] **Step 3: 检查格式、暂存区和用户原有修改**

```powershell
git diff --check
git status --short
git log -5 --oneline
```

Expected: `git diff --check` 不报告本任务文件问题；`AGENTS.md` 仍为用户原有未暂存修改；本任务没有遗漏的未提交代码。

- [ ] **Step 4: 运行 GitNexus 最终变更审计**

```text
detect_changes({repo: "jenkins-shared-library", scope: "compare", base_ref: "main", worktree: "C:\workspace\code\github\daluobai-devops\jenkins-shared-library"})
```

Expected: 变更仅覆盖两个 Pipeline 配置入口、`StepsWeb.deploy`、PID 模板和对应测试；记录受影响流程与最终风险。若报告额外业务符号，返回对应 Task 修正并重新验证。

- [ ] **Step 5: 交付结果**

向用户报告：四个根因及对应修复、旧配置兼容策略、Web 失败语义、实际测试命令与结果、GitNexus 风险、提交列表，以及未纳入提交的 `AGENTS.md` 状态。

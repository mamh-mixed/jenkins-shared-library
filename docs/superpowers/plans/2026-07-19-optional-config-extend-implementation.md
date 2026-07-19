# Optional CONFIG_EXTEND Missing-File Tolerance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** 让 deployWeb、deployJavaWeb、syncGit2Git 的可选 CONFIG_EXTEND 在目标不存在时按空配置继续执行，同时保留其他配置错误的严格失败语义。

**Architecture:** 在 ConfigUtils 新增独立的 readOptionalConfigFromFullPath(String): Map，集中识别 HOST_PATH 缺失、Jenkins Shared Library 资源缺失和 URL 404；现有严格读取方法保持不变。三个 Pipeline 只把非空扩展路径交给该入口，后续仍只合并统一 Map。

**Tech Stack:** Groovy 4、Jenkins Shared Library Pipeline DSL、JUnit Jupiter 5、Gradle Wrapper、GitNexus

## Global Constraints

- 设计依据：docs/superpowers/specs/2026-07-19-optional-config-extend-design.md。
- 仅修改 CONFIG_EXTEND；默认配置、Maven settingsFullPath、Java 服务模板和 shell 模板仍严格失败。
- 现有 ConfigUtils.readConfigFromFullPath(String) 与 readConfig(EFileReadType, String) 保持严格语义。
- 只有 RESOURCES 明确缺失、HOST_PATH 不存在和 URL HTTP 404 返回空 Map；非法类型、无效 JSON、HTTP 非 404、权限和连接错误继续抛出。
- 配置优先级保持 Jenkins 参数 > customConfig > 扩展配置 > 默认配置。
- 旧版 Java 配置兼容仍集中在 deployJavaWeb.compatibleConfig(Map)。
- 所有 Java/Gradle 命令在同一条 PowerShell 命令中设置 JAVA_HOME=C:\workspace\env\jdk\jdk21 和 PATH。
- 修改任何现有函数、类或方法前，必须运行 GitNexus impact 并向用户报告直接调用者、受影响流程和风险；HIGH 或 CRITICAL 时停止编辑。
- 每次提交前只暂存本任务文件，运行 git diff --cached --check 和 GitNexus detect_changes(scope: staged)。
- 保留工作区现有 AGENTS.md 修改，不覆盖、不暂存、不提交。

---

### Task 0: 变更前影响分析

**Files:**
- Inspect: src/com/daluobai/jenkinslib/utils/ConfigUtils.groovy
- Inspect: test/com/daluobai/jenkinslib/vars/DeployPipelineVarTestSupport.groovy
- Inspect: vars/deployWeb.groovy
- Inspect: vars/deployJavaWeb.groovy
- Inspect: vars/syncGit2Git.groovy

**Interfaces:**
- Consumes: GitNexus 仓库索引 jenkins-shared-library。
- Produces: ConfigUtils、loadPipelineScript 和三个 mergeConfig 符号的上游影响结论。

- [ ] **Step 1: 对配置工具类运行上游影响分析**

调用：

~~~text
impact({repo: "jenkins-shared-library", target: "ConfigUtils", kind: "Class", file_path: "src/com/daluobai/jenkinslib/utils/ConfigUtils.groovy", direction: "upstream", includeTests: true})
~~~

Expected: 返回直接使用 ConfigUtils 的 Pipeline/工具类和风险级别；如果 Groovy 类无法解析，记录 UNKNOWN，不能当作零风险。

- [ ] **Step 2: 对测试支撑方法和三个配置合并方法运行上游影响分析**

调用：

~~~text
impact({repo: "jenkins-shared-library", target: "loadPipelineScript", file_path: "test/com/daluobai/jenkinslib/vars/DeployPipelineVarTestSupport.groovy", direction: "upstream", includeTests: true})
impact({repo: "jenkins-shared-library", target: "mergeConfig", file_path: "vars/deployWeb.groovy", direction: "upstream", includeTests: true})
impact({repo: "jenkins-shared-library", target: "mergeConfig", file_path: "vars/deployJavaWeb.groovy", direction: "upstream", includeTests: true})
impact({repo: "jenkins-shared-library", target: "mergeConfig", file_path: "vars/syncGit2Git.groovy", direction: "upstream", includeTests: true})
~~~

Expected: d=1 项优先列出；若索引不能区分同名 Groovy 脚本方法，按文件路径分别记录为 UNKNOWN。

- [ ] **Step 3: 报告爆炸半径并决定是否继续**

向用户报告每个目标的风险、直接调用者数量和受影响流程。LOW、MEDIUM 或 UNKNOWN 时说明会通过三个 Pipeline 的定向测试与完整测试集补偿；HIGH 或 CRITICAL 时停止，不进入 Task 1。

---

### Task 1: ConfigUtils 可选扩展配置读取入口

**Files:**
- Create: test/com/daluobai/jenkinslib/utils/ConfigUtilsTest.groovy
- Modify: src/com/daluobai/jenkinslib/utils/ConfigUtils.groovy:20-62

**Interfaces:**
- Consumes: EFileReadType.get(String)、StrUtils.subBefore、StrUtils.subAfter、steps.fileExists(String)、现有严格 readConfig(EFileReadType, String)。
- Produces: Map readOptionalConfigFromFullPath(String configFullPath)；仅缺失目标返回 [:]。

- [ ] **Step 1: 创建可选读取失败测试**

创建 test/com/daluobai/jenkinslib/utils/ConfigUtilsTest.groovy：

~~~groovy
package com.daluobai.jenkinslib.utils

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

class ConfigUtilsTest {

    @Test
    void optionalResourceMissingReturnsEmptyMap() {
        FakeSteps steps = new FakeSteps()

        Map result = new ConfigUtils(steps)
                .readOptionalConfigFromFullPath('RESOURCES:config/missing.json') as Map

        assertEquals([:], result)
        assertEquals(['config/missing.json'], steps.resourceReads)
    }

    @Test
    void strictResourceReadStillThrowsWhenMissing() {
        FakeSteps steps = new FakeSteps()

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class) {
            new ConfigUtils(steps).readConfigFromFullPath('RESOURCES:config/missing.json')
        }

        assertTrue(error.message.contains('No such library resource'))
    }

    @Test
    void optionalHostPathMissingReturnsEmptyMapWithoutReadingFile() {
        FakeSteps steps = new FakeSteps(hostFileExists: false)

        Map result = new ConfigUtils(steps)
                .readOptionalConfigFromFullPath('HOST_PATH:/agent/missing.json') as Map

        assertEquals([:], result)
        assertEquals(['/agent/missing.json'], steps.hostPathChecks)
        assertFalse(steps.hostFileRead)
    }

    @Test
    void optionalResourceInvalidJsonStillThrows() {
        FakeSteps steps = new FakeSteps(resources: ['config/broken.json': '{'])

        assertThrows(Exception.class) {
            new ConfigUtils(steps).readOptionalConfigFromFullPath('RESOURCES:config/broken.json')
        }
    }

    @Test
    void optionalUnknownTypeStillThrows() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class) {
            new ConfigUtils(new FakeSteps())
                    .readOptionalConfigFromFullPath('UNKNOWN:config/extend.json')
        }

        assertTrue(error.message.contains('配置类型为空'))
    }

    @Test
    void optionalUrl404ReturnsEmptyMap() {
        HttpUtils.metaClass.'static'.get = { String ignoredUrl ->
            throw new RuntimeException(
                    'GET请求失败: https://example.com/<redacted>',
                    new RuntimeException('HTTP请求失败，响应码: 404')
            )
        }

        try {
            Map result = new ConfigUtils(new FakeSteps())
                    .readOptionalConfigFromFullPath('URL:https://example.com/missing.json') as Map
            assertEquals([:], result)
        } finally {
            GroovySystem.metaClassRegistry.removeMetaClass(HttpUtils)
        }
    }

    @Test
    void optionalUrl500StillThrows() {
        HttpUtils.metaClass.'static'.get = { String ignoredUrl ->
            throw new RuntimeException(
                    'GET请求失败: https://example.com/<redacted>',
                    new RuntimeException('HTTP请求失败，响应码: 500')
            )
        }

        try {
            RuntimeException error = assertThrows(RuntimeException.class) {
                new ConfigUtils(new FakeSteps())
                        .readOptionalConfigFromFullPath('URL:https://example.com/error.json')
            }
            assertTrue(error.cause.message.contains('500'))
        } finally {
            GroovySystem.metaClassRegistry.removeMetaClass(HttpUtils)
        }
    }

    static class FakeSteps {
        Map<String, String> resources = [:]
        boolean hostFileExists = true
        boolean hostFileRead = false
        List<String> resourceReads = []
        List<String> hostPathChecks = []

        String libraryResource(String path) {
            resourceReads.add(path)
            if (!resources.containsKey(path)) {
                throw new IllegalArgumentException(
                        'No such library resource ' + path + ' could be found.'
                )
            }
            return resources[path]
        }

        boolean fileExists(String path) {
            hostPathChecks.add(path)
            return hostFileExists
        }

        String readFile(Map ignoredArgs) {
            hostFileRead = true
            return '{"host":true}'
        }
    }
}
~~~

- [ ] **Step 2: 运行测试并确认测试先失败**

Run:

~~~powershell
$env:JAVA_HOME='C:\workspace\env\jdk\jdk21'; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"; .\gradlew.bat test --tests "com.daluobai.jenkinslib.utils.ConfigUtilsTest"
~~~

Expected: FAIL，错误为 MissingMethodException，指出 ConfigUtils 尚无 readOptionalConfigFromFullPath。

- [ ] **Step 3: 在 ConfigUtils 实现统一可选读取**

在 readConfigFromFullPath 后加入：

~~~groovy
    /**
     * 从完整路径读取可选配置；仅目标不存在时返回空配置。
     */
    Map readOptionalConfigFromFullPath(String configFullPath) {
        AssertUtils.notBlank(configFullPath, "configFullPath为空")
        String configType = StrUtils.subBefore(configFullPath, ":", false)
        String path = StrUtils.subAfter(configFullPath, ":", false)
        EFileReadType eConfigType = EFileReadType.get(configType)
        AssertUtils.notNull(eConfigType, "配置类型为空")
        AssertUtils.notBlank(path, "path为空")

        if (eConfigType == EFileReadType.HOST_PATH && !hostPathExists(path)) {
            return [:]
        }

        try {
            return readConfig(eConfigType, path) as Map
        } catch (Exception error) {
            if (isMissingOptionalConfig(eConfigType, error)) {
                return [:]
            }
            throw error
        }
    }

    private boolean hostPathExists(String path) {
        if (steps != null) {
            return steps.fileExists(path)
        }
        return IoUtils.isFile(new File(path))
    }

    private static boolean isMissingOptionalConfig(EFileReadType eConfigType, Throwable error) {
        Throwable current = error
        while (current != null) {
            String message = current.message ?: ""
            if (eConfigType == EFileReadType.RESOURCES
                    && message.contains("No such library resource")
                    && message.contains("could be found")) {
                return true
            }
            if (eConfigType == EFileReadType.URL
                    && message.contains("HTTP请求失败，响应码: 404")) {
                return true
            }
            current = current.cause
        }
        return false
    }
~~~

不得修改现有 readConfigFromFullPath、readConfig、FileUtils 或 HttpUtils。

- [ ] **Step 4: 运行 ConfigUtils 定向测试**

Run:

~~~powershell
$env:JAVA_HOME='C:\workspace\env\jdk\jdk21'; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"; .\gradlew.bat test --tests "com.daluobai.jenkinslib.utils.ConfigUtilsTest"
~~~

Expected: ConfigUtilsTest 的 7 个测试全部 PASS。

- [ ] **Step 5: 检测并提交配置工具变更**

Run:

~~~powershell
git add -- src/com/daluobai/jenkinslib/utils/ConfigUtils.groovy test/com/daluobai/jenkinslib/utils/ConfigUtilsTest.groovy
git diff --cached --check
git diff --cached --stat
~~~

调用：

~~~text
detect_changes({repo: "jenkins-shared-library", scope: "staged", worktree: "C:\\workspace\\code\\github\\daluobai-devops\\jenkins-shared-library"})
~~~

Expected: 只包含 ConfigUtils 和其测试，风险不是 HIGH/CRITICAL。然后运行：

~~~powershell
git commit -m "fix: 增加可选扩展配置读取入口"
~~~

---

### Task 2: Web 与 Java Pipeline 使用可选读取入口

**Files:**
- Modify: test/com/daluobai/jenkinslib/vars/DeployPipelineVarTestSupport.groovy:22-28
- Modify: test/com/daluobai/jenkinslib/vars/DeployWebVarTest.groovy
- Modify: test/com/daluobai/jenkinslib/vars/DeployJavaWebVarTest.groovy
- Modify: vars/deployWeb.groovy:164-168
- Modify: vars/deployJavaWeb.groovy:183-187

**Interfaces:**
- Consumes: ConfigUtils.readOptionalConfigFromFullPath(String): Map（Task 1）。
- Produces: Web/Java mergeConfig(Map): Map 在扩展资源缺失时继续合并默认、自定义与参数配置。

- [ ] **Step 1: 让测试支撑类模拟 Jenkins 的真实缺失资源异常**

将资源不存在分支改为：

~~~groovy
            if (!resources.containsKey(path)) {
                throw new IllegalArgumentException(
                        "No such library resource " + path + " could be found."
                )
            }
~~~

- [ ] **Step 2: 添加 Web 缺失扩展配置回归测试**

在 DeployWebVarTest 中加入：

~~~groovy
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
~~~

- [ ] **Step 3: 添加 Java 缺失扩展配置回归测试**

在 DeployJavaWebVarTest 中加入：

~~~groovy
    @Test
    void mergeConfigSkipsMissingExtensionResourceAndKeepsLegacyCompatibility() {
        List<String> reads = []
        Map defaultConfig = disabledJavaConfig([defaultKey: 'default-value'])
        Script script = loadPipelineScript('vars/deployJavaWeb.groovy', [
                'config/config.json': defaultConfig
        ], [:], reads)

        Map result = script.invokeMethod('mergeConfig', [[
                CONFIG_EXTEND : [configFullPath: 'RESOURCES:config/missing.json'],
                DEPLOY_PIPELINE: [
                        stepsBuildMaven: [enable: true, gitUrl: 'legacy.git']
                ]
        ]] as Object[]) as Map

        assertEquals(['config/config.json', 'config/missing.json'], reads)
        assertEquals('default-value', result.SHARE_PARAM.defaultKey)
        assertEquals(true, result.DEPLOY_PIPELINE.stepsBuild.enable)
        assertEquals('legacy.git', result.DEPLOY_PIPELINE.stepsBuild.stepsBuildMaven.gitUrl)
        assertFalse(result.DEPLOY_PIPELINE.containsKey('stepsBuildMaven'))
    }
~~~

- [ ] **Step 4: 运行两个 Pipeline 测试并确认先失败**

Run:

~~~powershell
$env:JAVA_HOME='C:\workspace\env\jdk\jdk21'; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"; .\gradlew.bat test --tests "com.daluobai.jenkinslib.vars.DeployWebVarTest" --tests "com.daluobai.jenkinslib.vars.DeployJavaWebVarTest"
~~~

Expected: 两个新增测试因当前 mergeConfig 仍调用严格读取入口而 FAIL，错误包含 No such library resource。

- [ ] **Step 5: 切换两个 Pipeline 的扩展配置读取方法**

Web 读取块：

~~~groovy
    Map extendConfig = [:]
    String configFullPath = customConfig?.CONFIG_EXTEND?.configFullPath?.toString()
    if (StrUtils.isNotBlank(configFullPath)) {
        extendConfig = new ConfigUtils(this).readOptionalConfigFromFullPath(configFullPath)
    }
~~~

Java 读取块：

~~~groovy
    Map extendConfig = [:]
    String configFullPath = customConfig?.CONFIG_EXTEND?.configFullPath?.toString()
    if (StrUtils.isNotBlank(configFullPath)) {
        extendConfig = compatibleConfig(
                new ConfigUtils(this).readOptionalConfigFromFullPath(configFullPath)
        )
    }
~~~

- [ ] **Step 6: 运行 Web/Java 定向测试**

Run:

~~~powershell
$env:JAVA_HOME='C:\workspace\env\jdk\jdk21'; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"; .\gradlew.bat test --tests "com.daluobai.jenkinslib.vars.DeployWebVarTest" --tests "com.daluobai.jenkinslib.vars.DeployJavaWebVarTest"
~~~

Expected: 两个测试类全部 PASS；现有未知类型测试仍验证 配置类型为空。

- [ ] **Step 7: 检测并提交 Web/Java Pipeline 变更**

Run:

~~~powershell
git add -- vars/deployWeb.groovy vars/deployJavaWeb.groovy test/com/daluobai/jenkinslib/vars/DeployPipelineVarTestSupport.groovy test/com/daluobai/jenkinslib/vars/DeployWebVarTest.groovy test/com/daluobai/jenkinslib/vars/DeployJavaWebVarTest.groovy
git diff --cached --check
git diff --cached --stat
~~~

调用 detect_changes(scope: staged)，确认只影响 Web/Java 配置合并和相关测试且风险不是 HIGH/CRITICAL，然后运行：

~~~powershell
git commit -m "fix: 缺失扩展资源时继续 Web 和 Java Pipeline"
~~~

---

### Task 3: syncGit2Git 使用统一完整路径解析

**Files:**
- Create: test/com/daluobai/jenkinslib/vars/SyncGit2GitVarTest.groovy
- Modify: vars/syncGit2Git.groovy:101-116

**Interfaces:**
- Consumes: ConfigUtils.readOptionalConfigFromFullPath(String): Map（Task 1）、StrUtils.isNotBlank(String)。
- Produces: syncGit2Git.mergeConfig(Map): Map 对合法 TYPE:path 进行读取，缺失目标按空配置继续合并。

- [ ] **Step 1: 创建 syncGit2Git 配置合并回归测试**

~~~groovy
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
~~~

- [ ] **Step 2: 运行 syncGit2Git 测试并确认先失败**

Run:

~~~powershell
$env:JAVA_HOME='C:\workspace\env\jdk\jdk21'; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"; .\gradlew.bat test --tests "com.daluobai.jenkinslib.vars.SyncGit2GitVarTest"
~~~

Expected: FAIL；当前代码把完整 RESOURCES:... 传给 EFileReadType.get，因此读取记录只有默认配置。

- [ ] **Step 3: 统一 syncGit2Git 的扩展配置入口**

将扩展配置读取块替换为：

~~~groovy
    Map extendConfig = [:]
    String configFullPath = customConfig?.CONFIG_EXTEND?.configFullPath?.toString()
    if (StrUtils.isNotBlank(configFullPath)) {
        extendConfig = new ConfigUtils(this).readOptionalConfigFromFullPath(configFullPath)
    }
~~~

默认配置读取、MapUtils.merge、参数覆盖和深拷贝逻辑保持原样。

- [ ] **Step 4: 运行 syncGit2Git 定向测试**

Run:

~~~powershell
$env:JAVA_HOME='C:\workspace\env\jdk\jdk21'; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"; .\gradlew.bat test --tests "com.daluobai.jenkinslib.vars.SyncGit2GitVarTest"
~~~

Expected: 2 个测试全部 PASS。

- [ ] **Step 5: 检测并提交 syncGit2Git 变更**

Run:

~~~powershell
git add -- vars/syncGit2Git.groovy test/com/daluobai/jenkinslib/vars/SyncGit2GitVarTest.groovy
git diff --cached --check
git diff --cached --stat
~~~

调用 detect_changes(scope: staged)，确认只影响 syncGit2Git.mergeConfig 和其测试且风险不是 HIGH/CRITICAL，然后运行：

~~~powershell
git commit -m "fix: 统一 Git 同步扩展配置读取"
~~~

---

### Task 4: 完整回归与变更范围验证

**Files:**
- Verify: src/com/daluobai/jenkinslib/utils/ConfigUtils.groovy
- Verify: vars/deployWeb.groovy
- Verify: vars/deployJavaWeb.groovy
- Verify: vars/syncGit2Git.groovy
- Verify: test/com/daluobai/jenkinslib/**/*.groovy

**Interfaces:**
- Consumes: Tasks 1–3 的所有提交。
- Produces: 完整测试结果、源码模式检查、GitNexus 最终变更范围报告和干净的任务文件状态。

- [ ] **Step 1: 检查三个 Pipeline 只使用可选读取入口**

Run:

~~~powershell
rg -n "read(Optional)?ConfigFromFullPath" vars/deployWeb.groovy vars/deployJavaWeb.groovy vars/syncGit2Git.groovy
~~~

Expected: 三个文件各出现一次 readOptionalConfigFromFullPath，不再出现 readConfigFromFullPath(configFullPath)。

- [ ] **Step 2: 运行全部定向测试**

Run:

~~~powershell
$env:JAVA_HOME='C:\workspace\env\jdk\jdk21'; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"; .\gradlew.bat test --tests "com.daluobai.jenkinslib.utils.ConfigUtilsTest" --tests "com.daluobai.jenkinslib.vars.DeployWebVarTest" --tests "com.daluobai.jenkinslib.vars.DeployJavaWebVarTest" --tests "com.daluobai.jenkinslib.vars.SyncGit2GitVarTest"
~~~

Expected: 四个测试类全部 PASS。

- [ ] **Step 3: 运行完整测试集**

Run:

~~~powershell
$env:JAVA_HOME='C:\workspace\env\jdk\jdk21'; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"; .\gradlew.bat clean test
~~~

Expected: BUILD SUCCESSFUL，无失败测试。

- [ ] **Step 4: 刷新 GitNexus 索引并检查最终改动**

按 wuzhao-env 执行：

~~~powershell
nvm list
$root = git rev-parse --show-toplevel
$claude = Join-Path $root 'CLAUDE.md'
$claudeExisted = Test-Path -LiteralPath $claude
$backup = [System.IO.Path]::GetTempFileName()
try {
    if ($claudeExisted) {
        [System.IO.File]::WriteAllBytes($backup, [System.IO.File]::ReadAllBytes($claude))
    }
    nvm use 22.22.0
    node .gitnexus/run.cjs analyze
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    node .gitnexus/run.cjs status
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} finally {
    if ($claudeExisted) {
        [System.IO.File]::WriteAllBytes($claude, [System.IO.File]::ReadAllBytes($backup))
    } elseif (Test-Path -LiteralPath $claude) {
        Remove-Item -LiteralPath $claude -Force
    }
    Remove-Item -LiteralPath $backup -Force -ErrorAction SilentlyContinue
}
~~~

Expected: 索引对应当前 HEAD；保留 AGENTS.md 的 GitNexus 自动更新，恢复 CLAUDE.md 原始字节，不生成 Wiki。

随后调用：

~~~text
detect_changes({repo: "jenkins-shared-library", scope: "compare", base_ref: "408e421", worktree: "C:\\workspace\\code\\github\\daluobai-devops\\jenkins-shared-library"})
~~~

Expected: 改动限定在本实施计划、ConfigUtils、三个配置合并入口及其测试；没有部署模板、Maven settings 或默认资源改动。

- [ ] **Step 5: 最终工作区与提交检查**

Run:

~~~powershell
git status --short
git log --oneline 408e421..HEAD
~~~

Expected: 任务代码和测试均已提交；工作区只允许存在预先已有或 GitNexus 自动更新的 AGENTS.md 修改，不存在未提交的任务文件。

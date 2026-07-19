# 可选 CONFIG_EXTEND 缺失容错设计

日期：2026-07-19
状态：已批准

## 1. 背景与目标

调用方可以通过 `CONFIG_EXTEND.configFullPath` 指定额外配置。当前只要该字段非空，Pipeline 就会严格读取目标；当共享库资源、宿主机文件或 URL 不存在时，配置合并直接失败。

本次调整把 `CONFIG_EXTEND` 明确为可选配置：目标不存在时视为空配置并继续执行。默认配置、调用方自定义配置和 Jenkins 参数仍按原有优先级合并。

本文仅针对“扩展配置目标不存在”的行为，替代 `2026-07-19-config-and-web-deploy-fixes-design.md` 第 3.1 节中“文件读取失败时应明确失败”的旧约定；该节的路径格式、非法类型校验和其他配置合并约定继续有效。

## 2. 范围

覆盖以下扩展配置入口：

- `vars/deployWeb.groovy`
- `vars/deployJavaWeb.groovy`
- `vars/syncGit2Git.groovy`

支持的扩展配置来源保持不变：

- `RESOURCES:path`
- `HOST_PATH:path`
- `URL:https://example.com/config.json`

以下读取不在本次范围内，缺失时继续失败：

- `resources/config/config.json` 等默认配置
- Maven `settingsFullPath`
- Java 服务和 shell 模板
- 其他直接调用 `FileUtils` 或 Jenkins `libraryResource` 的资源

## 3. 方案

### 3.1 统一可选读取入口

在 `ConfigUtils` 增加 `readOptionalConfigFromFullPath(String configFullPath)`。现有 `readConfigFromFullPath` 和 `readConfig` 保持严格语义，避免改变共享库其他调用方的历史行为。

三个 Pipeline 的配置合并入口只在 `CONFIG_EXTEND.configFullPath` 非空时调用新方法。新方法返回统一的 Map：

- 目标存在且内容有效：返回解析后的配置 Map。
- 目标不存在：返回空 Map `[:]`。
- 其他错误：继续抛出原异常。

实现层继续只合并返回后的统一 Map，不分散文件来源或兼容判断。

### 3.2 “不存在”的判定

不同来源按其可观测能力判定：

- `HOST_PATH`：读取前使用当前 Jenkins node 的 `fileExists(path)`；非 Jenkins 单元测试环境使用本地 `File` 判定。不存在时直接返回 `[:]`。
- `RESOURCES`：Jenkins Shared Library 没有独立的资源存在性检查步骤，因此尝试读取，并且只在异常链明确包含 `No such library resource ... could be found` 时返回 `[:]`。
- `URL`：只在异常链明确包含 HTTP 404 响应时返回 `[:]`。

异常链检查集中在 `ConfigUtils` 的私有辅助方法中，不修改通用的 `FileUtils` 和 `HttpUtils` 语义。

### 3.3 必须保留的失败

以下情况不能视为“文件不存在”，仍然让 Pipeline 失败：

- `UNKNOWN:path` 等不支持的读取类型
- 空路径或不符合 `TYPE:path` 格式的值
- JSON 语法错误或解析结果无效
- 宿主机文件存在但不可读
- HTTP 401、403、5xx、连接失败或超时
- Jenkins 返回的其他资源读取异常

这可以避免把配置错误、权限问题和基础设施故障静默降级为空配置。

## 4. 配置数据流

```text
CONFIG_EXTEND.configFullPath
  ├─ 空值 ────────────────────────────────> 不读取，扩展配置 = [:]
  └─ 非空
       └─ readOptionalConfigFromFullPath
            ├─ 目标不存在 / HTTP 404 ─────> 扩展配置 = [:]
            ├─ 内容有效 ──────────────────> 扩展配置 = 解析结果
            └─ 其他异常 ──────────────────> Pipeline 失败

默认配置 + 扩展配置 + customConfig
  └─ Jenkins 参数覆盖
       └─ fullConfig
```

配置优先级保持：Jenkins 参数 > `customConfig` > 扩展配置 > 默认配置。

`deployJavaWeb` 继续在每个配置来源进入合并前执行旧版结构兼容转换。缺失扩展配置返回 `[:]` 后也走同一转换入口，不需要特殊分支。

`syncGit2Git` 改为与另外两个 Pipeline 相同：先提取非空的完整路径，再调用统一入口；不再把完整的 `TYPE:path` 字符串直接传给 `EFileReadType.get`。

## 5. 测试策略

遵循先失败测试、后实现：

- `ConfigUtils`：共享库资源不存在时返回 `[:]`；资源存在但 JSON 无效时继续抛错；未知读取类型继续抛错。
- `deployWeb.mergeConfig`：缺失的 `RESOURCES` 扩展不会中断，默认配置、自定义配置和 Jenkins 参数仍生效。
- `deployJavaWeb.mergeConfig`：缺失扩展不会中断，旧版配置结构归一化和来源优先级保持不变。
- `syncGit2Git.mergeConfig`：合法完整路径会进入统一读取入口，缺失扩展返回空配置并继续合并。
- 运行完整 Gradle 测试集，确认默认配置和必需模板的严格失败行为没有被改变。

## 6. 验收标准

- 三个 `CONFIG_EXTEND` 入口对缺失资源采用相同行为。
- `RESOURCES` 不存在、`HOST_PATH` 不存在或 URL 返回 404 时，扩展配置按 `[:]` 处理且 Pipeline 不报错。
- 无效类型、无效 JSON、权限错误、HTTP 非 404 错误仍然失败。
- 默认配置、Maven settings 和部署模板的读取行为不变。
- 配置合并顺序及旧版 Java 配置兼容规则不变。
- 定向测试和完整测试集通过。

# Jenkins 配置与 Web 发布问题修复设计（#2/#4/#6/#7）

日期：2026-07-19
状态：已批准

## 1. 背景

本次修复覆盖已确认的四个问题：

- #2：`CONFIG_EXTEND.configFullPath` 使用 `RESOURCES:path`、`HOST_PATH:path` 或 `URL:path` 格式时，扩展配置不会被读取。
- #4：`deployWeb` 与 `deployJavaWeb` 在配置合并前访问 `customConfig.SHARE_PARAM`，并在合并后继续使用原始配置，导致空指针及继承配置、默认配置不生效。
- #6：Web 发布会先删除线上 `app` 目录再解压；解压失败时，当前站点内容已经丢失。
- #7：Java 服务脚本模板通过未定义变量 `$path` 计算脚本目录，导致 PID 文件目录错误。

目标是在不要求现有 Jenkinsfile、配置文件或 Web 服务器配置迁移的前提下，修复上述行为，并用自动化测试锁定配置优先级和发布失败语义。

## 2. 设计原则与范围

### 2.1 设计原则

- 旧版配置继续可用；兼容转换集中在配置合并入口。
- 默认配置、扩展配置、自定义配置和 Jenkins 参数最终形成唯一的运行时配置，后续流程不再读取原始 `customConfig`。
- Web 压缩包必须先完整解压和校验，线上 `app` 才允许切换。
- 切换失败时尽力恢复旧 `app`；错误继续向 Jenkins 抛出，不能把失败伪装成成功。
- 不新增必填配置项，不调整现有部署根目录、归档备份目录或 Web 服务器站点根目录。

### 2.2 非目标

- 不将现有物理 `app` 目录改造成 `current` 符号链接。
- 不处理部署完成后的业务健康检查或自动回滚。
- 不重构所有 Pipeline 变量脚本，也不处理本次四个问题之外的通知接口差异。
- 不改变归档文件保留三天的现有策略。

## 3. 配置读取与统一运行时配置

### 3.1 扩展配置读取

`deployWeb.mergeConfig` 与 `deployJavaWeb.mergeConfig` 只判断 `CONFIG_EXTEND.configFullPath` 是否为非空字符串；非空时直接调用 `ConfigUtils.readConfigFromFullPath`。

`ConfigUtils.readConfigFromFullPath` 已负责把完整路径解析为读取类型和实际路径，因此入口不再使用 `EFileReadType.get(configFullPath)` 对完整字符串做错误的枚举匹配。读取类型无效、路径为空或文件读取失败时应明确失败，避免静默跳过用户声明的扩展配置。

支持格式保持不变：

- `RESOURCES:config/example.json`
- `HOST_PATH:/absolute/path/config.json`
- `URL:https://example.com/config.json`

### 3.2 合并顺序

统一的配置数据流为：

```text
默认配置 → 旧版结构归一化 ┐
扩展配置 → 旧版结构归一化 ├→ 按来源优先级合并
customConfig → 旧版结构归一化 ┘
  ↓ 被 Jenkins 参数覆盖
fullConfig
```

最终优先级从高到低为：Jenkins 参数 > `customConfig` > 扩展配置 > 默认配置。

### 3.3 旧版 Java 构建配置兼容

`deployJavaWeb` 的旧字段 `DEPLOY_PIPELINE.stepsBuildMaven` 在配置入口转换到当前结构 `DEPLOY_PIPELINE.stepsBuild.stepsBuildMaven`。默认配置、扩展配置和调用方自定义配置分别归一化后再合并，使跨来源优先级仍保持“自定义 > 扩展 > 默认”；转换发生在应用 Jenkins 参数之前，使 Jenkins 参数只需面向当前统一结构。

兼容规则采用“新结构优先、旧结构补缺”：

- 在同一个配置来源中，只有旧字段存在且当前 `stepsBuild.stepsBuildMaven` 缺失时，才用旧值补齐；同一来源内新结构优先。
- 已存在的 `stepsBuild.enable`、`stepsBuild.stepsBuildEnv` 及其他当前结构字段不得被兼容逻辑覆盖或丢弃。
- 当前结构未显式提供 `stepsBuild.enable` 时，沿用旧 `stepsBuildMaven.enable`；旧字段也未提供时默认启用，以保持旧版行为。
- 转换完成后，构建实现只访问 `DEPLOY_PIPELINE.stepsBuild`，不再散落旧字段判断。

Web 配置目前没有需要转换的旧结构，但仍遵循同一入口归一化原则。

### 3.4 Pipeline 入口行为

`deployWeb.call` 和 `deployJavaWeb.call` 在首次读取 `SHARE_PARAM` 前完成配置合并。随后：

1. 确保 `fullConfig.SHARE_PARAM` 是 Map。
2. 若最终 `appName` 为空，使用 `currentBuild.projectName` 补齐。
3. 将 `globalParameterMap` 设置为 `fullConfig`。
4. 构建、部署、开始通知、重启前通知和结束通知全部读取 `fullConfig`，不再读取 `customConfig.SHARE_PARAM`。

为了覆盖配置读取自身失败的场景，`fullConfig` 在 `try` 外保留安全的空值。`finally` 优先使用已经合并的 `SHARE_PARAM`；若合并尚未完成，仅在原始 `customConfig.SHARE_PARAM` 确实是 Map 时将其作为通知回退。回退数据不存在时跳过通知，但保留原始异常，不能因通知字段空指针掩盖根因。

## 4. Web 暂存发布与失败回滚

### 4.1 目录布局

现有目录保持不变：

```text
<pathRoot>/<appName>/
├── app/                 # Web 服务器继续使用的线上目录
├── backup/              # 历史压缩包
├── <archiveName>        # 本次压缩包
├── .app-next/           # 本次发布暂存目录
└── .app-previous/       # 切换期间的回滚目录
```

`.app-next` 与 `.app-previous` 是同一应用目录下的内部临时目录。现有 Pipeline 本身已要求同一应用部署不能并发覆盖同一路径；本设计不引入新的并发语义。

### 4.2 发布顺序

每个部署节点按以下顺序执行：

1. 校验 `pathRoot`、`appName`、`archiveName` 等用于构造路径的值非空。
2. 保持现有逻辑：把上一份同名压缩包移入 `backup`，清理超过三天的归档，再复制新包。
3. 清理上次遗留的 `.app-next`；若 `.app-previous` 与当前 `app` 同时存在，则把前者视为过期回滚目录并清理。若 `.app-previous` 存在但 `app` 缺失，说明上次恢复未完成，本次发布立即失败并保留该目录供人工恢复。
4. 创建新的 `.app-next`。
5. 根据 `archiveType` 将新包完整解压到 `.app-next`。
6. 校验 `.app-next` 至少包含一个文件或目录；空包视为失败。
7. 仅在前述步骤全部成功后执行目录切换：
   - 若线上 `app` 存在，将其重命名为 `.app-previous`。
   - 将 `.app-next` 重命名为 `app`。
   - 若第二次重命名失败，将 `.app-previous` 恢复为 `app`，然后返回失败。
   - 切换成功后删除 `.app-previous`。
8. 输出新 `app` 目录内容，保留现有部署可观察性。

暂存解压与目录切换分别使用失败即退出的 shell 块。因为暂存目录和线上目录位于同一父目录，重命名不涉及跨文件系统复制。

### 4.3 失败语义

- 下载、复制、解压或暂存校验失败：线上 `app` 完全不动，Pipeline 失败。
- 旧 `app` 重命名失败：线上内容不变，Pipeline 失败。
- 新 `app` 重命名失败：尝试恢复旧 `app`，Pipeline 失败。
- 恢复也失败：保留 `.app-previous` 供人工恢复，并在错误信息中指出其位置；Pipeline 失败。
- 切换成功后的回滚目录清理失败：部署内容已经生效，但仍报告失败，避免隐藏部署节点的文件系统异常。

该方案在两个同文件系统重命名之间存在极短切换窗口，因此属于兼容现有物理目录布局的“近原子切换”，而不是符号链接方案的一次原子指针替换。它的关键保证是：解压失败不会删除当前站点，切换失败有明确回滚路径。

## 5. Java 服务脚本模板

`resources/template/shell/javaWeb/serviceByPID.sh` 中：

```bash
SHELL_PATH=$(readlink -f "$0")
SHELL_DIR=$(dirname "$SHELL_PATH")
```

第二行必须使用已经定义的 `SHELL_PATH`，不再引用不存在的 `$path`。其余启动、停止和 PID 文件行为保持不变。

## 6. 测试策略

遵循先失败测试、后实现的顺序。

### 6.1 配置测试

- `RESOURCES:...` 扩展配置会被实际读取。
- 无扩展配置时保持当前默认行为。
- 无效读取类型明确失败，不静默忽略。
- 验证优先级：Jenkins 参数 > 自定义配置 > 扩展配置 > 默认配置。
- 只在扩展配置中提供 `SHARE_PARAM` 时，两个 Pipeline 入口均不发生空指针，并使用扩展值发送通知/执行流程。
- 最终 `appName` 为空时使用 Jenkins 项目名。
- Java 旧版 `stepsBuildMaven` 会转换到当前结构；新旧结构同时存在时，新结构优先且相邻字段不丢失。

### 6.2 Web 发布测试

使用伪 Jenkins steps 捕获生成的 shell 命令，验证 ZIP 和 TAR 两条路径：

- 解压目标是 `.app-next`，解压成功前没有移动或删除线上 `app`。
- 暂存内容校验发生在目录切换前。
- 切换脚本包含 `.app-previous` 恢复逻辑。
- 成功后清理回滚目录。
- 归档备份与过期清理行为仍然存在。

### 6.3 模板测试

- 生成模板包含 `dirname "$SHELL_PATH"`。
- 生成模板不包含 `dirname "$path"`。

### 6.4 回归验证

- 运行新增的定向测试，确认修复前失败、修复后通过。
- 运行完整 Gradle 测试集。
- 使用 GitNexus `detect_changes` 检查变更仅影响预期配置入口、Web 部署和服务模板。

## 7. 验收标准

- 三类合法 `CONFIG_EXTEND` 完整路径均进入统一读取入口，非法类型立即报错。
- 两个 Pipeline 的实现阶段只消费 `fullConfig`，扩展配置可独立提供 `SHARE_PARAM`。
- 旧版 Java 构建配置无需修改即可运行，且不会覆盖已提供的新结构配置。
- Web 压缩包解压失败时，部署前的 `app` 内容仍在原路径。
- Web 目录切换失败时恢复旧目录，无法恢复时给出可人工操作的回滚目录。
- 服务脚本的工作目录由脚本自身路径正确计算。
- 新增测试和完整测试集全部通过。

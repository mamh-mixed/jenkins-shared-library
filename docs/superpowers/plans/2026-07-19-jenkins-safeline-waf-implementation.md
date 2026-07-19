# Jenkins SafeLine WAF Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 精确放行 Jenkins Pipeline 管理 POST 误报，同时将雷池 TLS、HSTS 和 X-Forwarded-For 配置收敛到安全状态，并删除 NPM 的重复 HSTS 配置。

**Architecture:** 雷池继续作为最外层 TLS/WAF，使用 `Host + POST + URI 正则` 自定义白名单只放行已确认误报的 Jenkins 管理接口；NPM 继续作为内层 HTTP 反代，保留 Jenkins 官方需要的转发头。所有外部配置先导出到本机临时目录，每一阶段完成独立验证后再进入下一阶段。

**Tech Stack:** SafeLine Community Edition REST API、Nginx Proxy Manager REST API、PowerShell 7、Jenkins Web/API、TLS `SslStream`

## Global Constraints

- 不关闭 Jenkins 全站防护，不排除整个 `/job/`，不影响其他子域名。
- 雷池白名单只匹配 `jenkins.nanjingzhengshang.com`、`POST` 和三组已确认误报路径。
- Jenkins 身份认证、权限和 Crumb 校验继续由 Jenkins 执行。
- 雷池仅启用 TLS 1.2/1.3，并负责 Force HTTPS、HSTS 和外部 XFF 重写。
- NPM Force SSL 保持关闭；仅在雷池 HSTS 已验证后删除 NPM 手工 HSTS 行。
- 任何登录密码、JWT、CSRF、NPM Token 均只保存在进程内存，不写入备份、文档或日志。
- 保留工作区现有 `AGENTS.md` 修改，不纳入本任务提交。

---

### Task 1: 导出现状并建立基线

**Files:**
- Create at runtime: `%TEMP%\jenkins-waf-backup-<timestamp>\safeline-site-1.json`
- Create at runtime: `%TEMP%\jenkins-waf-backup-<timestamp>\safeline-proxy-1.json`
- Create at runtime: `%TEMP%\jenkins-waf-backup-<timestamp>\safeline-policies.json`
- Create at runtime: `%TEMP%\jenkins-waf-backup-<timestamp>\npm-proxy-host-1.json`
- Reference: `docs/superpowers/specs/2026-07-19-jenkins-safeline-waf-design.md`

**Interfaces:**
- Consumes: 雷池管理员密码、NPM 管理员密码，均来自已授权会话并只在内存中使用。
- Produces: `$backupDir` 绝对路径、雷池 Bearer 头、NPM Bearer 头、变更前基线对象。

- [ ] **Step 1: 认证两个管理 API，且不输出任何令牌**

执行代理从当前已授权会话取得 `$wafPassword` 和 `$npmPassword` 两个内存字符串，不把它们写入文件。使用以下函数认证：

```powershell
function New-SafeLineHeaders([string]$Base, [string]$Password) {
  $key = (Invoke-RestMethod "$Base/api/open/system/key" -SkipCertificateCheck).data
  $csrf = (Invoke-RestMethod "$Base/api/open/auth/csrf" -SkipCertificateCheck).data.csrf_token
  $seed = New-Object byte[] 8
  [Security.Cryptography.RandomNumberGenerator]::Fill($seed)
  $iv = ([BitConverter]::ToString($seed) -replace '-', '').ToLowerInvariant()
  $aes = [Security.Cryptography.Aes]::Create()
  try {
    $aes.Key = [Text.Encoding]::UTF8.GetBytes([string]$key)
    $aes.IV = [Text.Encoding]::UTF8.GetBytes($iv)
    $aes.Mode = [Security.Cryptography.CipherMode]::CBC
    $aes.Padding = [Security.Cryptography.PaddingMode]::PKCS7
    $plain = [Text.Encoding]::UTF8.GetBytes($Password)
    $encryptor = $aes.CreateEncryptor()
    try { $cipher = $encryptor.TransformFinalBlock($plain, 0, $plain.Length) }
    finally { $encryptor.Dispose() }
  } finally { $aes.Dispose() }
  $prefix = [Text.Encoding]::ASCII.GetBytes($iv)
  $bytes = New-Object byte[] ($prefix.Length + $cipher.Length)
  [Array]::Copy($prefix, 0, $bytes, 0, $prefix.Length)
  [Array]::Copy($cipher, 0, $bytes, $prefix.Length, $cipher.Length)
  $body = @{
    username = 'admin'
    password = [Convert]::ToBase64String($bytes)
    csrf_token = [string]$csrf
  } | ConvertTo-Json -Compress
  $login = Invoke-RestMethod "$Base/api/open/auth/login" -Method Post -ContentType 'application/json' -Body $body -SkipCertificateCheck
  if (-not $login.data.jwt) { throw "SafeLine login failed: $($login.msg)" }
  return @{ Authorization = "Bearer $($login.data.jwt)" }
}

function New-NpmHeaders([string]$Base, [string]$Password) {
  $body = @{ identity = 'admin@njzs.com'; secret = $Password } | ConvertTo-Json -Compress
  $login = Invoke-RestMethod "$Base/api/tokens" -Method Post -ContentType 'application/json' -Body $body
  if (-not $login.token) { throw 'NPM login failed' }
  return @{ Authorization = "Bearer $($login.token)" }
}

$wafBase = 'https://192.168.10.171:9443'
$npmBase = 'http://192.168.10.111:81'
$wafHeaders = New-SafeLineHeaders $wafBase $wafPassword
$npmHeaders = New-NpmHeaders $npmBase $npmPassword
```

命令只输出 `SafeLineLogin=true`、`NpmLogin=true`。

- [ ] **Step 2: 导出四份无令牌配置快照**

```powershell
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backupDir = Join-Path ([IO.Path]::GetTempPath()) "jenkins-waf-backup-$stamp"
New-Item -ItemType Directory -Path $backupDir | Out-Null

$site = Invoke-RestMethod "$wafBase/api/open/site/1" -Headers $wafHeaders -SkipCertificateCheck
$proxy = Invoke-RestMethod "$wafBase/api/open/site/1/proxy" -Headers $wafHeaders -SkipCertificateCheck
$policies = Invoke-RestMethod "$wafBase/api/open/policy?page=1&page_size=100&action=-1" -Headers $wafHeaders -SkipCertificateCheck
$npmHost = Invoke-RestMethod "$npmBase/api/nginx/proxy-hosts/1" -Headers $npmHeaders

$site.data | ConvertTo-Json -Depth 20 | Set-Content (Join-Path $backupDir 'safeline-site-1.json')
$proxy.data | ConvertTo-Json -Depth 20 | Set-Content (Join-Path $backupDir 'safeline-proxy-1.json')
$policies.data | ConvertTo-Json -Depth 20 | Set-Content (Join-Path $backupDir 'safeline-policies.json')
$npmHost | ConvertTo-Json -Depth 20 | Set-Content (Join-Path $backupDir 'npm-proxy-host-1.json')
```

Expected: 四个 JSON 文件存在；内容不包含 `jwt`、`token`、`password`、`csrf_token`。

- [ ] **Step 3: 记录变更前失败与安全基线**

Run:

```powershell
# 雷池事件基线
Invoke-RestMethod "$wafBase/api/open/records?page=1&page_size=100&host=jenkins.nanjingzhengshang.com&action=1" -Headers $wafHeaders -SkipCertificateCheck

# 外部响应与 TLS 基线
Invoke-WebRequest 'https://jenkins.nanjingzhengshang.com/login' -Method Head
```

Expected: 雷池存在 Jenkins `config.xml`/Replay 的 `m_cmd_injection` 拦截；外部 HSTS 为 `max-age=31536000`；TLS 1.0/1.1 在变更前仍可握手。

### Task 2: 创建 Jenkins 精确白名单

**Files:**
- Modify externally: SafeLine custom policy collection `/api/open/policy`
- Test externally: SafeLine policy list and attack/allow logs

**Interfaces:**
- Consumes: Task 1 的 `$wafHeaders`。
- Produces: 唯一启用的策略 `Jenkins Pipeline 管理 POST 误报放行` 及其策略 ID。

- [ ] **Step 1: 确认同名策略不存在**

Run: GET `/api/open/policy?page=1&page_size=100&action=-1` 并按名称过滤。

Expected: 数量为 0；若数量为 1，则比较完整 `pattern/action/log/is_enabled`，完全一致时复用，不一致时停止，不覆盖未知配置。

- [ ] **Step 2: 创建精确白名单**

```json
{
  "name": "Jenkins Pipeline 管理 POST 误报放行",
  "is_enabled": true,
  "action": 0,
  "log": true,
  "auth_source_ids": [],
  "pattern": [
    [
      {"k":"host","op":"eq","v":["jenkins.nanjingzhengshang.com"],"sub_k":""},
      {"k":"method","op":"eq","v":["POST"],"sub_k":""},
      {"k":"uri_no_query","op":"re","v":["^(?:/job/[^/]+)+/(?:config[.]xml|configSubmit)$"],"sub_k":""}
    ],
    [
      {"k":"host","op":"eq","v":["jenkins.nanjingzhengshang.com"],"sub_k":""},
      {"k":"method","op":"eq","v":["POST"],"sub_k":""},
      {"k":"uri_no_query","op":"re","v":["^(?:/job/[^/]+)+/descriptorByName/org[.]jenkinsci[.]plugins[.]workflow[.]cps[.]CpsFlowDefinition/(?:checkScript|checkScriptCompile)$"],"sub_k":""}
    ],
    [
      {"k":"host","op":"eq","v":["jenkins.nanjingzhengshang.com"],"sub_k":""},
      {"k":"method","op":"eq","v":["POST"],"sub_k":""},
      {"k":"uri_no_query","op":"re","v":["^(?:/job/[^/]+)+/[0-9]+/replay/(?:checkScript|checkScriptCompile|run)$"],"sub_k":""}
    ]
  ]
}
```

Run: `POST https://192.168.10.171:9443/api/open/policy` with the JSON body.

Expected: API 成功；重新 GET 后规则启用、`action=0`、`log=true`，三组条件保持不变。

当前雷池版本会在保存策略时移除一层反斜杠，因此正则必须使用 `[.]` 和 `[0-9]`，不要改回 `\.` 或 `\d`。

- [ ] **Step 3: 用无认证安全请求验证规则边界**

Run: 对一个匹配的 Replay `checkScriptCompile` 路径发送空 POST，并对 `/login` 发送 POST。

Expected: 匹配请求到达 Jenkins 并由 Jenkins 返回认证/Crumb/参数错误，不出现雷池拦截页且不触发构建；不匹配路径不命中该白名单日志。

### Task 3: 加固雷池 TLS、HSTS 和 XFF

**Files:**
- Modify externally: SafeLine site proxy `/api/open/site/1/proxy`
- Test externally: `jenkins.nanjingzhengshang.com:443`

**Interfaces:**
- Consumes: Task 1 的完整代理快照和 `$wafHeaders`。
- Produces: 站点级 TLS 1.2/1.3、HSTS 和 XFF 重写设置。

- [ ] **Step 1: 从当前代理对象构造显式 PUT 请求体**

保留当前设置，只覆盖设计要求的六个字段：

```powershell
$currentProxy = (Invoke-RestMethod "$wafBase/api/open/site/1/proxy" -Headers $wafHeaders -SkipCertificateCheck).data
$proxyPayload = [ordered]@{
  id = 1
  ssl_protocols = @{ value = 'TLSv1.2 TLSv1.3'; global = $false }
  force_https = $currentProxy.force_https
  'http_1.0' = $currentProxy.'http_1.0'
  default_server = $currentProxy.default_server
  reset_xff = @{ value = $true; global = $false }
  hsts = @{ value = $true; global = $false }
  hsts_max_age = @{ value = '31536000'; global = $false }
  hsts_preload = @{ value = $false; global = $false }
  hsts_sub = @{ value = $false; global = $false }
  host = $currentProxy.host
  xfp = $currentProxy.xfp
  xfh = $currentProxy.xfh
  gzip = $currentProxy.gzip
  br = $currentProxy.br
  http2 = $currentProxy.http2
  http3 = $currentProxy.http3
  sse = $currentProxy.sse
  ntlm = $currentProxy.ntlm
  ipv6 = $currentProxy.ipv6
  ssl_ciphers = $currentProxy.ssl_ciphers
  http_headers = $currentProxy.http_headers
  ip_source = $currentProxy.ip_source
  ip_value = $currentProxy.ip_value
}
```

不要把 GET 响应中的顶层 `global` 对象发送回更新接口。

- [ ] **Step 2: 更新雷池代理设置**

Run: `PUT https://192.168.10.171:9443/api/open/site/1/proxy` with `$proxyPayload`。

Expected: GET `/api/open/site/1/proxy` 返回 TLS 1.2/1.3、HSTS 开启、`max-age=31536000`、sub/preload 关闭、`reset_xff=true`；站点健康检查仍为正常。

- [ ] **Step 3: 在删除 NPM HSTS 前验证外层配置**

Run: HEAD `https://jenkins.nanjingzhengshang.com/login`，并分别请求 TLS 1.0/1.1/1.2/1.3 握手。

Expected: TLS 1.0/1.1 失败，TLS 1.2/1.3 成功；HSTS 至少存在一次。此阶段如出现重复 HSTS 属于预期，下一任务移除 NPM 手工行。

### Task 4: 删除 NPM 重复 HSTS 行

**Files:**
- Modify externally: NPM proxy host `PUT /api/nginx/proxy-hosts/1`
- Test externally: NPM host config and external Jenkins headers

**Interfaces:**
- Consumes: Task 1 的 NPM 快照、Task 3 已验证的雷池 HSTS、`$npmHeaders`。
- Produces: 除单行 HSTS 外完全不变的 NPM Proxy Host 1。

- [ ] **Step 1: 断言高级配置中恰好存在目标行**

```powershell
$hstsPattern = '(?m)^add_header Strict-Transport-Security "max-age=31536000" always;\r?\n?'
$matches = [regex]::Matches([string]$npmHost.advanced_config, $hstsPattern)
if ($matches.Count -ne 1) { throw "Expected exactly one manual HSTS line, found $($matches.Count)" }
$newAdvanced = [regex]::Replace([string]$npmHost.advanced_config, $hstsPattern, '')
```

- [ ] **Step 2: 只更新允许的 Proxy Host 字段**

```powershell
$npmPayload = [ordered]@{
  domain_names = $npmHost.domain_names
  forward_scheme = $npmHost.forward_scheme
  forward_host = $npmHost.forward_host
  forward_port = $npmHost.forward_port
  caching_enabled = $npmHost.caching_enabled
  allow_websocket_upgrade = $npmHost.allow_websocket_upgrade
  access_list_id = $npmHost.access_list_id
  certificate_id = $npmHost.certificate_id
  ssl_forced = $npmHost.ssl_forced
  hsts_enabled = $npmHost.hsts_enabled
  hsts_subdomains = $npmHost.hsts_subdomains
  http2_support = $npmHost.http2_support
  block_exploits = $npmHost.block_exploits
  advanced_config = $newAdvanced
  meta = $npmHost.meta
  locations = $npmHost.locations
}
```

Run: `PUT http://192.168.10.111:81/api/nginx/proxy-hosts/1`。

Expected: API 成功；重新 GET 后只有 HSTS 行消失，Force SSL 仍为 0，上游仍为 `http://192.168.10.181:9000`，WebSocket 仍开启。

- [ ] **Step 3: 验证外部只返回一个 HSTS 值**

Expected: `Strict-Transport-Security: max-age=31536000` 只出现一次；HTTPS `/login` 返回 200；当前站点未监听 HTTP 80，HTTP 连接保持拒绝且不存在重定向循环。

## Execution Result

- SafeLine 精确白名单策略 ID：`8`
- SafeLine TLS：仅 `TLSv1.2 TLSv1.3`
- SafeLine HSTS：开启，`max-age=31536000`，不含 subdomains/preload
- SafeLine XFF：清空并重写
- NPM 手工 HSTS 行：已删除；Force SSL 仍关闭
- Jenkins 原样配置保存：成功
- Jenkins Replay：从 `#41` 创建 `#42`，结果 `SUCCESS`，无 `#43`
- 变更前备份：`C:\Users\wuzhao\AppData\Local\Temp\jenkins-waf-backup-20260719-135422`
- 回滚：未执行

### Task 5: 端到端回归与回滚门禁

**Files:**
- Test externally: SafeLine、NPM、Jenkins Job `test`
- Modify externally only if rollback is required: restore Task 1 snapshots

**Interfaces:**
- Consumes: Tasks 1-4 的配置与策略 ID。
- Produces: 完整验证记录；失败时恢复到 Task 1 快照。

- [ ] **Step 1: 验证 Jenkins 反向代理与身份边界**

Run: 外部访问 `/login`、`/whoAmI/api/json`、反向代理诊断端点；分别执行匿名 POST 和已认证带 Crumb POST。

Expected: Jenkins URL 为 HTTPS；没有反向代理警告；匿名请求仍被 Jenkins 拒绝；认证请求通过 Jenkins 权限和 Crumb 校验。

- [ ] **Step 2: 验证 Pipeline 配置与 Replay**

使用已登录 Jenkins 会话执行 Pipeline `checkScript/checkScriptCompile`、保存测试 Job 配置，并对最近一次测试构建执行有效 Replay。

Expected: 请求不再被雷池拦截；有效 Replay 创建一个新构建；无效 Replay 返回 Jenkins 业务错误且不创建构建。

- [ ] **Step 3: 等待新构建到终态并检查结果**

轮询新构建的 `/api/json`，直到 `building=false`。

Expected: 新构建结果为 `SUCCESS`；只产生预期的一个有效 Replay 构建。

- [ ] **Step 4: 核对雷池命中与未扩大范围**

Expected: 新规则有白名单命中日志；Host 非 Jenkins、方法非 POST、路径不匹配时不命中新规则；站点健康状态正常。

- [ ] **Step 5: 失败时执行确定性回滚**

回滚顺序：恢复 NPM Proxy Host 1 快照；PUT 恢复雷池 Proxy 1 快照（将站点路径 ID 固定为 1，并移除顶层只读 `global`）；禁用或删除本次新建策略；重新运行 TLS、HSTS、登录页和 Jenkins 基线测试。

Expected: 回滚后链路恢复到 Task 1 基线，并记录未通过的具体门禁，不继续叠加修改。

### Task 6: 仓库状态与交付检查

**Files:**
- Reference: `docs/superpowers/specs/2026-07-19-jenkins-safeline-waf-design.md`
- Reference: `docs/superpowers/plans/2026-07-19-jenkins-safeline-waf-implementation.md`

**Interfaces:**
- Consumes: 全部验证结果。
- Produces: 用户可复核的配置差异、测试结果、备份目录和剩余风险。

- [ ] **Step 1: 对照设计逐项复核成功标准**

Expected: 精确白名单、TLS、HSTS、XFF、NPM、Jenkins POST/Replay/构建八类验证均有新鲜证据。

- [ ] **Step 2: 检查 Git 工作区**

Run: `git status --short` 和 `git diff --check`。

Expected: 用户原有 `AGENTS.md` 修改仍存在且未被覆盖；本任务没有意外源码修改。

- [ ] **Step 3: 交付结果**

报告实际配置值、白名单策略 ID、构建号与结果、备份绝对路径、未通过项和是否执行过回滚；不输出任何密码或令牌。

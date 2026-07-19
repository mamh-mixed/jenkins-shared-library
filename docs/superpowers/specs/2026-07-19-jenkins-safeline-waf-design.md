# Jenkins 雷池 WAF 反代与误报修复设计

## 背景与根因

外部请求链路为：

`客户端 HTTPS -> 雷池 WAF:443 -> Nginx Proxy Manager:80 -> Jenkins:9000`

雷池已记录 36 条 Jenkins 相关攻击事件，其中 24 条执行了拦截。被拦截请求集中在 Pipeline 配置保存和 Replay，最近事件明确由请求体检测模块 `m_cmd_injection` 判定为命令注入，另有信息泄露误报。当前没有 Jenkins 路径例外。

雷池负责 TLS 终止和强制 HTTPS，因此 NPM 的 Force SSL 必须保持关闭。Jenkins 所需的 `Host`、`X-Forwarded-Host`、`X-Forwarded-Proto=https` 和 `X-Forwarded-Port=443` 已正确传递。

## 已确认范围

- Jenkins 的配置保存、Pipeline 校验和 Replay 对所有 Jenkins 登录用户可用。
- 雷池位于 Jenkins 登录之前，无法验证 Jenkins 会话。因此，WAF 白名单只负责让精确匹配的请求到达 Jenkins；身份认证、权限和 Crumb 校验继续由 Jenkins 执行。
- 不关闭 Jenkins 全站防护，不排除整个 `/job/`，不影响通配域下的其他应用。
- 同步修复 TLS 旧协议、HSTS 归属和外部伪造 `X-Forwarded-For` 的风险。

## 配置设计

### 1. 精确自定义白名单

新增并启用一条雷池自定义放行规则，名称为 `Jenkins Pipeline 管理 POST 误报放行`，同时开启命中日志。

规则只在以下条件全部成立时放行：

- `Host` 等于 `jenkins.nanjingzhengshang.com`
- HTTP 方法等于 `POST`
- 不含查询参数的 URL 匹配下列任一组：

```text
^(?:/job/[^/]+)+/(?:config\.xml|configSubmit)$
^(?:/job/[^/]+)+/descriptorByName/org\.jenkinsci\.plugins\.workflow\.cps\.CpsFlowDefinition/(?:checkScript|checkScriptCompile)$
^(?:/job/[^/]+)+/\d+/replay/(?:checkScript|checkScriptCompile|run)$
```

正则兼容普通 Job 和 Folder 中的嵌套 Job。构建触发、登录、插件接口以及其他路径不进入白名单，继续由 WAF 检测。

### 2. 雷池 HTTPS 配置

在现有 `*.nanjingzhengshang.com` 站点上设置：

- TLS 协议：仅 `TLSv1.2 TLSv1.3`
- Force HTTPS：保持开启
- HSTS：开启，`max-age=31536000`
- HSTS `includeSubDomains`：关闭
- HSTS preload：关闭
- HTTP/2：本次保持现状，避免混入非必要变更

先确认雷池已输出 HSTS，再删除 NPM 高级配置中的手工 HSTS 响应头，防止重复响应头。NPM 的 Force SSL 保持关闭。

### 3. 可信转发头

雷池位于最外层，开启“清空并重写 X-Forwarded-For”。保留：

- `Host = $http_host`
- `X-Forwarded-Host = $http_host`
- `X-Forwarded-Proto = $scheme`
- 自定义 `X-Forwarded-Port = 443`

NPM 继续向 Jenkins 传递 HTTPS 外部协议和 443 端口。

## 变更与回滚

变更前只读导出雷池站点、代理、自定义规则和 NPM Proxy Host 配置，备份放在本机临时目录，不包含登录令牌。

若出现异常，按相反顺序回滚：

1. 恢复 NPM 手工 HSTS 响应头。
2. 恢复雷池站点原代理配置。
3. 禁用或删除新建的 Jenkins 精确白名单。
4. 重新验证外部 HTTPS 和 Jenkins 登录页。

## 验证设计

变更后执行以下验证：

1. TLS 1.0/1.1 握手失败，TLS 1.2/1.3 握手成功。
2. 外部 Jenkins HTTPS 返回单一 HSTS 响应头，HTTP 不产生循环重定向。
3. Jenkins URL 保持 HTTPS，反向代理监控不报警。
4. `config.xml`、`configSubmit`、Pipeline `checkScript/checkScriptCompile` 和 Replay POST 能到达 Jenkins。
5. 无效 Replay 请求由 Jenkins 返回业务错误且不触发构建；有效 Replay 能触发并完成测试构建。
6. Jenkins API POST 和 Crumb 校验行为正常。
7. 雷池白名单产生命中日志，其他路径仍由 WAF 检测。
8. 雷池站点健康检查和 NPM/Jenkins 上游状态正常。

## 成功标准

- 已确认的 Jenkins Pipeline/Groovy 误报不再被雷池拦截。
- 未扩大到 Jenkins 全站或其他子域名。
- TLS、HSTS、转发头和重定向行为符合设计。
- 测试 Job 构建成功，且未产生额外意外构建。

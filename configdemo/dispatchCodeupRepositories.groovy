@Library('jenkins-shared-library') _

withCredentials([string(credentialsId: 'codeup-token', variable: 'CODEUP_TOKEN')]) {
    dispatchCodeupRepositories(
        // Codeup 个人访问令牌，必填
        token: CODEUP_TOKEN,
        // Codeup 企业/组织 ID，必填
        organizationId: 'org-id',
        // Codeup API 域名，可不填，默认 https://openapi-rdc.aliyuncs.com
        domain: 'https://openapi-rdc.aliyuncs.com',
        // 自定义 domain 时必须同时加入白名单，防止令牌被发送到非预期主机
        allowedDomains: ['openapi-rdc.aliyuncs.com'],
        // 扫描分支，可不填，默认 master
        ref: 'master',
        // 指定要扫描和分发的 Jenkinsfile 文件名，可不填，默认 Jenkinsfile.groovy
        jenkinsfileName: 'Jenkinsfile.groovy',
        // 只处理指定仓库名称，安全起见默认必填；传空集合时全部跳过
        allowedRepositoryNames: ['repo-a', 'repo-b'],
        // 允许分发的方法，可不填，默认 deployJavaWeb 和 deployWeb
        allowedMethods: ['deployJavaWeb', 'deployWeb'],
        // Jenkinsfile 中出现构建/部署命令字段时，必须由调度任务精确授权
        allowedCommandValues: [
                'clean package',
                'npm ci && npm run build',
                '-Xms128M -Xmx128M',
                '--spring.profiles.active=dev'
        ],
        // 先用 dryRun 验证解析结果，确认后改为 false 或删除此配置
        dryRun: true,
        // dryRun 或执行中存在失败/拒绝项时，是否在最后让流水线失败
        failAtEnd: false
    )
}

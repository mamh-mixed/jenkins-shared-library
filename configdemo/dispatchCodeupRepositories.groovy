@Library('jenkins-shared-library') _

dispatchCodeupRepositories(
        // Codeup 个人访问令牌，必填
        token: 'pt-token',
        // Codeup 企业/组织 ID，必填
        organizationId: 'org-id',
        // Codeup API 域名，可不填，默认 https://openapi-rdc.aliyuncs.com
        domain: 'https://openapi-rdc.aliyuncs.com',
        // 扫描分支，可不填，默认 master
        ref: 'master',
        // 指定要扫描和分发的 Jenkinsfile 文件名，可不填，默认 Jenkinsfile.groovy
        jenkinsfileName: 'Jenkinsfile.groovy',
        // 只处理指定仓库名称，可不填；不传时处理全部仓库，传空集合时全部跳过
        allowedRepositoryNames: ['repo-a', 'repo-b'],
        // 允许分发的方法，可不填，默认 deployJavaWeb 和 deployWeb
        allowedMethods: ['deployJavaWeb', 'deployWeb'],
        // 先用 dryRun 验证解析结果，确认后改为 false 或删除此配置
        dryRun: true,
        // dryRun 或执行中存在失败/拒绝项时，是否在最后让流水线失败
        failAtEnd: false
)

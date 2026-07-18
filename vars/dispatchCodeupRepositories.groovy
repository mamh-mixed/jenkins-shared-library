import com.daluobai.jenkinslib.api.CodeupApi
import com.daluobai.jenkinslib.codeup.JenkinsfileInvocationParser
import com.daluobai.jenkinslib.utils.AssertUtils
import groovy.transform.Field

@Field static final Set<String> REMOTE_COMMAND_FIELDS = [
        'buildCMD', 'afterRunCMD', 'command', 'lifecycle', 'runOptions', 'runArgs', 'buildArgs'
] as Set<String>

/**
 * 扫描 Codeup 仓库中的 Jenkinsfile，并执行允许的方法。
 * jenkinsfileName 可选，按文件名匹配，默认 Jenkinsfile.groovy；
 * allowedRepositoryNames 默认必填，按 Codeup 返回的 repository.name 过滤；
 * 传空集合时全部跳过。只有显式设置 allowAllRepositories=true 才会扫描全部仓库。
 */
def call(Map config = [:]) {
    AssertUtils.notBlank(config.token?.toString(), 'token空的')
    AssertUtils.notBlank(config.organizationId?.toString(), 'organizationId空的')

    String token = config.token.toString()
    String organizationId = config.organizationId.toString()
    String domain = config.domain?.toString() ?: CodeupApi.DEFAULT_DOMAIN
    Set<String> allowedDomains = ((config.allowedDomains ?: [CodeupApi.DEFAULT_DOMAIN]) as Collection).collect { Object item ->
        return normalizeDomainHost(item?.toString())
    } as Set<String>
    validateDomain(domain, allowedDomains)
    String ref = config.ref?.toString() ?: 'master'
    String jenkinsfileName = config.jenkinsfileName?.toString()?.trim() ?: 'Jenkinsfile.groovy'
    boolean dryRun = config.dryRun == true
    boolean failAtEnd = config.failAtEnd == true
    boolean allowAllRepositories = config.allowAllRepositories == true
    boolean allowUnsafeCommandFields = config.allowUnsafeCommandFields == true
    Set<String> allowedCommandValues = ((config.allowedCommandValues ?: []) as Collection).collect { Object item ->
        return item?.toString()
    } as Set<String>

    Set<String> allowedMethods = ((config.allowedMethods ?: ['deployJavaWeb', 'deployWeb']) as Collection).collect { Object item ->
        return item.toString()
    } as Set<String>
    boolean hasAllowedRepositoryNames = config.containsKey('allowedRepositoryNames')
    Set<String> allowedRepositoryNames = null
    if (hasAllowedRepositoryNames) {
        if (!(config.allowedRepositoryNames instanceof Collection)) {
            throw new IllegalArgumentException('allowedRepositoryNames必须是集合')
        }
        allowedRepositoryNames = ((Collection) config.allowedRepositoryNames).collect { Object item ->
            return item?.toString()
        } as Set<String>
    }
    if (!hasAllowedRepositoryNames && !allowAllRepositories) {
        throw new IllegalArgumentException('必须配置allowedRepositoryNames；如确需扫描全部仓库，请显式设置allowAllRepositories=true')
    }

    def whitelist = [
            deployJavaWeb: { Map customConfig -> deployJavaWeb(customConfig) },
            deployWeb    : { Map customConfig -> deployWeb(customConfig) }
    ]
    Set<String> unsupportedMethods = allowedMethods.findAll { String methodName ->
        return !whitelist.containsKey(methodName)
    } as Set<String>
    if (!unsupportedMethods.isEmpty()) {
        throw new IllegalArgumentException("存在不支持的allowedMethods: ${unsupportedMethods.join(', ')}")
    }

    CodeupApi codeupApi = new CodeupApi(this)
    JenkinsfileInvocationParser parser = new JenkinsfileInvocationParser()
    Map summary = [
            scannedRepositories: 0,
            scannedFiles       : 0,
            dispatched         : 0,
            rejected           : [],
            failed             : []
    ]

    List<Map<String, Object>> repositories = codeupApi.listRepositories(domain, token, organizationId)
    summary.scannedRepositories = repositories.size()

    repositories.findAll { Map<String, Object> repository ->
        if (!hasAllowedRepositoryNames) {
            return true
        }
        String repositoryName = repository.name?.toString()
        boolean allowed = allowedRepositoryNames.contains(repositoryName)
        if (!allowed) {
            echo "Codeup仓库不在allowedRepositoryNames中，跳过。repositoryName=${repositoryName}"
        }
        return allowed
    }.each { Map<String, Object> repository ->
        processRepository(repository, codeupApi, parser, whitelist, allowedMethods, domain, token, organizationId, ref,
                jenkinsfileName, dryRun, allowedCommandValues, allowUnsafeCommandFields, summary)
    }

    if (failAtEnd && (!summary.failed.isEmpty() || !summary.rejected.isEmpty())) {
        error("Codeup仓库分发完成，但存在失败或拒绝项。失败: ${summary.failed.size()}，拒绝: ${summary.rejected.size()}")
    }
    return summary
}

private void processRepository(Map<String, Object> repository,
                               CodeupApi codeupApi,
                               JenkinsfileInvocationParser parser,
                               Map<String, Closure> whitelist,
                               Set<String> allowedMethods,
                               String domain,
                               String token,
                               String organizationId,
                               String ref,
                               String jenkinsfileName,
                               boolean dryRun,
                               Set<String> allowedCommandValues,
                               boolean allowUnsafeCommandFields,
                               Map summary) {
    String repositoryId = repository.id?.toString()
    String repositoryName = repository.name?.toString() ?: repository.path?.toString() ?: repository.pathWithNamespace?.toString() ?: repositoryId
    try {
        AssertUtils.notBlank(repositoryId, 'repositoryId空的')
        List<Map<String, Object>> files = codeupApi.listFiles(domain, token, repositoryId, '', ref, 'RECURSIVE', organizationId)
        List<Map<String, Object>> jenkinsfiles = files.findAll { Map<String, Object> fileItem ->
            String name = fileItem.name?.toString()
            String path = fileItem.path?.toString()
            String type = fileItem.type?.toString()
            boolean fileMatches = name == jenkinsfileName || path?.endsWith("/${jenkinsfileName}") || path == jenkinsfileName
            boolean notDirectory = type == null || type != 'tree'
            return fileMatches && notDirectory
        }

        jenkinsfiles.each { Map<String, Object> fileItem ->
            processJenkinsfile(repositoryId, repositoryName, fileItem, codeupApi, parser, whitelist, allowedMethods,
                    domain, token, organizationId, ref, dryRun, allowedCommandValues, allowUnsafeCommandFields, summary)
        }
    } catch (Exception e) {
        Map<String, Object> failure = buildEntry(repositoryId, repositoryName, null, "repository failed: ${e.message}")
        summary.failed.add(failure)
        echo "Codeup仓库处理失败，跳过继续。${failure}"
    }
}

private void processJenkinsfile(String repositoryId,
                                String repositoryName,
                                Map<String, Object> fileItem,
                                CodeupApi codeupApi,
                                JenkinsfileInvocationParser parser,
                                Map<String, Closure> whitelist,
                                Set<String> allowedMethods,
                                String domain,
                                String token,
                                String organizationId,
                                String ref,
                                boolean dryRun,
                                Set<String> allowedCommandValues,
                                boolean allowUnsafeCommandFields,
                                Map summary) {
    String filePath = fileItem.path?.toString() ?: fileItem.name?.toString() ?: 'Jenkinsfile.groovy'
    summary.scannedFiles = ((summary.scannedFiles ?: 0) as int) + 1
    try {
        String content = codeupApi.getFileContent(domain, token, repositoryId, filePath, ref, organizationId)
        if (content == null || content.trim().isEmpty()) {
            throw new RuntimeException('Jenkinsfile内容为空')
        }

        Map invocation = parser.parse(content, allowedMethods)
        validateRemoteConfig(invocation.customConfig, allowedCommandValues, allowUnsafeCommandFields)
        if (dryRun) {
            echo "Codeup Jenkinsfile解析成功（dryRun），repo=${repositoryName}, path=${filePath}, method=${invocation.methodName}"
            return
        }

        whitelist[invocation.methodName](invocation.customConfig as Map)
        summary.dispatched = ((summary.dispatched ?: 0) as int) + 1
        echo "Codeup Jenkinsfile分发成功，repo=${repositoryName}, path=${filePath}, method=${invocation.methodName}"
    } catch (IllegalArgumentException e) {
        Map<String, Object> rejected = buildEntry(repositoryId, repositoryName, filePath, "parse rejected: ${e.message}")
        summary.rejected.add(rejected)
        echo "Codeup Jenkinsfile解析被拒绝，跳过继续。${rejected}"
    } catch (Exception e) {
        Map<String, Object> failure = buildEntry(repositoryId, repositoryName, filePath, "execution failed: ${e.message}")
        summary.failed.add(failure)
        echo "Codeup Jenkinsfile处理失败，跳过继续。${failure}"
    }
}

private static void validateRemoteConfig(Object value,
                                         Set<String> allowedCommandValues,
                                         boolean allowUnsafeCommandFields,
                                         String path = 'customConfig') {
    if (value instanceof Map) {
        ((Map) value).each { Object rawKey, Object nestedValue ->
            String key = rawKey?.toString()
            String currentPath = "${path}.${key}"
            if (REMOTE_COMMAND_FIELDS.contains(key)) {
                if (!allowUnsafeCommandFields) {
                    String commandValue = nestedValue?.toString()
                    if (commandValue != null && commandValue.trim().isEmpty()) {
                        return
                    }
                    if (commandValue == null || !allowedCommandValues.contains(commandValue)) {
                        throw new IllegalArgumentException("远端配置字段${currentPath}包含命令内容，必须通过allowedCommandValues精确授权")
                    }
                }
                return
            }
            validateRemoteConfig(nestedValue, allowedCommandValues, allowUnsafeCommandFields, currentPath)
        }
    } else if (value instanceof Collection) {
        ((Collection) value).eachWithIndex { Object nestedValue, int index ->
            validateRemoteConfig(nestedValue, allowedCommandValues, allowUnsafeCommandFields, "${path}[${index}]")
        }
    } else if (value instanceof CharSequence) {
        String text = value.toString()
        List<String> forbiddenTokens = ['\u0000', '\r', '\n', ';', '&', '|', '`', '$(', '>', '<', "'", '"']
        if (forbiddenTokens.any { String token -> text.contains(token) }) {
            throw new IllegalArgumentException("远端配置字段${path}包含未授权的Shell字符")
        }
    }
}

private static void validateDomain(String domain, Set<String> allowedDomains) {
    String normalized = domain.contains('://') ? domain : "https://${domain}"
    URI uri
    try {
        uri = new URI(normalized)
    } catch (Exception ignored) {
        throw new IllegalArgumentException('domain格式不正确')
    }
    if (!uri.scheme?.equalsIgnoreCase('https') || !uri.host || uri.userInfo != null) {
        throw new IllegalArgumentException('domain必须是无用户信息的HTTPS地址')
    }
    if (!allowedDomains.contains(uri.host.toLowerCase(Locale.ROOT))) {
        throw new IllegalArgumentException("domain不在allowedDomains白名单中: ${uri.host}")
    }
}

private static String normalizeDomainHost(String domain) {
    if (domain == null) {
        return ''
    }
    String normalized = domain.contains('://') ? domain : "https://${domain}"
    try {
        return new URI(normalized).host?.toLowerCase(Locale.ROOT) ?: ''
    } catch (Exception ignored) {
        return ''
    }
}

private static Map<String, Object> buildEntry(String repositoryId, String repositoryName, String filePath, String reason) {
    return [
            repositoryId  : repositoryId,
            repositoryName: repositoryName,
            filePath      : filePath,
            reason        : reason
    ]
}

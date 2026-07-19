import com.daluobai.jenkinslib.utils.AssertUtils
import com.daluobai.jenkinslib.utils.StrUtils
import com.daluobai.jenkinslib.utils.ObjUtils
import com.daluobai.jenkinslib.constant.EBuildStatusType
import com.daluobai.jenkinslib.constant.EFileReadType
import com.daluobai.jenkinslib.steps.StepsBuildNpm
import com.daluobai.jenkinslib.steps.StepsJenkins
import com.daluobai.jenkinslib.steps.StepsJavaWeb
import com.daluobai.jenkinslib.steps.StepsWeb
import com.daluobai.jenkinslib.utils.ConfigUtils
import com.daluobai.jenkinslib.utils.MapUtils
import com.daluobai.jenkinslib.utils.MessageUtils
import com.daluobai.jenkinslib.utils.ConfigMergeUtils
import groovy.transform.Field

@Field Map globalParameterMap = [:]

/**
 * @author daluobai@outlook.com
 * version 1.0.0
 * @title
 * @description https://github.com/daluobai-devops/jenkins-shared-library
 * @create 2023/4/25 12:10
 */
def call(Map customConfig) {

    /*******************初始化全局对象 开始*****************/
    def stepsBuildNpm = new StepsBuildNpm(this)
    def stepsJenkins = new StepsJenkins(this)
    def stepsWeb = new StepsWeb(this)
    def messageUtils = new MessageUtils(this)
    /*******************初始化全局对象 结束*****************/
    //用来运行构建的节点
    def nodeBuildNodeList = stepsJenkins.getNodeByLabel("buildNode")
    echo "获取到节点:${nodeBuildNodeList}"
    if (ObjUtils.isEmpty(nodeBuildNodeList)) {
        error '没有可用的构建节点'
    }
    /***初始化参数 开始**/
    //错误信息
    def errMessage = ""
    EBuildStatusType eBuildStatusType  = EBuildStatusType.FAILED
    //DEPLOY_PIPELINE顺序定义
    def deployPipelineIndex = ["stepsBuildNpm","stepsStorage","stepsJavaWebDeployToWebServer"]
    Map fullConfig = [:]
    /***初始化参数 结束**/
    //默认在同一个构建节点运行，如果需要在其他节点运行则单独写在node块中
    node(nodeBuildNodeList[0]) {
        boolean primaryFailed = false
        try {
            //获取并合并配置
            fullConfig = mergeConfig(customConfig ?: [:])
            if (!(fullConfig.SHARE_PARAM instanceof Map)) {
                fullConfig.SHARE_PARAM = [:]
            }
            if (StrUtils.isBlank(fullConfig.SHARE_PARAM.appName)) {
                fullConfig.SHARE_PARAM.appName = currentBuild.projectName
            }
            echo "配置加载完成，appName=${fullConfig.SHARE_PARAM.appName}"
            //设置共享参数。
            globalParameterMap = fullConfig
            messageUtils.sendMessage(
                    false,
                    fullConfig.SHARE_PARAM.message,
                    "发布开始：${fullConfig.SHARE_PARAM.appName}",
                    "发布开始: ${currentBuild.fullDisplayName}"
            )
            //执行流程
            deployPipelineIndex.each { pipelineStep ->
                stage("${pipelineStep}") {
                    def pipelineConfigItemMap = fullConfig.DEPLOY_PIPELINE[pipelineStep]
                    if (pipelineConfigItemMap["enable"] != null && pipelineConfigItemMap["enable"] == false) {
                        echo "跳过流程: ${pipelineStep}"
                        return
                    }
                    echo "开始执行流程: ${pipelineStep}"
                    if (pipelineStep == "stepsBuildNpm") {
                        stepsBuildNpm.build(fullConfig)
                    } else if (pipelineStep == "stepsStorage") {
                        stepsJenkins.stash(pipelineConfigItemMap)
                    } else if (pipelineStep == "stepsJavaWebDeployToWebServer") {
                        stepsWeb.deploy(pipelineConfigItemMap)
                    }
                }
                echo "结束执行流程: ${pipelineStep}"
            }
            eBuildStatusType = EBuildStatusType.SUCCESS
        } catch (Exception e) {
            primaryFailed = true
            if (e.getClass().getName() == 'org.jenkinsci.plugins.workflow.steps.FlowInterruptedException') {
                eBuildStatusType = EBuildStatusType.ABORTED
            } else {
                eBuildStatusType = EBuildStatusType.FAILED
                errMessage = e.getMessage()
            }
            throw e
        } finally {
            Exception secondaryFailure = null
            try {
                Map notificationShareParam = fullConfig?.SHARE_PARAM instanceof Map
                        ? fullConfig.SHARE_PARAM as Map
                        : (customConfig?.SHARE_PARAM instanceof Map ? customConfig?.SHARE_PARAM as Map : [:])
                if (ObjUtils.isNotEmpty(notificationShareParam.message)) {
                    String messageTitle = ""
                    String messageContent = ""
                    if (eBuildStatusType == EBuildStatusType.SUCCESS) {
                        messageTitle = "成功:${notificationShareParam.appName}"
                        messageContent = "发布成功: ${currentBuild.fullDisplayName}"
                    } else if (eBuildStatusType == EBuildStatusType.FAILED) {
                        messageTitle = "失败:${notificationShareParam.appName}"
                        messageContent = "发布失败: ${currentBuild.fullDisplayName},异常信息: ${errMessage},构建日志:(${BUILD_URL}console)"
                    } else if (eBuildStatusType == EBuildStatusType.ABORTED) {
                        //发布终止
                    }
                    if (StrUtils.isNotBlank(messageTitle) && StrUtils.isNotBlank(messageContent)) {
                        messageUtils.sendMessage(notificationShareParam.message, messageTitle, messageContent)
                    }
                }
            } catch (Exception notificationFailure) {
                secondaryFailure = notificationFailure
                try {
                    echo "结束通知失败: ${notificationFailure.message}"
                } catch (Exception ignored) {
                }
            }
            try {
                deleteDir()
            } catch (Exception cleanupFailure) {
                if (secondaryFailure == null) {
                    secondaryFailure = cleanupFailure
                }
                try {
                    echo "工作区清理失败: ${cleanupFailure.message}"
                } catch (Exception ignored) {
                }
            }
            if (!primaryFailed && secondaryFailure != null) {
                throw secondaryFailure
            }
        }
    }
}

//获取默认配置路径
def defaultConfigPath(EFileReadType eConfigType) {
    AssertUtils.notNull(eConfigType, "配置类型为空")
    def configPath = null
    if (eConfigType == EFileReadType.HOST_PATH) {
        configPath = "/usr/local/workspace/config/jenkins-pipeline/jenkins-pipeline-config/config.json"
    } else if (eConfigType == EFileReadType.RESOURCES) {
        configPath = "config/config.json"
    }  else {
        throw new Exception("暂无默认配置类型")
    }
    return configPath
}

//合并配置customConfig >> extendConfig >> defaultConfig = fullConfig
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



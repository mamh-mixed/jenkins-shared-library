package com.daluobai.jenkinslib.utils

import com.daluobai.jenkinslib.utils.AssertUtils
import com.daluobai.jenkinslib.utils.IoUtils
import com.daluobai.jenkinslib.utils.StrUtils
import com.daluobai.jenkinslib.utils.HttpUtils
import com.daluobai.jenkinslib.constant.EFileReadType
import java.nio.charset.Charset
/**
 * @author daluobai@outlook.com
 * version 1.0.0
 * @title 
 * @description https://github.com/daluobai-devops/jenkins-shared-library
 * @create 2023/4/25 12:10
 */
class ConfigUtils implements Serializable {

    def steps

    ConfigUtils(steps) { this.steps = steps }

    /**
     * 从完整路径读取配置
     * @param configFullPath
     * @return
     */
    def readConfigFromFullPath(String configFullPath) {
        AssertUtils.notBlank(configFullPath, "configFullPath为空");
        def configType = StrUtils.subBefore(configFullPath, ":", false)
        //获取后缀
        def path = StrUtils.subAfter(configFullPath, ":", false)
        EFileReadType extendConfigType = EFileReadType.get(configType)
        return this.readConfig(extendConfigType, path)
    }

    /**
     * 从完整路径读取可选配置；仅目标不存在时返回空配置。
     */
    Map readOptionalConfigFromFullPath(String configFullPath) {
        AssertUtils.notBlank(configFullPath, "configFullPath为空")
        int separatorIndex = configFullPath.indexOf(':')
        AssertUtils.isTrue(
                separatorIndex > 0 && separatorIndex < configFullPath.length() - 1,
                "configFullPath格式错误，应为TYPE:path"
        )
        String configType = configFullPath.substring(0, separatorIndex)
        String path = configFullPath.substring(separatorIndex + 1)
        EFileReadType eConfigType = EFileReadType.get(configType)
        AssertUtils.notNull(eConfigType, "配置类型为空")
        AssertUtils.notBlank(path, "path为空")

        if (eConfigType == EFileReadType.HOST_PATH && !hostPathExists(path)) {
            return [:]
        }

        try {
            return readConfig(eConfigType, path) as Map
        } catch (Exception error) {
            if (isMissingOptionalConfig(eConfigType, path, error)) {
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

    private static boolean isMissingOptionalConfig(EFileReadType eConfigType, String path, Throwable error) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>())
        Throwable current = error
        while (current != null && visited.add(current)) {
            String message = current.message ?: ""
            if (eConfigType == EFileReadType.RESOURCES
                    && message == "No such library resource ${path} could be found.") {
                return true
            }
            if (eConfigType == EFileReadType.URL
                    && message == "HTTP请求失败，响应码: 404") {
                return true
            }
            current = current.cause
        }
        return false
    }

/**
 * 修改文件
 * @param path 文件路径
 * @return
 */
    def readConfig(EFileReadType eConfigType, String path) {
        AssertUtils.notNull(eConfigType, "配置类型为空");
        AssertUtils.notBlank(path, "path为空");
        def configMap = [:]
        def configStr = new FileUtils(steps).readString(eConfigType, path)
        if (eConfigType == EFileReadType.HOST_PATH) {
            configMap = MapUtils.mapJsonString2Map(configStr)
        } else if (eConfigType == EFileReadType.RESOURCES) {
            configMap = MapUtils.mapJsonString2Map(configStr)
        } else if (eConfigType == EFileReadType.URL) {
            configMap = MapUtils.mapJsonString2Map(configStr)
        } else {
            throw new Exception("暂不支持的配置类型")
        }
        AssertUtils.notNull(configMap, "配置文件读取失败");
        return configMap
    }
}

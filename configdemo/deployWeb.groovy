def customConfig = [
        //公共参数
        "SHARE_PARAM"    : [
                //app 名称,如果没填则使用jenkins job名称。可选
                "appName": "testWeb",
                //消息通知，可选
                "message": [
                        //企业微信通知 可选
                        "wecom": [
                                //企业微信机器人token 必填
                                "key": ""
                        ]
                ]
        ],
        //发布流程
        "DEPLOY_PIPELINE": [
                //构建
                "stepsBuildNpm": [
                        //是否激活,默认true
                        "enable": true,
                        //app git url 必填.
                        "gitUrl": "https://gitee.com/log4j/pig-ui.git",
                        //git 分支
                        "gitBranch": "master",
                        //构建命令 必填
                        "buildCMD"   : "npm install && npm run build",
                        //用来打包的镜像 可选
                        "dockerBuildImage"   : "registry.cn-hangzhou.aliyuncs.com/wuzhaozhongguo/build-npm:10.16.0",
                        //使用缓存node_modules 可选，默认true
                        "cacheNodeModules": "true"
                ],
                //存储
                "stepsStorage"  : [
                        //是否激活,默认true
                        "enable": true,
                        //构建产物类型 JAR,WAR,TAR,FOLDER 必填
                        "archiveType":"TAR",
                        //存储类型 jenkinsStash,dockerRegistry 必填
                        "jenkinsStash"  : [
                                //是否激活,默认false
                                "enable": true,
                        ],
                        "dockerRegistry"  : [
                                //是否激活,默认false
                                "enable": false,
                        ],

                ],
                //发布
                "stepsJavaWebDeployToWebServer"  : [
                        //是否激活,默认true
                        "enable": true,
                        //服务发布根目录 必填
                        "pathRoot"  : "/apps/application/projectGroup/web/",
                        //服务发布服务label 必填
                        "labels"  : ["NODE-DEMO"]
                ]
        ],
        //默认配置
        "DEFAULT_CONFIG": [
                "docker": [
                        "registry": [
                                "domain": "docker.io"
                        ]
                ]
        ],
//        //继承配置
        "CONFIG_EXTEND"    : [
                //配置文件完整路径configType:path,支持URL，HOST_PATH，RESOURCES，默认RESOURCES. 必填.
                "configFullPath": "RESOURCES:config/extendConfigWeb.json",
        ]
]
@Library('jenkins-shared-library') _
deployWeb(customConfig)
package com.daluobai.jenkinslib.vars

import com.daluobai.jenkinslib.utils.ConfigUtils
import groovy.json.JsonOutput
import groovy.lang.Binding
import groovy.lang.GroovyShell

abstract class DeployPipelineVarTestSupport {

    protected static Script loadPipelineScript(String scriptPath,
                                               Map<String, Map> resources,
                                               Map params = [:],
                                               List<String> resourceReads = []) {
        Binding binding = new Binding([
                params      : params,
                currentBuild: [projectName: 'jenkins-project', fullDisplayName: 'job #1'],
                BUILD_URL   : 'https://jenkins.example/job/1/'
        ])
        GroovyShell shell = new GroovyShell(ConfigUtils.class.classLoader, binding)
        Script script = shell.parse(new File(scriptPath))

        script.metaClass.libraryResource = { String path ->
            resourceReads.add(path)
            if (!resources.containsKey(path)) {
                throw new IllegalArgumentException(
                        "No such library resource " + path + " could be found."
                )
            }
            return JsonOutput.toJson(resources[path])
        }
        script.metaClass.nodesByLabel = { Map ignored -> ['agent-1'] }
        script.metaClass.echo = { Object ignored -> }
        script.metaClass.error = { Object message -> throw new IllegalStateException(message.toString()) }
        script.metaClass.node = { String ignored, Closure body -> body.call() }
        script.metaClass.stage = { String ignored, Closure body -> body.call() }
        script.metaClass.withEnv = { List ignored, Closure body -> body.call() }
        script.metaClass.deleteDir = { -> }
        return script
    }

    protected static Map disabledWebConfig(Map shareParam = [:]) {
        return [
                SHARE_PARAM    : shareParam,
                DEPLOY_PIPELINE: [
                        stepsBuildNpm                : [enable: false],
                        stepsStorage                 : [enable: false],
                        stepsJavaWebDeployToWebServer: [enable: false]
                ]
        ]
    }

    protected static Map disabledJavaConfig(Map shareParam = [:]) {
        return [
                SHARE_PARAM    : shareParam,
                DEPLOY_PIPELINE: [
                        stepsBuild  : [enable: false, stepsBuildMaven: [:]],
                        stepsStorage: [enable: false],
                        stepsDeploy : [enable: false]
                ]
        ]
    }
}

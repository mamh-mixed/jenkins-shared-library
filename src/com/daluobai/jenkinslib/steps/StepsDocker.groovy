package com.daluobai.jenkinslib.steps

import com.daluobai.jenkinslib.utils.AssertUtils/**
 * @author wuzhao
 * version 1.0.0
 * @title
 * @description <TODO description class purpose>
 * @create 2023/4/25 12:10
 */
class StepsDocker implements Serializable {
    def steps

    StepsDocker(steps) { this.steps = steps }

    def login(String registry,String userName,String password) {
        AssertUtils.notBlank(registry, "registry为空")
        AssertUtils.notBlank(userName, "userName为空")
        AssertUtils.notBlank(password, "password为空")
        steps.withEnv(["DOCKER_REGISTRY=${registry}", "DOCKER_USERNAME=${userName}", "DOCKER_PASSWORD=${password}"]) {
            steps.sh label: "Docker registry login", script: '''#!/bin/sh
set +x
printf '%s' "$DOCKER_PASSWORD" | docker login --username "$DOCKER_USERNAME" --password-stdin "$DOCKER_REGISTRY"
'''
        }
    }

}

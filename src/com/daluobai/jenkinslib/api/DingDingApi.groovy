package com.daluobai.jenkinslib.api

import com.daluobai.jenkinslib.utils.HttpUtils
import com.daluobai.jenkinslib.utils.AssertUtils
import com.daluobai.jenkinslib.utils.JsonUtils
import com.daluobai.jenkinslib.utils.StrUtils
/**
 * @author daluobai@outlook.com
 * version 1.0.0
 * @title 
 * @description https://github.com/daluobai-devops/jenkins-shared-library
 * @create 2023/4/25 12:10
 */
class DingDingApi implements Serializable {
    def steps

    DingDingApi(steps) { this.steps = steps }

    /**
     * 发送钉钉消息
     * @param access_token token
     * @param text 消息内容
     * @return
     */
    def sendMsg(String accessToken,String text) {
        AssertUtils.notBlank(accessToken,"accessToken空的");
        AssertUtils.notBlank(text,"text空的");
        Map<String,Object> params = new HashMap<>();
        params.put("msgtype","text");
        Map<String,Object> paramsText = new HashMap<>();
        paramsText.put("content",text);
        params.put("text",paramsText);

        String paramsStr = JsonUtils.toJsonStr(params);
        try {
            String response = HttpUtils.postJson("https://oapi.dingtalk.com/robot/send?access_token="+accessToken,
                    paramsStr)
            if (StrUtils.isBlank(response)){
                return false
            }
            Map<String, Object> responseJson = JsonUtils.parseObj(response)
            Object errcode = responseJson.get("errcode")
            return errcode != null && Integer.parseInt(errcode.toString()) == 0
        } catch (Exception e) {
            steps?.echo "钉钉通知发送失败: ${e.class.simpleName}"
            return false
        }
    }
}

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
class FeishuApi implements Serializable {
    def steps

    FeishuApi(steps) { this.steps = steps }

    /**
     * 发送飞书消息
     * @param chatToken 群组ID
     * @param msg 消息内容
     * @return
     */
    def sendMsg(String chatToken,String title,String text, String webhookVersion = "v1") {
        AssertUtils.notBlank(chatToken,"chatToken空的");
        AssertUtils.notBlank(title,"title空的");
        AssertUtils.notBlank(text,"text空的");

        boolean useV2 = webhookVersion?.equalsIgnoreCase("v2") || chatToken.startsWith("https://open.feishu.cn/open-apis/bot/v2/")
        String webhookUrl = chatToken.startsWith("http://") || chatToken.startsWith("https://") ?
                chatToken : "https://open.feishu.cn/open-apis/bot/${useV2 ? 'v2/' : ''}hook/${chatToken}"
        Map<String,Object> params = useV2 ? [
                msg_type: "text",
                content : [text: "${title}\n${text}"]
        ] : [title: title, text: text]

        String paramsStr = JsonUtils.toJsonStr(params);
        try {
            String response = HttpUtils.postJson(webhookUrl, paramsStr)
            if (StrUtils.isBlank(response)){
                return false
            }
            Map<String, Object> responseJson = JsonUtils.parseObj(response)
            if (useV2) {
                Object code = responseJson.get("code")
                return code != null && Integer.parseInt(code.toString()) == 0
            }
            return responseJson.get("ok") == true
        } catch (Exception e) {
            steps?.echo "飞书通知发送失败: ${e.class.simpleName}"
            return false
        }
    }
}

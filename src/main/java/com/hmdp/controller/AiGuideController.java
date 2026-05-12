package com.hmdp.controller;

import com.hmdp.agent.ShopAiAgent;
import com.hmdp.dto.Result;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/guide")
public class AiGuideController {
    @Resource
    private ShopAiAgent shopAiAgent;

    @GetMapping
    public Result guide(@RequestParam("query") String query) {
        try {
            // 调用大模型开始思考
            Object aiResponse = shopAiAgent.chat(query);
            return Result.ok(aiResponse);
        } catch (Exception e) {
            e.printStackTrace();
            return Result.fail("AI 导购小助手开小差啦，请稍后再试~");
        }
    }

}

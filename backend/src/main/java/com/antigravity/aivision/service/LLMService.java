package com.antigravity.aivision.service;

import com.antigravity.aivision.model.SessionContext;
import org.springframework.stereotype.Service;
import java.util.stream.Collectors;

@Service
public class LLMService {

    // 模拟调用大模型 (因为没有提供真实的 API Key)
    // 实际项目中应使用 HTTP Client 调用 Gemini 1.5 Pro / GPT-4o API
    public String generateResponse(String imageBase64, String newText, SessionContext context) {
        // 构建提示词的逻辑
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("历史对话上下文：\n");
        for (SessionContext.Turn turn : context.getHistory()) {
            promptBuilder.append("User: ").append(turn.getUserText()).append("\n");
            if (turn.getAiResponse() != null) {
                promptBuilder.append("AI: ").append(turn.getAiResponse()).append("\n");
            }
        }
        promptBuilder.append("最新图像已提供。\n");
        promptBuilder.append("用户最新输入: ").append(newText).append("\n");

        System.out.println("发送给 LLM 的 Prompt: \n" + promptBuilder.toString());

        // 模拟识别与响应逻辑 (根据 PRD 测试场景简单 mock)
        String mockResponse;
        if (newText == null || newText.trim().isEmpty()) {
            mockResponse = "你好呀！很高兴见到你，环境看起来不错。"; // 场景感知
        } else if (newText.contains("这是什么") || newText.contains("看")) {
            mockResponse = "看起来像是一个很有趣的物品！"; // 实体物品识别
        } else if (newText.contains("解释") || newText.contains("指")) {
            mockResponse = "好的，结合你指的画面部分，这是一个非常有意思的细节..."; // 多模态复杂指令
        } else {
            mockResponse = "我明白你的意思了，这确实是个好问题。";
        }
        
        try {
            // 模拟大模型思考延迟
            Thread.sleep(1000); 
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return mockResponse;
    }
}

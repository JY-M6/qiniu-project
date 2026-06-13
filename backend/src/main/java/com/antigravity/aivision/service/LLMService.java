package com.antigravity.aivision.service;

import com.antigravity.aivision.model.ClientMessage;
import com.antigravity.aivision.model.SessionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Service
public class LLMService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public String generateResponse(ClientMessage clientMessage, SessionContext context) {
        String apiKey = clientMessage.getApiKey();
        String baseUrl = clientMessage.getBaseUrl();
        String modelName = clientMessage.getModelName();
        String newText = clientMessage.getText();
        String imageBase64 = clientMessage.getImageBase64();

        // 检查配置是否完整，否则返回提示
        if (apiKey == null || apiKey.trim().isEmpty() || baseUrl == null || baseUrl.trim().isEmpty()) {
            return "请先在页面左下角配置 API Key 和 Base URL 哦。";
        }

        try {
            // 构建 OpenAI Vision 格式的请求
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", modelName != null && !modelName.isEmpty() ? modelName : "gpt-4o");
            
            ArrayNode messagesArray = requestBody.putArray("messages");

            // 1. 放入历史对话
            for (SessionContext.Turn turn : context.getHistory()) {
                if (turn.getUserText() != null && !turn.getUserText().isEmpty()) {
                    ObjectNode userMsg = messagesArray.addObject();
                    userMsg.put("role", "user");
                    userMsg.put("content", turn.getUserText());
                }
                if (turn.getAiResponse() != null && !turn.getAiResponse().isEmpty()) {
                    ObjectNode aiMsg = messagesArray.addObject();
                    aiMsg.put("role", "assistant");
                    aiMsg.put("content", turn.getAiResponse());
                }
            }

            // 2. 放入最新一轮请求（多模态：文字+图片）
            ObjectNode latestUserMsg = messagesArray.addObject();
            latestUserMsg.put("role", "user");
            ArrayNode contentArray = latestUserMsg.putArray("content");

            // 文本部分
            ObjectNode textContent = contentArray.addObject();
            textContent.put("type", "text");
            textContent.put("text", newText != null && !newText.isEmpty() ? newText : "请描述一下你看到了什么。");

            // 图片部分
            if (imageBase64 != null && !imageBase64.isEmpty()) {
                ObjectNode imageContent = contentArray.addObject();
                imageContent.put("type", "image_url");
                ObjectNode imageUrlObj = imageContent.putObject("image_url");
                // 构建 data URL
                imageUrlObj.put("url", "data:image/jpeg;base64," + imageBase64);
            }

            String jsonPayload = objectMapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl.endsWith("/chat/completions") ? baseUrl : baseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode rootNode = objectMapper.readTree(response.body());
                JsonNode choices = rootNode.path("choices");
                if (choices.isArray() && choices.size() > 0) {
                    return choices.get(0).path("message").path("content").asText();
                }
                return "模型返回了空结果";
            } else {
                System.err.println("API Error: " + response.body());
                return "API 调用失败，状态码：" + response.statusCode();
            }

        } catch (Exception e) {
            e.printStackTrace();
            return "调用大模型时发生内部错误：" + e.getMessage();
        }
    }
}

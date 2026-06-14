package com.antigravity.aivision.service;

import com.antigravity.aivision.model.ClientMessage;
import com.antigravity.aivision.model.SessionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.net.ProxySelector;
import java.net.InetSocketAddress;

@Service
public class LLMService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient;

    public LLMService(
            @Value("${proxy.enabled:false}") boolean proxyEnabled,
            @Value("${proxy.host:}") String proxyHost,
            @Value("${proxy.port:0}") int proxyPort) {
        
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10));
        
        if (proxyEnabled && proxyHost != null && !proxyHost.trim().isEmpty() && proxyPort > 0) {
            System.out.println("LLMService: Enabling proxy " + proxyHost + ":" + proxyPort);
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxyHost, proxyPort)));
        } else {
            System.out.println("LLMService: Proxy disabled, using default system settings");
            builder.proxy(ProxySelector.getDefault());
        }
        
        this.httpClient = builder.build();
    }

    public String generateResponse(ClientMessage clientMessage, SessionContext context) {
        String apiKey = clientMessage.getApiKey();
        String baseUrl = clientMessage.getBaseUrl();
        String modelName = clientMessage.getModelName();

        if (apiKey == null || apiKey.trim().isEmpty() || baseUrl == null || baseUrl.trim().isEmpty()) {
            return "请先在页面配置 API Key 和 Base URL 哦。";
        }

        boolean isGeminiNative = baseUrl.contains("generativelanguage.googleapis.com");

        if (isGeminiNative) {
            return generateGeminiResponse(clientMessage, context);
        } else {
            return generateOpenAIResponse(clientMessage, context);
        }
    }

    private String generateGeminiResponse(ClientMessage clientMessage, SessionContext context) {
        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            ArrayNode contentsArray = requestBody.putArray("contents");

            // 1. History (排除最后一轮，因为最新的一轮会在下面作为 Latest User Input 显式添加并携带可能存在的图片)
            int historySize = context.getHistory().size();
            for (int i = 0; i < historySize - 1; i++) {
                SessionContext.Turn turn = context.getHistory().get(i);
                if (turn.getUserText() != null && !turn.getUserText().isEmpty()) {
                    ObjectNode userMsg = contentsArray.addObject();
                    userMsg.put("role", "user");
                    userMsg.putArray("parts").addObject().put("text", turn.getUserText());
                }
                if (turn.getAiResponse() != null && !turn.getAiResponse().isEmpty()) {
                    ObjectNode aiMsg = contentsArray.addObject();
                    aiMsg.put("role", "model");
                    aiMsg.putArray("parts").addObject().put("text", turn.getAiResponse());
                }
            }

            // 2. Latest User Input
            ObjectNode latestMsg = contentsArray.addObject();
            latestMsg.put("role", "user");
            ArrayNode partsArray = latestMsg.putArray("parts");

            String newText = clientMessage.getText();
            partsArray.addObject().put("text", newText != null && !newText.isEmpty() ? newText : "请描述一下你看到了什么。");

            if (clientMessage.getImageBase64() != null && !clientMessage.getImageBase64().isEmpty()) {
                ObjectNode inlineDataWrapper = partsArray.addObject();
                ObjectNode inlineData = inlineDataWrapper.putObject("inlineData");
                inlineData.put("mimeType", "image/jpeg");
                inlineData.put("data", clientMessage.getImageBase64());
            }

            String jsonPayload = objectMapper.writeValueAsString(requestBody);

            // Construct Native URL
            String model = clientMessage.getModelName() != null ? clientMessage.getModelName().trim() : "gemini-3.5-flash";
            String targetUrl = "https://generativelanguage.googleapis.com/v1beta/models/" + model 
                    + ":generateContent?key=" + URLEncoder.encode(clientMessage.getApiKey(), StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode rootNode = objectMapper.readTree(response.body());
                JsonNode candidates = rootNode.path("candidates");
                if (candidates.isArray() && candidates.size() > 0) {
                    JsonNode parts = candidates.get(0).path("content").path("parts");
                    if (parts.isArray() && parts.size() > 0) {
                        return parts.get(0).path("text").asText();
                    }
                }
                return "Gemini 模型返回了空结果";
            } else {
                System.err.println("Gemini API Error: " + response.body());
                return "API 调用失败，状态码：" + response.statusCode() + "，详情：" + response.body();
            }

        } catch (Exception e) {
            e.printStackTrace();
            return "调用 Gemini 时发生内部错误：" + e.getMessage();
        }
    }

    private String generateOpenAIResponse(ClientMessage clientMessage, SessionContext context) {
        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", clientMessage.getModelName() != null && !clientMessage.getModelName().trim().isEmpty() ? clientMessage.getModelName().trim() : "gpt-4o");
            
            ArrayNode messagesArray = requestBody.putArray("messages");

            // 1. History (排除最后一轮，最新一轮会在下面显式作为 Latest Input 添加)
            int historySize = context.getHistory().size();
            for (int i = 0; i < historySize - 1; i++) {
                SessionContext.Turn turn = context.getHistory().get(i);
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

            // 2. Latest Input
            ObjectNode latestUserMsg = messagesArray.addObject();
            latestUserMsg.put("role", "user");
            ArrayNode contentArray = latestUserMsg.putArray("content");

            String newText = clientMessage.getText();
            ObjectNode textContent = contentArray.addObject();
            textContent.put("type", "text");
            textContent.put("text", newText != null && !newText.isEmpty() ? newText : "请描述一下你看到了什么。");

            if (clientMessage.getImageBase64() != null && !clientMessage.getImageBase64().isEmpty()) {
                ObjectNode imageContent = contentArray.addObject();
                imageContent.put("type", "image_url");
                ObjectNode imageUrlObj = imageContent.putObject("image_url");
                imageUrlObj.put("url", "data:image/jpeg;base64," + clientMessage.getImageBase64());
            }

            String jsonPayload = objectMapper.writeValueAsString(requestBody);

            String baseUrl = clientMessage.getBaseUrl();
            String targetUrl = baseUrl.trim();
            if (!targetUrl.endsWith("/chat/completions")) {
                if (targetUrl.endsWith("/")) {
                    targetUrl += "chat/completions";
                } else {
                    targetUrl += "/chat/completions";
                }
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + clientMessage.getApiKey())
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
                System.err.println("OpenAI API Error: " + response.body());
                return "API 调用失败，状态码：" + response.statusCode() + "，详情：" + response.body();
            }

        } catch (Exception e) {
            e.printStackTrace();
            return "调用大模型时发生内部错误：" + e.getMessage();
        }
    }

    public String validateConfiguration(ClientMessage clientMessage) {
        String apiKey = clientMessage.getApiKey() != null ? clientMessage.getApiKey().trim() : null;
        String baseUrl = clientMessage.getBaseUrl() != null ? clientMessage.getBaseUrl().trim() : null;
        String modelName = clientMessage.getModelName() != null ? clientMessage.getModelName().trim() : null;

        if (apiKey == null || apiKey.isEmpty()) return "API_INVALID:API Key 不能为空";
        if (baseUrl == null || baseUrl.isEmpty()) return "URL_INVALID:Base URL 不能为空";
        if (modelName == null || modelName.isEmpty()) return "MODEL_INVALID:模型名称不能为空";

        boolean isGeminiNative = baseUrl.contains("generativelanguage.googleapis.com");

        if (isGeminiNative) {
            return validateGemini(apiKey, modelName);
        } else {
            return validateOpenAI(apiKey, baseUrl, modelName);
        }
    }

    private String validateGemini(String apiKey, String modelName) {
        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            ArrayNode contentsArray = requestBody.putArray("contents");
            ObjectNode testMsg = contentsArray.addObject();
            testMsg.put("role", "user");
            testMsg.putArray("parts").addObject().put("text", "hi");

            String jsonPayload = objectMapper.writeValueAsString(requestBody);
            String targetUrl = "https://generativelanguage.googleapis.com/v1beta/models/" + modelName 
                    + ":generateContent?key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();

            if (statusCode != 200) {
                System.err.println("Gemini Validation Error HTTP " + statusCode + ": " + response.body());
            }

            if (statusCode == 200) {
                return "SUCCESS";
            } else if (statusCode == 400 && response.body().contains("API key not valid")) {
                return "API_INVALID:API Key 无效";
            } else if (statusCode == 404 || statusCode == 400) {
                return "MODEL_INVALID:模型名称错误或不支持(HTTP " + statusCode + ")";
            } else {
                String errDetail = response.body();
                if (errDetail.length() > 200) errDetail = errDetail.substring(0, 200);
                return "URL_INVALID:服务异常 (HTTP " + statusCode + "): " + errDetail;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return "URL_INVALID:连接失败，请检查网络: " + e.toString();
        }
    }

    private String validateOpenAI(String apiKey, String baseUrl, String modelName) {
        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", modelName);
            requestBody.put("max_tokens", 1);
            ArrayNode messagesArray = requestBody.putArray("messages");
            ObjectNode testMsg = messagesArray.addObject();
            testMsg.put("role", "user");
            testMsg.put("content", "hi");

            String jsonPayload = objectMapper.writeValueAsString(requestBody);

            String targetUrl = baseUrl.trim();
            if (!targetUrl.endsWith("/chat/completions")) {
                if (targetUrl.endsWith("/")) {
                    targetUrl += "chat/completions";
                } else {
                    targetUrl += "/chat/completions";
                }
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();

            if (statusCode != 200) {
                System.err.println("OpenAI Validation Error HTTP " + statusCode + ": " + response.body());
            }

            if (statusCode == 200) {
                return "SUCCESS";
            } else if (statusCode == 401 || statusCode == 403) {
                return "API_INVALID:API Key 无效或无权限";
            } else if (statusCode == 404) {
                return "MODEL_INVALID:模型接入点不存在或 URL 错误";
            } else {
                return "URL_INVALID:网络或服务异常 (HTTP " + statusCode + ")";
            }
        } catch (Exception e) {
            e.printStackTrace();
            return "URL_INVALID:连接失败，请检查网络或 URL 格式: " + e.toString();
        }
    }
}

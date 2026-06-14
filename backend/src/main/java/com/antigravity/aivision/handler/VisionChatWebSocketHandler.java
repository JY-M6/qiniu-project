package com.antigravity.aivision.handler;

import com.antigravity.aivision.model.ClientMessage;
import com.antigravity.aivision.model.ServerMessage;
import com.antigravity.aivision.model.SessionContext;
import com.antigravity.aivision.service.LLMService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class VisionChatWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LLMService llmService;
    private final ConcurrentHashMap<String, SessionContext> sessions = new ConcurrentHashMap<>();
    
    // 用于处理请求的线程池，避免阻塞 WebSocket IO
    private final ExecutorService executorService = Executors.newFixedThreadPool(10);

    public VisionChatWebSocketHandler(LLMService llmService) {
        this.llmService = llmService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.put(session.getId(), new SessionContext());
        System.out.println("New WebSocket connection: " + session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        ClientMessage clientMessage = objectMapper.readValue(payload, ClientMessage.class);
        
        SessionContext context = sessions.get(session.getId());
        if (context == null) return;

        // 简单的防抖/限流：1秒内只处理一次请求，避免恶意刷流
        long now = System.currentTimeMillis();
        if (now - context.getLastRequestTime() < 1000) {
            return;
        }
        context.setLastRequestTime(now);

        executorService.submit(() -> {
            try {
                if ("validate_config".equals(clientMessage.getEvent())) {
                    String validationResult = llmService.validateConfiguration(clientMessage);
                    if ("SUCCESS".equals(validationResult)) {
                        sendMessage(session, ServerMessage.builder().status("validation_success").text("配置验证成功").build());
                    } else {
                        // validationResult format: "ERROR_TYPE:Error Message"
                        sendMessage(session, ServerMessage.builder().status("validation_error").text(validationResult).build());
                    }
                    return; // 验证请求处理完毕
                }

                if ("translate".equals(clientMessage.getEvent())) {
                    SessionContext dummyContext = new SessionContext();
                    String originalText = clientMessage.getText();
                    clientMessage.setText("请将以下文字进行中英互译（如果是中文则翻译为英文，如果是英文则翻译为中文）。只需直接输出最符合原文风格的对应译文，不要有任何多余的解释、引号或Markdown包装标签。翻译内容如下：\n" + originalText);
                    String response = llmService.generateResponse(clientMessage, dummyContext);
                    sendMessage(session, ServerMessage.builder().status("translate_success").text(response).build());
                    return;
                }

                // 发送 "思考中" 状态
                sendMessage(session, ServerMessage.builder().status("processing").build());

                // 更新历史上下文
                String text = clientMessage.getText() != null ? clientMessage.getText() : "";
                context.addUserInput(text);

                // 调用大模型服务 (传递客户端配置和上下文)
                String response = llmService.generateResponse(clientMessage, context);
                
                // 更新上下文
                context.updateAiResponse(response);

                // 发送回复
                sendMessage(session, ServerMessage.builder().status("completed").text(response).build());
                
            } catch (Exception e) {
                e.printStackTrace();
                try {
                    sendMessage(session, ServerMessage.builder().status("error")
                            .text("服务器开小差了: " + e.getMessage()).build());
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
            }
        });
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        sessions.remove(session.getId());
        System.out.println("WebSocket connection closed: " + session.getId());
    }

    private void sendMessage(WebSocketSession session, ServerMessage message) throws IOException {
        if (session.isOpen()) {
            synchronized(session) {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(message)));
            }
        }
    }
}

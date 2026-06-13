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
                // 发送 "思考中" 状态
                sendMessage(session, ServerMessage.builder().status("processing").build());

                // 更新历史上下文
                String text = clientMessage.getText() != null ? clientMessage.getText() : "";
                context.addUserInput(text);

                // 调用大模型服务 (传递最新1帧图片和上下文)
                String response = llmService.generateResponse(clientMessage.getImageBase64(), text, context);
                
                // 更新上下文
                context.updateAiResponse(response);

                // 发送回复
                sendMessage(session, ServerMessage.builder().status("completed").text(response).build());
                
            } catch (Exception e) {
                e.printStackTrace();
                try {
                    sendMessage(session, ServerMessage.builder().status("error").text("服务器开小差了").build());
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

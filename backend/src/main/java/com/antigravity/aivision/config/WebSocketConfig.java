package com.antigravity.aivision.config;

import com.antigravity.aivision.handler.VisionChatWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final VisionChatWebSocketHandler visionChatWebSocketHandler;

    public WebSocketConfig(VisionChatWebSocketHandler visionChatWebSocketHandler) {
        this.visionChatWebSocketHandler = visionChatWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(visionChatWebSocketHandler, "/ws/chat")
                .setAllowedOrigins("*"); // 允许跨域
    }
}

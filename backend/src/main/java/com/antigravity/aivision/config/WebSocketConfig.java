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

    @org.springframework.context.annotation.Bean
    public org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean createWebSocketContainer() {
        org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean container = new org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(5 * 1024 * 1024); // 5MB
        container.setMaxBinaryMessageBufferSize(5 * 1024 * 1024); // 5MB
        return container;
    }
}

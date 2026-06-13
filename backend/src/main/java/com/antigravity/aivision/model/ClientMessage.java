package com.antigravity.aivision.model;

import lombok.Data;

@Data
public class ClientMessage {
    private String event; // e.g., "vad_triggered", "text_input"
    private String text; // 用户说的文本
    private String imageBase64; // 最新的1帧图片
    private long timestamp;
    
    // 动态大模型配置
    private String apiKey;
    private String baseUrl;
    private String modelName;
}

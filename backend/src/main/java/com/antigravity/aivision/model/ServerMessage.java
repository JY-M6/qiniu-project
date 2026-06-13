package com.antigravity.aivision.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ServerMessage {
    private String status; // e.g., "processing", "streaming", "completed", "error"
    private String text; // AI回执的文本流
}

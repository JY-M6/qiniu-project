package com.antigravity.aivision.model;

import lombok.Data;
import java.util.LinkedList;

@Data
public class SessionContext {
    private String sessionId;
    // 滑动窗口：保存最近5轮对话，每轮包含用户输入和AI输出
    private LinkedList<Turn> history = new LinkedList<>();
    
    // 速率限制相关
    private long lastRequestTime = 0;

    @Data
    public static class Turn {
        private String userText;
        private String aiResponse;
        
        public Turn(String userText) {
            this.userText = userText;
        }
    }

    public void addUserInput(String text) {
        if (history.size() >= 5) {
            history.removeFirst();
        }
        history.addLast(new Turn(text));
    }

    public void updateAiResponse(String response) {
        if (!history.isEmpty()) {
            Turn lastTurn = history.getLast();
            lastTurn.setAiResponse(response);
        }
    }
}

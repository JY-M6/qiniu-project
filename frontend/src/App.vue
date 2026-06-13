<script setup>
import { ref, onMounted, onUnmounted, nextTick } from 'vue';

const videoRef = ref(null);
const canvasRef = ref(null);
const chatListRef = ref(null);

const isMicOn = ref(false);
const isCamOn = ref(false);
const status = ref('idle'); // idle, listening, speaking, thinking, expressing
const chatHistory = ref([]);
const ws = ref(null);
let mediaStream = null;
let frameInterval = null;
let latestImageBase64 = null;
let speechRecognition = null;

// 大模型配置
const apiKey = ref('');
const baseUrl = ref('https://ark.cn-beijing.volces.com/api/v3');
const modelName = ref('ep-xxxxxxxx-xxxx');
const showSettings = ref(false);

// 初始化 WebSocket
const initWebSocket = () => {
  ws.value = new WebSocket('ws://localhost:8080/ws/chat');
  
  ws.value.onopen = () => {
    console.log('WebSocket Connected');
    chatHistory.value.push({ role: 'system', text: '连接已建立。' });
  };
  
  ws.value.onmessage = (event) => {
    const data = JSON.parse(event.data);
    if (data.status === 'processing') {
      status.value = 'thinking';
    } else if (data.status === 'completed') {
      status.value = 'expressing';
      chatHistory.value.push({ role: 'ai', text: data.text });
      scrollToBottom();
      
      // 模拟表达完成恢复倾听状态
      setTimeout(() => {
        if (isMicOn.value) status.value = 'listening';
        else status.value = 'idle';
      }, 2000);
    } else if (data.status === 'error') {
      chatHistory.value.push({ role: 'system', text: data.text });
      status.value = 'idle';
    }
  };

  ws.value.onclose = () => {
    console.log('WebSocket Disconnected');
    chatHistory.value.push({ role: 'system', text: '连接已断开。' });
  };
};

const scrollToBottom = async () => {
  await nextTick();
  if (chatListRef.value) {
    chatListRef.value.scrollTop = chatListRef.value.scrollHeight;
  }
};

// 发送消息到后端
const sendMessage = (text) => {
  if (ws.value && ws.value.readyState === WebSocket.OPEN) {
    chatHistory.value.push({ role: 'user', text: text });
    scrollToBottom();
    
    ws.value.send(JSON.stringify({
      event: 'user_input',
      text: text,
      imageBase64: latestImageBase64,
      timestamp: Date.now(),
      apiKey: apiKey.value,
      baseUrl: baseUrl.value,
      modelName: modelName.value
    }));
  }
};

// 开启摄像头
const toggleCamera = async () => {
  if (isCamOn.value) {
    // 关闭摄像头
    if (mediaStream) {
      mediaStream.getVideoTracks().forEach(track => track.stop());
    }
    if (frameInterval) clearInterval(frameInterval);
    isCamOn.value = false;
  } else {
    // 开启摄像头
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ video: true });
      mediaStream = stream;
      if (videoRef.value) {
        videoRef.value.srcObject = stream;
      }
      isCamOn.value = true;
      
      // 1fps 抽帧
      frameInterval = setInterval(() => {
        captureFrame();
      }, 1000);
    } catch (err) {
      console.error('Camera error:', err);
      alert('无法访问摄像头');
    }
  }
};

// 抽取视频帧
const captureFrame = () => {
  if (videoRef.value && canvasRef.value) {
    const context = canvasRef.value.getContext('2d');
    // 设置较低分辨率以节省带宽
    canvasRef.value.width = 320;
    canvasRef.value.height = 240;
    context.drawImage(videoRef.value, 0, 0, 320, 240);
    // 获取无前缀的 base64
    const dataUrl = canvasRef.value.toDataURL('image/jpeg', 0.7);
    latestImageBase64 = dataUrl.split(',')[1];
  }
};

// 初始化本地语音识别 (Web Speech API)
const initSpeechRecognition = () => {
  const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
  if (!SpeechRecognition) {
    console.warn('浏览器不支持 Web Speech API');
    return;
  }
  
  speechRecognition = new SpeechRecognition();
  speechRecognition.continuous = true;
  speechRecognition.interimResults = false; // 只获取最终结果
  speechRecognition.lang = 'zh-CN';

  speechRecognition.onstart = () => {
    console.log('Speech recognition started');
    status.value = 'listening';
  };

  speechRecognition.onresult = (event) => {
    status.value = 'speaking'; // 正在说话时的波动效果
    
    const lastResultIndex = event.results.length - 1;
    const text = event.results[lastResultIndex][0].transcript;
    
    console.log('识别到文本: ', text);
    if (text.trim()) {
      sendMessage(text);
    }
    
    // 恢复倾听状态
    setTimeout(() => {
      if (status.value === 'speaking') {
        status.value = 'listening';
      }
    }, 500);
  };

  speechRecognition.onend = () => {
    // 自动重启以保持监听
    if (isMicOn.value) {
      try {
         speechRecognition.start();
      } catch(e){}
    }
  };
};

const toggleMic = () => {
  if (isMicOn.value) {
    if (speechRecognition) speechRecognition.stop();
    isMicOn.value = false;
    status.value = 'idle';
  } else {
    if (!speechRecognition) initSpeechRecognition();
    if (speechRecognition) {
      try {
        speechRecognition.start();
      } catch(e) {}
    }
    isMicOn.value = true;
  }
};

onMounted(() => {
  initWebSocket();
});

onUnmounted(() => {
  if (ws.value) ws.value.close();
  if (mediaStream) mediaStream.getTracks().forEach(track => track.stop());
  if (frameInterval) clearInterval(frameInterval);
  if (speechRecognition) speechRecognition.stop();
});

</script>

<template>
  <div class="app-container">
    <!-- 状态指示器 (AI 核心) -->
    <div class="status-orb-container">
      <div class="status-orb" :class="status"></div>
      <div class="status-text">{{ status === 'idle' ? '未激活' : status === 'listening' ? '倾听中...' : status === 'speaking' ? '接收中...' : status === 'thinking' ? '思考中...' : '表达中' }}</div>
    </div>

    <!-- 左侧顶部：设置按钮与面板 -->
    <button class="settings-toggle glass-panel" @click="showSettings = !showSettings">
      ⚙️ 配置模型
    </button>
    <div v-if="showSettings" class="settings-panel glass-panel">
      <h3>大模型 API 配置 (OpenAI 兼容)</h3>
      <div class="input-group">
        <label>Base URL</label>
        <input v-model="baseUrl" type="text" placeholder="https://api.openai.com/v1" />
      </div>
      <div class="input-group">
        <label>API Key</label>
        <input v-model="apiKey" type="password" placeholder="sk-..." />
      </div>
      <div class="input-group">
        <label>Model Name (Endpoint)</label>
        <input v-model="modelName" type="text" placeholder="gpt-4o / ep-..." />
      </div>
      <p class="hint">请确保使用的模型支持多模态（Vision）功能</p>
    </div>

    <!-- 主视觉区：摄像头 -->
    <div class="camera-section glass-panel">
      <video ref="videoRef" autoplay playsinline muted class="camera-preview"></video>
      <canvas ref="canvasRef" style="display: none;"></canvas>
      <div v-if="!isCamOn" class="placeholder">
        <p>摄像头未开启</p>
      </div>
    </div>

    <!-- 右侧：对话流 -->
    <div class="chat-section glass-panel">
      <div class="chat-header">对话记录</div>
      <div class="chat-list" ref="chatListRef">
        <div v-for="(msg, index) in chatHistory" :key="index" :class="['chat-bubble', msg.role]">
          {{ msg.text }}
        </div>
      </div>
    </div>

    <!-- 底部控制栏 -->
    <div class="control-bar glass-panel">
      <button @click="toggleCamera" :class="{ active: isCamOn }">
        {{ isCamOn ? '关闭摄像头' : '开启摄像头' }}
      </button>
      <button @click="toggleMic" :class="{ active: isMicOn }">
        {{ isMicOn ? '关闭麦克风' : '开启麦克风' }}
      </button>
    </div>
  </div>
</template>

<style scoped>
/* 玻璃拟物化核心样式 */
.app-container {
  display: flex;
  flex-wrap: wrap;
  width: 100vw;
  height: 100vh;
  padding: 20px;
  box-sizing: border-box;
  background: linear-gradient(135deg, #0f2027, #203a43, #2c5364);
  color: white;
  font-family: 'Inter', system-ui, sans-serif;
  overflow: hidden;
  position: relative;
}

.glass-panel {
  background: rgba(255, 255, 255, 0.05);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 24px;
  box-shadow: 0 4px 30px rgba(0, 0, 0, 0.1);
}

/* 状态指示器 (呼吸灯) */
.status-orb-container {
  position: absolute;
  top: 40px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  flex-direction: column;
  align-items: center;
  z-index: 10;
}

.status-orb {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.3);
  transition: all 0.5s ease;
}

.status-orb.idle {
  background: rgba(200, 200, 200, 0.3);
}

.status-orb.listening {
  background: #00d2ff;
  box-shadow: 0 0 20px #00d2ff;
  animation: breathe 3s infinite ease-in-out;
}

.status-orb.speaking {
  background: #3a7bd5;
  box-shadow: 0 0 30px #3a7bd5;
  animation: pulse 0.5s infinite alternate;
}

.status-orb.thinking {
  background: #fdfc47;
  box-shadow: 0 0 25px #fdfc47;
  animation: spinbreathe 2s infinite linear;
}

.status-orb.expressing {
  background: #24fe41;
  box-shadow: 0 0 30px #24fe41;
  animation: flash 1s infinite alternate;
}

.status-text {
  margin-top: 10px;
  font-size: 0.8rem;
  letter-spacing: 2px;
  opacity: 0.8;
}

@keyframes breathe {
  0%, 100% { transform: scale(1); opacity: 0.8; }
  50% { transform: scale(1.2); opacity: 1; }
}

@keyframes pulse {
  0% { transform: scale(1); border-radius: 50%; }
  100% { transform: scale(1.5); border-radius: 30%; }
}

@keyframes spinbreathe {
  0% { transform: rotate(0deg) scale(1); opacity: 0.8; }
  50% { transform: rotate(180deg) scale(1.1); opacity: 1; }
  100% { transform: rotate(360deg) scale(1); opacity: 0.8; }
}

@keyframes flash {
  0% { opacity: 0.4; }
  100% { opacity: 1; box-shadow: 0 0 40px #24fe41; }
}

/* 布局区域 */
.camera-section {
  flex: 2;
  margin-right: 20px;
  margin-bottom: 80px;
  position: relative;
  overflow: hidden;
  display: flex;
  justify-content: center;
  align-items: center;
}

.camera-preview {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.placeholder {
  position: absolute;
  color: rgba(255,255,255,0.5);
}

.chat-section {
  flex: 1;
  margin-bottom: 80px;
  display: flex;
  flex-direction: column;
  min-width: 300px;
}

.chat-header {
  padding: 20px;
  font-weight: 600;
  border-bottom: 1px solid rgba(255,255,255,0.1);
  text-align: center;
}

.chat-list {
  flex: 1;
  padding: 20px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 15px;
}

.chat-list::-webkit-scrollbar {
  width: 6px;
}
.chat-list::-webkit-scrollbar-thumb {
  background: rgba(255,255,255,0.2);
  border-radius: 3px;
}

.chat-bubble {
  max-width: 80%;
  padding: 12px 16px;
  border-radius: 18px;
  font-size: 0.95rem;
  line-height: 1.5;
  animation: slideIn 0.3s ease-out;
}

@keyframes slideIn {
  from { opacity: 0; transform: translateY(10px); }
  to { opacity: 1; transform: translateY(0); }
}

.chat-bubble.user {
  align-self: flex-end;
  background: rgba(0, 132, 255, 0.6);
  border-bottom-right-radius: 4px;
}

.chat-bubble.ai {
  align-self: flex-start;
  background: rgba(255, 255, 255, 0.15);
  border-bottom-left-radius: 4px;
}

.chat-bubble.system {
  align-self: center;
  background: transparent;
  font-size: 0.8rem;
  color: rgba(255,255,255,0.5);
  text-align: center;
}

/* 控制栏 */
.control-bar {
  position: absolute;
  bottom: 20px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  gap: 20px;
  padding: 15px 30px;
}

button {
  background: rgba(255, 255, 255, 0.1);
  border: 1px solid rgba(255, 255, 255, 0.2);
  color: white;
  padding: 12px 24px;
  border-radius: 30px;
  font-size: 1rem;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.3s ease;
  backdrop-filter: blur(10px);
}

button:hover {
  background: rgba(255, 255, 255, 0.2);
}

button.active {
  background: rgba(0, 210, 255, 0.3);
  border-color: #00d2ff;
  box-shadow: 0 0 15px rgba(0, 210, 255, 0.4);
}

/* 设置面板 */
.settings-toggle {
  position: absolute;
  top: 20px;
  left: 20px;
  z-index: 20;
  padding: 8px 16px;
  font-size: 0.9rem;
}

.settings-panel {
  position: absolute;
  top: 70px;
  left: 20px;
  z-index: 20;
  padding: 20px;
  width: 320px;
  display: flex;
  flex-direction: column;
  gap: 15px;
}

.settings-panel h3 {
  margin: 0;
  font-size: 1.1rem;
  font-weight: 500;
  border-bottom: 1px solid rgba(255, 255, 255, 0.2);
  padding-bottom: 10px;
}

.input-group {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.input-group label {
  font-size: 0.85rem;
  color: rgba(255, 255, 255, 0.8);
}

.input-group input {
  background: rgba(0, 0, 0, 0.2);
  border: 1px solid rgba(255, 255, 255, 0.2);
  color: white;
  padding: 8px 12px;
  border-radius: 8px;
  outline: none;
}

.input-group input:focus {
  border-color: #00d2ff;
}

.hint {
  font-size: 0.75rem;
  color: rgba(255, 255, 255, 0.5);
  margin: 0;
}
</style>

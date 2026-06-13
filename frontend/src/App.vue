<script setup>
import { ref, onMounted, onUnmounted, nextTick } from 'vue';

const videoRef = ref(null);
const canvasRef = ref(null);
const chatListRef = ref(null);

const isMicOn = ref(false);
const isCamOn = ref(false);
const isConversationActive = ref(false);

const status = ref('idle'); // idle, listening, speaking, thinking, expressing
const chatHistory = ref([]);
const ws = ref(null);
let mediaStream = null;
let frameInterval = null;
let latestImageBase64 = null;
let speechRecognition = null;

// 大模型配置
const apiKey = ref('');
const baseUrl = ref('');
const modelName = ref('');
const showSettings = ref(false);
const activeModelName = ref('');

// 错误状态
const apiError = ref('');
const urlError = ref('');
const modelError = ref('');

// 初始化 WebSocket (仅连接，不自动开启对话硬件)
const initWebSocket = () => {
  if (ws.value && ws.value.readyState !== WebSocket.CLOSED) return;

  ws.value = new WebSocket('ws://localhost:8080/ws/chat');
  
  ws.value.onopen = () => {
    console.log('WebSocket Connected');
  };
  
  ws.value.onmessage = (event) => {
    const data = JSON.parse(event.data);
    
    // 拦截验证消息
    if (data.status === 'validation_success') {
      alert('配置成功！');
      activeModelName.value = modelName.value;
      showSettings.value = false;
    } else if (data.status === 'validation_error') {
      // 格式: ERROR_TYPE:ErrorMessage
      const [type, msg] = data.text.split(':');
      if (type === 'API_INVALID') apiError.value = msg;
      else if (type === 'URL_INVALID') urlError.value = msg;
      else if (type === 'MODEL_INVALID') modelError.value = msg;
      else alert(data.text);
    }
    // 拦截常规对话状态
    else if (data.status === 'processing') {
      status.value = 'thinking';
    } else if (data.status === 'completed') {
      status.value = 'expressing';
      chatHistory.value.push({ role: 'ai', text: data.text });
      scrollToBottom();
      
      setTimeout(() => {
        if (isConversationActive.value) status.value = 'listening';
        else status.value = 'idle';
      }, 2000);
    } else if (data.status === 'error') {
      chatHistory.value.push({ role: 'system', text: data.text });
      status.value = 'idle';
    }
  };

  ws.value.onclose = () => {
    console.log('WebSocket Disconnected');
    if (isConversationActive.value) {
      chatHistory.value.push({ role: 'system', text: '连接已断开，会话结束。' });
      stopConversation();
    }
  };
};

const scrollToBottom = async () => {
  await nextTick();
  if (chatListRef.value) {
    chatListRef.value.scrollTop = chatListRef.value.scrollHeight;
  }
};

// 发送常规对话消息
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

// 验证配置
const validateConfig = () => {
  // 清空错误
  apiError.value = '';
  urlError.value = '';
  modelError.value = '';

  if (!apiKey.value) { apiError.value = "API Key 不能为空"; return; }
  if (!baseUrl.value) { urlError.value = "Base URL 不能为空"; return; }
  if (!modelName.value) { modelError.value = "模型名称不能为空"; return; }

  // 确保 socket 已连
  if (!ws.value || ws.value.readyState !== WebSocket.OPEN) {
    initWebSocket();
    setTimeout(validateConfig, 500); // 简单重试机制等连接好
    return;
  }

  ws.value.send(JSON.stringify({
    event: 'validate_config',
    apiKey: apiKey.value,
    baseUrl: baseUrl.value,
    modelName: modelName.value
  }));
};

const clearConfig = () => {
  apiKey.value = '';
  baseUrl.value = '';
  modelName.value = '';
  activeModelName.value = '';
  apiError.value = '';
  urlError.value = '';
  modelError.value = '';
};

// 开启硬件：摄像头
const startCamera = async () => {
  try {
    const stream = await navigator.mediaDevices.getUserMedia({ video: true });
    mediaStream = stream;
    if (videoRef.value) videoRef.value.srcObject = stream;
    isCamOn.value = true;
    
    frameInterval = setInterval(captureFrame, 1000);
  } catch (err) {
    console.error('Camera error:', err);
    alert('无法访问摄像头');
  }
};

// 关闭硬件：摄像头
const stopCamera = () => {
  if (mediaStream) mediaStream.getVideoTracks().forEach(track => track.stop());
  if (frameInterval) clearInterval(frameInterval);
  isCamOn.value = false;
};

// 抽取视频帧
const captureFrame = () => {
  if (videoRef.value && canvasRef.value && isCamOn.value) {
    const context = canvasRef.value.getContext('2d');
    canvasRef.value.width = 320;
    canvasRef.value.height = 240;
    context.drawImage(videoRef.value, 0, 0, 320, 240);
    const dataUrl = canvasRef.value.toDataURL('image/jpeg', 0.7);
    latestImageBase64 = dataUrl.split(',')[1];
  }
};

// 初始化麦克风 (Web Speech API)
const initSpeechRecognition = () => {
  const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
  if (!SpeechRecognition) {
    console.warn('浏览器不支持 Web Speech API');
    return;
  }
  
  speechRecognition = new SpeechRecognition();
  speechRecognition.continuous = true;
  speechRecognition.interimResults = false;
  speechRecognition.lang = 'zh-CN';

  speechRecognition.onstart = () => {
    status.value = 'listening';
  };

  speechRecognition.onresult = (event) => {
    status.value = 'speaking';
    const lastResultIndex = event.results.length - 1;
    const text = event.results[lastResultIndex][0].transcript;
    
    if (text.trim()) {
      sendMessage(text);
    }
    
    setTimeout(() => {
      if (status.value === 'speaking') status.value = 'listening';
    }, 500);
  };

  speechRecognition.onend = () => {
    if (isMicOn.value && isConversationActive.value) {
      try { speechRecognition.start(); } catch(e){}
    }
  };
};

const startMic = () => {
  if (!speechRecognition) initSpeechRecognition();
  if (speechRecognition) {
    try { speechRecognition.start(); } catch(e) {}
  }
  isMicOn.value = true;
};

const stopMic = () => {
  if (speechRecognition) speechRecognition.stop();
  isMicOn.value = false;
};

// 统管会话
const startConversation = () => {
  if (!activeModelName.value) {
    alert("请先在左上角配置大模型！");
    showSettings.value = true;
    return;
  }
  isConversationActive.value = true;
  chatHistory.value.push({ role: 'system', text: '对话已开始。' });
  if (!ws.value || ws.value.readyState !== WebSocket.OPEN) initWebSocket();
  startCamera();
  startMic();
  status.value = 'listening';
};

const stopConversation = () => {
  isConversationActive.value = false;
  status.value = 'idle';
  stopCamera();
  stopMic();
  chatHistory.value.push({ role: 'system', text: '对话已结束。' });
};

onMounted(() => {
  initWebSocket();
});

onUnmounted(() => {
  if (ws.value) ws.value.close();
  stopConversation();
});

</script>

<template>
  <div class="app-container">
    <!-- 当前激活模型显示 -->
    <div class="active-model-display" v-if="activeModelName">
      <span class="dot green"></span> 当前模型: {{ activeModelName }}
    </div>

    <!-- 状态指示器 (AI 核心) -->
    <div class="status-orb-container">
      <div class="status-orb" :class="status"></div>
      <div class="status-text">{{ status === 'idle' ? '未激活' : status === 'listening' ? '倾听中...' : status === 'speaking' ? '接收中...' : status === 'thinking' ? '思考中...' : '表达中' }}</div>
    </div>

    <!-- 左侧顶部：设置按钮与面板 -->
    <button class="settings-toggle glass-panel" @click="showSettings = !showSettings">
      ⚙️ 配置大模型
    </button>
    
    <div v-if="showSettings" class="settings-panel glass-panel">
      <h3>大模型 API 配置</h3>
      
      <div class="input-group">
        <label>
          Base URL 
          <span v-if="urlError" class="error-msg">{{ urlError }}</span>
        </label>
        <input v-model="baseUrl" type="text" :class="{ 'input-error': urlError }" placeholder="https://api.openai.com/v1" />
      </div>
      
      <div class="input-group">
        <label>
          API Key 
          <span v-if="apiError" class="error-msg">{{ apiError }}</span>
        </label>
        <input v-model="apiKey" type="password" :class="{ 'input-error': apiError }" placeholder="sk-..." />
      </div>
      
      <div class="input-group">
        <label>
          模型名称 / 接入点 
          <span v-if="modelError" class="error-msg">{{ modelError }}</span>
        </label>
        <input v-model="modelName" type="text" :class="{ 'input-error': modelError }" placeholder="gpt-4o / ep-..." />
      </div>
      
      <p class="hint">请确保使用的模型支持多模态（Vision）功能</p>
      
      <div class="panel-actions">
        <button class="btn-clear" @click="clearConfig">清空</button>
        <button class="btn-confirm" @click="validateConfig">确定并验证</button>
      </div>
    </div>

    <!-- 主视觉区：摄像头 -->
    <div class="camera-section glass-panel">
      <video ref="videoRef" autoplay playsinline muted class="camera-preview"></video>
      <canvas ref="canvasRef" style="display: none;"></canvas>
      <div v-if="!isCamOn" class="placeholder">
        <p>画面未开启</p>
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
      <button v-if="!isConversationActive" @click="startConversation" class="btn-start">
        开始对话
      </button>
      <button v-else @click="stopConversation" class="btn-stop">
        结束对话
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

.active-model-display {
  position: absolute;
  top: 25px;
  right: 25px;
  font-size: 0.85rem;
  background: rgba(0,0,0,0.3);
  padding: 6px 12px;
  border-radius: 20px;
  display: flex;
  align-items: center;
  gap: 8px;
  z-index: 5;
}
.dot.green {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #24fe41;
  box-shadow: 0 0 8px #24fe41;
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
  width: 340px;
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
  display: flex;
  justify-content: space-between;
}

.error-msg {
  color: #ff4d4f;
  font-weight: 600;
  font-size: 0.75rem;
}

.input-group input {
  background: rgba(0, 0, 0, 0.2);
  border: 1px solid rgba(255, 255, 255, 0.2);
  color: white;
  padding: 8px 12px;
  border-radius: 8px;
  outline: none;
  transition: all 0.3s;
}

.input-group input:focus {
  border-color: #00d2ff;
}

.input-group input.input-error {
  border-color: #ff4d4f;
  background: rgba(255, 77, 79, 0.1);
}

.hint {
  font-size: 0.75rem;
  color: rgba(255, 255, 255, 0.5);
  margin: 0;
}

.panel-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 10px;
}

.btn-clear {
  padding: 6px 12px;
  font-size: 0.85rem;
  background: transparent;
  border: 1px solid rgba(255, 255, 255, 0.3);
}

.btn-confirm {
  padding: 6px 16px;
  font-size: 0.85rem;
  background: #00d2ff;
  color: #0f2027;
  border: none;
  font-weight: 600;
}
.btn-confirm:hover {
  background: #3a7bd5;
  color: white;
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
.chat-list::-webkit-scrollbar { width: 6px; }
.chat-list::-webkit-scrollbar-thumb { background: rgba(255,255,255,0.2); border-radius: 3px; }

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
  padding: 15px 40px;
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
button:hover { background: rgba(255, 255, 255, 0.2); }

.btn-start {
  background: rgba(36, 254, 65, 0.2);
  border-color: #24fe41;
  color: #24fe41;
}
.btn-start:hover {
  background: rgba(36, 254, 65, 0.4);
}

.btn-stop {
  background: rgba(255, 77, 79, 0.2);
  border-color: #ff4d4f;
  color: #ff4d4f;
}
.btn-stop:hover {
  background: rgba(255, 77, 79, 0.4);
}
</style>

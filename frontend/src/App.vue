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
const baseUrl = ref('https://dashscope.aliyuncs.com/compatible-mode/v1');
const modelName = ref('qwen-vl-max');
const showSettings = ref(true); // 默认展开设置面板
const activeModelName = ref('');

// Toast 通知系统
const toast = ref({ visible: false, message: '', type: 'success' });
let toastTimer = null;
const showToast = (message, type = 'success', duration = 3000) => {
  if (toastTimer) clearTimeout(toastTimer);
  toast.value = { visible: true, message, type, duration };
  toastTimer = setTimeout(() => {
    toast.value.visible = false;
  }, duration);
};

// 实时语音识别预览
const speechPreviewText = ref('');

// 厂商和模型映射
const selectedVendor = ref('dashscope');
const selectedModelOption = ref('qwen-vl-max');
const customModelName = ref('');

const vendors = [
  { id: 'dashscope', name: '阿里百炼 (通义千问)', defaultUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1' },
  { id: 'openai', name: 'OpenAI (GPT)', defaultUrl: 'https://api.openai.com/v1' },
  { id: 'gemini', name: 'Google (Gemini)', defaultUrl: 'https://generativelanguage.googleapis.com/' },
  { id: 'volcengine', name: '火山引擎 (豆包)', defaultUrl: 'https://ark.cn-beijing.volces.com/api/v3' },
  { id: 'custom', name: '自定义配置', defaultUrl: '' }
];

const vendorModels = {
  dashscope: [
    { value: 'qwen-vl-max', label: '通义千问 (qwen-vl-max)' },
    { value: 'qwen-vl-plus', label: '通义千问 (qwen-vl-plus)' },
    { value: 'qwen2.5-vl-7b-instruct', label: '通义千问 2.5 (qwen2.5-vl-7b-instruct)' },
    { value: 'qwen2.5-vl-72b-instruct', label: '通义千问 2.5 (qwen2.5-vl-72b-instruct)' }
  ],
  openai: [
    { value: 'gpt-4o', label: 'GPT-4o (gpt-4o)' },
    { value: 'gpt-4o-mini', label: 'GPT-4o-mini (gpt-4o-mini)' }
  ],
  gemini: [
    { value: 'gemini-3.5-flash', label: 'Gemini 3.5 Flash (gemini-3.5-flash)' },
    { value: 'gemini-3.5-pro', label: 'Gemini 3.5 Pro (gemini-3.5-pro)' }
  ],
  volcengine: [
    { value: 'doubao-1.5-vision-pro', label: '豆包 1.5 视觉 Pro (doubao-1.5-vision-pro)' },
    { value: 'doubao-1.5-vision-lite', label: '豆包 1.5 视觉 Lite (doubao-1.5-vision-lite)' }
  ],
  custom: []
};

const handleVendorChange = () => {
  const v = vendors.find(item => item.id === selectedVendor.value);
  if (v) {
    baseUrl.value = v.defaultUrl;
  }
  
  const models = vendorModels[selectedVendor.value] || [];
  if (models.length > 0) {
    selectedModelOption.value = models[0].value;
    modelName.value = models[0].value;
  } else {
    selectedModelOption.value = 'custom';
    modelName.value = '';
    customModelName.value = '';
  }
};

const handleModelOptionChange = () => {
  if (selectedModelOption.value === 'custom') {
    if (selectedVendor.value === 'volcengine') {
      customModelName.value = 'ep-';
    } else {
      customModelName.value = '';
    }
    modelName.value = customModelName.value;
  } else {
    modelName.value = selectedModelOption.value;
  }
};

const handleCustomModelNameInput = () => {
  modelName.value = customModelName.value;
};

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
      showToast('✅ 配置成功！模型已连接', 'success');
      activeModelName.value = modelName.value;
      showSettings.value = false;
    } else if (data.status === 'validation_error') {
      // 格式: ERROR_TYPE:ErrorMessage
      const [type, msg] = data.text.split(':');
      if (type === 'API_INVALID') apiError.value = msg;
      else if (type === 'URL_INVALID') urlError.value = msg;
      else if (type === 'MODEL_INVALID') modelError.value = msg;
      else showToast(data.text, 'error');
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

const manualInputText = ref('');
const sendManualMessage = () => {
  if (manualInputText.value.trim()) {
    sendMessage(manualInputText.value.trim());
    manualInputText.value = '';
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
  selectedVendor.value = 'custom';
  selectedModelOption.value = 'custom';
  customModelName.value = '';
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
    showToast('⚠️ 无法访问摄像头', 'warning');
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
  speechRecognition.interimResults = true;
  speechRecognition.lang = 'zh-CN';

  speechRecognition.onstart = () => {
    status.value = 'listening';
    showToast('🎙️ 麦克风已就绪，请开始说话！', 'success', 2000);
  };

  speechRecognition.onresult = (event) => {
    let interimTranscript = '';
    let finalTranscript = '';
    
    for (let i = event.resultIndex; i < event.results.length; ++i) {
      const transcript = event.results[i][0].transcript;
      if (event.results[i].isFinal) {
        finalTranscript += transcript;
      } else {
        interimTranscript += transcript;
      }
    }
    
    if (interimTranscript) {
      status.value = 'speaking';
      speechPreviewText.value = interimTranscript;
      scrollToBottom();
    }
    
    if (finalTranscript.trim()) {
      sendMessage(finalTranscript.trim());
      speechPreviewText.value = '';
      status.value = 'thinking';
    }
  };

  speechRecognition.onerror = (event) => {
    console.error('Speech recognition error:', event.error);
    if (event.error === 'not-allowed') {
      showToast('❌ 麦克风权限被拒绝，请在浏览器中允许访问麦克风', 'error');
      isMicOn.value = false;
      status.value = 'idle';
    } else if (event.error === 'no-speech') {
      // 仅无声，不中断
    } else {
      showToast(`⚠️ 语音助手提示: ${event.error}`, 'warning', 2000);
    }
  };

  speechRecognition.onend = () => {
    if (isMicOn.value && isConversationActive.value) {
      try { 
        speechRecognition.start(); 
      } catch(e){}
    }
  };
};

const startMic = () => {
  if (!speechRecognition) initSpeechRecognition();
  if (speechRecognition) {
    try { 
      speechRecognition.start(); 
    } catch(e) {}
  }
  isMicOn.value = true;
};

const stopMic = () => {
  isMicOn.value = false;
  if (speechRecognition) {
    try { 
      speechRecognition.stop(); 
    } catch(e) {}
  }
  speechPreviewText.value = '';
};

// 统管会话
const startConversation = () => {
  if (!activeModelName.value) {
    showToast('⚠️ 请先在左上角配置大模型！', 'warning');
    showSettings.value = true;
    return;
  }
  isConversationActive.value = true;
  chatHistory.value.push({ role: 'system', text: '对话已开始。' });
  if (!ws.value || ws.value.readyState !== WebSocket.OPEN) initWebSocket();
  startCamera();
  status.value = 'thinking'; // 切换到初始化状态，待 onstart 激活为 listening
  startMic();
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
    <!-- 背景流光特效 -->
    <div class="ambient-glow bg-1"></div>
    <div class="ambient-glow bg-2"></div>
    
    <header class="app-header">
      <div class="logo">
        <span class="pulse-dot"></span>
        <span class="logo-text">AURA VISION</span>
        <span class="logo-badge">MVP</span>
      </div>
      <div class="active-model-display" v-if="activeModelName">
        <span class="dot green"></span> Connected: {{ activeModelName }}
      </div>
    </header>

    <main class="app-main">
      <!-- 左侧：视觉与控制 -->
      <section class="left-column">
        <!-- 摄像头预览 -->
        <div class="camera-section glass-panel" :class="status">
          <!-- 识别指针边角特效 -->
          <div class="corner-pointer top-left" :class="status"></div>
          <div class="corner-pointer top-right" :class="status"></div>
          <div class="corner-pointer bottom-left" :class="status"></div>
          <div class="corner-pointer bottom-right" :class="status"></div>
          
          <div class="scanlines"></div>
          <video ref="videoRef" autoplay playsinline muted class="camera-preview"></video>
          <canvas ref="canvasRef" style="display: none;"></canvas>
          <div v-if="!isCamOn" class="placeholder">
            <div class="placeholder-icon">👁️</div>
            <p>视频画面未开启</p>
          </div>
        </div>

        <!-- 状态指示器 (AI 核心呼吸球) -->
        <div class="status-section glass-panel">
          <div class="status-orb-container">
            <div class="orb-ring ring-1" :class="status"></div>
            <div class="orb-ring ring-2" :class="status"></div>
            <div class="status-orb" :class="status"></div>
            <div class="status-text">{{ status === 'idle' ? 'STANDBY' : status === 'listening' ? 'LISTENING' : status === 'speaking' ? 'HEARING' : status === 'thinking' ? 'THINKING' : 'SPEAKING' }}</div>
          </div>
        </div>
      </section>

      <!-- 右侧：对话流 -->
      <section class="right-column chat-section glass-panel">
        <div class="chat-header">
          <span>对话记录</span>
          <button class="btn-clear-chat" @click="chatHistory = []" v-if="chatHistory.length > 0">清空记录</button>
        </div>
        <div class="chat-list" ref="chatListRef">
          <div v-if="chatHistory.length === 0 && !speechPreviewText" class="chat-empty">
            <p>开启会话并对着摄像头说话，或者在下方打字开始交流</p>
          </div>
          <div v-for="(msg, index) in chatHistory" :key="index" :class="['chat-bubble', msg.role]">
            <div class="bubble-sender">{{ msg.role === 'user' ? 'YOU' : msg.role === 'ai' ? 'AURA' : 'SYSTEM' }}</div>
            <div class="bubble-content">{{ msg.text }}</div>
          </div>
          <!-- 实时语音输入预览 -->
          <div v-if="speechPreviewText" class="chat-bubble user interim-bubble">
            <div class="bubble-sender">🎙️ YOU (正在说话...)</div>
            <div class="bubble-content">
              {{ speechPreviewText }}
              <span class="typing-dots"><span>.</span><span>.</span><span>.</span></span>
            </div>
          </div>
        </div>
        
        <!-- 纯文本输入区域 -->
        <div class="chat-input-area" v-if="isConversationActive">
          <input 
            type="text" 
            v-model="manualInputText" 
            @keyup.enter="sendManualMessage"
            placeholder="在输入框中输入内容..." 
            class="manual-input"
          />
          <button @click="sendManualMessage" class="btn-send">发送</button>
        </div>
      </section>
    </main>

    <!-- 配置面板 -->
    <transition name="slide-fade">
      <div v-if="showSettings" class="settings-panel glass-panel">
        <div class="settings-header">
          <h3>配置大模型 API</h3>
          <button class="btn-close-settings" @click="showSettings = false">×</button>
        </div>
        
        <div class="settings-body">
          <div class="input-group">
            <label>选择厂商</label>
            <select v-model="selectedVendor" @change="handleVendorChange" class="preset-select">
              <option v-for="item in vendors" :key="item.id" :value="item.id">
                {{ item.name }}
              </option>
            </select>
          </div>
          
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
              模型名称
              <span v-if="modelError" class="error-msg">{{ modelError }}</span>
            </label>
            <select v-model="selectedModelOption" @change="handleModelOptionChange" class="preset-select">
              <option v-for="m in vendorModels[selectedVendor]" :key="m.value" :value="m.value">
                {{ m.label }}
              </option>
              <option value="custom">✍️ 手动输入自定义名字...</option>
            </select>
            
            <input 
              v-if="selectedModelOption === 'custom' || selectedVendor === 'custom'" 
              v-model="customModelName" 
              @input="handleCustomModelNameInput"
              type="text" 
              :class="{ 'input-error': modelError }" 
              placeholder="请输入自定义模型名称 / 接入点ID..." 
              style="margin-top: 8px;"
            />
            <span v-if="selectedVendor === 'volcengine'" class="input-hint" style="margin-top: 4px; display: block;">
              提示: 火山引擎官方直接调用时需填入 ep- 开头的端点 ID (选择手动输入名字进行填写)
            </span>
          </div>
          
          <p class="hint">请确保所使用的模型支持多模态图像/视觉识别功能</p>
        </div>
        
        <div class="panel-actions">
          <button class="btn-clear" @click="clearConfig">重置</button>
          <button class="btn-confirm" @click="validateConfig">确定并验证</button>
        </div>
      </div>
    </transition>

    <!-- 悬浮设置按钮 -->
    <button class="settings-toggle glass-panel fab" @click="showSettings = !showSettings" v-if="!showSettings">
      ⚙️
    </button>

    <!-- 底部控制栏 -->
    <div class="control-bar glass-panel">
      <button v-if="!isConversationActive" @click="startConversation" class="btn-start">
        开始对话
      </button>
      <button v-else @click="stopConversation" class="btn-stop">
        结束对话
      </button>
    </div>

    <!-- Toast 通知 -->
    <transition name="toast-slide">
      <div v-if="toast.visible" :class="['toast-notification', toast.type]" @click="toast.visible = false">
        <div class="toast-glow"></div>
        <div class="toast-content">
          <span class="toast-icon" v-if="toast.type === 'success'">✓</span>
          <span class="toast-icon" v-else-if="toast.type === 'error'">✕</span>
          <span class="toast-icon" v-else>⚡</span>
          <span class="toast-message">{{ toast.message }}</span>
        </div>
        <div class="toast-progress" :style="{ animationDuration: toast.duration + 'ms' }"></div>
      </div>
    </transition>
  </div>
</template>

<style scoped>
@import url('https://fonts.googleapis.com/css2?family=Space+Grotesk:wght@400;500;600;700&family=Outfit:wght@300;400;500;600;700&display=swap');

.app-container {
  display: flex;
  flex-direction: column;
  width: 100vw;
  height: 100vh;
  padding: 24px;
  box-sizing: border-box;
  background: #080c10;
  color: #f3f4f6;
  font-family: 'Outfit', 'Space Grotesk', system-ui, sans-serif;
  overflow: hidden;
  position: relative;
}

/* 渐变氛围背景 */
.ambient-glow {
  position: absolute;
  width: 600px;
  height: 600px;
  border-radius: 50%;
  filter: blur(120px);
  opacity: 0.15;
  z-index: 1;
  pointer-events: none;
}
.bg-1 {
  background: radial-gradient(circle, #00d2ff 0%, rgba(0,0,0,0) 70%);
  top: -100px;
  right: -100px;
}
.bg-2 {
  background: radial-gradient(circle, #7928ca 0%, rgba(0,0,0,0) 70%);
  bottom: -100px;
  left: -100px;
}

/* 玻璃面板基础规范 */
.glass-panel {
  background: rgba(13, 20, 30, 0.45);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border: 1px solid rgba(255, 255, 255, 0.06);
  border-radius: 20px;
  box-shadow: 0 8px 32px 0 rgba(0, 0, 0, 0.37);
  z-index: 2;
  transition: all 0.3s cubic-bezier(0.25, 0.8, 0.25, 1);
}
.glass-panel:hover {
  border-color: rgba(255, 255, 255, 0.1);
  box-shadow: 0 12px 40px 0 rgba(0, 0, 0, 0.5);
}

/* 识别指针边角特效 */
.camera-section {
  position: relative;
  transition: border-color 0.5s ease, box-shadow 0.5s ease;
}
.camera-section.listening {
  border-color: rgba(0, 210, 255, 0.3) !important;
  box-shadow: 0 0 20px rgba(0, 210, 255, 0.1);
}
.camera-section.thinking {
  border-color: rgba(245, 158, 11, 0.3) !important;
  box-shadow: 0 0 20px rgba(245, 158, 11, 0.1);
}
.camera-section.expressing {
  border-color: rgba(16, 185, 129, 0.3) !important;
  box-shadow: 0 0 20px rgba(16, 185, 129, 0.1);
}

.corner-pointer {
  position: absolute;
  width: 24px;
  height: 24px;
  border: 3px solid transparent;
  z-index: 10;
  transition: all 0.5s ease;
  pointer-events: none;
}
.corner-pointer.idle {
  border-color: rgba(255, 255, 255, 0.15);
}
.corner-pointer.listening {
  border-color: #00d2ff;
  filter: drop-shadow(0 0 6px #00d2ff);
  animation: pointer-breathe-cyan 1.5s infinite alternate ease-in-out;
}
.corner-pointer.thinking {
  border-color: #f59e0b;
  filter: drop-shadow(0 0 6px #f59e0b);
  animation: pointer-breathe-gold 1.5s infinite alternate ease-in-out;
}
.corner-pointer.expressing {
  border-color: #10b981;
  filter: drop-shadow(0 0 6px #10b981);
  animation: pointer-breathe-green 1.5s infinite alternate ease-in-out;
}
.corner-pointer.speaking {
  border-color: #3b82f6;
  filter: drop-shadow(0 0 6px #3b82f6);
  animation: pointer-pulse 0.4s infinite alternate ease-in-out;
}

.top-left {
  top: 15px;
  left: 15px;
  border-right: none;
  border-bottom: none;
}
.top-right {
  top: 15px;
  right: 15px;
  border-left: none;
  border-bottom: none;
}
.bottom-left {
  bottom: 15px;
  left: 15px;
  border-right: none;
  border-top: none;
}
.bottom-right {
  bottom: 15px;
  right: 15px;
  border-left: none;
  border-top: none;
}

@keyframes pointer-breathe-cyan {
  0% { transform: scale(1); border-color: rgba(0, 210, 255, 0.6); }
  100% { transform: scale(1.08); border-color: rgba(0, 210, 255, 1); }
}
@keyframes pointer-breathe-gold {
  0% { transform: scale(1); border-color: rgba(245, 158, 11, 0.6); }
  100% { transform: scale(1.08); border-color: rgba(245, 158, 11, 1); }
}
@keyframes pointer-breathe-green {
  0% { transform: scale(1); border-color: rgba(16, 185, 129, 0.6); }
  100% { transform: scale(1.08); border-color: rgba(16, 185, 129, 1); }
}
@keyframes pointer-pulse {
  0% { transform: scale(0.95); opacity: 0.7; }
  100% { transform: scale(1.1); opacity: 1; }
}

/* 头部 Header */
.app-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
  z-index: 2;
}
.logo {
  display: flex;
  align-items: center;
  gap: 10px;
}
.pulse-dot {
  width: 10px;
  height: 10px;
  background: #00d2ff;
  border-radius: 50%;
  box-shadow: 0 0 10px #00d2ff;
  animation: pulse-glow 2s infinite;
}
@keyframes pulse-glow {
  0%, 100% { transform: scale(1); opacity: 0.6; }
  50% { transform: scale(1.3); opacity: 1; }
}
.logo-text {
  font-family: 'Space Grotesk', sans-serif;
  font-size: 1.4rem;
  font-weight: 700;
  letter-spacing: 2px;
  background: linear-gradient(90deg, #00d2ff, #7928ca);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
}
.logo-badge {
  font-size: 0.7rem;
  background: rgba(0, 210, 255, 0.1);
  border: 1px solid rgba(0, 210, 255, 0.3);
  color: #00d2ff;
  padding: 2px 6px;
  border-radius: 6px;
  font-weight: 600;
  letter-spacing: 0.5px;
}

.active-model-display {
  font-size: 0.85rem;
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid rgba(255, 255, 255, 0.05);
  padding: 6px 14px;
  border-radius: 30px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.dot.green {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #10b981;
  box-shadow: 0 0 10px #10b981;
}

/* 主容器 */
.app-main {
  display: flex;
  flex: 1;
  gap: 20px;
  height: calc(100vh - 160px);
  z-index: 2;
}

.left-column {
  display: flex;
  flex-direction: column;
  flex: 1.2;
  gap: 20px;
}

/* 摄像头预览区 */
.camera-section {
  flex: 1.8;
  position: relative;
  overflow: hidden;
  display: flex;
  justify-content: center;
  align-items: center;
  border: 1px solid rgba(255,255,255,0.05);
}
.camera-preview {
  width: 100%;
  height: 100%;
  object-fit: cover;
  border-radius: 20px;
}
.scanlines {
  position: absolute;
  top: 0; left: 0; width: 100%; height: 100%;
  background: linear-gradient(
    rgba(18, 16, 16, 0) 50%, 
    rgba(0, 0, 0, 0.25) 50%
  );
  background-size: 100% 4px;
  z-index: 3;
  pointer-events: none;
}
.placeholder {
  position: absolute;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 15px;
  color: rgba(255, 255, 255, 0.35);
  text-align: center;
}
.placeholder-icon {
  font-size: 2.8rem;
  opacity: 0.6;
}

/* 状态 Orb 指示器区 */
.status-section {
  flex: 1;
  display: flex;
  justify-content: center;
  align-items: center;
  position: relative;
  background: rgba(13, 20, 30, 0.25);
}
.status-orb-container {
  display: flex;
  flex-direction: column;
  align-items: center;
  position: relative;
}
.status-orb {
  width: 60px;
  height: 60px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.1);
  transition: all 0.5s cubic-bezier(0.4, 0, 0.2, 1);
  box-shadow: 0 0 20px rgba(255,255,255,0.05);
}
.orb-ring {
  position: absolute;
  width: 60px;
  height: 60px;
  border-radius: 50%;
  border: 1px solid transparent;
  transition: all 0.5s ease;
  pointer-events: none;
}

/* 状态样式 */
.status-orb.idle {
  background: rgba(255,255,255,0.06);
}
.status-orb.listening {
  background: #00d2ff;
  box-shadow: 0 0 30px #00d2ff;
  animation: orb-breathe 2.5s infinite ease-in-out;
}
.ring-1.listening {
  border-color: rgba(0, 210, 255, 0.4);
  transform: scale(1.3);
  animation: ring-pulse 2.5s infinite ease-in-out;
}
.ring-2.listening {
  border-color: rgba(0, 210, 255, 0.2);
  transform: scale(1.6);
  animation: ring-pulse 2.5s infinite ease-in-out 0.6s;
}

.status-orb.speaking {
  background: #3b82f6;
  box-shadow: 0 0 35px #3b82f6;
  animation: orb-talk 0.6s infinite alternate ease-in-out;
}
.ring-1.speaking {
  border-color: rgba(59, 130, 246, 0.5);
  animation: ring-ripple 1.2s infinite linear;
}

.status-orb.thinking {
  background: #f59e0b;
  box-shadow: 0 0 30px #f59e0b;
  animation: orb-think 2s infinite linear;
}
.ring-1.thinking {
  border-color: rgba(245, 158, 11, 0.4);
  border-top-color: transparent;
  border-bottom-color: transparent;
  transform: scale(1.2) rotate(360deg);
  animation: spin 3s infinite linear;
}

.status-orb.expressing {
  background: #10b981;
  box-shadow: 0 0 40px #10b981;
  animation: orb-express 1s infinite alternate ease-in-out;
}
.ring-1.expressing {
  border-color: rgba(16, 185, 129, 0.4);
  transform: scale(1.4);
  animation: ring-pulse 1s infinite alternate ease-in-out;
}

.status-text {
  margin-top: 20px;
  font-family: 'Space Grotesk', sans-serif;
  font-size: 0.85rem;
  letter-spacing: 3px;
  font-weight: 600;
  opacity: 0.7;
}

/* 动效 */
@keyframes orb-breathe {
  0%, 100% { transform: scale(1); opacity: 0.85; }
  50% { transform: scale(1.1); opacity: 1; }
}
@keyframes ring-pulse {
  0%, 100% { transform: scale(1.2); opacity: 0.8; }
  50% { transform: scale(1.5); opacity: 0.2; }
}
@keyframes ring-ripple {
  0% { transform: scale(1); opacity: 1; }
  100% { transform: scale(1.8); opacity: 0; }
}
@keyframes orb-talk {
  0% { transform: scale(1); }
  100% { transform: scale(1.2); border-radius: 40%; }
}
@keyframes orb-think {
  0%, 100% { transform: scale(1); opacity: 0.8; }
  50% { transform: scale(0.9); opacity: 1; }
}
@keyframes spin {
  0% { transform: scale(1.2) rotate(0deg); }
  100% { transform: scale(1.2) rotate(360deg); }
}
@keyframes orb-express {
  0% { transform: scale(1); filter: hue-rotate(0deg); }
  100% { transform: scale(1.15); filter: hue-rotate(45deg); }
}

/* 右侧对话流 */
.right-column {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 320px;
  border: 1px solid rgba(255,255,255,0.05);
}
.chat-header {
  padding: 16px 20px;
  font-weight: 600;
  border-bottom: 1px solid rgba(255,255,255,0.06);
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.btn-clear-chat {
  background: transparent;
  border: 1px solid rgba(255, 255, 255, 0.1);
  color: rgba(255, 255, 255, 0.5);
  font-size: 0.75rem;
  padding: 4px 10px;
  border-radius: 12px;
  cursor: pointer;
  transition: all 0.3s;
}
.btn-clear-chat:hover {
  border-color: #ff4d4f;
  color: #ff4d4f;
}

.chat-list {
  flex: 1;
  padding: 20px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.chat-list::-webkit-scrollbar { width: 5px; }
.chat-list::-webkit-scrollbar-thumb { background: rgba(255,255,255,0.1); border-radius: 10px; }

.chat-empty {
  flex: 1;
  display: flex;
  justify-content: center;
  align-items: center;
  text-align: center;
  color: rgba(255,255,255,0.25);
  font-size: 0.85rem;
  padding: 20px;
}

.chat-bubble {
  max-width: 85%;
  padding: 10px 14px;
  border-radius: 16px;
  font-size: 0.9rem;
  line-height: 1.5;
  animation: slideIn 0.3s cubic-bezier(0.18, 0.89, 0.32, 1.28);
  display: flex;
  flex-direction: column;
  gap: 4px;
}
@keyframes slideIn {
  from { opacity: 0; transform: translateY(15px); }
  to { opacity: 1; transform: translateY(0); }
}
.bubble-sender {
  font-family: 'Space Grotesk', sans-serif;
  font-size: 0.65rem;
  font-weight: 700;
  letter-spacing: 1.5px;
  opacity: 0.5;
}
.chat-bubble.user {
  align-self: flex-end;
  background: linear-gradient(135deg, rgba(0, 210, 255, 0.2) 0%, rgba(59, 130, 246, 0.2) 100%);
  border: 1px solid rgba(0, 210, 255, 0.15);
  border-bottom-right-radius: 4px;
}
.chat-bubble.ai {
  align-self: flex-start;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.06);
  border-bottom-left-radius: 4px;
}

.chat-bubble.interim-bubble {
  opacity: 0.85;
  border-style: dashed;
  border-color: rgba(0, 210, 255, 0.4);
  box-shadow: 0 0 12px rgba(0, 210, 255, 0.1);
  animation: pulse-glow-border 1.5s infinite alternate ease-in-out;
}
@keyframes pulse-glow-border {
  0% { border-color: rgba(0, 210, 255, 0.2); }
  100% { border-color: rgba(0, 210, 255, 0.6); }
}
.typing-dots span {
  animation: blink 1.4s infinite both;
  font-weight: bold;
}
.typing-dots span:nth-child(2) {
  animation-delay: .2s;
}
.typing-dots span:nth-child(3) {
  animation-delay: .4s;
}
@keyframes blink {
  0% { opacity: .2; }
  20% { opacity: 1; }
  100% { opacity: .2; }
}

/* 输入框 */
.chat-input-area {
  padding: 15px;
  display: flex;
  gap: 10px;
  background: rgba(0,0,0,0.15);
  border-top: 1px solid rgba(255,255,255,0.06);
  border-radius: 0 0 20px 20px;
}
.manual-input {
  flex: 1;
  background: rgba(0, 0, 0, 0.2);
  border: 1px solid rgba(255, 255, 255, 0.08);
  color: white;
  padding: 10px 16px;
  border-radius: 24px;
  outline: none;
  font-size: 0.85rem;
  transition: all 0.3s cubic-bezier(0.25, 0.8, 0.25, 1);
}
.manual-input:hover, .preset-select:hover, .input-group input:hover {
  border-color: rgba(255, 255, 255, 0.2);
  background: rgba(0, 0, 0, 0.45);
}
.manual-input:focus, .preset-select:focus, .input-group input:focus {
  border-color: #00d2ff;
  box-shadow: 0 0 12px rgba(0, 210, 255, 0.25);
  background: rgba(0, 0, 0, 0.5);
  transform: scale(1.01);
}
.btn-send {
  padding: 8px 20px;
  border-radius: 24px;
  background: #3b82f6;
  color: white;
  border: none;
  font-weight: 600;
  font-size: 0.85rem;
  cursor: pointer;
  transition: all 0.3s cubic-bezier(0.25, 0.8, 0.25, 1);
}
.btn-send:hover {
  background: #00d2ff;
  box-shadow: 0 0 12px rgba(0, 210, 255, 0.3);
  transform: translateY(-2px) scale(1.03);
}
.btn-send:active {
  transform: translateY(1px) scale(0.97);
}

/* 浮动配置按钮 & 面板 */
.settings-toggle.fab {
  position: absolute;
  top: 20px;
  right: 20px;
  width: 42px;
  height: 42px;
  padding: 0;
  border-radius: 50%;
  display: flex;
  justify-content: center;
  align-items: center;
  font-size: 1.1rem;
  cursor: pointer;
  transition: all 0.4s cubic-bezier(0.34, 1.56, 0.64, 1);
}
.settings-toggle.fab:hover {
  transform: rotate(90deg) scale(1.1);
  box-shadow: 0 0 15px rgba(0, 210, 255, 0.3);
}
.settings-toggle.fab:active {
  transform: scale(0.95);
}

.settings-panel {
  position: absolute;
  top: 80px;
  right: 20px;
  width: 350px;
  z-index: 30;
  padding: 24px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  display: flex;
  flex-direction: column;
  gap: 18px;
}
.settings-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
  padding-bottom: 12px;
}
.settings-header h3 {
  margin: 0;
  font-family: 'Space Grotesk', sans-serif;
  font-size: 1.05rem;
  font-weight: 600;
}
.btn-close-settings {
  background: transparent;
  border: none;
  color: rgba(255,255,255,0.5);
  font-size: 1.5rem;
  cursor: pointer;
  line-height: 1;
  transition: all 0.3s ease;
}
.btn-close-settings:hover {
  color: white;
  transform: scale(1.2) rotate(90deg);
}

.settings-body {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.input-group label {
  font-size: 0.8rem;
  color: rgba(255, 255, 255, 0.7);
  margin-bottom: 6px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.input-hint {
  font-size: 0.65rem;
  color: rgba(255, 255, 255, 0.4);
  font-weight: normal;
}
.error-msg {
  color: #ef4444;
  font-weight: 600;
  font-size: 0.75rem;
}
.input-group input, .preset-select {
  background: rgba(0, 0, 0, 0.35);
  border: 1px solid rgba(255, 255, 255, 0.08);
  color: white;
  padding: 8px 12px;
  border-radius: 8px;
  outline: none;
  font-size: 0.8rem;
  transition: all 0.3s cubic-bezier(0.25, 0.8, 0.25, 1);
  width: 100%;
  box-sizing: border-box;
}
.preset-select {
  cursor: pointer;
}
.preset-select option {
  background: #0d141e;
  color: white;
}
.input-group input:focus, .preset-select:focus {
  border-color: #00d2ff;
}
.input-group input.input-error {
  border-color: #ef4444;
  background: rgba(239, 68, 68, 0.05);
}
.hint {
  font-size: 0.7rem;
  color: rgba(255, 255, 255, 0.4);
  margin: 0;
  line-height: 1.4;
}

.panel-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 10px;
}
.btn-clear {
  padding: 6px 14px;
  font-size: 0.8rem;
  background: transparent;
  border: 1px solid rgba(255, 255, 255, 0.15);
  border-radius: 8px;
  color: rgba(255,255,255,0.7);
  cursor: pointer;
  transition: all 0.3s cubic-bezier(0.25, 0.8, 0.25, 1);
}
.btn-clear:hover {
  background: rgba(255,255,255,0.05);
  color: white;
  transform: translateY(-2px);
}
.btn-clear:active {
  transform: translateY(1px);
}
.btn-confirm {
  padding: 6px 18px;
  font-size: 0.8rem;
  background: linear-gradient(135deg, #00d2ff 0%, #3b82f6 100%);
  color: white;
  border: none;
  border-radius: 8px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.3s cubic-bezier(0.25, 0.8, 0.25, 1);
}
.btn-confirm:hover {
  box-shadow: 0 0 12px rgba(0, 210, 255, 0.3);
  transform: translateY(-2px);
}
.btn-confirm:active {
  transform: translateY(1px);
}

/* 控制栏 */
.control-bar {
  display: flex;
  justify-content: center;
  align-items: center;
  padding: 12px 30px;
  margin-top: 20px;
  z-index: 2;
  align-self: center;
  border: 1px solid rgba(255,255,255,0.04);
}
.control-bar button {
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.1);
  color: white;
  padding: 10px 32px;
  border-radius: 30px;
  font-size: 0.95rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.3s cubic-bezier(0.25, 0.8, 0.25, 1);
  letter-spacing: 1px;
}
.btn-start {
  background: linear-gradient(135deg, rgba(16, 185, 129, 0.15) 0%, rgba(5, 150, 105, 0.15) 100%) !important;
  border-color: rgba(16, 185, 129, 0.45) !important;
  color: #10b981 !important;
}
.btn-start:hover {
  background: linear-gradient(135deg, rgba(16, 185, 129, 0.35) 0%, rgba(5, 150, 105, 0.35) 100%) !important;
  box-shadow: 0 0 15px rgba(16, 185, 129, 0.3);
  transform: translateY(-2px) scale(1.02);
}
.btn-start:active {
  transform: translateY(1px) scale(0.98);
}
.btn-stop {
  background: linear-gradient(135deg, rgba(239, 68, 68, 0.15) 0%, rgba(220, 38, 38, 0.15) 100%) !important;
  border-color: rgba(239, 68, 68, 0.45) !important;
  color: #ef4444 !important;
}
.btn-stop:hover {
  background: linear-gradient(135deg, rgba(239, 68, 68, 0.35) 0%, rgba(220, 38, 38, 0.35) 100%) !important;
  box-shadow: 0 0 15px rgba(239, 68, 68, 0.3);
  transform: translateY(-1px);
}

/* 动效过渡 */
.slide-fade-enter-active, .slide-fade-leave-active {
  transition: all 0.3s ease-out;
}
.slide-fade-enter-from, .slide-fade-leave-to {
  transform: translateY(-20px);
  opacity: 0;
}

/* Toast 通知 */
.toast-notification {
  position: fixed;
  top: 32px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 999;
  min-width: 300px;
  max-width: 480px;
  padding: 16px 24px 16px 20px;
  border-radius: 16px;
  backdrop-filter: blur(24px);
  -webkit-backdrop-filter: blur(24px);
  cursor: pointer;
  overflow: hidden;
  font-family: 'Outfit', 'Space Grotesk', sans-serif;
  animation: toast-entrance 0.5s cubic-bezier(0.34, 1.56, 0.64, 1);
}
.toast-notification.success {
  background: linear-gradient(135deg, rgba(16, 185, 129, 0.2) 0%, rgba(5, 150, 105, 0.12) 100%);
  border: 1px solid rgba(16, 185, 129, 0.35);
  box-shadow: 0 8px 32px rgba(16, 185, 129, 0.15), 0 0 60px rgba(16, 185, 129, 0.08), inset 0 1px 0 rgba(255, 255, 255, 0.05);
}
.toast-notification.error {
  background: linear-gradient(135deg, rgba(239, 68, 68, 0.2) 0%, rgba(220, 38, 38, 0.12) 100%);
  border: 1px solid rgba(239, 68, 68, 0.35);
  box-shadow: 0 8px 32px rgba(239, 68, 68, 0.15), 0 0 60px rgba(239, 68, 68, 0.08), inset 0 1px 0 rgba(255, 255, 255, 0.05);
}
.toast-notification.warning {
  background: linear-gradient(135deg, rgba(245, 158, 11, 0.2) 0%, rgba(217, 119, 6, 0.12) 100%);
  border: 1px solid rgba(245, 158, 11, 0.35);
  box-shadow: 0 8px 32px rgba(245, 158, 11, 0.15), 0 0 60px rgba(245, 158, 11, 0.08), inset 0 1px 0 rgba(255, 255, 255, 0.05);
}

.toast-glow {
  position: absolute;
  top: -50%;
  left: -20%;
  width: 140%;
  height: 200%;
  opacity: 0.06;
  pointer-events: none;
}
.toast-notification.success .toast-glow {
  background: radial-gradient(ellipse, #10b981 0%, transparent 70%);
}
.toast-notification.error .toast-glow {
  background: radial-gradient(ellipse, #ef4444 0%, transparent 70%);
}
.toast-notification.warning .toast-glow {
  background: radial-gradient(ellipse, #f59e0b 0%, transparent 70%);
}

.toast-content {
  display: flex;
  align-items: center;
  gap: 12px;
  position: relative;
  z-index: 1;
}
.toast-icon {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 0.85rem;
  font-weight: 700;
  flex-shrink: 0;
}
.toast-notification.success .toast-icon {
  background: rgba(16, 185, 129, 0.25);
  color: #34d399;
  box-shadow: 0 0 12px rgba(16, 185, 129, 0.3);
}
.toast-notification.error .toast-icon {
  background: rgba(239, 68, 68, 0.25);
  color: #f87171;
  box-shadow: 0 0 12px rgba(239, 68, 68, 0.3);
}
.toast-notification.warning .toast-icon {
  background: rgba(245, 158, 11, 0.25);
  color: #fbbf24;
  box-shadow: 0 0 12px rgba(245, 158, 11, 0.3);
}
.toast-message {
  font-size: 0.88rem;
  font-weight: 500;
  color: rgba(255, 255, 255, 0.92);
  letter-spacing: 0.3px;
  line-height: 1.4;
}

.toast-progress {
  position: absolute;
  bottom: 0;
  left: 0;
  height: 3px;
  border-radius: 0 0 16px 16px;
  animation: toast-progress-shrink linear forwards;
}
.toast-notification.success .toast-progress {
  background: linear-gradient(90deg, #10b981, #34d399);
}
.toast-notification.error .toast-progress {
  background: linear-gradient(90deg, #ef4444, #f87171);
}
.toast-notification.warning .toast-progress {
  background: linear-gradient(90deg, #f59e0b, #fbbf24);
}

@keyframes toast-entrance {
  0% {
    opacity: 0;
    transform: translateX(-50%) translateY(-30px) scale(0.9);
    filter: blur(4px);
  }
  100% {
    opacity: 1;
    transform: translateX(-50%) translateY(0) scale(1);
    filter: blur(0);
  }
}
@keyframes toast-progress-shrink {
  0% { width: 100%; }
  100% { width: 0%; }
}
.toast-slide-enter-active {
  transition: all 0.5s cubic-bezier(0.34, 1.56, 0.64, 1);
}
.toast-slide-leave-active {
  transition: all 0.35s cubic-bezier(0.55, 0, 1, 0.45);
}
.toast-slide-enter-from {
  opacity: 0;
  transform: translateX(-50%) translateY(-30px) scale(0.9);
  filter: blur(4px);
}
.toast-slide-leave-to {
  opacity: 0;
  transform: translateX(-50%) translateY(-20px) scale(0.95);
  filter: blur(4px);
}
</style>

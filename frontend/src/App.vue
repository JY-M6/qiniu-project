<script setup>
import { ref, onMounted, onUnmounted, nextTick, watch } from 'vue';
import { marked } from 'marked';

// 配置 marked 解析选项以优化段落回车与解析安全性
marked.setOptions({
  breaks: true,
  gfm: true
});

const renderMarkdown = (text) => {
  if (!text) return '';
  try {
    return marked.parse(text);
  } catch (e) {
    console.error('Markdown 渲染失败:', e);
    return text;
  }
};

const videoRef = ref(null);
const canvasRef = ref(null);
const chatListRef = ref(null);
const fileInputRef = ref(null);
const cropCanvasRef = ref(null);

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

// 模型配置
const apiKey = ref(localStorage.getItem('aura_api_key') || '');
const baseUrl = ref(localStorage.getItem('aura_base_url') || 'https://dashscope.aliyuncs.com/compatible-mode/v1');
const modelName = ref(localStorage.getItem('aura_model_name') || 'qwen-vl-max');
const showSettings = ref(true);
const activeModelName = ref(localStorage.getItem('aura_model_name') || '');

// 个性化与系统设置
const activeTheme = ref(localStorage.getItem('aura_theme') || 'dark');
const fontSizeBase = ref(Number(localStorage.getItem('aura_font_size')) || 14);
const speechLang = ref(localStorage.getItem('aura_speech_lang') || 'zh-CN');
const ttsEnabled = ref(localStorage.getItem('aura_tts_enabled') === 'true');
const videoResolution = ref(localStorage.getItem('aura_video_resolution') || 'standard');
const bgGlowOpacity = ref(localStorage.getItem('aura_bg_glow_opacity') !== null ? Number(localStorage.getItem('aura_bg_glow_opacity')) : 15);
const typewriterSpeed = ref(Number(localStorage.getItem('aura_typewriter_speed')) || 30);
const activeSettingsTab = ref('api'); // 'api' | 'personal'

// 补充交互与功能状态
const activeSessionId = ref(null);
const historySessions = ref(JSON.parse(localStorage.getItem('aura_history_sessions') || '[]'));

const uploadedImageSrc = ref(null); // 上传的原图 base64
const croppedImageSrc = ref(null);  // 裁剪后的图 base64 (若未裁剪则为原图)
const hasCrop = ref(false);
const cropRect = ref({ x: 0, y: 0, w: 0, h: 0 });

// 弹窗控制
const showCropModal = ref(false);
const showTranslateModal = ref(false);
const showConfirmClearModal = ref(false);

// 裁剪坐标与变量
const isDrawingCrop = ref(false);
const cropStartX = ref(0);
const cropStartY = ref(0);
const cropCurrentX = ref(0);
const cropCurrentY = ref(0);
const cropSelection = ref(null);

// 翻译模态框数据
const translateOriginalText = ref('');
const translateResultText = ref('');
const isTranslating = ref(false);

// 单次语音输入状态
const isRecordingInput = ref(false);
let inputSpeechRecognition = null;

// AI 思考与生成状态
const speechPreviewText = ref('');
const isAiThinking = ref(false);
const streamingText = ref('');
const isStreaming = ref(false);
let streamTimer = null;
const activeMessageCategory = ref('画面解读');

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

// 保存配置到 localStorage 的 Watch 监听
watch(apiKey, (val) => localStorage.setItem('aura_api_key', val));
watch(baseUrl, (val) => localStorage.setItem('aura_base_url', val));
watch(modelName, (val) => localStorage.setItem('aura_model_name', val));
watch(activeTheme, (val) => localStorage.setItem('aura_theme', val));
watch(fontSizeBase, (val) => localStorage.setItem('aura_font_size', val));
watch(speechLang, (val) => localStorage.setItem('aura_speech_lang', val));
watch(ttsEnabled, (val) => {
  localStorage.setItem('aura_tts_enabled', val);
  if (!val) stopSpeaking();
});
watch(videoResolution, (val) => localStorage.setItem('aura_video_resolution', val));
watch(bgGlowOpacity, (val) => localStorage.setItem('aura_bg_glow_opacity', val));
watch(typewriterSpeed, (val) => localStorage.setItem('aura_typewriter_speed', val));

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

// --- 打字机效果 ---
const startTypewriter = (text) => {
  isStreaming.value = true;
  streamingText.value = '';
  const chars = text.split('');
  let index = 0;
  
  if (streamTimer) clearInterval(streamTimer);
  
  streamTimer = setInterval(() => {
    if (index < chars.length) {
      streamingText.value += chars[index];
      index++;
      scrollToBottom();
    } else {
      clearInterval(streamTimer);
      streamTimer = null;
      isStreaming.value = false;
      
      // 完成后推入对话历史，保存分类信息
      chatHistory.value.push({ 
        role: 'ai', 
        text: text, 
        category: activeMessageCategory.value 
      });
      
      saveCurrentSession();
      scrollToBottom();
      
      // If conversation is active, status returns to listening; else idle
      setTimeout(() => {
        if (isConversationActive.value) {
          status.value = 'listening';
          startMic();
        } else {
          status.value = 'idle';
        }
      }, 1000);
    }
  }, typewriterSpeed.value);
};

// --- AI TTS 朗读功能 ---
const speakText = (text) => {
  if (!ttsEnabled.value) return;
  if (!window.speechSynthesis) {
    console.warn('浏览器不支持 Web Speech API');
    return;
  }
  
  // 过滤掉 markdown 或其他格式标记
  let cleanText = text
    .replace(/#+\s+/g, '') // remove headings
    .replace(/\*\*([^*]+)\*\*/g, '$1') // remove bold
    .replace(/\*([^*]+)\*/g, '$1') // remove italic
    .replace(/`([^`]+)`/g, '$1') // remove inline code
    .replace(/```[\s\S]*?```/g, '') // remove code blocks
    .replace(/[-*+]\s+/g, '') // remove list markers
    .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1') // remove links
    .replace(/\n+/g, ' '); // replace newlines with space
    
  if (!cleanText.trim()) return;

  // 取消当前播放
  window.speechSynthesis.cancel();

  const utterance = new SpeechSynthesisUtterance(cleanText);
  const hasChinese = /[\u4e00-\u9fa5]/.test(cleanText);
  utterance.lang = hasChinese ? 'zh-CN' : 'en-US';
  
  window.speechSynthesis.speak(utterance);
};

const stopSpeaking = () => {
  if (window.speechSynthesis) {
    try {
      // 1. 先暂停当前播放，减少缓冲突变产生的电流声爆音
      window.speechSynthesis.pause();
      // 2. 清空待播放队列
      window.speechSynthesis.cancel();
      
      // 3. 产生一个极短时间的静音片段并播放，强行重置音频输出上下文缓冲
      const silenceUtterance = new SpeechSynthesisUtterance('');
      silenceUtterance.volume = 0;
      window.speechSynthesis.speak(silenceUtterance);
      setTimeout(() => {
        window.speechSynthesis.cancel();
      }, 50);
    } catch (e) {
      console.error('停止语音朗读错误:', e);
    }
  }
};

// 初始化 WebSocket
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
      showToast('验证成功！模型已连接', 'success');
      activeModelName.value = modelName.value;
      showSettings.value = false;
    } else if (data.status === 'validation_error') {
      const [type, msg] = data.text.split(':');
      if (type === 'API_INVALID') apiError.value = msg;
      else if (type === 'URL_INVALID') urlError.value = msg;
      else if (type === 'MODEL_INVALID') modelError.value = msg;
      else showToast(data.text, 'error');
    }
    else if (data.status === 'processing') {
      stopMic();
      status.value = 'thinking';
      isAiThinking.value = true;
      scrollToBottom();
    } else if (data.status === 'completed') {
      isAiThinking.value = false;
      status.value = 'expressing';
      // 使用打字机效果显示回复
      startTypewriter(data.text);
      scrollToBottom();
      
      // 触发 AI 朗读
      if (ttsEnabled.value) {
        speakText(data.text);
      }
    } else if (data.status === 'translate_success') {
      translateResultText.value = data.text;
      isTranslating.value = false;
    } else if (data.status === 'error') {
      isAiThinking.value = false;
      chatHistory.value.push({ role: 'error-card', text: '该内容无法识别，请更换图片或问题' });
      saveCurrentSession();
      status.value = 'idle';
      scrollToBottom();
    }
  };

  ws.value.onclose = () => {
    console.log('WebSocket Disconnected');
    if (isConversationActive.value) {
      chatHistory.value.push({ role: 'error-card', text: '该内容无法识别，请更换图片或问题' });
      stopConversation();
    }
  };

  ws.value.onerror = (err) => {
    console.error('WebSocket Error:', err);
  };
};

// 发送防重缓存
let lastSentText = '';
let lastSentTime = 0;

// 发送消息
const sendMessage = (text, customCategory = null) => {
  // 防重拦截：2秒内禁止发送完全一样的消息，防止麦克风逻辑并发调用 sendMessage 产生两条重复消息
  const now = Date.now();
  if (text === lastSentText && (now - lastSentTime < 2000)) {
    console.warn('已拦截重复发送的内容:', text);
    return;
  }
  
  if (ws.value && ws.value.readyState === WebSocket.OPEN) {
    lastSentText = text;
    lastSentTime = now;
    let category = customCategory;
    if (!category) {
      const lower = text.toLowerCase();
      if (lower.includes('ocr') || lower.includes('文字') || lower.includes('提取') || lower.includes('写')) {
        category = 'OCR 文字';
      } else if (lower.includes('科普') || lower.includes('介绍') || lower.includes('什么花') || lower.includes('这是什么') || lower.includes('识图')) {
        category = '物品科普';
      } else if (lower.includes('短句') || lower.includes('文案') || lower.includes('诗') || lower.includes('点评')) {
        category = '趣味短句';
      } else {
        category = '画面解读';
      }
    }

    // 图像优先级：框选裁剪区域 > 上传原图 > 摄像头帧
    const currentImg = croppedImageSrc.value || uploadedImageSrc.value || (latestImageBase64 ? 'data:image/jpeg;base64,' + latestImageBase64 : null);
    const currentImgBase64 = currentImg ? (currentImg.includes('base64,') ? currentImg.split('base64,')[1] : currentImg) : null;

    chatHistory.value.push({ 
      role: 'user', 
      text: text, 
      image: currentImg
    });
    
    isAiThinking.value = true;
    status.value = 'thinking';
    scrollToBottom();
    
    activeMessageCategory.value = category;
    
    ws.value.send(JSON.stringify({
      event: 'user_input',
      text: text,
      imageBase64: currentImgBase64,
      apiKey: apiKey.value,
      baseUrl: baseUrl.value,
      modelName: modelName.value
    }));

    saveCurrentSession();
  } else {
    showToast('WebSocket 未连接，请重新配置并激活！', 'error');
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
  apiError.value = '';
  urlError.value = '';
  modelError.value = '';

  if (!apiKey.value) { apiError.value = "API Key 不能为空"; return; }
  if (!baseUrl.value) { urlError.value = "Base URL 不能为空"; return; }
  if (!modelName.value) { modelError.value = "模型名称不能为空"; return; }

  if (!ws.value || ws.value.readyState !== WebSocket.OPEN) {
    initWebSocket();
    setTimeout(validateConfig, 500);
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

// 分辨率画质对照表
const resolutions = {
  smooth: { width: 320, height: 240, quality: 0.6 },
  standard: { width: 640, height: 480, quality: 0.75 },
  hd: { width: 1280, height: 720, quality: 0.85 }
};

// === 摄像头控制（使用 requestAnimationFrame 代替 setInterval 减少卡顿，支持清晰度调节）===
const startCamera = async () => {
  try {
    const resSettings = resolutions[videoResolution.value] || resolutions.standard;
    const constraints = {
      video: {
        width: { ideal: resSettings.width },
        height: { ideal: resSettings.height }
      }
    };
    const stream = await navigator.mediaDevices.getUserMedia(constraints);
    mediaStream = stream;
    if (videoRef.value) videoRef.value.srcObject = stream;
    isCamOn.value = true;
    
    let lastCapture = 0;
    const FRAME_INTERVAL = 1000; // 1秒一帧
    
    const frameLoop = (timestamp) => {
      if (!isCamOn.value) return;
      if (timestamp - lastCapture >= FRAME_INTERVAL) {
        captureFrame();
        lastCapture = timestamp;
      }
      frameInterval = requestAnimationFrame(frameLoop);
    };
    
    frameInterval = requestAnimationFrame(frameLoop);
  } catch (err) {
    console.error('Camera error:', err);
    showToast('无法访问摄像头，请检查权限', 'warning');
  }
};

const stopCamera = () => {
  if (mediaStream) mediaStream.getVideoTracks().forEach(track => track.stop());
  if (frameInterval) cancelAnimationFrame(frameInterval);
  isCamOn.value = false;
};

const captureFrame = () => {
  if (videoRef.value && canvasRef.value && isCamOn.value) {
    const context = canvasRef.value.getContext('2d');
    const resSettings = resolutions[videoResolution.value] || resolutions.standard;
    canvasRef.value.width = resSettings.width;
    canvasRef.value.height = resSettings.height;
    context.drawImage(videoRef.value, 0, 0, resSettings.width, resSettings.height);
    const dataUrl = canvasRef.value.toDataURL('image/jpeg', resSettings.quality);
    latestImageBase64 = dataUrl.split(',')[1];
  }
};

const handleResolutionChange = async () => {
  if (isCamOn.value) {
    stopCamera();
    await startCamera();
    showToast('视频分辨率已更新', 'success', 1500);
  }
};

// === 语音识别（优化版：多候选置信度权重 + 实时无锁同步显示 + 中英文切换）===
const initSpeechRecognition = () => {
  const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
  if (!SpeechRecognition) {
    console.warn('浏览器不支持 Web Speech API');
    return;
  }
  
  speechRecognition = new SpeechRecognition();
  speechRecognition.continuous = true;
  speechRecognition.interimResults = true;
  speechRecognition.lang = speechLang.value;
  speechRecognition.maxAlternatives = 5;
  
  speechRecognition.onstart = () => {
    status.value = 'listening';
    showToast('麦克风已就绪，请开始说话！', 'success', 2000);
  };

  speechRecognition.onresult = (event) => {
    let interimTranscript = '';
    let finalTranscript = '';
    
    requestAnimationFrame(() => {
      for (let i = event.resultIndex; i < event.results.length; ++i) {
        let bestTranscript = '';
        let bestConfidence = -1;
        for (let j = 0; j < event.results[i].length; j++) {
          if (event.results[i][j].confidence >= bestConfidence) {
            bestConfidence = event.results[i][j].confidence;
            bestTranscript = event.results[i][j].transcript;
          }
        }
        if (event.results[i].isFinal) {
          finalTranscript += bestTranscript;
        } else {
          interimTranscript += bestTranscript;
        }
      }
      
      if (interimTranscript) {
        status.value = 'speaking';
        speechPreviewText.value = interimTranscript;
      }
      
      if (finalTranscript.trim()) {
        sendMessage(finalTranscript.trim());
        speechPreviewText.value = '';
        status.value = 'thinking';
      }
    });
  };

  speechRecognition.onerror = (event) => {
    console.error('Speech recognition error:', event.error);
    if (event.error === 'not-allowed') {
      showToast('麦克风权限被拒绝，请在浏览器中允许访问麦克风', 'error');
      isMicOn.value = false;
      status.value = 'idle';
    } else if (event.error === 'no-speech') {
      // 仅无声，不中断
    } else {
      showToast(`语音助手提示: ${event.error}`, 'warning', 2000);
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

const handleLangChange = () => {
  if (speechRecognition) {
    speechRecognition.lang = speechLang.value;
    if (isMicOn.value && isConversationActive.value) {
      stopMic();
      setTimeout(() => {
        startMic();
      }, 300);
    }
  }
};

const startMic = () => {
  if (!speechRecognition) initSpeechRecognition();
  if (speechRecognition) {
    speechRecognition.lang = speechLang.value;
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
  // 如果当前有未转为 final 的临时语音文本，主动将其作为最终结果发送
  if (speechPreviewText.value && speechPreviewText.value.trim()) {
    sendMessage(speechPreviewText.value.trim());
  }
  speechPreviewText.value = '';
};

// 管理会话
const startConversation = () => {
  if (!activeModelName.value) {
    showToast('请先配置大模型！', 'warning');
    showSettings.value = true;
    return;
  }
  isConversationActive.value = true;
  chatHistory.value.push({ role: 'system', text: '对话已开始。' });
  if (!ws.value || ws.value.readyState !== WebSocket.OPEN) initWebSocket();
  startCamera();
  status.value = 'thinking';
  startMic();
  saveCurrentSession();
};

const stopConversation = () => {
  isConversationActive.value = false;
  status.value = 'idle';
  stopCamera();
  stopMic();
  stopSpeaking();
  if (streamTimer) clearInterval(streamTimer);
  isStreaming.value = false;
  streamingText.value = '';
  chatHistory.value.push({ role: 'system', text: '对话已结束。' });
  saveCurrentSession();
};

// 滚动到底部
const scrollToBottom = () => {
  nextTick(() => {
    if (chatListRef.value) {
      chatListRef.value.scrollTop = chatListRef.value.scrollHeight;
    }
  });
};

// --- 会话历史及持久化操作 ---
const createNewSession = () => {
  // 强行关闭与清除前一个会话未完成的异步逻辑
  stopSpeaking();
  if (streamTimer) {
    clearInterval(streamTimer);
    streamTimer = null;
  }
  isStreaming.value = false;
  streamingText.value = '';
  isAiThinking.value = false;

  activeSessionId.value = null;
  chatHistory.value = [];
  uploadedImageSrc.value = null;
  croppedImageSrc.value = null;
  hasCrop.value = false;
  latestImageBase64 = null;
  showToast('已开启全新对话', 'success', 1500);
};

const saveCurrentSession = () => {
  if (chatHistory.value.length === 0) return;
  
  const now = new Date();
  const timeStr = `${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')} ${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`;
  
  const firstUserMsg = chatHistory.value.find(m => m.role === 'user');
  const previewText = firstUserMsg ? firstUserMsg.text : '视觉分析会话';
  
  const thumbnail = croppedImageSrc.value || uploadedImageSrc.value || (latestImageBase64 ? 'data:image/jpeg;base64,' + latestImageBase64 : null);

  if (!activeSessionId.value) {
    activeSessionId.value = 'session_' + Date.now();
  }

  const existingIndex = historySessions.value.findIndex(s => s.id === activeSessionId.value);
  const sessionData = {
    id: activeSessionId.value,
    timestamp: timeStr,
    preview: previewText,
    thumbnail: thumbnail,
    chatHistory: JSON.parse(JSON.stringify(chatHistory.value)),
    uploadedImageSrc: uploadedImageSrc.value,
    croppedImageSrc: croppedImageSrc.value,
    hasCrop: hasCrop.value,
    cropRect: JSON.parse(JSON.stringify(cropRect.value))
  };

  if (existingIndex !== -1) {
    historySessions.value[existingIndex] = sessionData;
  } else {
    historySessions.value.unshift(sessionData);
  }

  localStorage.setItem('aura_history_sessions', JSON.stringify(historySessions.value));
};

const loadSession = (session) => {
  // 强行关闭与清除前一个会话未完成的异步逻辑
  stopSpeaking();
  if (streamTimer) {
    clearInterval(streamTimer);
    streamTimer = null;
  }
  isStreaming.value = false;
  streamingText.value = '';
  isAiThinking.value = false;

  activeSessionId.value = session.id;
  chatHistory.value = JSON.parse(JSON.stringify(session.chatHistory));
  uploadedImageSrc.value = session.uploadedImageSrc;
  croppedImageSrc.value = session.croppedImageSrc;
  hasCrop.value = session.hasCrop;
  cropRect.value = JSON.parse(JSON.stringify(session.cropRect || { x: 0, y: 0, w: 0, h: 0 }));
  
  showToast('会话已加载', 'success', 1500);
  scrollToBottom();
};

const deleteSession = (sessionId, event) => {
  if (event) event.stopPropagation();
  historySessions.value = historySessions.value.filter(s => s.id !== sessionId);
  localStorage.setItem('aura_history_sessions', JSON.stringify(historySessions.value));
  
  if (activeSessionId.value === sessionId) {
    // 若删除的是当前活动会话，强行重置所有流和状态
    stopSpeaking();
    if (streamTimer) {
      clearInterval(streamTimer);
      streamTimer = null;
    }
    isStreaming.value = false;
    streamingText.value = '';
    isAiThinking.value = false;

    activeSessionId.value = null;
    chatHistory.value = [];
    uploadedImageSrc.value = null;
    croppedImageSrc.value = null;
    hasCrop.value = false;
  }
  showToast('会话已删除', 'success', 1500);
};

const clearAllSessions = () => {
  historySessions.value = [];
  localStorage.removeItem('aura_history_sessions');
  activeSessionId.value = null;
  chatHistory.value = [];
  uploadedImageSrc.value = null;
  croppedImageSrc.value = null;
  hasCrop.value = false;
  showConfirmClearModal.value = false;
  showToast('历史记录已清空', 'success', 1500);
};

const clearChat = () => {
  chatHistory.value = [];
  uploadedImageSrc.value = null;
  croppedImageSrc.value = null;
  hasCrop.value = false;
  latestImageBase64 = null;
  saveCurrentSession();
};

// --- 图片上传及 Canvas 预处理 (降噪 & 对比度增强) ---
const triggerFileSelect = () => {
  if (fileInputRef.value) {
    fileInputRef.value.click();
  }
};

const handleFileChange = (e) => {
  const file = e.target.files[0];
  if (file) processUploadFile(file);
};

const handleFileDrop = (e) => {
  const file = e.dataTransfer.files[0];
  if (file) processUploadFile(file);
};

const processUploadFile = (file) => {
  const reader = new FileReader();
  reader.onload = (event) => {
    const img = new Image();
    img.src = event.target.result;
    img.onload = () => {
      applyCanvasPreprocessing(img, (processedBase64) => {
        uploadedImageSrc.value = processedBase64;
        croppedImageSrc.value = processedBase64; 
        hasCrop.value = false;
        showCropModal.value = true;
        saveCurrentSession();
      });
    };
  };
  reader.readAsDataURL(file);
};

const applyCanvasPreprocessing = (img, callback) => {
  const canvas = document.createElement('canvas');
  const ctx = canvas.getContext('2d');
  canvas.width = img.naturalWidth || img.width;
  canvas.height = img.naturalHeight || img.height;
  ctx.drawImage(img, 0, 0);

  const imageData = ctx.getImageData(0, 0, canvas.width, canvas.height);
  const data = imageData.data;

  // 图像增强算法对对比度进行20%拉伸，实现弱化背景噪点，强化OCR文字清晰度
  const contrast = 20;
  const factor = (259 * (contrast + 255)) / (255 * (259 - contrast));
  
  for (let i = 0; i < data.length; i += 4) {
    data[i] = Math.min(255, Math.max(0, factor * (data[i] - 128) + 128));     // R
    data[i+1] = Math.min(255, Math.max(0, factor * (data[i+1] - 128) + 128)); // G
    data[i+2] = Math.min(255, Math.max(0, factor * (data[i+2] - 128) + 128)); // B
  }

  ctx.putImageData(imageData, 0, 0);
  callback(canvas.toDataURL('image/jpeg', 0.9));
};

const clearUploadedImage = () => {
  uploadedImageSrc.value = null;
  croppedImageSrc.value = null;
  hasCrop.value = false;
  latestImageBase64 = null;
  saveCurrentSession();
};

// --- Canvas 框选裁剪器 ---
const initCropCanvas = () => {
  const canvas = cropCanvasRef.value;
  if (!canvas) return;
  const ctx = canvas.getContext('2d');
  
  const img = new Image();
  img.src = uploadedImageSrc.value;
  img.onload = () => {
    const maxW = window.innerWidth * 0.8;
    const maxH = window.innerHeight * 0.6;
    let w = img.naturalWidth || img.width;
    let h = img.naturalHeight || img.height;
    
    const scale = Math.min(maxW / w, maxH / h, 1);
    canvas.width = w * scale;
    canvas.height = h * scale;
    
    drawCropState();
  };
};

const drawCropState = () => {
  const canvas = cropCanvasRef.value;
  if (!canvas) return;
  const ctx = canvas.getContext('2d');
  const img = new Image();
  img.src = uploadedImageSrc.value;
  
  img.onload = () => {
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
    
    let sx = 0, sy = 0, sw = 0, sh = 0;
    if (isDrawingCrop.value) {
      sx = Math.min(cropStartX.value, cropCurrentX.value);
      sy = Math.min(cropStartY.value, cropCurrentY.value);
      sw = Math.abs(cropStartX.value - cropCurrentX.value);
      sh = Math.abs(cropStartY.value - cropCurrentY.value);
    } else if (cropSelection.value) {
      sx = cropSelection.value.x;
      sy = cropSelection.value.y;
      sw = cropSelection.value.w;
      sh = cropSelection.value.h;
    }
    
    if (sw > 0 && sh > 0) {
      ctx.fillStyle = 'rgba(0, 0, 0, 0.6)';
      ctx.fillRect(0, 0, canvas.width, sy);
      ctx.fillRect(0, sy + sh, canvas.width, canvas.height - (sy + sh));
      ctx.fillRect(0, sy, sx, sh);
      ctx.fillRect(sx + sw, sy, canvas.width - (sx + sw), sh);
      
      ctx.strokeStyle = '#2563eb';
      ctx.lineWidth = 2;
      ctx.strokeRect(sx, sy, sw, sh);
      
      ctx.fillStyle = 'rgba(37, 99, 235, 0.15)';
      ctx.fillRect(sx, sy, sw, sh);
      
      ctx.fillStyle = '#2563eb';
      const hs = 6;
      ctx.fillRect(sx - hs/2, sy - hs/2, hs, hs);
      ctx.fillRect(sx + sw - hs/2, sy - hs/2, hs, hs);
      ctx.fillRect(sx - hs/2, sy + sh - hs/2, hs, hs);
      ctx.fillRect(sx + sw - hs/2, sy + sh - hs/2, hs, hs);
    }
  };
};

const getEventOffset = (e) => {
  const canvas = cropCanvasRef.value;
  if (!canvas) return { x: 0, y: 0 };
  const rect = canvas.getBoundingClientRect();
  
  if (e.touches && e.touches.length > 0) {
    return {
      x: e.touches[0].clientX - rect.left,
      y: e.touches[0].clientY - rect.top
    };
  }
  return {
    x: e.clientX - rect.left,
    y: e.clientY - rect.top
  };
};

const onCropStart = (e) => {
  const offset = getEventOffset(e);
  isDrawingCrop.value = true;
  cropStartX.value = offset.x;
  cropStartY.value = offset.y;
  cropCurrentX.value = offset.x;
  cropCurrentY.value = offset.y;
  cropSelection.value = null;
  drawCropState();
};

const onCropMove = (e) => {
  if (!isDrawingCrop.value) return;
  const offset = getEventOffset(e);
  cropCurrentX.value = offset.x;
  cropCurrentY.value = offset.y;
  drawCropState();
};

const onCropEnd = (e) => {
  if (!isDrawingCrop.value) return;
  isDrawingCrop.value = false;
  const offset = getEventOffset(e);
  cropCurrentX.value = offset.x;
  cropCurrentY.value = offset.y;
  
  const sx = Math.min(cropStartX.value, cropCurrentX.value);
  const sy = Math.min(cropStartY.value, cropCurrentY.value);
  const sw = Math.abs(cropStartX.value - cropCurrentX.value);
  const sh = Math.abs(cropStartY.value - cropCurrentY.value);
  
  if (sw > 5 && sh > 5) {
    cropSelection.value = { x: sx, y: sy, w: sw, h: sh };
  } else {
    cropSelection.value = null;
  }
  drawCropState();
};

const onTouchCropStart = (e) => { e.preventDefault(); onCropStart(e); };
const onTouchCropMove = (e) => { e.preventDefault(); onCropMove(e); };
const onTouchCropEnd = (e) => { e.preventDefault(); onCropEnd(e); };

const reselectCrop = () => {
  cropSelection.value = null;
  isDrawingCrop.value = false;
  drawCropState();
};

const cancelCrop = () => {
  croppedImageSrc.value = uploadedImageSrc.value;
  hasCrop.value = false;
  showCropModal.value = false;
  latestImageBase64 = uploadedImageSrc.value.split(',')[1];
  saveCurrentSession();
};

const confirmCrop = () => {
  if (!cropSelection.value) {
    cancelCrop();
    return;
  }
  
  const canvas = cropCanvasRef.value;
  const img = new Image();
  img.src = uploadedImageSrc.value;
  
  img.onload = () => {
    const scaleX = img.naturalWidth / canvas.width;
    const scaleY = img.naturalHeight / canvas.height;
    
    const cropX = cropSelection.value.x * scaleX;
    const cropY = cropSelection.value.y * scaleY;
    const cropW = cropSelection.value.w * scaleX;
    const cropH = cropSelection.value.h * scaleY;
    
    const tempCanvas = document.createElement('canvas');
    tempCanvas.width = cropW;
    tempCanvas.height = cropH;
    const tempCtx = tempCanvas.getContext('2d');
    
    tempCtx.drawImage(img, cropX, cropY, cropW, cropH, 0, 0, cropW, cropH);
    
    const croppedDataUrl = tempCanvas.toDataURL('image/jpeg', 0.9);
    croppedImageSrc.value = croppedDataUrl;
    hasCrop.value = true;
    cropRect.value = { x: cropX, y: cropY, w: cropW, h: cropH };
    
    latestImageBase64 = croppedDataUrl.split(',')[1];
    showCropModal.value = false;
    showToast('局部选区裁剪成功', 'success', 1500);
    saveCurrentSession();
  };
};

watch(showCropModal, (val) => {
  if (val) {
    nextTick(() => {
      initCropCanvas();
    });
  }
});

// --- 气泡工具操作 ---
const copyText = (text) => {
  navigator.clipboard.writeText(text).then(() => {
    showToast('文本已复制到剪贴板', 'success', 1500);
  }).catch(err => {
    showToast('复制失败', 'error', 1500);
  });
};

// --- 打断 AI 回答 ---
const interruptAnswer = () => {
  // 停止播放当前语音
  stopSpeaking();
  // 清除打字机计时器
  if (streamTimer) {
    clearInterval(streamTimer);
    streamTimer = null;
  }

  // 保留已生成的内容：把打字机已显示的文字 push 进历史，再清空流式显示区
  if (streamingText.value.trim()) {
    chatHistory.value.push({
      role: 'ai',
      text: streamingText.value,
      category: activeMessageCategory.value
    });
    saveCurrentSession();
    scrollToBottom();
  }
  isStreaming.value = false;
  streamingText.value = '';

  // 根据不同的状态，显示更精准的 Toast 提示
  if (isAiThinking.value) {
    showToast('已打断 AI 思考', 'warning', 1500);
  } else {
    showToast('已打断 AI 回答，内容已保留', 'warning', 1500);
  }
  isAiThinking.value = false;

  // 如果处于连续对话模式，中断后重新开始监听用户说话
  if (isConversationActive.value) {
    status.value = 'listening';
    startMic();
  } else {
    status.value = 'idle';
  }
};

const triggerTranslate = (text) => {
  translateOriginalText.value = text;
  translateResultText.value = '';
  isTranslating.value = true;
  showTranslateModal.value = true;
  
  if (ws.value && ws.value.readyState === WebSocket.OPEN) {
    ws.value.send(JSON.stringify({
      event: 'translate',
      text: text,
      apiKey: apiKey.value,
      baseUrl: baseUrl.value,
      modelName: modelName.value
    }));
  } else {
    showToast('未连接服务器，无法翻译', 'error', 2000);
    isTranslating.value = false;
  }
};

const swapTranslate = () => {
  const temp = translateOriginalText.value;
  translateOriginalText.value = translateResultText.value || '';
  translateResultText.value = temp;
  
  if (translateOriginalText.value) {
    isTranslating.value = true;
    if (ws.value && ws.value.readyState === WebSocket.OPEN) {
      ws.value.send(JSON.stringify({
        event: 'translate',
        text: translateOriginalText.value,
        apiKey: apiKey.value,
        baseUrl: baseUrl.value,
        modelName: modelName.value
      }));
    }
  }
};

// --- 单次录音语音输入 ---
const toggleInputMic = () => {
  const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
  if (!SpeechRecognition) {
    showToast('浏览器不支持语音识别', 'warning');
    return;
  }
  
  if (isRecordingInput.value) {
    stopInputMic();
  } else {
    if (isMicOn.value) {
      // 先清空主麦临时文字，避免 stopMic() 内部自动 sendMessage() 造成重复发送
      speechPreviewText.value = '';
      stopMic();
    }
    startInputMic();
  }
};

const startInputMic = () => {
  const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
  inputSpeechRecognition = new SpeechRecognition();
  inputSpeechRecognition.continuous = false;
  inputSpeechRecognition.interimResults = true;
  inputSpeechRecognition.lang = speechLang.value;
  
  isRecordingInput.value = true;
  
  inputSpeechRecognition.onstart = () => {
    showToast('录音中，请开始说话...', 'success', 2000);
  };
  
  inputSpeechRecognition.onresult = (event) => {
    let transcript = '';
    for (let i = event.resultIndex; i < event.results.length; ++i) {
      transcript += event.results[i][0].transcript;
    }
    manualInputText.value = transcript;
  };
  
  inputSpeechRecognition.onerror = (e) => {
    console.error('Single mic error:', e.error);
    isRecordingInput.value = false;
  };
  
  inputSpeechRecognition.onend = () => {
    isRecordingInput.value = false;
  };
  
  try {
    inputSpeechRecognition.start();
  } catch(err) {
    isRecordingInput.value = false;
  }
};

const stopInputMic = () => {
  if (inputSpeechRecognition) {
    try {
      inputSpeechRecognition.stop();
    } catch(e) {}
  }
  isRecordingInput.value = false;
};

// 停止录音并立即发送已识别的文字（「说完了」按钮专用）
const stopInputMicAndSend = () => {
  // 先停止录音
  if (inputSpeechRecognition) {
    try { inputSpeechRecognition.stop(); } catch(e) {}
  }
  isRecordingInput.value = false;
  // 稍微延迟一下，等待 onresult 最后一个 interim 写入 manualInputText
  setTimeout(() => {
    const text = manualInputText.value.trim();
    if (text) {
      sendMessage(text);
      manualInputText.value = '';
    } else {
      showToast('未识别到内容，请重试', 'warning', 1500);
    }
  }, 200);
};

const toggleTheme = () => {
  activeTheme.value = activeTheme.value === 'dark' ? 'light' : 'dark';
};

onMounted(() => {
  initWebSocket();
  // Restore vendor configurations if cached
  const savedVendor = localStorage.getItem('aura_vendor');
  if (savedVendor) {
    selectedVendor.value = savedVendor;
    const models = vendorModels[savedVendor] || [];
    const savedModelOption = localStorage.getItem('aura_model_option');
    if (savedModelOption) {
      selectedModelOption.value = savedModelOption;
    }
  }
});

onUnmounted(() => {
  if (ws.value) ws.value.close();
  stopConversation();
  stopSpeaking();
});
</script>


<template>
  <div class="app-layout" :data-theme="activeTheme" :style="{ '--font-size-base': fontSizeBase + 'px' }">
    <!-- 背景流光特效 -->
    <div class="ambient-glow bg-1" :style="{ opacity: bgGlowOpacity / 100 }"></div>
    <div class="ambient-glow bg-2" :style="{ opacity: bgGlowOpacity / 100 }"></div>
    
    <!-- 左侧常驻侧边栏 -->
    <aside class="sidebar">
      <div class="sidebar-header">
        <span class="sidebar-title">历史记录</span>
        <div class="sidebar-actions">
          <button class="btn-new-chat" title="新建对话" @click="createNewSession">➕</button>
          <button class="btn-clear-all" title="清空全部历史会话" @click="showConfirmClearModal = true">🗑️</button>
        </div>
      </div>
      <div class="sidebar-divider"></div>
      <div class="sidebar-content">
        <div v-if="historySessions.length === 0" class="sidebar-empty">
          暂无历史会话
        </div>
        <div v-else class="session-list">
          <div 
            v-for="session in historySessions" 
            :key="session.id" 
            class="session-item"
            :class="{ active: activeSessionId === session.id }"
            @click="loadSession(session)"
          >
            <div class="session-thumb">
              <img v-if="session.thumbnail" :src="session.thumbnail" class="session-img" />
              <div v-else class="session-thumb-placeholder">📄</div>
            </div>
            <div class="session-info">
              <div class="session-preview">{{ session.preview }}</div>
              <div class="session-time">{{ session.timestamp }}</div>
            </div>
            <button class="btn-session-delete" title="删除当前记录" @click="deleteSession(session.id, $event)">🗑️</button>
          </div>
        </div>
      </div>
    </aside>

    <!-- 右侧主工作区 -->
    <div class="main-workspace">
      <!-- 顶部导航区 -->
      <header class="app-header">
        <div class="logo">
          <span class="pulse-dot"></span>
          <span class="logo-text">AURA VISION</span>
          <span class="logo-badge">MVP</span>
        </div>
        


        <div class="header-right">
          <div class="active-model-display" v-if="activeModelName">
            <span class="dot green"></span> {{ activeModelName }}
          </div>
          <!-- 偏好与系统设置按钮 -->
          <button 
            class="theme-square-btn" 
            title="偏好与系统配置" 
            @click="showSettings = !showSettings"
          >
            ⚙️
          </button>
          <!-- 主题方形切换按钮 -->
          <button 
            class="theme-square-btn" 
            :title="activeTheme === 'dark' ? '切换浅色模式' : '切换深色模式'" 
            @click="toggleTheme"
          >
            {{ activeTheme === 'dark' ? '☀️' : '🌙' }}
          </button>
        </div>
      </header>
      
      <main class="app-main">
        <!-- 左侧：视频与呼吸球 -->
        <section class="left-column">
          <div class="camera-section glass-panel" :class="status">
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
          <div class="status-section glass-panel" style="position: relative;">
            <div class="status-orb-container">
              <div class="orb-ring ring-1" :class="status"></div>
              <div class="orb-ring ring-2" :class="status"></div>
              <div class="status-orb" :class="status"></div>
              <div class="status-text">{{ status === 'idle' ? 'STANDBY' : status === 'listening' ? 'LISTENING' : status === 'speaking' ? 'HEARING' : status === 'thinking' ? 'THINKING' : 'SPEAKING' }}</div>
            </div>
            <!-- 主动结束说话按钮，当状态处于 listening 或 speaking 且正在使用连续语音时，悬浮于侧方 -->
            <button 
              v-if="(status === 'listening' || status === 'speaking') && isMicOn"
              @click="stopMic" 
              class="btn-send"
              style="position: absolute; right: 20px; background: #f59e0b; padding: 6px 12px; font-size: 0.8em; border-radius: 6px; box-shadow: 0 4px 12px rgba(245, 158, 11, 0.3);"
              title="立即停止麦克风监听并对已捕获的声音进行识别"
            >
              ✋ 停止倾听并发送
            </button>
          </div>
        </section>

        <!-- 右侧：对话记录 -->
        <section class="right-column chat-section glass-panel">
          <div class="chat-header">
            <span>对话记录</span>
            <button class="btn-clear-chat" @click="clearChat" :disabled="chatHistory.length === 0" :style="{ opacity: chatHistory.length === 0 ? 0.45 : 1, cursor: chatHistory.length === 0 ? 'not-allowed' : 'pointer' }">清空记录</button>
          </div>
          
          <div class="chat-list" ref="chatListRef">
            <div v-if="chatHistory.length === 0 && !speechPreviewText" class="chat-empty">
              <p>暂无历史会话</p>
              <p class="chat-empty-sub">开启摄像头或在下方上传图片，并说点什么开始交流吧</p>
            </div>
            
            <div v-for="(msg, index) in chatHistory" :key="index" :class="['chat-bubble', msg.role]">
              <!-- 异常提示卡片 -->
              <div v-if="msg.role === 'error-card'" class="error-card-content">
                该内容无法识别，请更换图片或问题
              </div>
              
              <div v-else>
                <div class="bubble-sender-row">
                  <span class="bubble-sender">{{ msg.role === 'user' ? 'YOU' : msg.role === 'ai' ? 'AURA' : 'SYSTEM' }}</span>
                  <span v-if="msg.role === 'ai'" class="bubble-category-label">{{ msg.category || '画面解读' }}</span>
                </div>
                
                <!-- If user sent an image with this message, display it -->
                <div v-if="msg.image" class="bubble-image-container">
                  <img :src="msg.image" class="bubble-image" />
                </div>
                
                <!-- Monospace scrolling block for OCR -->
                <div v-if="msg.role === 'ai' && msg.category === 'OCR 文字'" class="ocr-scroll-block">
                  <pre>{{ msg.text }}</pre>
                </div>
                <div v-else class="bubble-content markdown-body" v-html="renderMarkdown(msg.text)"></div>
                
                <!-- Hover action icons -->
                <div v-if="msg.role === 'ai'" class="bubble-actions">
                  <button class="bubble-action-btn" title="复制全部文本" @click="copyText(msg.text)">📋</button>
                  <button class="bubble-action-btn" title="中英翻译" @click="triggerTranslate(msg.text)">🌐</button>
                  <button class="bubble-action-btn" title="语音朗读" @click="speakText(msg.text)">🔊</button>
                </div>
              </div>
            </div>

            <!-- AI 正在思考中 loader -->
            <div v-if="isAiThinking && !isStreaming" class="chat-bubble ai thinking-bubble">
              <div class="bubble-sender-row">
                <span class="bubble-sender">AURA</span>
                <span class="bubble-category-label">正在识别 & 思考中</span>
              </div>
              <div class="thinking-loader-row">
                <div class="thinking-spinner"></div>
                <span class="thinking-loading-text">正在识别 & 思考中...</span>
              </div>
              <!-- 正在思考时也直接展示打断按钮 -->
              <div class="bubble-actions" style="opacity: 1; pointer-events: auto; transform: translateY(0);">
                <button class="bubble-action-btn" title="打断回答" @click="interruptAnswer">✖️ 打断回答</button>
              </div>
            </div>

            <!-- AI 流式打字效果显示 (置于 v-for 循环之外) -->
            <div v-if="isStreaming" class="chat-bubble ai streaming-bubble">
              <div class="bubble-sender-row">
                <span class="bubble-sender">AURA</span>
                <span class="bubble-category-label">{{ activeMessageCategory }}</span>
              </div>
              <div class="bubble-content streaming-text markdown-body" v-html="renderMarkdown(streamingText) + '<span class=\'cursor-blink\'>|</span>'">
              </div>
              <!-- 正在流式输出时直接展示打断按钮 -->
              <div class="bubble-actions" style="opacity: 1; pointer-events: auto; transform: translateY(0);">
                <button class="bubble-action-btn" title="打断回答" @click="interruptAnswer">✖️ 打断回答</button>
              </div>
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

          <!-- 图片上传与专属工具组件 -->
          <div class="chat-image-tool-section">
            <div 
              v-if="!uploadedImageSrc" 
              class="upload-dashed-container" 
              @click="triggerFileSelect" 
              @dragover.prevent 
              @dragenter.prevent 
              @drop.prevent="handleFileDrop"
            >
              <span class="upload-icon">📤</span>
              <span class="upload-hint">点击上传 / 拖拽图片至此</span>
            </div>
            <div v-else class="upload-preview-container">
              <img :src="croppedImageSrc || uploadedImageSrc" class="upload-preview-img" />
              <div class="upload-preview-actions">
                <button class="tool-btn" @click="showCropModal = true">✂️ 局部选区</button>
                <button class="tool-btn danger" @click="clearUploadedImage">🗑️ 清除图片</button>
              </div>
            </div>
          </div>

          <!-- 快捷操作任务栏 -->
          <div class="quick-tasks-bar" v-if="isConversationActive || uploadedImageSrc">
            <button class="quick-task-btn" @click="sendMessage('请详细描述一下这张图片中的画面内容。', '画面解读')">🔍 画面描述</button>
            <button class="quick-task-btn" @click="sendMessage('请识别并提取出这张图片中的所有文字内容（包含手写或印刷体），并直接排版规整输出。', 'OCR 文字')">📝 文字提取</button>
            <button class="quick-task-btn" @click="sendMessage('请帮我识别图片中的物品（如花草、食物、日常用品等），并对其进行简单的科普介绍。', '物品科普')">🏷️ 物品科普</button>
            <button class="quick-task-btn" @click="sendMessage('请根据图片画面创作一两句有创意的文案、趣味短句或简单点评。', '趣味短句')">✍️ 趣味短句</button>
          </div>
          
          <!-- 纯文本与语音输入区域 -->
          <div class="chat-input-area" v-if="isConversationActive">
            <!-- 录音状态 -->
            <button 
              class="btn-input-mic" 
              :class="{ recording: isRecordingInput }" 
              title="按一下录音转文字"
              @click="toggleInputMic"
            >
              🎙️
            </button>
            
            <div v-if="isRecordingInput" class="recording-hint-text" style="flex: 1; display: flex; justify-content: space-between; align-items: center;">
              <div style="display: flex; align-items: center; gap: 8px;">
                正在录音…
                <span class="voice-wave-dots"><span></span><span></span><span></span></span>
              </div>
              <!-- 主动结束说话，即时触发发送 -->
              <button @click="stopInputMicAndSend" class="btn-send" style="background: #10b981; height: 32px; padding: 0 12px; font-size: 0.85em; border-radius: 4px;">
                ✅ 说完了，发送
              </button>
            </div>
            
            <input 
              v-else
              type="text" 
              v-model="manualInputText" 
              @keyup.enter="sendManualMessage"
              placeholder="在输入框中输入内容..." 
              class="manual-input"
            />
            <button v-if="!isRecordingInput" @click="sendManualMessage" class="btn-send">发送</button>

            <!-- 核心交互区中途打断按钮：在 AI 正在思考中、流式打印或朗读播放时，固定在输入框右侧以供随时打断 -->
            <button 
              v-if="isAiThinking || isStreaming || (window?.speechSynthesis && window.speechSynthesis.speaking)"
              @click="interruptAnswer" 
              class="btn-send danger"
              style="background: #ef4444; margin-left: 4px; display: flex; align-items: center; gap: 4px;"
              title="打断当前思考、打字流或语音朗读"
            >
              🛑 打断
            </button>
          </div>
        </section>
      </main>
    </div>

    <!-- 隐藏的文件选择输入项 -->
    <input type="file" ref="fileInputRef" accept="image/*" style="display: none;" @change="handleFileChange" />

    <!-- 局部框选模态浮层 -->
    <transition name="fade">
      <div v-if="showCropModal" class="modal-backdrop crop-modal-backdrop">
        <div class="crop-modal-content glass-panel">
          <div class="crop-modal-header">
            <h4>✂️ 局部区域框选识别</h4>
            <span class="crop-modal-hint">在下方图片中拖动鼠标画出框选范围</span>
          </div>
          <div class="crop-canvas-container">
            <canvas 
              ref="cropCanvasRef" 
              class="crop-canvas"
              @mousedown="onCropStart"
              @mousemove="onCropMove"
              @mouseup="onCropEnd"
              @touchstart="onTouchCropStart"
              @touchmove="onTouchCropMove"
              @touchend="onTouchCropEnd"
            ></canvas>
          </div>
          <div class="crop-modal-actions">
            <button class="crop-action-btn" @click="reselectCrop">重选</button>
            <button class="crop-action-btn primary" @click="confirmCrop">确认</button>
            <button class="crop-action-btn" @click="cancelCrop">取消</button>
          </div>
        </div>
      </div>
    </transition>

    <!-- 翻译弹窗 -->
    <transition name="fade">
      <div v-if="showTranslateModal" class="modal-backdrop translate-modal-backdrop">
        <div class="translate-modal-content glass-panel">
          <div class="modal-header">
            <h4>🌐 翻译小助手</h4>
            <button class="btn-close-modal" @click="showTranslateModal = false">×</button>
          </div>
          <div class="translate-body">
            <div class="translate-row">
              <div class="translate-title-row">
                <span class="translate-label">原文</span>
              </div>
              <div class="translate-text-box">{{ translateOriginalText }}</div>
            </div>
            
            <div class="translate-middle-action">
              <button class="btn-swap-translate" @click="swapTranslate" title="中英互译交换">⇅ 交换原文译文</button>
            </div>

            <div class="translate-row">
              <div class="translate-title-row">
                <span class="translate-label">译文</span>
              </div>
              <div class="translate-text-box result">
                <div v-if="isTranslating" class="translate-loading">
                  <div class="spinner-small"></div> 正在翻译中...
                </div>
                <div v-else>{{ translateResultText }}</div>
              </div>
            </div>
          </div>
          <div class="modal-footer">
            <button class="btn-modal-close" @click="showTranslateModal = false">关闭</button>
          </div>
        </div>
      </div>
    </transition>

    <!-- 清空确认弹窗 -->
    <transition name="fade">
      <div v-if="showConfirmClearModal" class="modal-backdrop confirm-modal-backdrop">
        <div class="confirm-modal-content glass-panel">
          <h4>⚠️ 清空确认</h4>
          <p>确定要清空全部历史会话记录吗？此操作不可恢复。</p>
          <div class="confirm-modal-actions">
            <button class="confirm-btn" @click="showConfirmClearModal = false">取消</button>
            <button class="confirm-btn danger" @click="clearAllSessions">确认</button>
          </div>
        </div>
      </div>
    </transition>

    <!-- 配置与个性化面板 -->
    <transition name="slide-fade">
      <div v-if="showSettings" class="settings-panel glass-panel">
        <div class="settings-header">
          <h3>偏好与系统配置</h3>
          <button class="btn-close-settings" @click="showSettings = false">×</button>
        </div>

        <div class="settings-tabs">
          <button 
            class="settings-tab-btn" 
            :class="{ active: activeSettingsTab === 'api' }" 
            @click="activeSettingsTab = 'api'"
          >
            ⚙️ API 配置
          </button>
          <button 
            class="settings-tab-btn" 
            :class="{ active: activeSettingsTab === 'personal' }" 
            @click="activeSettingsTab = 'personal'"
          >
            🎨 个性化设置
          </button>
        </div>
        
        <!-- Tab 1: API配置 -->
        <div v-show="activeSettingsTab === 'api'" class="settings-body">
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

        <!-- Tab 2: 个性化配置 -->
        <div v-show="activeSettingsTab === 'personal'" class="settings-body">
          <div class="input-group">
            <label>界面主题</label>
            <div class="theme-toggle-group">
              <button 
                class="toggle-choice-btn" 
                :class="{ active: activeTheme === 'dark' }" 
                @click="activeTheme = 'dark'"
              >
                🌙 深色
              </button>
              <button 
                class="toggle-choice-btn" 
                :class="{ active: activeTheme === 'light' }" 
                @click="activeTheme = 'light'"
              >
                ☀️ 浅色
              </button>
            </div>
          </div>
          
          <div class="input-group">
            <div class="input-label-row">
              <label>全局文字大小</label>
              <span class="value-badge">{{ fontSizeBase }}px</span>
            </div>
            <input type="range" v-model.number="fontSizeBase" min="12" max="20" step="1" class="slider-input" />
          </div>

          <div class="input-group">
            <label>语音识别/输入语言</label>
            <select v-model="speechLang" @change="handleLangChange" class="preset-select">
              <option value="zh-CN">🇨🇳 中文 (Chinese)</option>
              <option value="en-US">🇺🇸 英文 (English)</option>
            </select>
          </div>

          <div class="input-group">
            <div class="switch-row">
              <label for="ttsToggle">启用 AI 语音合成朗读</label>
              <div class="switch-container">
                <input type="checkbox" id="ttsToggle" v-model="ttsEnabled" class="switch-input" />
                <label for="ttsToggle" class="switch-slider"></label>
              </div>
            </div>
            <span class="input-hint">开启后，AI 回复将自动通过声音播放</span>
          </div>

          <div class="input-group">
            <label>视觉摄像头清晰度</label>
            <select v-model="videoResolution" @change="handleResolutionChange" class="preset-select">
              <option value="smooth">⚡ 流畅优先 (320×240 @低画质)</option>
              <option value="standard">🎨 均衡模式 (640×480 @标准画质)</option>
              <option value="hd">👁️ 细节优先 (1280×720 @高画质)</option>
            </select>
          </div>

          <div class="input-group">
            <div class="input-label-row">
              <label>背景流光特效强度</label>
              <span class="value-badge">{{ bgGlowOpacity }}%</span>
            </div>
            <input type="range" v-model.number="bgGlowOpacity" min="0" max="100" step="5" class="slider-input" />
          </div>

          <div class="input-group">
            <div class="input-label-row">
              <label>AI 文字生成显示速度</label>
              <span class="value-badge">{{ typewriterSpeed }}ms</span>
            </div>
            <input type="range" v-model.number="typewriterSpeed" min="10" max="80" step="5" class="slider-input" />
          </div>
        </div>
        
        <div class="panel-actions">
          <button v-if="activeSettingsTab === 'api'" class="btn-clear" @click="clearConfig">重置</button>
          <button v-if="activeSettingsTab === 'api'" class="btn-confirm" @click="validateConfig">确定并验证</button>
          <button v-else class="btn-confirm" @click="showSettings = false" style="width: 100%; text-align: center;">完成设置</button>
        </div>
      </div>
    </transition>

    <!-- 偏好与系统设置按钮已移入顶部导航栏 -->

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

.app-layout {
  /* Default Dark Theme Variables */
  --bg-color: #121212;
  --text-color: #f3f4f6;
  --card-bg: #1e1e1e;
  --glass-bg: rgba(30, 30, 30, 0.75);
  --glass-border: rgba(255, 255, 255, 0.08);
  --glass-hover-border: rgba(255, 255, 255, 0.15);
  --glass-shadow: rgba(0, 0, 0, 0.5);
  
  --chat-ai-bg: rgba(255, 255, 255, 0.04);
  --chat-ai-border: rgba(255, 255, 255, 0.06);
  --input-bg: rgba(0, 0, 0, 0.2);
  --input-border: rgba(255, 255, 255, 0.1);
  --input-hover-bg: rgba(0, 0, 0, 0.4);
  --input-focus-bg: rgba(0, 0, 0, 0.5);
  
  --label-color: rgba(255, 255, 255, 0.7);
  --hint-color: #9ca3af;
  --border-color: #374151;
  --primary-color: #2563eb;
  --primary-hover: #1d4ed8;
  --btn-close-color: rgba(255, 255, 255, 0.5);
  --btn-clear-border: rgba(255, 255, 255, 0.1);
  --btn-clear-color: rgba(255, 255, 255, 0.5);
  --chat-empty-color: rgba(255, 255, 255, 0.25);
  --chat-header-border: rgba(255, 255, 255, 0.06);
  --settings-toggle-glow: rgba(0, 210, 255, 0.3);
  --status-orb-bg: rgba(255, 255, 255, 0.1);
  --status-orb-shadow: rgba(255, 255, 255, 0.05);
  --status-orb-idle-bg: rgba(255, 255, 255, 0.06);

  display: flex;
  width: 100vw;
  height: 100vh;
  background: var(--bg-color);
  color: var(--text-color);
  font-family: 'Outfit', 'Space Grotesk', system-ui, sans-serif;
  overflow: hidden;
  position: relative;
  font-size: var(--font-size-base, 14px);
  transition: background 0.3s ease, color 0.3s ease, font-size 0.25s ease;
}

/* Light Theme Variables Override */
.app-layout[data-theme="light"] {
  --bg-color: #f8f9fa;
  --text-color: #1f2937;
  --card-bg: #ffffff;
  --glass-bg: rgba(255, 255, 255, 0.85);
  --glass-border: rgba(0, 0, 0, 0.08);
  --glass-hover-border: rgba(0, 0, 0, 0.15);
  --glass-shadow: rgba(31, 41, 55, 0.06);
  
  --chat-ai-bg: rgba(0, 0, 0, 0.03);
  --chat-ai-border: rgba(0, 0, 0, 0.07);
  --input-bg: rgba(255, 255, 255, 0.85);
  --input-border: rgba(0, 0, 0, 0.12);
  --input-hover-bg: rgba(255, 255, 255, 0.95);
  --input-focus-bg: #ffffff;
  
  --label-color: rgba(31, 41, 55, 0.8);
  --hint-color: #6b7280;
  --border-color: #e5e7eb;
  --btn-close-color: rgba(31, 41, 55, 0.5);
  --btn-clear-border: rgba(0, 0, 0, 0.15);
  --btn-clear-color: rgba(31, 41, 55, 0.5);
  --chat-empty-color: rgba(31, 41, 55, 0.45);
  --chat-header-border: rgba(0, 0, 0, 0.06);
  --settings-toggle-glow: rgba(59, 130, 246, 0.25);
  --status-orb-bg: rgba(0, 0, 0, 0.06);
  --status-orb-shadow: rgba(0, 0, 0, 0.03);
  --status-orb-idle-bg: rgba(0, 0, 0, 0.04);
}

.preset-select option {
  background: var(--card-bg);
  color: var(--text-color);
}

/* 渐变氛围背景 */
.ambient-glow {
  position: absolute;
  width: 600px;
  height: 600px;
  border-radius: 50%;
  filter: blur(120px);
  z-index: 1;
  pointer-events: none;
  transition: opacity 0.5s ease;
}
.bg-1 {
  background: radial-gradient(circle, rgba(37, 99, 235, 0.18) 0%, rgba(0,0,0,0) 70%);
  top: -100px;
  right: -100px;
}
.bg-2 {
  background: radial-gradient(circle, rgba(121, 40, 202, 0.12) 0%, rgba(0,0,0,0) 70%);
  bottom: -100px;
  left: -100px;
}

/* 玻璃面板基础规范 */
.glass-panel {
  background: var(--glass-bg);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border: 1px solid var(--glass-border);
  border-radius: 10px;
  box-shadow: 0 8px 32px 0 var(--glass-shadow);
  z-index: 2;
  transition: all 0.3s cubic-bezier(0.25, 0.8, 0.25, 1);
}
.glass-panel:hover {
  border-color: var(--glass-hover-border);
  box-shadow: 0 12px 40px 0 var(--glass-shadow);
}

/* ==========================================================================
   一、左侧常驻侧边栏 (Sidebar Style)
   ========================================================================== */
.sidebar {
  width: 260px;
  background: var(--card-bg);
  border-right: 1px solid var(--border-color);
  display: flex;
  flex-direction: column;
  height: 100%;
  flex-shrink: 0;
  box-sizing: border-box;
  z-index: 10;
  transition: background 0.3s ease, border-color 0.3s ease;
}
.sidebar-header {
  padding: 16px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.sidebar-title {
  font-size: 1.15em;
  font-weight: 700;
  color: var(--text-color);
}
.sidebar-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.btn-new-chat, .btn-clear-all {
  background: transparent;
  border: none;
  color: var(--hint-color);
  cursor: pointer;
  font-size: 1.1em;
  padding: 4px;
  border-radius: 6px;
  transition: all 0.2s;
  display: flex;
  align-items: center;
  justify-content: center;
}
.btn-new-chat:hover {
  background: rgba(16, 185, 129, 0.1);
  color: #10b981;
}
.btn-new-chat:active, .btn-clear-all:active {
  transform: translateY(1px);
}
.btn-clear-all:hover {
  background: rgba(239, 68, 68, 0.1);
  color: #ef4444;
}
.sidebar-divider {
  height: 1px;
  background: var(--border-color);
  margin: 0 16px;
}
.sidebar-content {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
}
.sidebar-empty {
  text-align: center;
  color: var(--hint-color);
  font-size: 0.85em;
  padding-top: 40px;
}
.session-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.session-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px;
  border-radius: 8px;
  cursor: pointer;
  background: rgba(0, 0, 0, 0.02);
  border: 1px solid transparent;
  position: relative;
  transition: all 0.2s ease;
}
.session-item:hover {
  background: rgba(0, 0, 0, 0.05);
}
.app-layout[data-theme="light"] .session-item:hover {
  background: rgba(0, 0, 0, 0.03);
}
.session-item.active {
  background: rgba(37, 99, 235, 0.08);
  border-color: var(--primary-color);
}
.session-thumb {
  width: 48px;
  height: 48px;
  border-radius: 8px;
  overflow: hidden;
  background: rgba(0, 0, 0, 0.05);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  border: 1px solid var(--border-color);
}
.session-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.session-thumb-placeholder {
  font-size: 1.5em;
}
.session-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.session-preview {
  font-size: 0.95em;
  font-weight: 500;
  color: var(--text-color);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.session-time {
  font-size: 0.8em;
  color: var(--hint-color);
}
.btn-session-delete {
  position: absolute;
  right: 10px;
  background: var(--card-bg);
  border: 1px solid var(--border-color);
  color: var(--hint-color);
  border-radius: 6px;
  padding: 4px 6px;
  cursor: pointer;
  opacity: 0;
  pointer-events: none;
  font-size: 0.9em;
  box-shadow: 0 2px 5px rgba(0, 0, 0, 0.1);
  transition: all 0.2s ease;
}
.session-item:hover .btn-session-delete {
  opacity: 1;
  pointer-events: auto;
}
.btn-session-delete:hover {
  background: #ef4444;
  color: #fff;
  border-color: #ef4444;
}
.btn-session-delete:active {
  transform: translateY(1px);
}

/* ==========================================================================
   二、右侧主工作区 (Main Workspace)
   ========================================================================== */
.main-workspace {
  flex: 1;
  display: flex;
  flex-direction: column;
  height: 100%;
  min-width: 0;
  position: relative;
  z-index: 2;
}

/* 顶部导航 */
.app-header {
  height: 60px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 24px;
  border-bottom: 1px solid var(--border-color);
  background: rgba(var(--bg-color), 0.5);
  backdrop-filter: blur(10px);
  z-index: 5;
}
.logo {
  display: flex;
  align-items: center;
  gap: 10px;
}
.pulse-dot {
  width: 8px;
  height: 8px;
  background-color: var(--primary-color);
  border-radius: 50%;
  box-shadow: 0 0 10px var(--primary-color);
}
.logo-text {
  font-family: 'Space Grotesk', sans-serif;
  font-weight: 700;
  font-size: 1.1em;
  letter-spacing: 1.5px;
}
.logo-badge {
  font-size: 0.55em;
  background: rgba(37, 99, 235, 0.15);
  color: var(--primary-color);
  padding: 2px 6px;
  border-radius: 6px;
  font-weight: 600;
  border: 1px solid rgba(37, 99, 235, 0.2);
}

/* 对话 / 视频 永久居中切换标签 */
.view-toggle-tabs {
  display: flex;
  background: rgba(0, 0, 0, 0.05);
  padding: 3px;
  border-radius: 8px;
  gap: 2px;
}
.app-layout[data-theme="light"] .view-toggle-tabs {
  background: rgba(0, 0, 0, 0.03);
}
.view-tab-btn {
  background: transparent;
  border: none;
  color: var(--text-color);
  padding: 6px 12px;
  font-size: 0.9em;
  font-weight: 500;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.2s ease;
}
.view-tab-btn.active {
  background: var(--card-bg);
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.08);
  color: var(--primary-color);
}
.view-tab-btn:active {
  transform: translateY(1px);
}

.header-right {
  display: flex;
  align-items: center;
  gap: 16px;
}
.active-model-display {
  font-size: 0.8em;
  padding: 6px 12px;
  background: rgba(0, 0, 0, 0.15);
  border: 1px solid var(--border-color);
  border-radius: 6px;
  display: flex;
  align-items: center;
  gap: 8px;
  font-family: 'Space Grotesk', sans-serif;
}
.app-layout[data-theme="light"] .active-model-display {
  background: rgba(0, 0, 0, 0.02);
}
.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
}
.dot.green {
  background: #10b981;
  box-shadow: 0 0 8px #10b981;
}

/* 主题方形切换按钮 */
.theme-square-btn {
  width: 32px;
  height: 32px;
  border-radius: 6px;
  background: var(--card-bg);
  border: 1px solid var(--border-color);
  color: var(--text-color);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.3s ease;
}
.theme-square-btn:hover {
  background: rgba(0, 0, 0, 0.05);
  border-color: var(--primary-color);
}
.theme-square-btn:active {
  transform: translateY(1px);
}

/* 核心内容区 */
.app-main {
  flex: 1;
  display: flex;
  padding: 16px;
  gap: 16px;
  overflow: hidden;
  box-sizing: border-box;
}

/* 左侧分栏：视频与呼吸球模式面板 */
.left-column {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 16px;
  height: 100%;
  min-width: 0;
}
.camera-section {
  flex: 1;
  position: relative;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
}
.camera-preview {
  width: 100%;
  height: 100%;
  object-fit: cover;
  z-index: 2;
}
.placeholder {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  color: var(--hint-color);
  z-index: 3;
}
.placeholder-icon {
  font-size: 3.5em;
  opacity: 0.45;
  animation: pulse 2s infinite ease-in-out;
}

/* 右侧分栏：对话模式面板 */
.right-column {
  flex: 1.2;
  display: flex;
  flex-direction: column;
  height: 100%;
  min-width: 0;
  box-sizing: border-box;
  overflow: hidden;
}
.chat-header {
  padding: 16px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  border-bottom: 1px solid var(--border-color);
}
.btn-clear-chat {
  background: transparent;
  border: 1px solid var(--border-color);
  color: var(--hint-color);
  padding: 4px 10px;
  border-radius: 6px;
  font-size: 0.8em;
  cursor: pointer;
  transition: all 0.2s;
}
.btn-clear-chat:hover:not(:disabled) {
  background: rgba(239, 68, 68, 0.1);
  color: #ef4444;
  border-color: #ef4444;
}

.chat-list {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.chat-empty {
  text-align: center;
  color: var(--hint-color);
  padding-top: 60px;
  font-size: 0.95em;
}
.chat-empty-sub {
  font-size: 0.8em;
  margin-top: 6px;
  opacity: 0.7;
}

/* 对话气泡 */
.chat-bubble {
  max-width: 85%;
  padding: 10px 14px;
  border-radius: 10px;
  font-size: 0.95em;
  line-height: 1.5;
  animation: slideIn 0.3s cubic-bezier(0.18, 0.89, 0.32, 1.28);
  display: flex;
  flex-direction: column;
  gap: 4px;
  position: relative;
}
@keyframes slideIn {
  from { opacity: 0; transform: translateY(15px); }
  to { opacity: 1; transform: translateY(0); }
}
.bubble-sender-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}
.bubble-sender {
  font-family: 'Space Grotesk', sans-serif;
  font-size: 0.7em;
  font-weight: 700;
  letter-spacing: 1px;
  opacity: 0.6;
}
.bubble-category-label {
  font-size: 0.8em;
  color: var(--hint-color);
}
.chat-bubble.user {
  align-self: flex-end;
  background: linear-gradient(135deg, rgba(37, 99, 235, 0.15) 0%, rgba(59, 130, 246, 0.15) 100%);
  border: 1px solid rgba(37, 99, 235, 0.25);
  border-bottom-right-radius: 2px;
}
.chat-bubble.ai {
  align-self: flex-start;
  background: var(--chat-ai-bg);
  border: 1px solid var(--chat-ai-border);
  border-bottom-left-radius: 2px;
}
.bubble-image-container {
  max-width: 100%;
  margin-top: 6px;
  border-radius: 8px;
  overflow: hidden;
  border: 1px solid var(--border-color);
}
.bubble-image {
  max-width: 200px;
  max-height: 150px;
  object-fit: contain;
  display: block;
}

/* OCR 识别区块 */
.ocr-scroll-block {
  background: rgba(0, 0, 0, 0.05);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 10px;
  max-height: 200px;
  overflow-y: auto;
  margin: 6px 0;
}
.app-layout[data-theme="light"] .ocr-scroll-block {
  background: rgba(0, 0, 0, 0.02);
}
.ocr-scroll-block pre {
  margin: 0;
  font-family: 'Space Grotesk', monospace;
  font-size: 0.9em;
  white-space: pre-wrap;
  word-break: break-all;
  color: var(--text-color);
}

/* 气泡右下角工具组 */
.bubble-actions {
  position: absolute;
  bottom: -15px;
  right: 10px;
  display: flex;
  gap: 4px;
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.2s ease, transform 0.2s ease;
  transform: translateY(5px);
  z-index: 5;
}
.chat-bubble.ai:hover .bubble-actions {
  opacity: 1;
  pointer-events: auto;
  transform: translateY(0);
}
.bubble-action-btn {
  background: var(--card-bg);
  border: 1px solid var(--border-color);
  color: var(--text-color);
  padding: 4px 6px;
  font-size: 11px;
  border-radius: 6px;
  cursor: pointer;
  box-shadow: 0 2px 8px rgba(0,0,0,0.15);
  transition: all 0.2s ease;
}
.bubble-action-btn:hover {
  background: var(--primary-color);
  color: #fff;
  transform: scale(1.05);
}
.bubble-action-btn:active {
  transform: scale(0.95);
}

/* 异常提示卡片 */
.chat-bubble.error-card {
  align-self: center;
  max-width: 90%;
  animation: slideIn 0.3s ease;
}
.error-card-content {
  background: rgba(0,0,0,0.05);
  border: 1px solid var(--border-color);
  color: var(--hint-color);
  padding: 12px 20px;
  border-radius: 10px;
  font-size: 0.95em;
  text-align: center;
}
.app-layout[data-theme="light"] .error-card-content {
  background: rgba(0,0,0,0.02);
}

/* ==========================================================================
   四、图片上传与框选工具 (Image Upload & Box Crop)
   ========================================================================== */
.chat-image-tool-section {
  padding: 12px 16px;
  border-top: 1px solid var(--border-color);
  background: rgba(0, 0, 0, 0.02);
}
.upload-dashed-container {
  border: 2px dashed var(--border-color);
  border-radius: 8px;
  padding: 16px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  gap: 8px;
  transition: border-color 0.2s ease;
}
.upload-dashed-container:hover {
  border-color: var(--primary-color);
}
.upload-icon {
  font-size: 1.8em;
}
.upload-hint {
  font-size: 0.85em;
  color: var(--hint-color);
}
.upload-preview-container {
  display: flex;
  align-items: center;
  gap: 16px;
}
.upload-preview-img {
  width: 90px;
  height: 60px;
  object-fit: cover;
  border-radius: 8px;
  border: 1px solid var(--border-color);
}
.upload-preview-actions {
  display: flex;
  gap: 8px;
}
.tool-btn {
  background: var(--card-bg);
  border: 1px solid var(--border-color);
  color: var(--text-color);
  padding: 6px 12px;
  border-radius: 6px;
  font-size: 0.85em;
  cursor: pointer;
  transition: all 0.2s ease;
}
.tool-btn:hover {
  background: rgba(0, 0, 0, 0.05);
  border-color: var(--primary-color);
}
.tool-btn.danger:hover {
  background: rgba(239, 68, 68, 0.1);
  border-color: #ef4444;
  color: #ef4444;
}
.tool-btn:active {
  transform: translateY(1px);
}

/* 快捷操作栏 */
.quick-tasks-bar {
  display: flex;
  gap: 6px;
  padding: 8px 16px;
  overflow-x: auto;
  background: rgba(0, 0, 0, 0.01);
}
.quick-task-btn {
  background: var(--card-bg);
  border: 1px solid var(--border-color);
  color: var(--text-color);
  padding: 6px 10px;
  border-radius: 6px;
  font-size: 0.85em;
  cursor: pointer;
  white-space: nowrap;
  transition: all 0.2s ease;
}
.quick-task-btn:hover {
  background: var(--primary-color);
  color: #fff;
  border-color: var(--primary-color);
}
.quick-task-btn:active {
  transform: translateY(1px);
}

/* 输入栏 & 单次语音 */
.chat-input-area {
  padding: 12px 16px;
  display: flex;
  gap: 8px;
  align-items: center;
  border-top: 1px solid var(--border-color);
  background: rgba(var(--bg-color), 0.5);
  position: relative;
}
.btn-input-mic {
  width: 38px;
  height: 38px;
  border-radius: 50%;
  background: var(--card-bg);
  border: 1px solid var(--border-color);
  color: var(--text-color);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  font-size: 1.1em;
  transition: all 0.25s ease;
}
.btn-input-mic:hover {
  border-color: var(--primary-color);
  box-shadow: 0 0 10px rgba(37, 99, 235, 0.2);
}
.btn-input-mic.recording {
  background: #ef4444;
  border-color: #ef4444;
  color: #fff;
  animation: pulse-recording 1.2s infinite alternate ease-in-out;
}
@keyframes pulse-recording {
  0% { transform: scale(1); box-shadow: 0 0 4px rgba(239, 68, 68, 0.4); }
  100% { transform: scale(1.08); box-shadow: 0 0 16px rgba(239, 68, 68, 0.8); }
}
.recording-hint-text {
  flex: 1;
  height: 38px;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 0.9em;
  color: #ef4444;
  font-weight: 500;
}
.voice-wave-dots {
  display: inline-flex;
  gap: 3px;
  align-items: center;
}
.voice-wave-dots span {
  width: 4px;
  height: 8px;
  background: #ef4444;
  border-radius: 2px;
  animation: voice-bar-bounce 0.8s infinite ease-in-out;
}
.voice-wave-dots span:nth-child(2) { height: 14px; animation-delay: 0.2s; }
.voice-wave-dots span:nth-child(3) { height: 10px; animation-delay: 0.4s; }
@keyframes voice-bar-bounce {
  0%, 100% { transform: scaleY(0.4); }
  50% { transform: scaleY(1); }
}

.manual-input {
  flex: 1;
  height: 38px;
  background: var(--input-bg);
  border: 1px solid var(--input-border);
  border-radius: 6px;
  padding: 0 14px;
  color: var(--text-color);
  font-size: 0.95em;
  transition: all 0.25s ease;
}
.manual-input:hover {
  background: var(--input-hover-bg);
}
.manual-input:focus {
  outline: none;
  border-color: var(--primary-color);
  background: var(--input-focus-bg);
}
.btn-send {
  height: 38px;
  background: var(--primary-color);
  border: none;
  color: white;
  padding: 0 18px;
  border-radius: 6px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.2s;
}
.btn-send:hover {
  background: var(--primary-hover);
}
.btn-send:active {
  transform: translateY(1px);
}

/* ==========================================================================
   五、模态浮层与全局弹窗 (Modals Backdrop & Content)
   ========================================================================== */
.modal-backdrop {
  position: fixed;
  top: 0;
  left: 0;
  width: 100vw;
  height: 100vh;
  background: rgba(0, 0, 0, 0.7);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 9999;
}

/* 局部选区框选弹窗 */
.crop-modal-content {
  width: 85vw;
  max-width: 900px;
  display: flex;
  flex-direction: column;
  max-height: 85vh;
  box-shadow: 0 20px 50px rgba(0, 0, 0, 0.8);
  border-radius: 10px;
}
.crop-modal-header {
  padding: 16px;
  border-bottom: 1px solid var(--border-color);
}
.crop-modal-header h4 {
  margin: 0;
  font-size: 1.15em;
  font-weight: 700;
}
.crop-modal-hint {
  font-size: 0.8em;
  color: var(--hint-color);
}
.crop-canvas-container {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #000;
  padding: 10px;
  overflow: hidden;
  min-height: 200px;
}
.crop-canvas {
  cursor: crosshair;
  max-width: 100%;
  max-height: 100%;
  display: block;
}
.crop-modal-actions {
  padding: 16px;
  border-top: 1px solid var(--border-color);
  display: flex;
  gap: 12px;
}
.crop-action-btn {
  flex: 1;
  background: var(--card-bg);
  border: 1px solid var(--border-color);
  color: var(--text-color);
  padding: 10px 0;
  border-radius: 6px;
  font-weight: 500;
  cursor: pointer;
  text-align: center;
  transition: all 0.2s ease;
}
.crop-action-btn:hover {
  background: rgba(0, 0, 0, 0.05);
}
.crop-action-btn.primary {
  background: var(--primary-color);
  border-color: var(--primary-color);
  color: #fff;
}
.crop-action-btn.primary:hover {
  background: var(--primary-hover);
}
.crop-action-btn:active {
  transform: translateY(1px);
}

/* 翻译弹窗 */
.translate-modal-content {
  width: 90%;
  max-width: 500px;
  border-radius: 10px;
  box-shadow: 0 10px 40px rgba(0,0,0,0.5);
  display: flex;
  flex-direction: column;
}
.modal-header {
  padding: 16px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  border-bottom: 1px solid var(--border-color);
}
.modal-header h4 {
  margin: 0;
  font-size: 1.15em;
  font-weight: 700;
}
.btn-close-modal {
  background: transparent;
  border: none;
  font-size: 1.4em;
  cursor: pointer;
  color: var(--btn-close-color);
}
.translate-body {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.translate-row {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.translate-title-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.translate-label {
  font-size: 0.85em;
  color: var(--hint-color);
  font-weight: 600;
}
.translate-text-box {
  background: rgba(0, 0, 0, 0.05);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 10px;
  min-height: 70px;
  max-height: 120px;
  overflow-y: auto;
  font-size: 0.95em;
  color: var(--text-color);
}
.app-layout[data-theme="light"] .translate-text-box {
  background: rgba(0, 0, 0, 0.02);
}
.translate-text-box.result {
  border-color: rgba(37, 99, 235, 0.35);
}
.translate-middle-action {
  display: flex;
  justify-content: center;
}
.btn-swap-translate {
  background: transparent;
  border: 1px solid var(--border-color);
  color: var(--primary-color);
  padding: 5px 12px;
  font-size: 0.8em;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.2s ease;
}
.btn-swap-translate:hover {
  background: var(--primary-color);
  color: white;
  border-color: var(--primary-color);
}
.btn-swap-translate:active {
  transform: translateY(1px);
}
.translate-loading {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--hint-color);
}
.spinner-small {
  width: 14px;
  height: 14px;
  border: 2px solid rgba(255,255,255,0.15);
  border-top-color: var(--primary-color);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}
.app-layout[data-theme="light"] .spinner-small {
  border: 2px solid rgba(0,0,0,0.05);
  border-top-color: var(--primary-color);
}
.modal-footer {
  padding: 12px 16px;
  border-top: 1px solid var(--border-color);
  display: flex;
  justify-content: flex-end;
}
.btn-modal-close {
  background: var(--card-bg);
  border: 1px solid var(--border-color);
  color: var(--text-color);
  padding: 6px 14px;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.2s ease;
}
.btn-modal-close:hover {
  background: rgba(0, 0, 0, 0.05);
}

/* 清空确认弹窗 */
.confirm-modal-content {
  width: 90%;
  max-width: 350px;
  padding: 20px;
  border-radius: 10px;
  box-shadow: 0 10px 40px rgba(0,0,0,0.5);
  text-align: center;
}
.confirm-modal-content h4 {
  margin-top: 0;
  font-size: 1.15em;
  font-weight: 700;
}
.confirm-modal-content p {
  font-size: 0.95em;
  color: var(--hint-color);
  margin: 12px 0 20px;
}
.confirm-modal-actions {
  display: flex;
  gap: 12px;
}
.confirm-btn {
  flex: 1;
  background: var(--card-bg);
  border: 1px solid var(--border-color);
  color: var(--text-color);
  padding: 8px 0;
  border-radius: 6px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s ease;
}
.confirm-btn:hover {
  background: rgba(0, 0, 0, 0.05);
}
.confirm-btn.danger {
  background: #ef4444;
  border-color: #ef4444;
  color: #fff;
}
.confirm-btn.danger:hover {
  background: #dc2626;
}
.confirm-btn:active {
  transform: translateY(1px);
}

/* AI 思考中简约转圈加载 */
.thinking-loader-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 0;
}
.thinking-spinner {
  width: 20px;
  height: 20px;
  border: 2px solid rgba(255, 255, 255, 0.15);
  border-top-color: var(--primary-color);
  border-radius: 50%;
  animation: spin 1s linear infinite;
}
.app-layout[data-theme="light"] .thinking-spinner {
  border: 2px solid rgba(0,0,0,0.05);
  border-top-color: var(--primary-color);
}
.thinking-loading-text {
  font-size: 0.95em;
  color: var(--hint-color);
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* ==========================================================================
   六、系统原本配置面板样式 (Retained and Polished Settings Panel)
   ========================================================================== */
.settings-panel {
  position: absolute;
  top: 75px;
  right: 24px;
  width: 350px;
  display: flex;
  flex-direction: column;
  max-height: 80vh;
  box-shadow: 0 20px 50px rgba(0, 0, 0, 0.4);
  z-index: 99;
}
.settings-header {
  padding: 16px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  border-bottom: 1px solid var(--border-color);
}
.settings-header h3 {
  margin: 0;
  font-size: 1.15em;
  font-weight: 700;
}
.btn-close-settings {
  background: transparent;
  border: none;
  font-size: 1.5em;
  cursor: pointer;
  color: var(--btn-close-color);
}
.settings-tabs {
  display: flex;
  border-bottom: 1px solid var(--border-color);
}
.settings-tab-btn {
  flex: 1;
  background: transparent;
  border: none;
  color: var(--hint-color);
  padding: 12px 0;
  font-size: 0.9em;
  font-weight: 600;
  cursor: pointer;
  border-bottom: 2px solid transparent;
  transition: all 0.2s;
}
.settings-tab-btn.active {
  color: var(--primary-color);
  border-bottom-color: var(--primary-color);
}
.settings-body {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.input-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.input-group label {
  font-size: 0.85em;
  color: var(--label-color);
  font-weight: 600;
}
.input-group input, .input-group select {
  height: 38px;
  background: var(--input-bg);
  border: 1px solid var(--input-border);
  border-radius: 6px;
  padding: 0 10px;
  color: var(--text-color);
  font-size: 0.9em;
}
.input-group input:focus, .input-group select:focus {
  outline: none;
  border-color: var(--primary-color);
}
.error-msg {
  color: #ef4444;
  font-size: 0.85em;
  margin-left: 6px;
}
.input-error {
  border-color: #ef4444 !important;
}
.hint {
  font-size: 0.8em;
  color: var(--hint-color);
  margin: 0;
}
.input-hint {
  font-size: 0.8em;
  color: var(--hint-color);
}

/* Tab 2: Switch / Slider inputs */
.theme-toggle-group {
  display: flex;
  background: rgba(0, 0, 0, 0.05);
  padding: 3px;
  border-radius: 8px;
  gap: 2px;
}
.toggle-choice-btn {
  flex: 1;
  background: transparent;
  border: none;
  color: var(--text-color);
  padding: 6px 0;
  font-size: 0.85em;
  border-radius: 6px;
  cursor: pointer;
}
.toggle-choice-btn.active {
  background: var(--card-bg);
  color: var(--primary-color);
  box-shadow: 0 2px 6px rgba(0,0,0,0.1);
}
.input-label-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.value-badge {
  font-size: 0.8em;
  background: rgba(37, 99, 235, 0.15);
  color: var(--primary-color);
  padding: 2px 6px;
  border-radius: 6px;
  font-weight: 600;
}
.slider-input {
  width: 100%;
  accent-color: var(--primary-color);
}
.switch-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.switch-container {
  position: relative;
  width: 44px;
  height: 24px;
}
.switch-input {
  opacity: 0;
  width: 0;
  height: 0;
}
.switch-slider {
  position: absolute;
  cursor: pointer;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background-color: var(--border-color);
  transition: .4s;
  border-radius: 24px;
}
.switch-slider:before {
  position: absolute;
  content: "";
  height: 18px;
  width: 18px;
  left: 3px;
  bottom: 3px;
  background-color: white;
  transition: .4s;
  border-radius: 50%;
}
.switch-input:checked + .switch-slider {
  background-color: var(--primary-color);
}
.switch-input:checked + .switch-slider:before {
  transform: translateX(20px);
}

.panel-actions {
  padding: 16px;
  border-top: 1px solid var(--border-color);
  display: flex;
  gap: 12px;
}
.btn-clear {
  flex: 1;
  height: 38px;
  background: transparent;
  border: 1px solid var(--border-color);
  color: var(--text-color);
  border-radius: 6px;
  cursor: pointer;
}
.btn-clear:hover {
  background: rgba(0, 0, 0, 0.05);
}
.btn-confirm {
  flex: 2;
  height: 38px;
  background: var(--primary-color);
  border: none;
  color: white;
  border-radius: 6px;
  font-weight: 600;
  cursor: pointer;
}
.btn-confirm:hover {
  background: var(--primary-hover);
}

/* 悬浮配置按钮 */
.settings-toggle.fab {
  position: absolute;
  top: 80px;
  right: 24px;
  width: 44px;
  height: 44px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 1.3em;
  cursor: pointer;
  box-shadow: 0 4px 15px var(--settings-toggle-glow);
}
.settings-toggle.fab:hover {
  transform: rotate(30deg);
}

/* 底部控制栏 */
.control-bar {
  position: absolute;
  bottom: 24px;
  left: 50%;
  transform: translateX(-50%);
  padding: 10px 24px;
  display: flex;
  gap: 16px;
  z-index: 5;
  border-radius: 8px;
}
.btn-start, .btn-stop {
  height: 40px;
  padding: 0 24px;
  border: none;
  border-radius: 6px;
  font-weight: 700;
  font-size: 0.95em;
  cursor: pointer;
  letter-spacing: 0.5px;
  transition: all 0.3s;
}
.btn-start {
  background: var(--primary-color);
  color: white;
  box-shadow: 0 4px 14px rgba(37, 99, 235, 0.3);
}
.btn-start:hover {
  background: var(--primary-hover);
  box-shadow: 0 6px 20px rgba(37, 99, 235, 0.45);
}
.btn-stop {
  background: #ef4444;
  color: white;
  box-shadow: 0 4px 14px rgba(239, 68, 68, 0.3);
}
.btn-stop:hover {
  background: #dc2626;
  box-shadow: 0 6px 20px rgba(239, 68, 68, 0.45);
}

/* Toast 提示 */
.toast-notification {
  position: fixed;
  top: 24px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 10000;
  background: var(--glass-bg);
  border: 1px solid var(--glass-border);
  border-radius: 10px;
  box-shadow: 0 10px 30px rgba(0,0,0,0.5);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  cursor: pointer;
}
.toast-content {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 24px;
}
.toast-icon {
  font-weight: 700;
}
.toast-notification.success .toast-icon { color: #10b981; }
.toast-notification.error .toast-icon { color: #ef4444; }
.toast-notification.warning .toast-icon { color: #f59e0b; }
.toast-progress {
  height: 2px;
  background: var(--primary-color);
  width: 100%;
  animation: toast-timer linear forwards;
}
@keyframes toast-timer {
  0% { width: 100%; }
  100% { width: 0%; }
}

/* ==========================================================================
   七、视觉全息呼吸核心指示球 (Orb style)
   ========================================================================== */
.status-section {
  padding: 20px;
  display: flex;
  justify-content: center;
  align-items: center;
}
.status-orb-container {
  position: relative;
  width: 160px;
  height: 160px;
  display: flex;
  justify-content: center;
  align-items: center;
}
.status-orb {
  width: 60px;
  height: 60px;
  border-radius: 50%;
  background: var(--status-orb-idle-bg);
  z-index: 3;
  transition: all 0.5s ease;
  box-shadow: 0 0 20px var(--status-orb-shadow);
}
.orb-ring {
  position: absolute;
  border: 2px solid var(--status-orb-bg);
  border-radius: 50%;
  z-index: 2;
  transition: all 0.5s ease;
}
.ring-1 {
  width: 100px;
  height: 100px;
}
.ring-2 {
  width: 140px;
  height: 140px;
}
.status-text {
  position: absolute;
  bottom: 0px;
  font-size: 0.65em;
  font-weight: 700;
  letter-spacing: 2px;
  color: var(--hint-color);
  font-family: 'Space Grotesk', sans-serif;
  z-index: 3;
}

/* 状态动画映射 */
.status-orb.idle {
  background: rgba(255, 255, 255, 0.08);
  box-shadow: 0 0 20px rgba(255, 255, 255, 0.05);
  animation: orb-breathe-slow 3s infinite ease-in-out;
}
.orb-ring.ring-1.idle { animation: ring-spin-slow 15s infinite linear; }
.orb-ring.ring-2.idle { animation: ring-spin-reverse 20s infinite linear; }

.status-orb.listening {
  background: #00d2ff;
  box-shadow: 0 0 30px rgba(0, 210, 255, 0.6);
  animation: orb-listening-pulse 1s infinite alternate ease-in-out;
}
.orb-ring.listening {
  border-color: rgba(0, 210, 255, 0.3);
}
.orb-ring.ring-1.listening {
  animation: ring-listening-expand 1.2s infinite ease-out;
}
.orb-ring.ring-2.listening {
  animation: ring-listening-expand 1.2s infinite ease-out;
  animation-delay: 0.6s;
}

.status-orb.speaking {
  background: #10b981;
  box-shadow: 0 0 30px rgba(16, 185, 129, 0.6);
  animation: orb-speaking-pulse 0.4s infinite alternate ease-in-out;
}
.orb-ring.speaking {
  border-color: rgba(16, 185, 129, 0.35);
}
.orb-ring.ring-1.speaking {
  animation: ring-speaking-wave 1s infinite linear;
}
.orb-ring.ring-2.speaking {
  animation: ring-speaking-wave-delayed 1.2s infinite linear;
}

.status-orb.thinking {
  background: #7928ca;
  box-shadow: 0 0 35px rgba(121, 40, 202, 0.6);
  animation: orb-thinking-contract 1.5s infinite alternate ease-in-out;
}
.orb-ring.thinking {
  border-color: rgba(121, 40, 202, 0.4);
}
.orb-ring.ring-1.thinking {
  border-style: dashed;
  animation: ring-spin-fast 3s infinite linear;
}
.orb-ring.ring-2.thinking {
  border-style: dotted;
  animation: ring-spin-reverse-fast 4s infinite linear;
}

.status-orb.expressing {
  background: #3b82f6;
  box-shadow: 0 0 25px rgba(59, 130, 246, 0.5);
  animation: orb-expressing 2s infinite ease-in-out;
}
.orb-ring.expressing {
  border-color: rgba(59, 130, 246, 0.25);
}
.orb-ring.ring-1.expressing { animation: ring-spin-slow 8s infinite linear; }
.orb-ring.ring-2.expressing { animation: ring-spin-reverse-slow 12s infinite linear; }

/* Orb Keyframe 动画 */
@keyframes orb-breathe-slow {
  0%, 100% { transform: scale(1); opacity: 0.5; }
  50% { transform: scale(1.08); opacity: 0.9; }
}
@keyframes orb-listening-pulse {
  0% { transform: scale(0.95); box-shadow: 0 0 15px rgba(0, 210, 255, 0.4); }
  100% { transform: scale(1.1); box-shadow: 0 0 35px rgba(0, 210, 255, 0.8); }
}
@keyframes orb-speaking-pulse {
  0% { transform: scale(0.9); }
  100% { transform: scale(1.2); }
}
@keyframes orb-thinking-contract {
  0% { transform: scale(1.05); }
  100% { transform: scale(0.85); }
}
@keyframes orb-expressing {
  0%, 100% { transform: scale(1); opacity: 0.8; }
  50% { transform: scale(1.15); opacity: 1; }
}
@keyframes ring-spin-slow {
  to { transform: rotate(360deg); }
}
@keyframes ring-spin-reverse {
  to { transform: rotate(-360deg); }
}
@keyframes ring-spin-fast {
  to { transform: rotate(360deg); }
}
@keyframes ring-spin-reverse-fast {
  to { transform: rotate(-360deg); }
}
@keyframes ring-listening-expand {
  0% { transform: scale(0.6); opacity: 0.8; }
  100% { transform: scale(1.3); opacity: 0; }
}
@keyframes ring-speaking-wave {
  0% { transform: scale(1); border-radius: 40% 60% 50% 50% / 50% 50% 50% 50%; }
  50% { transform: scale(1.15) rotate(180deg); border-radius: 60% 40% 60% 40% / 40% 60% 40% 60%; }
  100% { transform: scale(1) rotate(360deg); border-radius: 40% 60% 50% 50% / 50% 50% 50% 50%; }
}

/* ==========================================================================
   八、通用动画过渡 与 响应式分栏
   ========================================================================== */
@media (max-width: 768px) {
  .sidebar {
    display: none !important;
  }
  .left-column.mobile-hide,
  .right-column.mobile-hide {
    display: none !important;
  }
  .left-column, .right-column {
    flex: 1 !important;
    width: 100% !important;
  }
}

.fade-enter-active, .fade-leave-active {
  transition: opacity 0.25s ease;
}
.fade-enter-from, .fade-leave-to {
  opacity: 0;
}

.slide-fade-enter-active {
  transition: all 0.3s ease-out;
}
.slide-fade-leave-active {
  transition: all 0.25s cubic-bezier(1, 0.5, 0.8, 1);
}
.slide-fade-enter-from, .slide-fade-leave-to {
  transform: translateY(-20px);
  opacity: 0;
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
}
.toast-slide-leave-to {
  opacity: 0;
  transform: translateX(-50%) translateY(-20px) scale(0.95);
}

/* ==========================================================================
   九、Markdown 气泡文本排版美化样式
   ========================================================================== */
.markdown-body {
  word-wrap: break-word;
  font-size: 1em;
  line-height: 1.6;
  color: inherit;
}

.markdown-body p {
  margin-top: 0;
  margin-bottom: 8px;
}

.markdown-body p:last-child {
  margin-bottom: 0;
}

.markdown-body h1,
.markdown-body h2,
.markdown-body h3,
.markdown-body h4,
.markdown-body h5,
.markdown-body h6 {
  margin-top: 12px;
  margin-bottom: 8px;
  font-weight: 600;
  line-height: 1.25;
  color: var(--primary-color, #10b981);
}

.markdown-body h1 { font-size: 1.4em; border-bottom: 1px solid rgba(255, 255, 255, 0.15); padding-bottom: 4px; }
.markdown-body h2 { font-size: 1.25em; border-bottom: 1px solid rgba(255, 255, 255, 0.1); padding-bottom: 3px; }
.markdown-body h3 { font-size: 1.15em; }
.markdown-body h4 { font-size: 1em; }

.markdown-body ul,
.markdown-body ol {
  padding-left: 20px;
  margin-top: 0;
  margin-bottom: 8px;
}

.markdown-body li {
  margin-top: 3px;
  list-style-position: outside;
}

.markdown-body code {
  padding: 0.2em 0.4em;
  margin: 0;
  font-size: 85%;
  background-color: rgba(255, 255, 255, 0.15);
  border-radius: 4px;
  font-family: SFMono-Regular, Consolas, "Liberation Mono", Menlo, monospace;
}

.markdown-body pre {
  padding: 12px;
  overflow: auto;
  font-size: 85%;
  line-height: 1.45;
  background-color: rgba(0, 0, 0, 0.25);
  border-radius: 6px;
  margin-bottom: 8px;
  border: 1px solid rgba(255, 255, 255, 0.08);
}

.markdown-body pre code {
  padding: 0;
  margin: 0;
  font-size: 100%;
  word-break: normal;
  white-space: pre;
  background: transparent;
  border: 0;
}

.markdown-body hr {
  height: 1px;
  padding: 0;
  margin: 16px 0;
  background-color: rgba(255, 255, 255, 0.12);
  border: 0;
}

.markdown-body strong {
  font-weight: bold;
  color: #ff9f43; /* 给强调加粗字体设计一个暖色调以增强观感 */
}

.markdown-body blockquote {
  padding: 0 1em;
  color: rgba(255, 255, 255, 0.7);
  border-left: 0.25em solid #10b981;
  margin: 0 0 8px 0;
}
</style>

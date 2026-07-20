package com.agentcontrolcenter.app.core.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * 语音对话管理器 — 完整的语音对话循环
 *
 * 语音模式流程：
 * 1. 用户说话 → STT 转文字
 * 2. 文字发送给 Agent
 * 3. Agent 回复 → TTS 朗读
 * 4. 朗读完 → 自动开始下一轮监听
 */
class VoiceChatManager(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var ttsReady = false

    data class VoiceState(
        val isVoiceMode: Boolean = false,
        val isSpeaking: Boolean = false,
        val isListening: Boolean = false,
        val recognizedText: String = "",
        val partialText: String = "",
        val error: String? = null,
        val ttsLanguage: Locale = Locale.getDefault()
    )

    private val _state = MutableStateFlow(VoiceState())
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    // Callback when user finishes speaking and we have final text
    var onSpeechResult: ((String) -> Unit)? = null

    // Whether to auto-restart listening after TTS finishes
    var autoRestartListening: Boolean = true

    init {
        initTTS()
    }

    private fun initTTS() {
        // Critical 7 修复：TextToSpeech 构造必须在主线程调用，否则可能初始化失败或抛异常。
        // init 可能在非主线程触发，用 Handler post 到主线程创建 TTS。
        Handler(Looper.getMainLooper()).post {
            tts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = _state.value.ttsLanguage
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            _state.value = _state.value.copy(isSpeaking = true)
                        }

                        override fun onDone(utteranceId: String?) {
                            _state.value = _state.value.copy(isSpeaking = false)
                            // Auto-restart listening after speaking.
                            // startListening 内部已 post 到主线程，此处直接调用即可。
                            if (_state.value.isVoiceMode && autoRestartListening) {
                                startListening()
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            _state.value = _state.value.copy(isSpeaking = false)
                            if (_state.value.isVoiceMode && autoRestartListening) {
                                startListening()
                            }
                        }
                    })
                    ttsReady = true
                }
            }
        }
    }

    /**
     * 进入语音模式
     */
    fun startVoiceMode() {
        _state.value = _state.value.copy(isVoiceMode = true, error = null)
        startListening()
    }

    /**
     * 退出语音模式
     */
    fun stopVoiceMode() {
        _state.value = _state.value.copy(
            isVoiceMode = false,
            isListening = false,
            isSpeaking = false,
            recognizedText = "",
            partialText = "",
            error = null
        )
        stopListeningInternal()
        stopSpeaking()
    }

    /**
     * TTS 朗读文本
     */
    fun speak(text: String) {
        if (!ttsReady || text.isBlank()) return

        // Strip markdown for better TTS output
        val cleanText = stripMarkdown(text)

        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        tts?.speak(
            cleanText,
            TextToSpeech.QUEUE_FLUSH,
            params,
            "agent_response_${System.currentTimeMillis()}"
        )
    }

    /**
     * 停止朗读
     */
    fun stopSpeaking() {
        tts?.stop()
        _state.value = _state.value.copy(isSpeaking = false)
    }

    /**
     * 开始语音识别
     */
    fun startListening() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _state.value = _state.value.copy(error = "RECORD_AUDIO permission not granted")
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _state.value = _state.value.copy(error = "Speech recognition not available")
            return
        }

        stopListeningInternal()
        _state.value = _state.value.copy(isListening = true, error = null, partialText = "")

        // Critical 7 修复：SpeechRecognizer.createSpeechRecognizer 必须在主线程调用，
        // 否则抛 RuntimeException。onDone/onError 等 binder 回调可能从非主线程触发
        // startListening，故用 Handler post 到主线程执行创建与 startListening。
        // 回调内部再次调用 startListening 时无需额外切线程（此处已 post）。
        Handler(Looper.getMainLooper()).post {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        _state.value = _state.value.copy(isListening = true)
                    }

                    override fun onBeginningOfSpeech() {}

                    override fun onRmsChanged(rmsdB: Float) {}

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        _state.value = _state.value.copy(isListening = false)
                    }

                    override fun onError(error: Int) {
                        val errorMsg = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                            SpeechRecognizer.ERROR_AUDIO -> "Audio error"
                            SpeechRecognizer.ERROR_NETWORK -> "Network error"
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                            SpeechRecognizer.ERROR_CLIENT -> "Client error"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                            else -> "Recognition error: $error"
                        }
                        _state.value = _state.value.copy(
                            isListening = false,
                            error = errorMsg
                        )
                        // Auto-retry listening in voice mode for certain errors
                        if (_state.value.isVoiceMode &&
                            error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS &&
                            error != SpeechRecognizer.ERROR_CLIENT
                        ) {
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                if (_state.value.isVoiceMode) startListening()
                            }, 1500)
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull() ?: ""
                        _state.value = _state.value.copy(
                            isListening = false,
                            recognizedText = text,
                            partialText = ""
                        )
                        if (text.isNotEmpty()) {
                            onSpeechResult?.invoke(text)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull() ?: ""
                        _state.value = _state.value.copy(partialText = text)
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, _state.value.ttsLanguage.toString())
            }
            speechRecognizer?.startListening(intent)
        }
    }

    /**
     * 停止语音识别
     */
    fun stopListening() {
        stopListeningInternal()
        _state.value = _state.value.copy(isListening = false)
    }

    private fun stopListeningInternal() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) { }
        speechRecognizer = null
    }

    /**
     * 设置 TTS 语言
     */
    fun setLanguage(locale: Locale) {
        _state.value = _state.value.copy(ttsLanguage = locale)
        tts?.language = locale
    }

    /**
     * 设置 TTS 语速 (0.5 - 2.0)
     */
    fun setSpeechRate(rate: Float) {
        tts?.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
    }

    /**
     * 检查 TTS 是否可用
     */
    fun isTTSAvailable(): Boolean = ttsReady

    /**
     * 检查 STT 是否可用
     */
    fun isSTTAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * 清除错误状态
     */
    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    /**
     * 释放资源
     */
    fun destroy() {
        stopVoiceMode()
        tts?.stop()
        tts?.shutdown()
        tts = null
        stopListeningInternal()
    }

    /**
     * 去除 markdown 标记，使 TTS 朗读更自然
     */
    private fun stripMarkdown(text: String): String {
        return text
            .replace(Regex("#{1,6}\\s+"), "")         // headings
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1") // bold
            .replace(Regex("\\*(.+?)\\*"), "$1")        // italic
            .replace(Regex("~~(.+?)~~"), "$1")          // strikethrough
            .replace(Regex("`(.+?)`"), "$1")            // inline code
            .replace(Regex("```[\\s\\S]*?```"), "code block") // code blocks
            .replace(Regex("\\[(.+?)\\]\\(.+?\\)"), "$1") // links
            .replace(Regex("!\\[.*?\\]\\(.+?\\)"), "image") // images
            .replace(Regex("^>\\s+", RegexOption.MULTILINE), "") // blockquotes
            .replace(Regex("^[-*+]\\s+", RegexOption.MULTILINE), "") // unordered lists
            .replace(Regex("^\\d+\\.\\s+", RegexOption.MULTILINE), "") // ordered lists
            .replace(Regex("---+"), "")                 // horizontal rules
            .replace(Regex("\n{2,}"), ". ")             // multiple newlines → pause
            .trim()
    }
}

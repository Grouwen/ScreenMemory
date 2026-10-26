package com.screenmemory.app.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * 系统语音识别（优先离线）。
 *
 * 走 `SpeechRecognizer`：Android 12+ 且设备支持时用
 * `createOnDeviceSpeechRecognizer()`（明确不联网），否则用普通实例并加
 * `EXTRA_PREFER_OFFLINE` 尽量走本地引擎。
 *
 * 两个必须接受的现实：
 * 1. 国产 ROM 经常没有可用引擎，`isRecognitionAvailable()` 会返回 false —— 上层要能回退云端；
 * 2. 它自己开麦克风，所以同一时刻不能再用自己的 AudioRecord 录音。
 */
class SpeechTranscriber(private val context: Context) {

    companion object {
        private const val TAG = "SpeechTranscriber"

        /** 说完之后最多等多久，超时就当没结果 */
        private const val STOP_TIMEOUT_MS = 4000L

        fun isAvailable(context: Context): Boolean = try {
            SpeechRecognizer.isRecognitionAvailable(context)
        } catch (t: Throwable) {
            Log.w(TAG, "isRecognitionAvailable failed", t)
            false
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var onResult: ((String?) -> Unit)? = null
    private var finished = false

    /** 会话结束时拿到的结果：部分引擎会在用户松手前就因静音自动结束，结果得留着补发 */
    private var finishedResult: String? = null

    fun start(onPartial: (String) -> Unit): Boolean {
        val engine = createRecognizer()
        if (engine == null) {
            Log.w(TAG, "no recognizer instance")
            return false
        }
        recognizer = engine
        finished = false
        finishedResult = null

        engine.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(partialResults: Bundle?) {
                val text = firstResult(partialResults)
                if (!text.isNullOrBlank()) onPartial(MathSpeech.normalize(text))
            }

            override fun onResults(results: Bundle?) {
                finish(firstResult(results))
            }

            override fun onError(error: Int) {
                Log.w(TAG, "recognition error $error")
                finish(null)
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putStringArrayListExtra(
                RecognizerIntent.EXTRA_BIASING_STRINGS,
                ArrayList(MathSpeech.biasingTerms)
            )
            putExtra(
                RecognizerIntent.EXTRA_ENABLE_FORMATTING,
                RecognizerIntent.FORMATTING_OPTIMIZE_LATENCY
            )
        }
        return try {
            engine.startListening(intent)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "startListening failed", t)
            release()
            false
        }
    }

    /** 松手：停止收音并等最终结果；超时或出错回调 null，由上层决定是否回退 */
    fun stop(callback: (String?) -> Unit) {
        if (finished) {
            // 会话在松手前就已经结束：引擎可能因静音自己收尾了（结果还在），也可能直接报错。
            // 那时 onResult 还没设置，结果会被丢掉；这里必须补一次回调，
            // 否则上层永远等不到通知，面板会一直停在「正在识别…」，也不会回退云端。
            val result = finishedResult
            finishedResult = null
            callback(result)
            return
        }
        onResult = callback
        try {
            recognizer?.stopListening()
        } catch (t: Throwable) {
            Log.w(TAG, "stopListening failed", t)
            finish(null)
            return
        }
        handler.postDelayed({ finish(null) }, STOP_TIMEOUT_MS)
    }

    fun cancel() {
        onResult = null
        release()
    }

    private fun createRecognizer(): SpeechRecognizer? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
    } catch (t: Throwable) {
        Log.w(TAG, "createSpeechRecognizer failed", t)
        null
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun finish(text: String?) {
        if (finished) return
        finished = true
        finishedResult = text
        handler.removeCallbacksAndMessages(null)
        val callback = onResult
        onResult = null
        release()
        callback?.invoke(text)
    }

    private fun release() {
        try {
            recognizer?.destroy()
        } catch (_: Throwable) {
        }
        recognizer = null
    }
}

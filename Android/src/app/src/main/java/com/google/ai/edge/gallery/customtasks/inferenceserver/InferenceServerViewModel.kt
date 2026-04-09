/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.customtasks.inferenceserver

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.common.processLlmResponse
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DEFAULT_MAX_TOKEN
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.litertlm.Contents
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.BindException
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val LOG_LIMIT = 80
private const val MB_IN_KB = 1024L

data class InferenceTelemetryUiState(
  val backend: String = "",
  val processRamMb: Long = 0L,
  val availableRamMb: Long = 0L,
  val processCpuPercent: Double = 0.0,
  val requestInProgress: Boolean = false,
  val requestDurationMs: Long = 0L,
  val lastRequestDurationMs: Long = 0L,
  val timeToFirstTokenMs: Long? = null,
  val approxTokensPerSecond: Double = 0.0,
  val approxGeneratedTokens: Int = 0,
)

data class InferenceServerUiState(
  val portInput: String = "8000",
  val exposeToLocalNetwork: Boolean = false,
  val requireApiKey: Boolean = true,
  val apiKey: String = "change-me",
  val serverRunning: Boolean = false,
  val boundHost: String = "",
  val boundPort: Int = 0,
  val selectedModelName: String = "",
  val boundModelName: String = "",
  val availableModelNames: List<String> = listOf(),
  val statusMessage: String = "Server stopped",
  val reachableUrls: List<String> = listOf(),
  val activeRequests: Int = 0,
  val totalRequests: Int = 0,
  val completedRequests: Int = 0,
  val logs: List<String> = listOf(),
  val lastError: String = "",
  val telemetry: InferenceTelemetryUiState = InferenceTelemetryUiState(),
)

private data class PromptContext(val systemPrompt: String?, val prompt: String)

private data class InferenceResult(
  val response: String,
  val finishReason: String,
  val timeToFirstTokenMs: Long?,
  val durationMs: Long,
)

private sealed interface InferenceStreamEvent {
  data class Delta(val text: String) : InferenceStreamEvent

  data class Completed(val result: InferenceResult) : InferenceStreamEvent

  data class Failed(val throwable: Throwable) : InferenceStreamEvent
}

@HiltViewModel
class InferenceServerViewModel
@Inject
constructor(@ApplicationContext private val appContext: Context) : ViewModel() {
  private val gson = Gson()
  private val requestMutex = Mutex()
  private val _uiState = MutableStateFlow(InferenceServerUiState())
  val uiState = _uiState.asStateFlow()

  private var server: EmbeddedInferenceHttpServer? = null
  private var telemetryJob: Job? = null
  private var boundModel: Model? = null

  fun syncAvailableModels(models: List<Model>) {
    _uiState.update {
      it.copy(
        availableModelNames =
          models.map { model -> model.name }.distinct().sortedBy { modelName -> modelName.lowercase() }
      )
    }
  }

  fun updatePortInput(portInput: String) {
    _uiState.update { it.copy(portInput = portInput.filter { c -> c.isDigit() }.take(5)) }
  }

  fun setExposeToLocalNetwork(expose: Boolean) {
    _uiState.update { it.copy(exposeToLocalNetwork = expose) }
  }

  fun setRequireApiKey(required: Boolean) {
    _uiState.update { it.copy(requireApiKey = required) }
  }

  fun updateApiKey(apiKey: String) {
    _uiState.update { it.copy(apiKey = apiKey) }
  }

  fun onSelectedModelChanged(model: Model) {
    _uiState.update {
      it.copy(
        selectedModelName = model.name,
        telemetry =
          it.telemetry.copy(
            backend =
              model.getStringConfigValue(
                key = ConfigKeys.ACCELERATOR,
                defaultValue = Accelerator.GPU.label,
              )
          ),
      )
    }

    if (_uiState.value.serverRunning && _uiState.value.boundModelName != model.name) {
      stopServer(reason = "Stopped server because the selected model changed")
    }
  }

  fun startServer(model: Model) {
    if (_uiState.value.serverRunning) {
      appendLog("Server is already running")
      return
    }

    if (model.instance == null) {
      setError("Initialize the selected model before starting the server")
      return
    }

    val port = _uiState.value.portInput.toIntOrNull()
    if (port == null || port !in 1..65535) {
      setError("Enter a valid port between 1 and 65535")
      return
    }

    if (_uiState.value.requireApiKey && _uiState.value.apiKey.isBlank()) {
      setError("API key is required before the server can start")
      return
    }

    val bindHost = if (_uiState.value.exposeToLocalNetwork) "0.0.0.0" else "127.0.0.1"

    try {
      val newServer =
        EmbeddedInferenceHttpServer(
          bindHost = bindHost,
          port = port,
          onLog = { appendLog(it) },
          requestHandler = { request -> handleRequest(request) },
        )
      newServer.start(scope = viewModelScope)
      server = newServer
      boundModel = model

      _uiState.update {
        it.copy(
          serverRunning = true,
          boundHost = bindHost,
          boundPort = port,
          boundModelName = model.name,
          selectedModelName = model.name,
          statusMessage = "Serving ${model.name} on $bindHost:$port",
          reachableUrls = getReachableUrls(bindHost = bindHost, port = port),
          lastError = "",
          telemetry =
            it.telemetry.copy(
              backend =
                model.getStringConfigValue(
                  key = ConfigKeys.ACCELERATOR,
                  defaultValue = Accelerator.GPU.label,
                )
            ),
        )
      }
      appendLog("Started inference server for model '${model.name}'")
      startTelemetrySampler(model)
    } catch (e: BindException) {
      setError("Port $port is already in use")
    } catch (e: Exception) {
      setError(e.message ?: "Failed to start server")
    }
  }

  fun stopServer(reason: String = "Server stopped") {
    val model = boundModel
    if (requestMutex.isLocked && model != null) {
      try {
        model.runtimeHelper.stopResponse(model)
      } catch (_: Exception) {}
    }

    server?.stop()
    server = null
    telemetryJob?.cancel()
    telemetryJob = null
    boundModel = null

    _uiState.update {
      it.copy(
        serverRunning = false,
        boundHost = "",
        boundPort = 0,
        boundModelName = "",
        statusMessage = reason,
        reachableUrls = listOf(),
        activeRequests = 0,
        telemetry =
          it.telemetry.copy(
            requestInProgress = false,
            requestDurationMs = 0L,
            timeToFirstTokenMs = null,
            approxTokensPerSecond = 0.0,
            approxGeneratedTokens = 0,
          ),
      )
    }

    appendLog(reason)
  }

  override fun onCleared() {
    stopServer(reason = "Server stopped")
    super.onCleared()
  }

  private suspend fun handleRequest(request: SimpleHttpRequest): SimpleHttpResponse {
    appendLog("${request.remoteAddress} ${request.method} ${request.path}")

    if (request.method == "OPTIONS") {
      return optionsResponse()
    }

    if (request.method == "GET" && (request.path == "/health" || request.path == "/v1/health")) {
      return jsonResponse(
        mapOf(
          "status" to "ok",
          "model" to _uiState.value.boundModelName.ifBlank { null },
          "server_running" to _uiState.value.serverRunning,
        )
      )
    }

    if (!isAuthorized(request)) {
      return errorResponse(
        statusCode = 401,
        message = "Invalid or missing API key",
        type = "authentication_error",
      )
    }

    return when {
      request.method == "GET" && request.path == "/v1/models" -> handleModelsRequest()
      request.method == "GET" && request.path == "/v1/local/metrics" -> handleMetricsJsonRequest()
      request.method == "GET" && request.path == "/metrics" -> handleMetricsTextRequest()
      request.method == "POST" && request.path == "/v1/chat/completions" ->
        handleChatCompletionsRequest(request)
      else ->
        errorResponse(
          statusCode = 404,
          message = "Unknown endpoint: ${request.method} ${request.path}",
          type = "invalid_request_error",
          code = "not_found",
        )
    }
  }

  private fun handleModelsRequest(): SimpleHttpResponse {
    val modelNames = _uiState.value.availableModelNames
    if (modelNames.isEmpty()) {
      return errorResponse(
        statusCode = 503,
        message = "No downloaded LLM models are currently available",
        type = "server_error",
      )
    }

    return jsonResponse(
      OpenAiModelsResponse(data = modelNames.map { modelName -> OpenAiModelInfo(id = modelName) })
    )
  }

  private fun handleMetricsJsonRequest(): SimpleHttpResponse {
    return jsonResponse(buildMetricsSnapshot())
  }

  private fun handleMetricsTextRequest(): SimpleHttpResponse {
    return textResponse(buildPrometheusMetrics(), contentType = "text/plain; version=0.0.4; charset=utf-8")
  }

  private suspend fun handleChatCompletionsRequest(request: SimpleHttpRequest): SimpleHttpResponse {
    val model = boundModel
    if (model == null || model.instance == null) {
      return errorResponse(
        statusCode = 503,
        message = "Selected model is not initialized",
        type = "server_error",
      )
    }

    val chatRequest =
      try {
        gson.fromJson(request.body, OpenAiChatCompletionRequest::class.java)
      } catch (e: Exception) {
        return errorResponse(
          statusCode = 400,
          message = e.message ?: "Invalid JSON body",
          type = "invalid_request_error",
        )
      }

    val requestedModel = chatRequest.model?.trim().orEmpty()
    if (requestedModel.isNotEmpty() && requestedModel != model.name) {
      return errorResponse(
        statusCode = 400,
        message = "This server is currently bound to '${model.name}', not '$requestedModel'",
        type = "invalid_request_error",
        code = "model_mismatch",
      )
    }

    val promptContext =
      buildPromptContext(chatRequest.messages)
        ?: return errorResponse(
          statusCode = 400,
          message = "At least one text message is required",
          type = "invalid_request_error",
        )

    if (!requestMutex.tryLock()) {
      return errorResponse(
        statusCode = 409,
        message = "The mobile server is already processing another request",
        type = "server_error",
        code = "busy",
      )
    }

    markRequestStarted()

    val oldConfigValues = model.configValues.toMutableMap()
    val requestConfigValues = oldConfigValues.toMutableMap()
    chatRequest.temperature?.toFloat()?.let {
      requestConfigValues[ConfigKeys.TEMPERATURE.label] = it
    }
    chatRequest.topP?.toFloat()?.let { requestConfigValues[ConfigKeys.TOPP.label] = it }
    val maxTokens =
      chatRequest.maxTokens?.coerceAtLeast(1)
        ?: model.getIntConfigValue(key = ConfigKeys.MAX_TOKENS, defaultValue = DEFAULT_MAX_TOKEN)

    return if (chatRequest.stream == true) {
      handleStreamingChatCompletion(
        model = model,
        promptContext = promptContext,
        requestConfigValues = requestConfigValues,
        oldConfigValues = oldConfigValues,
        maxTokens = maxTokens,
      )
    } else {
      handleBufferedChatCompletion(
        model = model,
        promptContext = promptContext,
        requestConfigValues = requestConfigValues,
        oldConfigValues = oldConfigValues,
        maxTokens = maxTokens,
      )
    }
  }

  private suspend fun handleBufferedChatCompletion(
    model: Model,
    promptContext: PromptContext,
    requestConfigValues: MutableMap<String, Any>,
    oldConfigValues: MutableMap<String, Any>,
    maxTokens: Int,
  ): SimpleHttpResponse {
    return try {
      model.configValues = requestConfigValues
      model.runtimeHelper.resetConversation(
        model = model,
        supportImage = false,
        supportAudio = false,
        systemInstruction = promptContext.systemPrompt?.let { Contents.of(it) },
      )

      val inferenceResult =
        runInference(
          model = model,
          prompt = promptContext.prompt,
          maxTokens = maxTokens,
        )

      val promptTokens = approximateTokenCount(promptContext.prompt)
      val completionTokens = approximateTokenCount(inferenceResult.response)
      appendLog(
        "Completed request in ${inferenceResult.durationMs} ms, " +
          "TTFT=${inferenceResult.timeToFirstTokenMs ?: -1} ms, tokens=$completionTokens"
      )
      markRequestFinished(inferenceResult, completionTokens)

      jsonResponse(
        OpenAiChatCompletionResponse(
          id = "chatcmpl-${System.currentTimeMillis()}",
          created = System.currentTimeMillis() / 1000,
          model = model.name,
          choices =
            listOf(
              OpenAiChatChoice(
                index = 0,
                message = OpenAiResponseMessage(content = inferenceResult.response),
                finishReason = inferenceResult.finishReason,
              )
            ),
          usage =
            OpenAiUsage(
              promptTokens = promptTokens,
              completionTokens = completionTokens,
              totalTokens = promptTokens + completionTokens,
            ),
        )
      )
    } catch (e: Exception) {
      setError(e.message ?: "Inference request failed")
      resetRequestStateAfterFailure()
      errorResponse(
        statusCode = 500,
        message = e.message ?: "Inference request failed",
        type = "server_error",
      )
    } finally {
      model.configValues = oldConfigValues
      requestMutex.unlock()
    }
  }

  private fun handleStreamingChatCompletion(
    model: Model,
    promptContext: PromptContext,
    requestConfigValues: MutableMap<String, Any>,
    oldConfigValues: MutableMap<String, Any>,
    maxTokens: Int,
  ): SimpleHttpResponse {
    val requestId = "chatcmpl-${System.currentTimeMillis()}"
    val created = System.currentTimeMillis() / 1000

    return StreamingHttpResponse(
      statusCode = 200,
      extraHeaders =
        defaultCorsHeaders() +
          mapOf(
            "Cache-Control" to "no-cache",
            "X-Accel-Buffering" to "no",
          ),
    ) { sendChunk ->
      try {
        model.configValues = requestConfigValues
        model.runtimeHelper.resetConversation(
          model = model,
          supportImage = false,
          supportAudio = false,
          systemInstruction = promptContext.systemPrompt?.let { Contents.of(it) },
        )

        sendSseChunk(
          sendChunk,
          OpenAiChatCompletionChunk(
            id = requestId,
            created = created,
            model = model.name,
            choices =
              listOf(
                OpenAiChatChunkChoice(
                  index = 0,
                  delta = OpenAiChatDelta(role = "assistant"),
                )
              ),
          ),
        )

        val inferenceResult =
          runInferenceStreaming(
            model = model,
            prompt = promptContext.prompt,
            maxTokens = maxTokens,
            onDelta = { deltaText ->
              if (deltaText.isNotEmpty()) {
                sendSseChunk(
                  sendChunk,
                  OpenAiChatCompletionChunk(
                    id = requestId,
                    created = created,
                    model = model.name,
                    choices =
                      listOf(
                        OpenAiChatChunkChoice(
                          index = 0,
                          delta = OpenAiChatDelta(content = deltaText),
                        )
                      ),
                  ),
                )
              }
            },
          )

        val completionTokens = approximateTokenCount(inferenceResult.response)
        appendLog(
          "Completed streaming request in ${inferenceResult.durationMs} ms, " +
            "TTFT=${inferenceResult.timeToFirstTokenMs ?: -1} ms, tokens=$completionTokens"
        )
        markRequestFinished(inferenceResult, completionTokens)

        sendSseChunk(
          sendChunk,
          OpenAiChatCompletionChunk(
            id = requestId,
            created = created,
            model = model.name,
            choices =
              listOf(
                OpenAiChatChunkChoice(
                  index = 0,
                  delta = OpenAiChatDelta(),
                  finishReason = inferenceResult.finishReason,
                )
              ),
          ),
        )
        sendChunk("data: [DONE]\n\n")
      } catch (e: Exception) {
        appendLog("Streaming request failed: ${e.message}")
        setError(e.message ?: "Streaming inference request failed")
        resetRequestStateAfterFailure()
        try {
          sendSseChunk(
            sendChunk,
            OpenAiErrorResponse(
              OpenAiErrorDetail(
                message = e.message ?: "Streaming inference request failed",
                type = "server_error",
              )
            ),
          )
          sendChunk("data: [DONE]\n\n")
        } catch (_: Exception) {}
        try {
          model.runtimeHelper.stopResponse(model)
        } catch (_: Exception) {}
      } finally {
        model.configValues = oldConfigValues
        requestMutex.unlock()
      }
    }
  }

  private suspend fun runInference(
    model: Model,
    prompt: String,
    maxTokens: Int,
  ): InferenceResult {
    val requestStartedMs = SystemClock.elapsedRealtime()
    var response = ""
    var finishReason = "stop"
    var firstTokenMs: Long? = null
    var stopRequested = false

    return suspendCancellableCoroutine { continuation ->
      try {
        model.runtimeHelper.runInference(
          model = model,
          input = prompt,
          resultListener = { partialResult: String, done: Boolean, _: String? ->
            if (partialResult.isNotEmpty()) {
              if (firstTokenMs == null) {
                firstTokenMs = SystemClock.elapsedRealtime() - requestStartedMs
              }

              val nextResponse = processLlmResponse("$response$partialResult")
              response = nextResponse
              val generatedTokens = approximateTokenCount(response)
              val elapsedMs = SystemClock.elapsedRealtime() - requestStartedMs
              updateStreamingTelemetry(generatedTokens = generatedTokens, elapsedMs = elapsedMs, firstTokenMs = firstTokenMs)

              if (!stopRequested && generatedTokens >= maxTokens) {
                stopRequested = true
                finishReason = "length"
                model.runtimeHelper.stopResponse(model)
              }
            }

            if (done && continuation.isActive) {
              continuation.resume(
                InferenceResult(
                  response = response,
                  finishReason = finishReason,
                  timeToFirstTokenMs = firstTokenMs,
                  durationMs = SystemClock.elapsedRealtime() - requestStartedMs,
                )
              )
            }
          },
          cleanUpListener = {
            if (continuation.isActive) {
              continuation.resumeWithException(
                IllegalStateException("Model was cleaned up during the request")
              )
            }
          },
          onError = { message ->
            if (continuation.isActive) {
              continuation.resumeWithException(IllegalStateException(message))
            }
          },
          coroutineScope = viewModelScope,
        )
      } catch (e: Exception) {
        continuation.resumeWithException(e)
      }

      continuation.invokeOnCancellation {
        try {
          model.runtimeHelper.stopResponse(model)
        } catch (_: Exception) {}
      }
    }
  }

  private suspend fun runInferenceStreaming(
    model: Model,
    prompt: String,
    maxTokens: Int,
    onDelta: suspend (String) -> Unit,
  ): InferenceResult {
    val requestStartedMs = SystemClock.elapsedRealtime()
    val events = Channel<InferenceStreamEvent>(Channel.UNLIMITED)
    var response = ""
    var finishReason = "stop"
    var firstTokenMs: Long? = null
    var stopRequested = false
    var terminalEventSent = false

    fun emitTerminal(event: InferenceStreamEvent) {
      if (!terminalEventSent) {
        terminalEventSent = true
        events.trySend(event)
        events.close()
      }
    }

    try {
      model.runtimeHelper.runInference(
        model = model,
        input = prompt,
        resultListener = { partialResult: String, done: Boolean, _: String? ->
          if (partialResult.isNotEmpty()) {
            if (firstTokenMs == null) {
              firstTokenMs = SystemClock.elapsedRealtime() - requestStartedMs
            }

            val nextResponse = processLlmResponse("$response$partialResult")
            val deltaText = nextResponse.removePrefix(response)
            response = nextResponse
            val generatedTokens = approximateTokenCount(response)
            val elapsedMs = SystemClock.elapsedRealtime() - requestStartedMs
            updateStreamingTelemetry(generatedTokens = generatedTokens, elapsedMs = elapsedMs, firstTokenMs = firstTokenMs)
            if (deltaText.isNotEmpty()) {
              events.trySend(InferenceStreamEvent.Delta(deltaText))
            }

            if (!stopRequested && generatedTokens >= maxTokens) {
              stopRequested = true
              finishReason = "length"
              model.runtimeHelper.stopResponse(model)
            }
          }

          if (done) {
            emitTerminal(
              InferenceStreamEvent.Completed(
                InferenceResult(
                  response = response,
                  finishReason = finishReason,
                  timeToFirstTokenMs = firstTokenMs,
                  durationMs = SystemClock.elapsedRealtime() - requestStartedMs,
                )
              )
            )
          }
        },
        cleanUpListener = {
          emitTerminal(
            InferenceStreamEvent.Failed(
              IllegalStateException("Model was cleaned up during the request")
            )
          )
        },
        onError = { message ->
          emitTerminal(InferenceStreamEvent.Failed(IllegalStateException(message)))
        },
        coroutineScope = viewModelScope,
      )
    } catch (e: Exception) {
      emitTerminal(InferenceStreamEvent.Failed(e))
    }

    try {
      for (event in events) {
        when (event) {
          is InferenceStreamEvent.Delta -> onDelta(event.text)
          is InferenceStreamEvent.Completed -> return event.result
          is InferenceStreamEvent.Failed -> throw event.throwable
        }
      }
    } finally {
      try {
        model.runtimeHelper.stopResponse(model)
      } catch (_: Exception) {}
    }

    throw IllegalStateException("Streaming request ended unexpectedly")
  }

  private fun startTelemetrySampler(model: Model) {
    telemetryJob?.cancel()
    telemetryJob =
      viewModelScope.launch(Dispatchers.Default) {
        var lastCpuTime = android.os.Process.getElapsedCpuTime()
        var lastWallTime = SystemClock.elapsedRealtime()

        while (_uiState.value.serverRunning) {
          delay(1000)

          val activityManager =
            appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
          val memoryInfo = ActivityManager.MemoryInfo()
          activityManager?.getMemoryInfo(memoryInfo)

          val processMemoryInfo = Debug.MemoryInfo()
          Debug.getMemoryInfo(processMemoryInfo)
          val processRamMb = processMemoryInfo.totalPss / MB_IN_KB
          val availableRamMb = memoryInfo.availMem / (1024 * 1024)

          val cpuTime = android.os.Process.getElapsedCpuTime()
          val wallTime = SystemClock.elapsedRealtime()
          val cpuDelta = cpuTime - lastCpuTime
          val wallDelta = wallTime - lastWallTime
          val cpuPercent =
            if (wallDelta > 0) {
              ((cpuDelta.toDouble() / wallDelta) /
                Runtime.getRuntime().availableProcessors().coerceAtLeast(1)) * 100.0
            } else {
              0.0
            }

          _uiState.update {
            it.copy(
              telemetry =
                it.telemetry.copy(
                  backend =
                    model.getStringConfigValue(
                      key = ConfigKeys.ACCELERATOR,
                      defaultValue = Accelerator.GPU.label,
                    ),
                  processRamMb = processRamMb,
                  availableRamMb = availableRamMb,
                  processCpuPercent = cpuPercent.coerceAtLeast(0.0),
                )
            )
          }

          lastCpuTime = cpuTime
          lastWallTime = wallTime
        }
      }
  }

  private fun isAuthorized(request: SimpleHttpRequest): Boolean {
    if (!_uiState.value.requireApiKey) {
      return true
    }

    val expectedApiKey = _uiState.value.apiKey.trim()
    if (expectedApiKey.isEmpty()) {
      return false
    }

    val authHeader = request.headers["authorization"].orEmpty()
    if (authHeader.startsWith("Bearer ", ignoreCase = true)) {
      return authHeader.substringAfter(' ').trim() == expectedApiKey
    }

    return request.headers["x-api-key"].orEmpty().trim() == expectedApiKey
  }

  private fun buildPromptContext(messages: List<OpenAiChatMessage>): PromptContext? {
    if (messages.isEmpty()) {
      return null
    }

    val systemPrompt =
      messages
        .filter { it.role.equals("system", ignoreCase = true) }
        .mapNotNull { extractMessageText(it) }
        .filter { it.isNotBlank() }
        .joinToString("\n\n")
        .ifBlank { null }

    val promptMessages =
      messages
        .filterNot { it.role.equals("system", ignoreCase = true) }
        .mapNotNull { message ->
          val text = extractMessageText(message)
          if (text.isNullOrBlank()) {
            null
          } else {
            message.role.lowercase() to text
          }
        }

    if (promptMessages.isEmpty()) {
      return null
    }

    val prompt =
      if (promptMessages.size == 1 && promptMessages[0].first == "user") {
        promptMessages[0].second
      } else {
        buildString {
          for ((role, text) in promptMessages) {
            append(role.uppercase())
            append(": ")
            append(text)
            append("\n\n")
          }
          append("ASSISTANT:")
        }
      }

    return PromptContext(systemPrompt = systemPrompt, prompt = prompt)
  }

  private fun extractMessageText(message: OpenAiChatMessage): String? {
    val content = message.content ?: return null
    if (content.isJsonNull) {
      return null
    }
    if (content.isJsonPrimitive) {
      return content.asString
    }
    if (content.isJsonArray) {
      val parts =
        content.asJsonArray.mapNotNull { item ->
          if (!item.isJsonObject) {
            return@mapNotNull null
          }
          val obj = item.asJsonObject
          val type = obj.get("type")?.asString.orEmpty()
          when (type) {
            "text" -> obj.get("text")?.asString
            "input_text" -> obj.get("text")?.asString ?: obj.get("input_text")?.asString
            else -> null
          }
        }
      return parts.joinToString("\n").ifBlank { null }
    }
    return content.toString()
  }

  private fun approximateTokenCount(text: String): Int {
    return text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.size
  }

  private fun jsonResponse(payload: Any): SimpleHttpResponse {
    return BufferedHttpResponse(
      statusCode = 200,
      body = gson.toJson(payload),
      extraHeaders = defaultCorsHeaders(),
    )
  }

  private fun textResponse(body: String, contentType: String): SimpleHttpResponse {
    return BufferedHttpResponse(
      statusCode = 200,
      body = body,
      contentType = contentType,
      extraHeaders = defaultCorsHeaders(),
    )
  }

  private fun errorResponse(
    statusCode: Int,
    message: String,
    type: String,
    code: String? = null,
  ): SimpleHttpResponse {
    return BufferedHttpResponse(
      statusCode = statusCode,
      body = gson.toJson(OpenAiErrorResponse(OpenAiErrorDetail(message, type, code))),
      extraHeaders = defaultCorsHeaders(),
    )
  }

  private fun optionsResponse(): SimpleHttpResponse {
    return BufferedHttpResponse(
      statusCode = 200,
      body = "",
      contentType = "text/plain; charset=utf-8",
      extraHeaders = defaultCorsHeaders(),
    )
  }

  private fun defaultCorsHeaders(): Map<String, String> {
    return mapOf(
      "Access-Control-Allow-Origin" to "*",
      "Access-Control-Allow-Headers" to "Authorization, Content-Type, X-API-Key",
      "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
    )
  }

  private fun buildMetricsSnapshot(): Map<String, Any?> {
    val state = _uiState.value
    val telemetry = state.telemetry
    return mapOf(
      "server_running" to state.serverRunning,
      "bound_host" to state.boundHost,
      "bound_port" to state.boundPort,
      "selected_model" to state.selectedModelName,
      "bound_model" to state.boundModelName,
      "available_models" to state.availableModelNames,
      "active_requests" to state.activeRequests,
      "total_requests" to state.totalRequests,
      "completed_requests" to state.completedRequests,
      "backend" to telemetry.backend,
      "process_ram_mb" to telemetry.processRamMb,
      "available_ram_mb" to telemetry.availableRamMb,
      "process_cpu_percent" to telemetry.processCpuPercent,
      "request_in_progress" to telemetry.requestInProgress,
      "request_duration_ms" to telemetry.requestDurationMs,
      "last_request_duration_ms" to telemetry.lastRequestDurationMs,
      "time_to_first_token_ms" to telemetry.timeToFirstTokenMs,
      "approx_tokens_per_second" to telemetry.approxTokensPerSecond,
      "approx_generated_tokens" to telemetry.approxGeneratedTokens,
    )
  }

  private fun buildPrometheusMetrics(): String {
    val state = _uiState.value
    val telemetry = state.telemetry
    val modelLabel = escapePrometheusLabel(state.boundModelName.ifBlank { "none" })
    val backendLabel = escapePrometheusLabel(telemetry.backend.ifBlank { "unknown" })
    val hostLabel = escapePrometheusLabel(state.boundHost.ifBlank { "none" })
    return buildString {
      appendLine("inferedge_server_running ${if (state.serverRunning) 1 else 0}")
      appendLine("inferedge_active_requests ${state.activeRequests}")
      appendLine("inferedge_total_requests ${state.totalRequests}")
      appendLine("inferedge_completed_requests ${state.completedRequests}")
      appendLine("inferedge_process_ram_mb ${telemetry.processRamMb}")
      appendLine("inferedge_available_ram_mb ${telemetry.availableRamMb}")
      appendLine("inferedge_process_cpu_percent ${telemetry.processCpuPercent}")
      appendLine("inferedge_request_in_progress ${if (telemetry.requestInProgress) 1 else 0}")
      appendLine("inferedge_request_duration_ms ${telemetry.requestDurationMs}")
      appendLine("inferedge_last_request_duration_ms ${telemetry.lastRequestDurationMs}")
      appendLine("inferedge_time_to_first_token_ms ${telemetry.timeToFirstTokenMs ?: 0}")
      appendLine("inferedge_tokens_per_second ${telemetry.approxTokensPerSecond}")
      appendLine("inferedge_generated_tokens ${telemetry.approxGeneratedTokens}")
      appendLine(
        "inferedge_info{model=\"$modelLabel\",backend=\"$backendLabel\",host=\"$hostLabel\",port=\"${state.boundPort}\"} 1"
      )
    }
  }

  private fun escapePrometheusLabel(value: String): String {
    return value.replace("\\", "\\\\").replace("\"", "\\\"")
  }

  private suspend fun sendSseChunk(sendChunk: suspend (String) -> Unit, payload: Any) {
    sendChunk("data: ${gson.toJson(payload)}\n\n")
  }

  private fun markRequestStarted() {
    _uiState.update {
      it.copy(
        activeRequests = 1,
        totalRequests = it.totalRequests + 1,
        lastError = "",
        telemetry =
          it.telemetry.copy(
            requestInProgress = true,
            requestDurationMs = 0L,
            timeToFirstTokenMs = null,
            approxTokensPerSecond = 0.0,
            approxGeneratedTokens = 0,
          ),
      )
    }
  }

  private fun markRequestFinished(inferenceResult: InferenceResult, completionTokens: Int) {
    _uiState.update {
      it.copy(
        activeRequests = 0,
        completedRequests = it.completedRequests + 1,
        telemetry =
          it.telemetry.copy(
            requestInProgress = false,
            requestDurationMs = 0L,
            lastRequestDurationMs = inferenceResult.durationMs,
            timeToFirstTokenMs = inferenceResult.timeToFirstTokenMs,
            approxTokensPerSecond =
              if (inferenceResult.durationMs <= 0L) {
                0.0
              } else {
                completionTokens * 1000.0 / inferenceResult.durationMs
              },
            approxGeneratedTokens = completionTokens,
          ),
      )
    }
  }

  private fun resetRequestStateAfterFailure() {
    _uiState.update {
      it.copy(
        activeRequests = 0,
        telemetry =
          it.telemetry.copy(
            requestInProgress = false,
            requestDurationMs = 0L,
          ),
      )
    }
  }

  private fun updateStreamingTelemetry(generatedTokens: Int, elapsedMs: Long, firstTokenMs: Long?) {
    _uiState.update {
      it.copy(
        telemetry =
          it.telemetry.copy(
            requestInProgress = true,
            requestDurationMs = elapsedMs,
            timeToFirstTokenMs = firstTokenMs,
            approxGeneratedTokens = generatedTokens,
            approxTokensPerSecond =
              if (elapsedMs <= 0L) {
                0.0
              } else {
                generatedTokens * 1000.0 / elapsedMs
              },
          )
      )
    }
  }

  private fun setError(message: String) {
    _uiState.update { it.copy(lastError = message, statusMessage = message) }
    appendLog(message)
  }

  private fun appendLog(message: String) {
    _uiState.update { currentState ->
      val updatedLogs = (currentState.logs + "[${System.currentTimeMillis()}] $message").takeLast(LOG_LIMIT)
      currentState.copy(logs = updatedLogs)
    }
  }

  private fun getReachableUrls(bindHost: String, port: Int): List<String> {
    if (bindHost == "127.0.0.1") {
      return listOf("http://127.0.0.1:$port/v1")
    }

    val urls = mutableListOf<String>()
    val interfaces = NetworkInterface.getNetworkInterfaces() ?: return listOf("http://$bindHost:$port/v1")
    while (interfaces.hasMoreElements()) {
      val networkInterface = interfaces.nextElement()
      if (!networkInterface.isUp || networkInterface.isLoopback) {
        continue
      }
      val addresses = networkInterface.inetAddresses
      while (addresses.hasMoreElements()) {
        val address = addresses.nextElement()
        if (address is Inet4Address && !address.isLoopbackAddress) {
          urls.add("http://${address.hostAddress}:$port/v1")
        }
      }
    }

    return if (urls.isEmpty()) listOf("http://$bindHost:$port/v1") else urls.distinct()
  }
}

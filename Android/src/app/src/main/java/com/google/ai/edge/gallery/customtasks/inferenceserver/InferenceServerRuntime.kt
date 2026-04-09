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

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class SimpleHttpRequest(
  val method: String,
  val path: String,
  val headers: Map<String, String>,
  val body: String,
  val remoteAddress: String,
)

sealed interface SimpleHttpResponse

data class BufferedHttpResponse(
  val statusCode: Int,
  val body: String,
  val contentType: String = "application/json; charset=utf-8",
  val extraHeaders: Map<String, String> = emptyMap(),
) : SimpleHttpResponse

data class StreamingHttpResponse(
  val statusCode: Int,
  val contentType: String = "text/event-stream; charset=utf-8",
  val extraHeaders: Map<String, String> = emptyMap(),
  val bodyWriter: suspend (sendChunk: suspend (String) -> Unit) -> Unit,
) : SimpleHttpResponse

data class OpenAiModelInfo(
  val id: String,
  val `object`: String = "model",
  val created: Long = 0L,
  @SerializedName("owned_by") val ownedBy: String = "local",
)

data class OpenAiModelsResponse(
  val `object`: String = "list",
  val data: List<OpenAiModelInfo>,
)

data class OpenAiChatMessage(
  val role: String,
  val content: JsonElement? = null,
)

data class OpenAiChatCompletionRequest(
  val model: String? = null,
  val messages: List<OpenAiChatMessage> = listOf(),
  val temperature: Double? = null,
  @SerializedName("top_p") val topP: Double? = null,
  @SerializedName("max_tokens") val maxTokens: Int? = null,
  val stream: Boolean? = null,
)

data class OpenAiResponseMessage(val role: String = "assistant", val content: String)

data class OpenAiChatChoice(
  val index: Int,
  val message: OpenAiResponseMessage,
  @SerializedName("finish_reason") val finishReason: String,
)

data class OpenAiUsage(
  @SerializedName("prompt_tokens") val promptTokens: Int,
  @SerializedName("completion_tokens") val completionTokens: Int,
  @SerializedName("total_tokens") val totalTokens: Int,
)

data class OpenAiChatCompletionResponse(
  val id: String,
  val `object`: String = "chat.completion",
  val created: Long,
  val model: String,
  val choices: List<OpenAiChatChoice>,
  val usage: OpenAiUsage,
)

data class OpenAiChatDelta(
  val role: String? = null,
  val content: String? = null,
)

data class OpenAiChatChunkChoice(
  val index: Int,
  val delta: OpenAiChatDelta,
  @SerializedName("finish_reason") val finishReason: String? = null,
)

data class OpenAiChatCompletionChunk(
  val id: String,
  val `object`: String = "chat.completion.chunk",
  val created: Long,
  val model: String,
  val choices: List<OpenAiChatChunkChoice>,
)

data class OpenAiErrorDetail(
  val message: String,
  val type: String,
  val code: String? = null,
)

data class OpenAiErrorResponse(val error: OpenAiErrorDetail)

class EmbeddedInferenceHttpServer(
  private val bindHost: String,
  private val port: Int,
  private val onLog: (String) -> Unit,
  private val requestHandler: suspend (SimpleHttpRequest) -> SimpleHttpResponse,
) {
  private var serverSocket: ServerSocket? = null
  private var acceptJob: Job? = null

  fun start(scope: CoroutineScope) {
    val socket = ServerSocket()
    socket.reuseAddress = true
    socket.bind(InetSocketAddress(InetAddress.getByName(bindHost), port))
    serverSocket = socket

    acceptJob =
      scope.launch(Dispatchers.IO) {
        onLog("Listening on $bindHost:${socket.localPort}")
        while (true) {
          val client = try {
            socket.accept()
          } catch (_: SocketException) {
            break
          }

          launch(Dispatchers.IO) { handleClient(client) }
        }
      }
  }

  fun stop() {
    acceptJob?.cancel()
    acceptJob = null
    try {
      serverSocket?.close()
    } catch (_: Exception) {}
    serverSocket = null
  }

  private suspend fun handleClient(client: Socket) {
    client.soTimeout = 30_000
    client.use { socket ->
      try {
        val inputStream = BufferedInputStream(socket.getInputStream())
        val requestLine = readLine(inputStream)
        if (requestLine.isNullOrBlank()) {
          return
        }

        val requestParts = requestLine.split(" ")
        if (requestParts.size < 2) {
          writeResponse(
            socket = socket,
            response = BufferedHttpResponse(statusCode = 400, body = "{\"error\":\"Malformed request\"}"),
          )
          return
        }

        val headers = mutableMapOf<String, String>()
        while (true) {
          val line = readLine(inputStream) ?: break
          if (line.isBlank()) {
            break
          }
          val colonIndex = line.indexOf(':')
          if (colonIndex > 0) {
            val key = line.substring(0, colonIndex).trim().lowercase()
            val value = line.substring(colonIndex + 1).trim()
            headers[key] = value
          }
        }

        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body =
          if (contentLength > 0) {
            val bytes = ByteArray(contentLength)
            var totalRead = 0
            while (totalRead < contentLength) {
              val read = inputStream.read(bytes, totalRead, contentLength - totalRead)
              if (read < 0) {
                break
              }
              totalRead += read
            }
            String(bytes, 0, totalRead, Charsets.UTF_8)
          } else {
            ""
          }

        val request =
          SimpleHttpRequest(
            method = requestParts[0].uppercase(),
            path = requestParts[1].substringBefore('?'),
            headers = headers,
            body = body,
            remoteAddress = socket.inetAddress?.hostAddress ?: "unknown",
          )

        val response = requestHandler(request)
        writeResponse(socket = socket, response = response)
      } catch (e: Exception) {
        onLog("Client request failed: ${e.message}")
        try {
          writeResponse(
            socket = socket,
            response =
              BufferedHttpResponse(
                statusCode = 500,
                body = "{\"error\":\"Internal server error\"}",
              ),
          )
        } catch (_: Exception) {}
      }
    }
  }

  private suspend fun writeResponse(socket: Socket, response: SimpleHttpResponse) {
    when (response) {
      is BufferedHttpResponse -> writeBufferedResponse(socket, response)
      is StreamingHttpResponse -> writeStreamingResponse(socket, response)
    }
  }

  private fun writeBufferedResponse(socket: Socket, response: BufferedHttpResponse) {
    val bodyBytes = response.body.toByteArray(Charsets.UTF_8)
    val outputStream = socket.getOutputStream()
    writeAscii(outputStream, "HTTP/1.1 ${response.statusCode} ${getReasonPhrase(response.statusCode)}\r\n")
    writeAscii(outputStream, "Content-Type: ${response.contentType}\r\n")
    writeAscii(outputStream, "Content-Length: ${bodyBytes.size}\r\n")
    writeAscii(outputStream, "Connection: close\r\n")
    for ((key, value) in response.extraHeaders) {
      writeAscii(outputStream, "$key: $value\r\n")
    }
    writeAscii(outputStream, "\r\n")
    outputStream.write(bodyBytes)
    outputStream.flush()
  }

  private suspend fun writeStreamingResponse(socket: Socket, response: StreamingHttpResponse) {
    val outputStream = socket.getOutputStream()
    writeAscii(outputStream, "HTTP/1.1 ${response.statusCode} ${getReasonPhrase(response.statusCode)}\r\n")
    writeAscii(outputStream, "Content-Type: ${response.contentType}\r\n")
    writeAscii(outputStream, "Transfer-Encoding: chunked\r\n")
    writeAscii(outputStream, "Connection: close\r\n")
    for ((key, value) in response.extraHeaders) {
      writeAscii(outputStream, "$key: $value\r\n")
    }
    writeAscii(outputStream, "\r\n")
    outputStream.flush()

    response.bodyWriter { chunk -> writeChunk(outputStream, chunk) }
    writeAscii(outputStream, "0\r\n\r\n")
    outputStream.flush()
  }

  private fun writeChunk(outputStream: OutputStream, chunk: String) {
    val bytes = chunk.toByteArray(Charsets.UTF_8)
    writeAscii(outputStream, bytes.size.toString(16))
    writeAscii(outputStream, "\r\n")
    outputStream.write(bytes)
    writeAscii(outputStream, "\r\n")
    outputStream.flush()
  }

  private fun writeAscii(outputStream: OutputStream, value: String) {
    outputStream.write(value.toByteArray(Charsets.UTF_8))
  }

  private fun readLine(inputStream: BufferedInputStream): String? {
    val bytes = mutableListOf<Byte>()
    while (true) {
      val nextByte = inputStream.read()
      if (nextByte < 0) {
        return if (bytes.isEmpty()) null else bytes.toByteArray().toString(Charsets.UTF_8)
      }
      if (nextByte == '\n'.code) {
        break
      }
      if (nextByte != '\r'.code) {
        bytes.add(nextByte.toByte())
      }
    }
    return bytes.toByteArray().toString(Charsets.UTF_8)
  }

  private fun getReasonPhrase(statusCode: Int): String {
    return when (statusCode) {
      200 -> "OK"
      400 -> "Bad Request"
      401 -> "Unauthorized"
      404 -> "Not Found"
      405 -> "Method Not Allowed"
      409 -> "Conflict"
      500 -> "Internal Server Error"
      503 -> "Service Unavailable"
      else -> "OK"
    }
  }
}

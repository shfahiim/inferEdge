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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

@Composable
fun InferenceServerScreen(
  modelManagerViewModel: ModelManagerViewModel,
  bottomPadding: Dp,
  setAppBarControlsDisabled: (Boolean) -> Unit,
  viewModel: InferenceServerViewModel = hiltViewModel(),
) {
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val selectedModel = modelManagerUiState.selectedModel
  val uiState by viewModel.uiState.collectAsState()
  val modelInitialized = modelManagerUiState.isModelInitialized(model = selectedModel)

  LaunchedEffect(selectedModel.name, modelManagerUiState.configValuesUpdateTrigger) {
    viewModel.onSelectedModelChanged(selectedModel)
  }

  LaunchedEffect(modelManagerUiState.modelDownloadStatus, modelManagerUiState.modelImportingUpdateTrigger) {
    viewModel.syncAvailableModels(modelManagerViewModel.getAllDownloadedModels())
  }

  LaunchedEffect(uiState.serverRunning) { setAppBarControlsDisabled(uiState.serverRunning) }

  DisposableEffect(Unit) {
    onDispose {
      setAppBarControlsDisabled(false)
      viewModel.stopServer(reason = "Server stopped because the screen was closed")
    }
  }

  Column(
    modifier =
      Modifier.fillMaxSize()
        .background(MaterialTheme.colorScheme.surface)
        .verticalScroll(rememberScrollState())
        .imePadding()
        .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = bottomPadding + 24.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text("Inference Server", style = MaterialTheme.typography.headlineSmall)
      Text(
        "Serve the selected on-device model over an OpenAI-compatible HTTP API from this device.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 1.dp) {
      Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Selected model", style = MaterialTheme.typography.titleMedium)
        Text(selectedModel.name, fontWeight = FontWeight.SemiBold)
        if (uiState.availableModelNames.isNotEmpty()) {
          Text(
            "Available models: ${uiState.availableModelNames.joinToString()}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
          )
        }
        Text(
          "Backend: ${uiState.telemetry.backend.ifBlank { "Unknown" }}",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          "Streaming: supported via SSE on /v1/chat/completions",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodySmall,
        )
        Text(
          if (modelInitialized) {
            "Model is initialized and ready to serve requests."
          } else {
            "Waiting for the model to finish initializing."
          },
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!modelInitialized) {
          Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            CircularProgressIndicator(
              modifier = Modifier.size(18.dp),
              strokeWidth = 2.dp,
              trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Text("Model preparation in progress")
          }
        }
      }
    }

    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 1.dp) {
      Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Server settings", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
          value = uiState.portInput,
          onValueChange = viewModel::updatePortInput,
          label = { Text("Port") },
          enabled = !uiState.serverRunning,
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )

        SettingSwitchRow(
          title = "Expose to local network",
          description = "Bind to 0.0.0.0 so other devices on your LAN can reach it.",
          checked = uiState.exposeToLocalNetwork,
          enabled = !uiState.serverRunning,
          onCheckedChange = viewModel::setExposeToLocalNetwork,
        )

        SettingSwitchRow(
          title = "Require API key",
          description = "Recommended whenever the server is reachable from another device.",
          checked = uiState.requireApiKey,
          enabled = !uiState.serverRunning,
          onCheckedChange = viewModel::setRequireApiKey,
        )

        if (uiState.requireApiKey) {
          OutlinedTextField(
            value = uiState.apiKey,
            onValueChange = viewModel::updateApiKey,
            label = { Text("API key") },
            enabled = !uiState.serverRunning,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
          )
        }

        if (uiState.exposeToLocalNetwork && !uiState.requireApiKey) {
          Text(
            "Warning: this will expose the model to your LAN without authentication.",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
          )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          Button(onClick = { viewModel.startServer(selectedModel) }, enabled = modelInitialized && !uiState.serverRunning) {
            Text("Start server")
          }
          Button(onClick = { viewModel.stopServer() }, enabled = uiState.serverRunning) {
            Text("Stop server")
          }
        }
      }
    }

    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 1.dp) {
      Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Connection info", style = MaterialTheme.typography.titleMedium)
        Text(uiState.statusMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (uiState.reachableUrls.isNotEmpty()) {
          SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
              for (url in uiState.reachableUrls) {
                Text(url, fontFamily = FontFamily.Monospace)
              }
            }
          }
          Text(
            "Set your OpenAI client base URL to one of the endpoints above. Extra endpoints: /v1/models, /v1/local/metrics, /metrics, /health.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        if (uiState.lastError.isNotBlank()) {
          Text(uiState.lastError, color = MaterialTheme.colorScheme.error)
        }
      }
    }

    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 1.dp) {
      Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Live telemetry", style = MaterialTheme.typography.titleMedium)
        MetricRow(label = "Active requests", value = uiState.activeRequests.toString())
        MetricRow(label = "Total requests", value = uiState.totalRequests.toString())
        MetricRow(label = "Completed requests", value = uiState.completedRequests.toString())
        MetricRow(label = "Process RAM", value = "${uiState.telemetry.processRamMb} MB")
        MetricRow(label = "Available RAM", value = "${uiState.telemetry.availableRamMb} MB")
        MetricRow(
          label = "Process CPU",
          value = String.format("%.1f%%", uiState.telemetry.processCpuPercent),
        )
        MetricRow(
          label = "Request running",
          value = if (uiState.telemetry.requestInProgress) "Yes" else "No",
        )
        MetricRow(label = "TTFT", value = uiState.telemetry.timeToFirstTokenMs?.let { "$it ms" } ?: "—")
        MetricRow(label = "Current request", value = "${uiState.telemetry.requestDurationMs} ms")
        MetricRow(label = "Last request", value = "${uiState.telemetry.lastRequestDurationMs} ms")
        MetricRow(
          label = "Approx tokens/sec",
          value = String.format("%.2f", uiState.telemetry.approxTokensPerSecond),
        )
        MetricRow(label = "Approx generated tokens", value = uiState.telemetry.approxGeneratedTokens.toString())
      }
    }

    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 1.dp) {
      Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Request examples", style = MaterialTheme.typography.titleMedium)
        SelectionContainer {
          Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
              text =
                "curl ${if (uiState.requireApiKey) "-H \"Authorization: Bearer ${uiState.apiKey}\" " else ""}" +
                  "-H \"Content-Type: application/json\" " +
                  "-d '{\"model\":\"${selectedModel.name}\",\"messages\":[{\"role\":\"user\",\"content\":\"Hello from my laptop\"}]}' " +
                  "${uiState.reachableUrls.firstOrNull() ?: "http://127.0.0.1:${uiState.portInput}/v1"}/chat/completions",
              fontFamily = FontFamily.Monospace,
              style = MaterialTheme.typography.bodySmall,
            )
            Text(
              text =
                "curl ${if (uiState.requireApiKey) "-H \"Authorization: Bearer ${uiState.apiKey}\" " else ""}" +
                  "-H \"Content-Type: application/json\" " +
                  "-N -d '{\"model\":\"${selectedModel.name}\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"Tell me a story\"}]}' " +
                  "${uiState.reachableUrls.firstOrNull() ?: "http://127.0.0.1:${uiState.portInput}/v1"}/chat/completions",
              fontFamily = FontFamily.Monospace,
              style = MaterialTheme.typography.bodySmall,
            )
            Text(
              text =
                "curl ${if (uiState.requireApiKey) "-H \"Authorization: Bearer ${uiState.apiKey}\" " else ""}" +
                  "${uiState.reachableUrls.firstOrNull() ?: "http://127.0.0.1:${uiState.portInput}/v1"}/local/metrics",
              fontFamily = FontFamily.Monospace,
              style = MaterialTheme.typography.bodySmall,
            )
          }
        }
      }
    }

    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 1.dp) {
      Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Logs", style = MaterialTheme.typography.titleMedium)
        if (uiState.logs.isEmpty()) {
          Text("No logs yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
          SelectionContainer {
            Column(
              modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              for ((index, line) in uiState.logs.reversed().withIndex()) {
                Text(
                  line,
                  fontFamily = FontFamily.Monospace,
                  style = MaterialTheme.typography.bodySmall,
                )
                if (index < uiState.logs.size - 1) {
                  HorizontalDivider()
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun MetricRow(label: String, value: String) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(value, fontWeight = FontWeight.Medium)
  }
}

@Composable
private fun SettingSwitchRow(
  title: String,
  description: String,
  checked: Boolean,
  enabled: Boolean,
  onCheckedChange: (Boolean) -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(title, fontWeight = FontWeight.Medium)
      Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
  }
}

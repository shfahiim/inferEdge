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

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.runtime.Composable
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskData
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.runtime.runtimeHelper
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

class InferenceServerTask @Inject constructor() : CustomTask {
  override val task: Task =
    Task(
      id = BuiltInTaskId.LLM_INFERENCE_SERVER,
      label = "Inference Server",
      category = Category.LLM,
      icon = Icons.Outlined.SettingsEthernet,
      description =
        "Serve a selected on-device LLM over an OpenAI-compatible HTTP API with live runtime telemetry.",
      shortDescription = "Serve an LLM over HTTP",
      models = mutableListOf(),
      experimental = true,
      useThemeColor = true,
    )

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: (String) -> Unit,
  ) {
    model.runtimeHelper.initialize(
      context = context,
      model = model,
      supportImage = false,
      supportAudio = false,
      onDone = onDone,
      coroutineScope = coroutineScope,
    )
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    model.runtimeHelper.cleanUp(model = model, onDone = onDone)
  }

  @Composable
  override fun MainScreen(data: Any) {
    val myData = data as CustomTaskData
    InferenceServerScreen(
      modelManagerViewModel = myData.modelManagerViewModel,
      bottomPadding = myData.bottomPadding,
      setAppBarControlsDisabled = myData.setAppBarControlsDisabled,
    )
  }
}

@Module
@InstallIn(SingletonComponent::class)
internal object InferenceServerTaskModule {
  @Provides
  @IntoSet
  fun provideTask(): CustomTask {
    return InferenceServerTask()
  }
}

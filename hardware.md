# Hardware / backend inference mechanism in this repo

This document explains how the Android app decides between `CPU`, `GPU`, and `NPU`, what it actually detects itself, and what is delegated to the underlying runtime.

## TL;DR

- This app does **not** implement low-level GPU/NPU probing itself.
- The app mainly does **static gating + backend selection**, then asks `LiteRT-LM` to initialize that backend.
- The **real “does this backend actually work on this device?” check happens inside** `Engine.initialize()` from `com.google.ai.edge.litertlm`.
- If backend init fails, the app surfaces the error; it does **not** automatically fall back from `GPU`/`NPU` to `CPU`.
- Current shipped allowlists in this repo expose **CPU/GPU models only**; code paths for `NPU` exist, but no bundled allowlist currently uses them.

## 1. Runtime stack used here

The inference path for LLM features is:

1. UI/model config picks an accelerator string.
2. App maps that string to a `LiteRT-LM` backend.
3. App builds `EngineConfig`.
4. App calls `Engine.initialize()`.
5. App creates a `Conversation` and sends messages asynchronously.

Relevant code:

- `Android/src/app/build.gradle.kts:91` adds `com.google.ai.edge.litertlm:litertlm-android`.
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/runtime/ModelHelperExt.kt:23` routes models to `LlmChatModelHelper`.
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt:60` is the main initialization path.

Important implication: this repo is mostly an **orchestrator** around `LiteRT-LM`, not the place where driver-level backend discovery is implemented.

## 2. Accelerator types the app understands

The app defines only three logical accelerator labels:

- `CPU`
- `GPU`
- `NPU`

Source:

- `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/Types.kt:19`

These are app-level labels only. They are later translated to:

- `Backend.CPU()`
- `Backend.GPU()`
- `Backend.NPU(nativeLibraryDir = ...)`

Source:

- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt:85`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt:95`

## 3. Where supported hardware comes from

### Built-in models

For built-in models, supported accelerators are declared in the allowlist JSON, not discovered dynamically.

Examples in the current allowlist:

- `Gemma-3n-E2B-it-int4` => `cpu,gpu`
- `Gemma-3n-E4B-it-int4` => `cpu,gpu`
- `Gemma3-1B-IT q4` => `gpu,cpu`
- `Qwen2.5-1.5B-Instruct q8` => `cpu`

Source:

- `model_allowlist.json:12`
- `model_allowlist.json:30`
- `model_allowlist.json:47`
- `model_allowlist.json:64`

The app parses the allowlist field `defaultConfig.accelerators` and converts each item into the local enum.

Source:

- `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/ModelAllowlist.kt:26`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/ModelAllowlist.kt:109`

### Imported models

For imported models, the user manually declares the compatible accelerators in the import dialog.

Source:

- `Android/src/app/src/main/proto/settings.proto:51`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/modelmanager/ModelImportDialog.kt:95`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/modelmanager/ModelImportDialog.kt:220`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/modelmanager/ModelManagerViewModel.kt:1069`

So for imported models, compatibility is also **declared metadata**, not auto-probed.

## 4. What the app actually detects on the device

The app performs only a small amount of device-specific detection itself.

### 4.1 SoC detection

It reads the device SoC string from `Build.SOC_MODEL` on Android 12+ and lowercases it.

Source:

- `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/Consts.kt:69`

This is used only for **model filtering / per-SoC file selection**, not for direct backend probing.

### 4.2 Pixel 10 special-case blacklist

The app has a hardcoded `Pixel 10` check:

- `isPixel10()` returns true when `Build.MODEL` contains `pixel 10`.
- When true, the app removes `GPU` from allowlist-backed models.
- The import dialog also limits compatible accelerators to `CPU` only.

Source:

- `Android/src/app/src/main/java/com/google/ai/edge/gallery/common/Utils.kt:347`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/ModelAllowlist.kt:121`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/modelmanager/ModelImportDialog.kt:95`

This is a **device-specific denylist workaround**, not a generic capability probe.

### 4.3 NPU-only model gating by SoC allowlist

If a model declares only `npu`, the app checks `socToModelFiles` and drops the model entirely unless the current SoC is explicitly listed.

Source:

- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/modelmanager/ModelManagerViewModel.kt:864`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/ModelAllowlist.kt:75`

So the NPU path is intended to be constrained by a **known-good SoC allowlist**.

### 4.4 Memory detection

The app can warn if device RAM is below a model’s declared minimum, but this is about memory safety, not backend selection.

Source:

- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/MemoryWarning.kt:44`

## 5. What the app does **not** detect itself

I searched the repo and did **not** find app-layer code using:

- `PackageManager.hasSystemFeature(...)` for accelerator capability
- Android NNAPI / `NeuralNetworks` APIs
- TensorFlow Lite `CompatibilityList`
- `GpuDelegate` or explicit delegate creation
- OpenCL probing logic
- Vulkan probing logic
- benchmark-based auto-selection
- automatic fallback from failed `GPU`/`NPU` init to `CPU`

Instead, the app chooses a backend from metadata/UI and tries to initialize it.

## 6. Exact backend selection flow

### 6.1 Config creation

For LLM models, the app creates a config UI that includes an accelerator selector. The first entry in the model’s accelerator list becomes the default.

Source:

- `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/Config.kt:221`

For NPU-only models, it deliberately omits `topK`, `topP`, and `temperature` controls.

Source:

- `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/Config.kt:280`

### 6.2 Model initialization / reinitialization

Changing a config with `needReinitialization = true` forces a full model re-init. Since the accelerator selector is one of those configs, backend changes rebuild the engine.

Source:

- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/ModelPageAppBar.kt:228`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/modelmanager/ModelManagerViewModel.kt:345`

The app also tracks which backends were already initialized for a model, but that is state tracking/UI bookkeeping, not discovery.

Source:

- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/modelmanager/ModelManagerViewModel.kt:84`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/modelmanager/ModelManagerViewModel.kt:475`

### 6.3 Built-in task flags

All LLM tasks share the same runtime helper, but tasks declare whether image or audio backends should be enabled:

- chat => text only
- ask image => image backend enabled
- ask audio => audio backend enabled

Source:

- `Android/src/app/src/main/java/com/google/ai/edge/gallery/runtime/ModelHelperExt.kt:23`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatTaskModule.kt:80`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatTaskModule.kt:162`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatTaskModule.kt:227`

### 6.4 Mapping to the actual runtime backend

During initialization, the helper reads the selected accelerator string and maps it like this:

- `CPU` -> `Backend.CPU()`
- `GPU` -> `Backend.GPU()`
- `NPU` -> `Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)`

Source:

- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt:78`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt:95`

The separate vision backend is mapped the same way.

Source:

- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt:80`

### 6.5 EngineConfig construction

The app builds `EngineConfig` with:

- `modelPath`
- `backend`
- `visionBackend` if image support is enabled
- `audioBackend = Backend.CPU()` if audio support is enabled
- `maxNumTokens`
- optional `cacheDir`

Source:

- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt:105`

Important behavior:

- image models can have a separate vision backend
- audio is hard-wired to `CPU` in this app
- there is no dynamic runtime fallback path here

### 6.6 The real backend viability test

This is the key line:

- `Engine(engineConfig)`
- `engine.initialize()`

Source:

- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt:119`

This is where the underlying library actually tries to bring up the selected backend. If the backend is unsupported, misconfigured, or missing required native support, the call throws and the app reports the error.

Source:

- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt:120`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/common/Utils.kt:57`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/modelmanager/ModelManagerViewModel.kt:378`

In other words, **this app treats successful engine initialization as the final compatibility probe**.

## 7. Special behavior for NPU

There are two NPU-specific behaviors in app code:

1. `Backend.NPU(...)` needs `nativeLibraryDir`.
2. `SamplerConfig` is omitted for NPU.

Source:

- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt:89`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt:129`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt:186`

The config layer mirrors this by creating a reduced config UI for NPU-only models.

Source:

- `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/Config.kt:280`

However, an important practical note: **no current bundled allowlist model in this repo is NPU-only or even includes `npu` in its accelerator list**. So the NPU path is implemented, but not exercised by the shipping allowlists in this checkout.

Source:

- `model_allowlist.json:1`

## 8. Inference execution itself

Once initialized, the app does not keep re-deciding hardware per token or per request.

It simply:

- gets the existing `Conversation`
- packages image/audio/text inputs into `Contents`
- calls `conversation.sendMessageAsync(...)`

Source:

- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt:243`

So backend choice is effectively **engine-level**, decided before generation starts.

## 9. Reset vs reinit

`resetConversation()` does **not** rebuild the engine/backend. It only recreates the conversation on the existing engine.

Source:

- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt:153`

That means:

- prompt/system/tool changes can reuse the existing backend
- accelerator changes require full engine reinit, which happens via the config dialog flow

## 10. Benchmark path

Benchmarking uses the same basic accelerator mapping:

- `gpu` -> `Backend.GPU()`
- `npu` -> `Backend.NPU(nativeLibraryDir = ...)`
- otherwise -> `Backend.CPU()`

Source:

- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/benchmark/BenchmarkViewModel.kt:135`

Then it calls the `LiteRT-LM` benchmark API directly.

Source:

- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/benchmark/BenchmarkViewModel.kt:145`

Again: the app does not benchmark all backends and auto-pick one. It benchmarks the backend the user asked for.

## 11. Native library hints in the manifest

The manifest declares optional native libraries:

- `libvndksupport.so`
- `libOpenCL.so`
- `libcdsprpc.so`

Source:

- `Android/src/app/src/main/AndroidManifest.xml:146`

This strongly suggests the underlying runtime may rely on vendor/OpenCL/DSP-side native pieces when certain accelerators are used.

But crucially:

- the app itself does not load these libraries manually
- the app does not inspect their presence directly
- the app just exposes them to the packaged/native runtime environment

## 12. One subtle wiring detail

The code parses `visionAccelerator` from the allowlist and stores it on `Model.visionAccelerator`.

Source:

- `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/ModelAllowlist.kt:126`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/Model.kt:240`

But the runtime helper reads `ConfigKeys.VISION_ACCELERATOR` from `model.configValues`, and I could not find any code that actually inserts the parsed `model.visionAccelerator` into `configValues` or exposes a UI editor for it.

Source:

- `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt:80`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/Model.kt:262`
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/Config.kt:221`

So in the current code, the **effective vision backend default appears to be `DEFAULT_VISION_ACCELERATOR = GPU`**, regardless of the parsed `Model.visionAccelerator` value.

Source:

- `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/Consts.kt:46`

This is worth remembering if you are cloning the design in Kotlin: if you want separate text and vision accelerators, wire both the persisted config value and the runtime read path.

## 13. Practical summary of the real mechanism

If you want to reproduce this app’s hardware strategy in Kotlin, the algorithm is essentially:

1. For each model, ship metadata saying which accelerators are allowed.
2. Optionally gate `NPU` models by exact SoC allowlist.
3. Add hardcoded device blacklists for known-bad combinations.
4. Let the user choose among the allowed accelerators.
5. Map that choice to your runtime backend enum/object.
6. Attempt engine initialization.
7. Treat successful init as the final “backend supported” result.
8. Cache/report failures; do not assume GPU/NPU is actually available until init succeeds.

That is the main pattern used here.

## 14. What lives outside this repo

Anything below this line is **not directly visible in this repo’s source** and appears to live inside `LiteRT-LM` / its native dependencies:

- actual GPU driver probing
- actual NPU/DSP availability checks
- OpenCL / vendor library loading strategy
- backend-specific graph compilation / delegate creation
- low-level fallback behavior inside the runtime, if any

From this repo alone, the strongest defensible statement is:

> The app itself does metadata-based gating and backend selection, and it relies on `LiteRT-LM` engine initialization as the real compatibility probe for CPU/GPU/NPU execution.

## 15. Relevant code snippets

This section copies the key code pieces into one place so you can reuse the mechanism elsewhere.

### 15.1 Accelerator enum

Source: `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/Types.kt:19`

```kotlin
enum class Accelerator(val label: String) {
  CPU(label = "CPU"),
  GPU(label = "GPU"),
  NPU(label = "NPU"),
}
```

### 15.2 Default accelerator + SoC detection

Source: `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/Consts.kt:45`

```kotlin
val DEFAULT_ACCELERATORS = listOf(Accelerator.GPU)
val DEFAULT_VISION_ACCELERATOR = Accelerator.GPU

val SOC =
  (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      Build.SOC_MODEL ?: ""
    } else {
      ""
    })
    .lowercase()
```

### 15.3 Device-specific blacklist: Pixel 10

Source: `Android/src/app/src/main/java/com/google/ai/edge/gallery/common/Utils.kt:347`

```kotlin
fun isPixel10(): Boolean {
  return Build.MODEL != null && Build.MODEL.lowercase().contains("pixel 10")
}
```

### 15.4 LLM config UI includes accelerator selector

Source: `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/Config.kt:221`

```kotlin
fun createLlmChatConfigs(
  defaultMaxToken: Int = DEFAULT_MAX_TOKEN,
  defaultMaxContextLength: Int? = null,
  defaultTopK: Int = DEFAULT_TOPK,
  defaultTopP: Float = DEFAULT_TOPP,
  defaultTemperature: Float = DEFAULT_TEMPERATURE,
  accelerators: List<Accelerator> = DEFAULT_ACCELERATORS,
  supportThinking: Boolean = false,
): List<Config> {
  var maxTokensConfig: Config =
    LabelConfig(key = ConfigKeys.MAX_TOKENS, defaultValue = "$defaultMaxToken")
  if (defaultMaxContextLength != null) {
    maxTokensConfig =
      NumberSliderConfig(
        key = ConfigKeys.MAX_TOKENS,
        sliderMin = 2000f,
        sliderMax = defaultMaxContextLength.toFloat(),
        defaultValue = defaultMaxToken.toFloat(),
        valueType = ValueType.INT,
      )
  }
  val configs =
    listOf(
        maxTokensConfig,
        NumberSliderConfig(
          key = ConfigKeys.TOPK,
          sliderMin = 5f,
          sliderMax = 100f,
          defaultValue = defaultTopK.toFloat(),
          valueType = ValueType.INT,
        ),
        NumberSliderConfig(
          key = ConfigKeys.TOPP,
          sliderMin = 0.0f,
          sliderMax = 1.0f,
          defaultValue = defaultTopP,
          valueType = ValueType.FLOAT,
        ),
        NumberSliderConfig(
          key = ConfigKeys.TEMPERATURE,
          sliderMin = 0.0f,
          sliderMax = 2.0f,
          defaultValue = defaultTemperature,
          valueType = ValueType.FLOAT,
        ),
        SegmentedButtonConfig(
          key = ConfigKeys.ACCELERATOR,
          defaultValue = accelerators[0].label,
          options = accelerators.map { it.label },
        ),
      )
      .toMutableList()

  if (supportThinking) {
    configs.add(BooleanSwitchConfig(key = ConfigKeys.ENABLE_THINKING, defaultValue = false))
  }
  return configs
}
```

### 15.5 NPU-only models get a reduced config surface

Source: `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/Config.kt:285`

```kotlin
fun createLlmChatConfigsForNpuModel(
  defaultMaxToken: Int = DEFAULT_MAX_TOKEN,
  accelerators: List<Accelerator> = DEFAULT_ACCELERATORS,
): List<Config> {
  return listOf(
    LabelConfig(key = ConfigKeys.MAX_TOKENS, defaultValue = "$defaultMaxToken"),
    SegmentedButtonConfig(
      key = ConfigKeys.ACCELERATOR,
      defaultValue = accelerators[0].label,
      options = accelerators.map { it.label },
    ),
  )
}
```

### 15.6 Allowlist parsing + GPU removal on Pixel 10 + NPU-only handling

Source: `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/ModelAllowlist.kt:103`

```kotlin
if (isLlmModel) {
  val defaultTopK: Int = defaultConfig.topK ?: DEFAULT_TOPK
  val defaultTopP: Float = defaultConfig.topP ?: DEFAULT_TOPP
  val defaultTemperature: Float = defaultConfig.temperature ?: DEFAULT_TEMPERATURE
  llmMaxToken = defaultConfig.maxTokens ?: 1024
  llmMaxContextLength = defaultConfig.maxContextLength
  if (defaultConfig.accelerators != null) {
    val items = defaultConfig.accelerators.split(",")
    accelerators = mutableListOf()
    for (item in items) {
      if (item == "cpu") {
        accelerators.add(Accelerator.CPU)
      } else if (item == "gpu") {
        accelerators.add(Accelerator.GPU)
      } else if (item == "npu") {
        accelerators.add(Accelerator.NPU)
      }
    }
    if (isPixel10()) {
      accelerators.remove(Accelerator.GPU)
    }
  }
  if (defaultConfig.visionAccelerator != null) {
    val accelerator = defaultConfig.visionAccelerator
    if (accelerator == "cpu") {
      visionAccelerator = Accelerator.CPU
    } else if (accelerator == "gpu") {
      visionAccelerator = Accelerator.GPU
    } else if (accelerator == "npu") {
      visionAccelerator = Accelerator.NPU
    }
  }
  val npuOnly = accelerators.size == 1 && accelerators[0] == Accelerator.NPU
  configs =
    (
      if (npuOnly) {
        createLlmChatConfigsForNpuModel(
          defaultMaxToken = llmMaxToken,
          accelerators = accelerators,
        )
      } else {
        createLlmChatConfigs(
          defaultTopK = defaultTopK,
          defaultTopP = defaultTopP,
          defaultTemperature = defaultTemperature,
          defaultMaxToken = llmMaxToken,
          defaultMaxContextLength = llmMaxContextLength,
          accelerators = accelerators,
          supportThinking = llmSupportThinking == true,
        )
      })
      .toMutableList()
}
```

### 15.7 NPU-only models are SoC-gated before being shown

Source: `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/modelmanager/ModelManagerViewModel.kt:864`

```kotlin
val accelerators = allowedModel.defaultConfig.accelerators ?: ""
val acceleratorList = accelerators.split(",").map { it.trim() }.filter { it.isNotEmpty() }
if (acceleratorList.size == 1 && acceleratorList[0] == "npu") {
  val socToModelFiles = allowedModel.socToModelFiles
  if (socToModelFiles != null && !socToModelFiles.containsKey(SOC)) {
    Log.d(
      TAG,
      "Ignoring model '${allowedModel.name}' because it's NPU-only and not supported on SOC: $SOC",
    )
    continue
  }
}
```

### 15.8 Imported models also declare compatible accelerators

Source: `Android/src/app/src/main/proto/settings.proto:51`

```proto
message LlmConfig {
  repeated string compatible_accelerators = 1;
  int32 default_max_tokens = 2;
  int32 default_topk = 3;
  float default_topp = 4;
  float default_temperature = 5;
  bool support_image = 6;
  bool support_audio = 7;
  bool support_tiny_garden = 8;
  bool support_mobile_actions = 9;
  bool support_thinking = 10;
}
```

### 15.9 Import UI lets the user pick declared accelerators

Source: `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/modelmanager/ModelImportDialog.kt:95`

```kotlin
private val SUPPORTED_ACCELERATORS: List<Accelerator> =
  if (isPixel10()) {
    listOf(Accelerator.CPU)
  } else {
    listOf(Accelerator.CPU, Accelerator.GPU, Accelerator.NPU)
  }

private val IMPORT_CONFIGS_LLM: List<Config> =
  listOf(
    // ...
    SegmentedButtonConfig(
      key = ConfigKeys.COMPATIBLE_ACCELERATORS,
      defaultValue = SUPPORTED_ACCELERATORS[0].label,
      options = SUPPORTED_ACCELERATORS.map { it.label },
      allowMultiple = true,
    ),
  )
```

Source: `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/modelmanager/ModelImportDialog.kt:220`

```kotlin
val supportedAccelerators =
  (convertValueToTargetType(
      value = values.get(ConfigKeys.COMPATIBLE_ACCELERATORS.label)!!,
      valueType = ValueType.STRING,
    )
      as String)
    .split(",")

val importedModel: ImportedModel =
  ImportedModel.newBuilder()
    .setFileName(fileName)
    .setFileSize(fileSize)
    .setLlmConfig(
      LlmConfig.newBuilder()
        .addAllCompatibleAccelerators(supportedAccelerators)
        .setDefaultMaxTokens(defaultMaxTokens)
        .setDefaultTopk(defaultTopk)
        .setDefaultTopp(defaultTopp)
        .setDefaultTemperature(defaultTemperature)
        .setSupportImage(supportImage)
        .setSupportAudio(supportAudio)
        .setSupportMobileActions(supportMobileActions)
        .setSupportThinking(supportThinking)
        .setSupportTinyGarden(supportTinyGarden)
        .build()
    )
    .build()
```

### 15.10 Imported models are converted back into enum accelerators

Source: `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/modelmanager/ModelManagerViewModel.kt:1069`

```kotlin
val accelerators: MutableList<Accelerator> =
  info.llmConfig.compatibleAcceleratorsList
    .mapNotNull { acceleratorLabel ->
      when (acceleratorLabel.trim()) {
        Accelerator.GPU.label -> Accelerator.GPU
        Accelerator.CPU.label -> Accelerator.CPU
        Accelerator.NPU.label -> Accelerator.NPU
        else -> null
      }
    }
    .toMutableList()
```

### 15.11 Changing accelerator forces model re-init

Source: `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/ModelPageAppBar.kt:228`

```kotlin
var same = true
var needReinitialization = false
for (config in modelConfigs) {
  val key = config.key.label
  val oldValue =
    convertValueToTargetType(
      value = model.configValues.getValue(key),
      valueType = config.valueType,
    )
  val newValue =
    convertValueToTargetType(
      value = curConfigValues.getValue(key),
      valueType = config.valueType,
    )
  if (oldValue != newValue) {
    same = false
    if (config.needReinitialization) {
      needReinitialization = true
    }
    break
  }
}

model.prevConfigValues = oldConfigValues
model.configValues = curConfigValues

if (needReinitialization) {
  modelManagerViewModel.initializeModel(
    context = context,
    task = task,
    model = model,
    force = true,
  )
}
```

### 15.12 Runtime backend mapping and engine initialization

Source: `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt:72`

```kotlin
val maxTokens =
  model.getIntConfigValue(key = ConfigKeys.MAX_TOKENS, defaultValue = DEFAULT_MAX_TOKEN)
val topK = model.getIntConfigValue(key = ConfigKeys.TOPK, defaultValue = DEFAULT_TOPK)
val topP = model.getFloatConfigValue(key = ConfigKeys.TOPP, defaultValue = DEFAULT_TOPP)
val temperature =
  model.getFloatConfigValue(key = ConfigKeys.TEMPERATURE, defaultValue = DEFAULT_TEMPERATURE)
val accelerator =
  model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = Accelerator.GPU.label)
val visionAccelerator =
  model.getStringConfigValue(
    key = ConfigKeys.VISION_ACCELERATOR,
    defaultValue = DEFAULT_VISION_ACCELERATOR.label,
  )

val visionBackend =
  when (visionAccelerator) {
    Accelerator.CPU.label -> Backend.CPU()
    Accelerator.GPU.label -> Backend.GPU()
    Accelerator.NPU.label ->
      Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
    else -> Backend.GPU()
  }

val preferredBackend =
  when (accelerator) {
    Accelerator.CPU.label -> Backend.CPU()
    Accelerator.GPU.label -> Backend.GPU()
    Accelerator.NPU.label ->
      Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
    else -> Backend.CPU()
  }

val engineConfig =
  EngineConfig(
    modelPath = modelPath,
    backend = preferredBackend,
    visionBackend = if (shouldEnableImage) visionBackend else null,
    audioBackend = if (shouldEnableAudio) Backend.CPU() else null,
    maxNumTokens = maxTokens,
    cacheDir =
      if (modelPath.startsWith("/data/local/tmp"))
        context.getExternalFilesDir(null)?.absolutePath
      else null,
  )

val engine = Engine(engineConfig)
engine.initialize()
```

### 15.13 NPU skips sampler config

Source: `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt:127`

```kotlin
val conversation =
  engine.createConversation(
    ConversationConfig(
      samplerConfig =
        if (preferredBackend is Backend.NPU) {
          null
        } else {
          SamplerConfig(
            topK = topK,
            topP = topP.toDouble(),
            temperature = temperature.toDouble(),
          )
        },
      systemInstruction = systemInstruction,
      tools = tools,
    )
  )
```

### 15.14 Benchmark uses the same backend mapping strategy

Source: `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/benchmark/BenchmarkViewModel.kt:135`

```kotlin
val backend: Backend =
  when (accelerator.lowercase()) {
    "gpu" -> Backend.GPU()
    "npu" -> Backend.NPU(nativeLibraryDir = appContext.applicationInfo.nativeLibraryDir)
    else -> Backend.CPU()
  }

val benchmarkInfo =
  benchmark(
    modelPath = modelPath,
    backend = backend,
    prefillTokens = prefillTokens,
    decodeTokens = decodeTokens,
    cacheDir = cacheDirPath,
  )
```

### 15.15 Native library hints exposed in the manifest

Source: `Android/src/app/src/main/AndroidManifest.xml:146`

```xml
<uses-native-library android:name="libvndksupport.so" android:required="false"/>
<uses-native-library android:name="libOpenCL.so" android:required="false"/>
<uses-native-library android:name="libcdsprpc.so" android:required="false" />
```

### 15.16 Current built-in model accelerator metadata

Source: `model_allowlist.json:1`

```json
{
  "models": [
    {
      "name": "Gemma-3n-E2B-it-int4",
      "defaultConfig": {
        "topK": 64,
        "topP": 0.95,
        "temperature": 1.0,
        "maxTokens": 4096,
        "accelerators": "cpu,gpu"
      }
    },
    {
      "name": "Gemma-3n-E4B-it-int4",
      "defaultConfig": {
        "topK": 64,
        "topP": 0.95,
        "temperature": 1.0,
        "maxTokens": 4096,
        "accelerators": "cpu,gpu"
      }
    },
    {
      "name": "Gemma3-1B-IT q4",
      "defaultConfig": {
        "topK": 64,
        "topP": 0.95,
        "temperature": 1.0,
        "maxTokens": 1024,
        "accelerators": "gpu,cpu"
      }
    },
    {
      "name": "Qwen2.5-1.5B-Instruct q8",
      "defaultConfig": {
        "topK": 40,
        "topP": 0.95,
        "temperature": 1.0,
        "maxTokens": 1024,
        "accelerators": "cpu"
      }
    }
  ]
}
```

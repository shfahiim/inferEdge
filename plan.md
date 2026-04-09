# Inference Server Plan

## Goal

Modify the app to add a new screen that can:

- select one of the available local models
- start and stop an embedded inference server
- expose that server on a configurable port such as `8000`
- serve an OpenAI-compatible HTTP API
- show real-time telemetry while inference is running
- allow requests from another device such as a laptop

## Phase 1: Define API and security scope

Decide the MVP endpoint surface and exposure model before implementation.

### MVP endpoints

- `GET /v1/models`
- `POST /v1/chat/completions`
- optional later: streaming responses
- optional later: `POST /v1/responses`

### Security decisions

- bind to `127.0.0.1` or `0.0.0.0`
- configurable port
- API key authentication
- request size limits
- timeout policy
- optional CORS policy

### Important note

Directly exposing a phone-hosted inference endpoint to the internet is risky. The safer default is:

- local network exposure first
- authentication required
- optional tunnel or reverse proxy later if internet access is needed

## Phase 2: Add embedded HTTP server layer

Add an embedded server that runs inside the Android app.

### Responsibilities

- start server on selected port
- stop server cleanly
- expose current bind address and port
- report running state and errors
- keep server lifecycle separate from the screen as much as possible

### Suggested approach

Use an embedded Kotlin/Android-friendly HTTP server such as Ktor.

## Phase 3: Create inference server screen

Add a new screen dedicated to hosting inference.

### UI controls

- model picker
- port input field
- bind address option
- API key enable/disable
- API key input field
- start server button
- stop server button
- status text and recent logs

### Live status section

- selected model
- selected accelerator/backend
- active requests
- completed requests
- current port
- current server state

## Phase 4: Bridge HTTP requests to the existing runtime

Reuse the existing model loading and runtime path rather than building a second inference engine.

### Reuse

- `ModelManagerViewModel`
- existing model download/init logic
- `LlmChatModelHelper`
- current backend selection flow

### Add a request coordinator

The coordinator should:

- ensure the selected model is loaded and initialized
- reject requests if the model is unavailable
- serialize or queue requests initially
- map API request fields to model config
- collect per-request timing and token metrics

### MVP concurrency model

Start with a single active generation at a time. This is the simplest and safest option for mobile hardware.

## Phase 5: Implement OpenAI-compatible endpoints

### `GET /v1/models`

Return the locally available models in an OpenAI-style shape.

### `POST /v1/chat/completions`

Support:

- `model`
- `messages`
- `temperature`
- `top_p`
- `max_tokens`
- `stream`

### Response mapping

- OpenAI-style JSON for non-streaming
- later add SSE for streaming

### Error handling

Return clear errors for:

- missing model
- model not initialized
- unsupported multimodal inputs
- bad request body
- unauthorized request
- busy server

## Phase 6: Add live telemetry collection

Collect metrics that are realistic on Android.

### Strong candidates

- selected backend in use: `CPU`, `GPU`, or `NPU`
- process RAM usage
- available device RAM
- process CPU usage
- request latency
- time to first token
- decode tokens per second
- generated token count
- active model name

### Optional metrics

- thermal state
- battery status
- memory pressure

### Important limitation

True GPU utilization percentage is not portable across Android devices. A practical implementation should show:

- GPU backend selected or active
- inference throughput
- memory usage
- latency

rather than promising universal GPU `%` telemetry.

## Phase 7: Stream metrics to UI and API

### UI

Show real-time telemetry while a request is running.

Suggested fields:

- backend used
- current request state
- request duration
- tokens/sec
- TTFT
- RAM usage
- CPU usage
- recent errors

### Optional API

Add a non-OpenAI diagnostic endpoint such as:

- `GET /metrics`
- or `GET /v1/local/metrics`

Keep this separate from the OpenAI-compatible endpoints.

## Phase 8: Validate from a laptop client

Test the server from another device on the same network.

### Validation targets

- `curl`
- OpenAI Python SDK using custom `base_url`
- other HTTP clients

### Verify

- server starts on configured port
- model loads correctly
- requests succeed
- auth works
- metrics update during inference
- server stops cleanly

## Recommended MVP

Implement the first version with the following scope:

- one selected model at a time
- configurable port
- local network exposure
- API key auth
- `GET /v1/models`
- `POST /v1/chat/completions`
- non-streaming first
- real-time CPU/RAM/backend/tokens-per-second display

Then expand later with:

- streaming SSE
- request queueing
- multimodal support
- metrics endpoint
- internet-facing hardening

## Key architectural constraints

- the current app has no inference port today
- inference is currently in-process
- model init and runtime are UI-driven today, so a server-side coordinator will be needed
- backend selection already exists and should be reused
- backend success is effectively determined by runtime initialization success

## Suggested module layout

### `server/`

- embedded server bootstrap
- routing
- auth
- OpenAI request/response DTOs

### `inference/`

- request coordinator
- runtime bridge
- request queue
- streaming adapter later

### `telemetry/`

- CPU sampler
- RAM sampler
- request metrics collector
- exposed state for UI

### `ui/inferenceserver/`

- inference server screen
- viewmodel
- telemetry panels
- start/stop controls

## First implementation milestone

The first milestone should be:

1. add the new screen
2. start an embedded server on configurable port
3. expose `GET /v1/models`
4. expose `POST /v1/chat/completions`
5. run a single selected local model
6. show real-time CPU/RAM/backend/tokens-per-second while a request is active

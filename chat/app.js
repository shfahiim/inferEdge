const STORAGE_KEYS = {
  apiKey: "inferedge_api_key",
  models: "inferedge_models",
  chats: "inferedge_chats",
  activeChatId: "inferedge_active_chat_id",
  settings: "inferedge_settings",
};

const DEFAULTS = {
  baseUrl: "https://poco.fahiim.me/v1",
  defaultModel: "Qwen2.5-1.5B-Instruct",
  systemPrompt:
    "You are a helpful AI assistant. Be concise, accurate, and friendly in your responses.",
  temperature: 0.7,
  maxTokens: 1024,
  topP: 0.9,
  stream: true,
  saveHistory: true,
};

const dom = {
  chatForm: document.querySelector("#chat-form"),
  messageInput: document.querySelector("#message"),
  sendButton: document.querySelector("#send-button"),
  messages: document.querySelector("#messages"),
  typingRow: document.querySelector("#typing-row"),
  status: document.querySelector("#status"),
  chatTitle: document.querySelector("#chat-title"),
  chatList: document.querySelector("#chat-list"),
  newChatButton: document.querySelector("#new-chat-button"),
  copyLastButton: document.querySelector("#copy-last-button"),
  clearChatButton: document.querySelector("#clear-chat-button"),
  baseUrlInput: document.querySelector("#base-url"),
  apiKeyInput: document.querySelector("#api-key"),
  toggleApiKeyButton: document.querySelector("#toggle-api-key"),
  modelSelect: document.querySelector("#model"),
  customModelInput: document.querySelector("#custom-model"),
  saveModelButton: document.querySelector("#save-model-button"),
  checkHealthButton: document.querySelector("#check-health-button"),
  healthStatus: document.querySelector("#health-status"),
  healthText: document.querySelector("#health-text"),
  modelBadge: document.querySelector("#model-badge"),
  modelBadgeText: document.querySelector("#model-badge-text"),
  modelDot: document.querySelector("#model-dot"),
  toggleConfigButton: document.querySelector("#toggle-config-button"),
  closeConfigButton: document.querySelector("#close-config-button"),
  configPanel: document.querySelector("#config-panel"),
  systemPrompt: document.querySelector("#system-prompt"),
  temperature: document.querySelector("#temperature"),
  maxTokens: document.querySelector("#max-tokens"),
  topP: document.querySelector("#top-p"),
  tempVal: document.querySelector("#temp-val"),
  tokVal: document.querySelector("#tok-val"),
  toppVal: document.querySelector("#topp-val"),
  streamToggle: document.querySelector("#stream-toggle"),
  historyToggle: document.querySelector("#history-toggle"),
  messageCount: document.querySelector("#message-count"),
  tokenCount: document.querySelector("#token-count"),
};

const state = {
  chats: loadChats(),
  activeChatId: loadActiveChatId(),
  latestAssistantText: "",
  requestInFlight: false,
  defaultModel: DEFAULTS.defaultModel,
  settings: loadSettings(),
};

hydrateControls();
ensureChat();
renderModelOptions();
renderChatList();
renderActiveChat();
syncParameterLabels();
checkHealth();

dom.chatForm.addEventListener("submit", handleSubmit);
dom.messageInput.addEventListener("input", autoResizeTextarea);
dom.messageInput.addEventListener("keydown", (event) => {
  if (event.key === "Enter" && !event.shiftKey) {
    event.preventDefault();
    if (!dom.sendButton.disabled && dom.messageInput.value.trim()) {
      dom.chatForm.requestSubmit();
    }
  }
});
dom.newChatButton.addEventListener("click", () => {
  const activeChat = getActiveChat();
  if (isDraftChat(activeChat)) {
    dom.messageInput.focus();
    setStatus("You already have a blank draft open.");
    return;
  }

  const existingDraft = state.chats.find(isDraftChat);
  if (existingDraft) {
    state.activeChatId = existingDraft.id;
  } else {
    createChat({ persist: false });
  }

  renderChatList();
  renderActiveChat();
  dom.messageInput.focus();
});
dom.chatList.addEventListener("click", handleChatListClick);
dom.copyLastButton.addEventListener("click", copyLastAssistantMessage);
dom.clearChatButton.addEventListener("click", clearConversation);
dom.saveModelButton.addEventListener("click", () =>
  addModel(dom.customModelInput.value),
);
dom.customModelInput.addEventListener("keydown", (event) => {
  if (event.key === "Enter") {
    event.preventDefault();
    addModel(dom.customModelInput.value);
  }
});
dom.toggleApiKeyButton.addEventListener("click", toggleApiKeyVisibility);
dom.checkHealthButton.addEventListener("click", checkHealth);
dom.modelBadge.addEventListener("click", checkHealth);
dom.baseUrlInput.addEventListener("change", () => {
  persistSettings();
  checkHealth();
});
dom.modelSelect.addEventListener("change", () => {
  addModel(dom.modelSelect.value, { silent: true });
  persistSettings();
  updateModelBadge(dom.modelSelect.value);
});
dom.systemPrompt.addEventListener("input", persistSettings);
dom.temperature.addEventListener("input", () => {
  syncParameterLabels();
  persistSettings();
});
dom.maxTokens.addEventListener("input", () => {
  syncParameterLabels();
  persistSettings();
});
dom.topP.addEventListener("input", () => {
  syncParameterLabels();
  persistSettings();
});
dom.streamToggle.addEventListener("click", () =>
  toggleSwitch(dom.streamToggle, "stream"),
);
dom.historyToggle.addEventListener("click", () =>
  toggleSwitch(dom.historyToggle, "saveHistory"),
);
dom.toggleConfigButton.addEventListener("click", toggleConfigPanel);
dom.closeConfigButton.addEventListener("click", toggleConfigPanel);

function loadSettings() {
  try {
    const parsed = JSON.parse(
      window.localStorage.getItem(STORAGE_KEYS.settings) || "{}",
    );
    return { ...DEFAULTS, ...parsed };
  } catch {
    return { ...DEFAULTS };
  }
}

function persistSettings() {
  state.settings.baseUrl = dom.baseUrlInput.value.trim() || DEFAULTS.baseUrl;
  state.settings.systemPrompt = dom.systemPrompt.value;
  state.settings.temperature = Number(dom.temperature.value);
  state.settings.maxTokens = Number(dom.maxTokens.value);
  state.settings.topP = Number(dom.topP.value);
  window.localStorage.setItem(
    STORAGE_KEYS.settings,
    JSON.stringify(state.settings),
  );
}

function loadChats() {
  try {
    const parsed = JSON.parse(
      window.localStorage.getItem(STORAGE_KEYS.chats) || "[]",
    );
    if (!Array.isArray(parsed)) return [];
    return parsed.filter(isPersistableChat);
  } catch {
    return [];
  }
}

function loadActiveChatId() {
  return window.localStorage.getItem(STORAGE_KEYS.activeChatId);
}

function persistChatState() {
  if (!state.settings.saveHistory) {
    window.localStorage.removeItem(STORAGE_KEYS.chats);
    window.localStorage.removeItem(STORAGE_KEYS.activeChatId);
    return;
  }

  const persistableChats = state.chats.filter(isPersistableChat);
  window.localStorage.setItem(
    STORAGE_KEYS.chats,
    JSON.stringify(persistableChats),
  );

  if (persistableChats.some((chat) => chat.id === state.activeChatId)) {
    window.localStorage.setItem(STORAGE_KEYS.activeChatId, state.activeChatId);
  } else {
    window.localStorage.removeItem(STORAGE_KEYS.activeChatId);
  }
}

function hydrateControls() {
  const savedApiKey = window.localStorage.getItem(STORAGE_KEYS.apiKey);
  if (savedApiKey) {
    dom.apiKeyInput.value = savedApiKey;
  }

  dom.baseUrlInput.value = state.settings.baseUrl || DEFAULTS.baseUrl;
  dom.systemPrompt.value = state.settings.systemPrompt || DEFAULTS.systemPrompt;
  dom.temperature.value = String(
    state.settings.temperature ?? DEFAULTS.temperature,
  );
  dom.maxTokens.value = String(state.settings.maxTokens ?? DEFAULTS.maxTokens);
  dom.topP.value = String(state.settings.topP ?? DEFAULTS.topP);
  setToggleState(dom.streamToggle, !!state.settings.stream);
  setToggleState(dom.historyToggle, !!state.settings.saveHistory);
}

function setToggleState(element, isOn) {
  element.classList.toggle("off", !isOn);
  element.setAttribute("aria-pressed", String(isOn));
}

function toggleSwitch(element, key) {
  const nextValue = element.getAttribute("aria-pressed") !== "true";
  state.settings[key] = nextValue;
  setToggleState(element, nextValue);
  persistSettings();
  if (key === "saveHistory") {
    persistChatState();
  }
}

function syncParameterLabels() {
  dom.tempVal.textContent = Number(dom.temperature.value).toFixed(1);
  dom.tokVal.textContent = String(Math.round(Number(dom.maxTokens.value)));
  dom.toppVal.textContent = Number(dom.topP.value).toFixed(2);
}

function getSavedModels() {
  try {
    const parsed = JSON.parse(
      window.localStorage.getItem(STORAGE_KEYS.models) || "[]",
    );
    const models = Array.isArray(parsed) ? parsed : [];
    const normalized = models
      .map((model) => String(model || "").trim())
      .filter(Boolean);
    if (!normalized.includes(state.defaultModel)) {
      normalized.unshift(state.defaultModel);
    }
    return [...new Set(normalized)];
  } catch {
    return [state.defaultModel];
  }
}

function saveModels(models) {
  window.localStorage.setItem(STORAGE_KEYS.models, JSON.stringify(models));
}

function renderModelOptions(
  selectedModel = dom.modelSelect.value || state.defaultModel,
) {
  const models = getSavedModels();
  dom.modelSelect.innerHTML = "";

  for (const model of models) {
    const option = document.createElement("option");
    option.value = model;
    option.textContent = model;
    option.selected = model === selectedModel;
    dom.modelSelect.append(option);
  }

  updateModelBadge(selectedModel);
}

function addModel(modelName, options = {}) {
  const normalized = String(modelName || "").trim();
  if (!normalized) {
    if (!options.silent) {
      setStatus("Enter a model name first.");
    }
    return;
  }

  const models = getSavedModels();
  if (!models.includes(normalized)) {
    models.push(normalized);
    saveModels(models);
  }

  renderModelOptions(normalized);
  dom.customModelInput.value = "";
  if (!options.silent) {
    setStatus("Model saved.");
  }
}

function updateModelBadge(modelName) {
  dom.modelBadgeText.textContent = modelName || "Model unavailable";
}

function ensureChat() {
  const exists = state.chats.some((chat) => chat.id === state.activeChatId);
  if (!exists) {
    state.activeChatId = state.chats[0]?.id || null;
  }

  persistChatState();
}

function createChat(options = {}) {
  const chat = {
    id: crypto.randomUUID(),
    title: "New chat",
    createdAt: new Date().toISOString(),
    messages: [],
  };
  state.chats.unshift(chat);
  state.activeChatId = chat.id;
  if (options.persist !== false) {
    persistChatState();
  }
  return chat;
}

function getActiveChat() {
  return (
    state.chats.find((chat) => chat.id === state.activeChatId) ||
    state.chats[0] ||
    null
  );
}

function handleChatListClick(event) {
  const button = event.target.closest("[data-chat-id]");
  if (!button) return;

  state.activeChatId = button.dataset.chatId;
  persistChatState();
  renderChatList();
  renderActiveChat();
}

function renderChatList() {
  const fragment = document.createDocumentFragment();

  for (const chat of state.chats) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = `chat-item${chat.id === state.activeChatId ? " active" : ""}`;
    button.dataset.chatId = chat.id;
    button.innerHTML = `
      <div class="chat-item-title">${escapeHtml(chat.title || "New chat")}</div>
      <div class="chat-item-meta">${formatSidebarMeta(chat)}</div>
    `;
    fragment.append(button);
  }

  dom.chatList.replaceChildren(fragment);
}

function renderActiveChat() {
  const chat = getActiveChat();
  dom.chatTitle.textContent = chat?.title || "New chat";
  dom.messages.innerHTML = "";

  if (!chat || !chat.messages.length) {
      dom.messages.innerHTML = `
        <div class="empty-state">
          <h1>Start a chat</h1>
          <p>Messages stream from your configured endpoint, and Markdown responses render in the conversation.</p>
        </div>
      `;
    updateStats();
    updateActionStates();
    return;
  }

  const fragment = document.createDocumentFragment();
  for (const message of chat.messages) {
    fragment.append(renderMessageNode(message));
  }
  dom.messages.append(fragment);
  updateStats();
  updateActionStates();
  scrollMessagesToBottom();
}

function renderMessageNode(message) {
  const wrapper = document.createElement("div");
  wrapper.className = `msg${message.role === "user" ? " user" : ""}`;
  wrapper.dataset.messageId = message.id;

  const initials = message.role === "user" ? "PC" : "IE";
  const bubbleClass =
    message.role === "user" ? "bubble user" : "bubble ai markdown";
  const body =
    message.role === "assistant"
      ? renderMarkdown(message.content || "")
      : `<div>${escapeHtml(message.content || "")}</div>`;

  wrapper.innerHTML = `
    <div class="msg-avatar ${message.role === "user" ? "user" : "ai"}">${initials}</div>
    <div class="msg-content">
      <div class="${bubbleClass}">${body}</div>
      ${
        message.role === "assistant"
          ? `<div class="msg-actions"><button class="msg-action" type="button" data-copy-id="${message.id}">Copy</button></div>`
          : ""
      }
      <div class="msg-time">${formatTime(message.createdAt)}</div>
    </div>
  `;

  wrapper
    .querySelector("[data-copy-id]")
    ?.addEventListener("click", () => copyMessageById(message.id));
  return wrapper;
}

function updateAssistantMessage(chatId, messageId, text) {
  const chat = state.chats.find((entry) => entry.id === chatId);
  const message = chat?.messages.find((entry) => entry.id === messageId);
  if (!message) return;

  message.content = text;
  const node = dom.messages.querySelector(
    `[data-message-id="${messageId}"] .bubble`,
  );
  if (node) {
    node.innerHTML = renderMarkdown(text || "");
  }
  updateStats();
  if (state.settings.saveHistory) {
    persistChatState();
  }
  updateActionStates();
  scrollMessagesToBottom();
}

function appendMessage(role, content) {
  let chat = getActiveChat();
  if (!chat) {
    chat = createChat({ persist: false });
  }
  const message = {
    id: crypto.randomUUID(),
    role,
    content,
    createdAt: new Date().toISOString(),
  };
  chat.messages.push(message);
  touchChatTitle(chat);
  if (state.settings.saveHistory) {
    persistChatState();
  }
  renderChatList();
  dom.messages.querySelector(".empty-state")?.remove();
  dom.messages.append(renderMessageNode(message));
  updateStats();
  updateActionStates();
  scrollMessagesToBottom();
  return message;
}

function touchChatTitle(chat) {
  if (chat.title !== "New chat" || !chat.messages.length) return;
  const firstUser = chat.messages.find((message) => message.role === "user");
  if (!firstUser) return;
  chat.title = summarizeTitle(firstUser.content);
  dom.chatTitle.textContent = chat.title;
}

function summarizeTitle(text) {
  return text.trim().replace(/\s+/g, " ").slice(0, 38) || "New chat";
}

async function handleSubmit(event) {
  event.preventDefault();
  if (state.requestInFlight) return;

  const apiKey = dom.apiKeyInput.value.trim();
  const baseUrl = dom.baseUrlInput.value.trim().replace(/\/+$/, "");
  const model = dom.modelSelect.value.trim();
  const message = dom.messageInput.value.trim();

  if (!apiKey || !baseUrl || !model || !message) {
    setStatus("Fill in the API key, base URL, model, and message.");
    return;
  }

  window.localStorage.setItem(STORAGE_KEYS.apiKey, apiKey);
  persistSettings();
  addModel(model, { silent: true });

  let chat = getActiveChat();
  if (!chat) {
    chat = createChat({ persist: false });
  }
  const chatId = chat.id;
  appendMessage("user", message);
  dom.messageInput.value = "";
  autoResizeTextarea();

  state.requestInFlight = true;
  dom.sendButton.disabled = true;
  showTyping(true);
  updateActionStates();
  setStatus(
    state.settings.stream ? "Streaming response..." : "Waiting for response...",
  );

  state.latestAssistantText = "";
  let assistantMessage = null;

  try {
    const payload = {
      model,
      stream: state.settings.stream,
      messages: buildApiMessages(message),
      temperature: Number(dom.temperature.value),
      max_tokens: Number(dom.maxTokens.value),
      top_p: Number(dom.topP.value),
    };

    const response = await fetch(`${baseUrl}/chat/completions`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${apiKey}`,
      },
      body: JSON.stringify(payload),
    });

    if (!response.ok) {
      const data = await response.json().catch(() => ({}));
      throw new Error(
        data?.error?.message ||
          data?.message ||
          `Request failed with status ${response.status}.`,
      );
    }

    let assistantText = "";
    if (state.settings.stream) {
      assistantText = await readStream(response, (chunk) => {
        state.latestAssistantText = chunk;
        if (!assistantMessage) {
          assistantMessage = appendMessage("assistant", "");
          showTyping(false);
        }
        updateAssistantMessage(chatId, assistantMessage.id, chunk);
      });
    } else {
      const data = await response.json().catch(() => ({}));
      assistantText = extractCompletionText(data);
      state.latestAssistantText = assistantText;
    }

    if (!assistantText) {
      assistantText = "No output returned.";
    }

    if (!assistantMessage) {
      assistantMessage = appendMessage("assistant", assistantText);
    } else if (!state.settings.stream) {
      updateAssistantMessage(chatId, assistantMessage.id, assistantText);
    }

    setStatus("Response complete.");
    showTyping(false);
    if (state.settings.saveHistory) {
      persistChatState();
    }
  } catch (error) {
    if (assistantMessage) {
      removeMessage(chatId, assistantMessage.id);
    }
    setStatus(error instanceof Error ? error.message : "Request failed.");
    showTyping(false);
  } finally {
    state.requestInFlight = false;
    dom.sendButton.disabled = false;
    updateActionStates();
    renderChatList();
    renderActiveChat();
  }
}

function buildApiMessages(userMessage) {
  const chat = getActiveChat();
  const messages = [];

  const systemPrompt = dom.systemPrompt.value.trim();
  if (systemPrompt) {
    messages.push({ role: "system", content: systemPrompt });
  }

  for (const message of chat.messages) {
    if (message.role === "user" || message.role === "assistant") {
      messages.push({ role: message.role, content: message.content });
    }
  }

  if (
    !chat.messages.length ||
    chat.messages[chat.messages.length - 1]?.content !== userMessage
  ) {
    messages.push({ role: "user", content: userMessage });
  }

  return messages;
}

function extractCompletionText(data) {
  const content =
    data?.choices?.[0]?.message?.content ?? data?.choices?.[0]?.text ?? "";
  return typeof content === "string"
    ? content
    : JSON.stringify(content, null, 2);
}

async function readStream(response, onChunk) {
  const reader = response.body?.getReader();
  if (!reader) {
    throw new Error("Streaming is not available in this browser.");
  }

  const decoder = new TextDecoder();
  let buffer = "";
  let assembled = "";

  while (true) {
    const { value, done } = await reader.read();
    buffer += decoder.decode(value || new Uint8Array(), { stream: !done });

    const events = buffer.split("\n\n");
    buffer = events.pop() || "";

    for (const event of events) {
      const lines = event
        .split("\n")
        .map((line) => line.trim())
        .filter((line) => line.startsWith("data:"));

      for (const line of lines) {
        const payload = line.slice(5).trim();
        if (!payload) continue;
        if (payload === "[DONE]") return assembled;

        const data = JSON.parse(payload);
        const delta = data?.choices?.[0]?.delta?.content;
        if (typeof delta === "string" && delta.length > 0) {
          assembled += delta;
          onChunk(assembled);
        }
      }
    }

    if (done) break;
  }

  return assembled;
}

function removeMessage(chatId, messageId) {
  const chat = state.chats.find((entry) => entry.id === chatId);
  if (!chat) return;
  chat.messages = chat.messages.filter((message) => message.id !== messageId);
  if (state.settings.saveHistory) {
    persistChatState();
  }
}

function showTyping(show) {
  dom.typingRow.classList.toggle("hidden", !show);
}

function setStatus(text) {
  dom.status.textContent = text;
}

function copyMessageById(messageId) {
  const chat = getActiveChat();
  const message = chat?.messages.find((entry) => entry.id === messageId);
  if (!message) return;
  navigator.clipboard.writeText(message.content || "").then(
    () => setStatus("Copied output."),
    () => setStatus("Copy failed."),
  );
}

function copyLastAssistantMessage() {
  const chat = getActiveChat();
  const lastAssistant = [...(chat?.messages || [])]
    .reverse()
    .find((message) => message.role === "assistant");
  if (!lastAssistant) {
    setStatus("No assistant message to copy.");
    return;
  }
  copyMessageById(lastAssistant.id);
}

function clearConversation() {
  const chat = getActiveChat();
  if (!chat) return;

  state.chats = state.chats.filter((entry) => entry.id !== chat.id);
  state.activeChatId = state.chats[0]?.id || null;
  persistChatState();
  renderChatList();
  renderActiveChat();
  setStatus("Conversation cleared.");
}

function toggleApiKeyVisibility() {
  const isHidden = dom.apiKeyInput.type === "password";
  dom.apiKeyInput.type = isHidden ? "text" : "password";
  dom.toggleApiKeyButton.textContent = isHidden ? "Hide" : "Show";
}

function toggleConfigPanel() {
  dom.configPanel.classList.toggle("hidden-panel");
}

function autoResizeTextarea() {
  dom.messageInput.style.height = "auto";
  dom.messageInput.style.height = `${Math.min(dom.messageInput.scrollHeight, 120)}px`;
}

function updateStats() {
  const chat = getActiveChat();
  const messages = chat?.messages || [];
  const assistantChars = messages
    .filter((message) => message.role === "assistant")
    .reduce((sum, message) => sum + (message.content || "").length, 0);
  dom.messageCount.textContent = String(messages.length);
  dom.tokenCount.textContent = assistantChars.toLocaleString();
}

function updateActionStates() {
  const chat = getActiveChat();
  const hasMessages = Boolean(chat?.messages.length);
  const hasAssistantMessage = Boolean(
    chat?.messages.some(
      (message) => message.role === "assistant" && message.content.trim(),
    ),
  );
  dom.copyLastButton.disabled = !hasAssistantMessage;
  dom.clearChatButton.disabled = !hasMessages;
}

function scrollMessagesToBottom() {
  dom.messages.scrollTop = dom.messages.scrollHeight;
}

async function checkHealth() {
  dom.checkHealthButton.disabled = true;
  setHealthState("", "Checking server...", "Checking server...");

  try {
    const response = await fetch(getHealthUrl());
    const data = await response.json().catch(() => ({}));
    if (!response.ok) {
      throw new Error(
        data?.message || `Health check failed with status ${response.status}.`,
      );
    }

    if (data?.server_running && data?.model) {
      state.defaultModel = String(data.model).trim() || state.defaultModel;
      addModel(state.defaultModel, { silent: true });
      renderModelOptions(state.defaultModel);
      setHealthState("ok", `Server running`, state.defaultModel);
      persistSettings();
      return;
    }

    throw new Error("Unexpected health response.");
  } catch (error) {
    setHealthState(
      "error",
      error instanceof Error ? error.message : "Health check failed.",
      dom.modelSelect.value || state.defaultModel,
    );
  } finally {
    dom.checkHealthButton.disabled = false;
  }
}

function setHealthState(kind, healthText, modelText) {
  dom.healthText.textContent = healthText;
  dom.healthStatus.classList.toggle("active", kind === "ok");
  dom.healthStatus.classList.toggle("inactive", kind !== "ok");
  dom.modelDot.classList.remove("ok", "error");
  dom.healthStatus
    .querySelector(".health-dot")
    ?.classList.remove("ok", "error");

  if (kind === "ok" || kind === "error") {
    dom.modelDot.classList.add(kind);
    dom.healthStatus.querySelector(".health-dot")?.classList.add(kind);
  }

  updateModelBadge(modelText);
}

function getHealthUrl() {
  const url = new URL(dom.baseUrlInput.value.trim());
  const pathname = url.pathname.replace(/\/+$/, "");
  if (pathname.endsWith("/v1")) {
    url.pathname = pathname.slice(0, -3) || "/";
  }
  const nextPath = `${url.pathname.replace(/\/+$/, "")}/health`;
  url.pathname = nextPath || "/health";
  return url.toString();
}

function formatSidebarMeta(chat) {
  const count = chat.messages.length;
  if (!count) return "Draft";
  const date = new Date(chat.createdAt);
  return `${date.toLocaleDateString(undefined, { month: "short", day: "numeric" })} · ${count} msg${count > 1 ? "s" : ""}`;
}

function formatTime(isoString) {
  return new Date(isoString)
    .toLocaleTimeString([], {
      hour: "numeric",
      minute: "2-digit",
    })
    .toLowerCase();
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function renderInline(text) {
  let html = escapeHtml(text);
  html = html.replace(/`([^`]+)`/g, "<code>$1</code>");
  html = html.replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>");
  html = html.replace(/\*([^*]+)\*/g, "<em>$1</em>");
  html = html.replace(
    /\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g,
    '<a href="$2" target="_blank" rel="noreferrer">$1</a>',
  );
  return html;
}

function renderMarkdown(markdown) {
  const normalized = String(markdown || "").replace(/\r\n/g, "\n");
  const lines = normalized.split("\n");
  const html = [];
  let paragraph = [];
  let listType = null;
  let listItems = [];
  let inCodeBlock = false;
  let codeFence = "";
  let codeLines = [];

  const flushParagraph = () => {
    if (!paragraph.length) return;
    html.push(`<p>${renderInline(paragraph.join(" "))}</p>`);
    paragraph = [];
  };

  const flushList = () => {
    if (!listItems.length || !listType) return;
    const items = listItems
      .map((item) => `<li>${renderInline(item)}</li>`)
      .join("");
    html.push(`<${listType}>${items}</${listType}>`);
    listItems = [];
    listType = null;
  };

  const flushCodeBlock = () => {
    const code = escapeHtml(codeLines.join("\n"));
    const className = codeFence
      ? ` class="language-${escapeHtml(codeFence)}"`
      : "";
    html.push(`<pre><code${className}>${code}</code></pre>`);
    codeLines = [];
    codeFence = "";
  };

  for (const line of lines) {
    if (line.startsWith("```")) {
      flushParagraph();
      flushList();
      if (inCodeBlock) {
        flushCodeBlock();
        inCodeBlock = false;
      } else {
        inCodeBlock = true;
        codeFence = line.slice(3).trim();
      }
      continue;
    }

    if (inCodeBlock) {
      codeLines.push(line);
      continue;
    }

    if (!line.trim()) {
      flushParagraph();
      flushList();
      continue;
    }

    const heading = line.match(/^(#{1,3})\s+(.*)$/);
    if (heading) {
      flushParagraph();
      flushList();
      const level = heading[1].length;
      html.push(`<h${level}>${renderInline(heading[2].trim())}</h${level}>`);
      continue;
    }

    const blockquote = line.match(/^>\s?(.*)$/);
    if (blockquote) {
      flushParagraph();
      flushList();
      html.push(
        `<blockquote><p>${renderInline(blockquote[1])}</p></blockquote>`,
      );
      continue;
    }

    const unordered = line.match(/^[-*]\s+(.*)$/);
    if (unordered) {
      flushParagraph();
      if (listType && listType !== "ul") flushList();
      listType = "ul";
      listItems.push(unordered[1]);
      continue;
    }

    const ordered = line.match(/^\d+\.\s+(.*)$/);
    if (ordered) {
      flushParagraph();
      if (listType && listType !== "ol") flushList();
      listType = "ol";
      listItems.push(ordered[1]);
      continue;
    }

    paragraph.push(line.trim());
  }

  flushParagraph();
  flushList();
  if (inCodeBlock) flushCodeBlock();

  return html.join("") || "<p>No output returned.</p>";
}

function isDraftChat(chat) {
  return Boolean(chat) && chat.messages.length === 0;
}

function isPersistableChat(chat) {
  return (
    Boolean(chat) &&
    chat.messages.some((message) => String(message.content || "").trim())
  );
}

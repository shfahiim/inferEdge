# HuggingFace Token Setup Dialog - Implementation Summary

## ✅ What Was Implemented

Added a user-friendly dialog that guides users to set up HuggingFace authentication when they try to download gated models without a token configured.

---

## 📝 Changes Made

### File Modified: `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/DownloadAndTryButton.kt`

#### 1. Added State Variable (Line ~147)
```kotlin
var showTokenSetupDialog by remember { mutableStateOf(false) }
```

#### 2. Added Token Check Before OAuth Flow (Line ~280)
```kotlin
when (tokenStatusAndData.status) {
  TokenStatus.NOT_STORED,
  TokenStatus.EXPIRED -> {
    // Check if token is not stored - show setup dialog
    if (tokenStatusAndData.status == TokenStatus.NOT_STORED) {
      Log.d(TAG, "No token found. Showing setup dialog.")
      withContext(Dispatchers.Main) {
        showTokenSetupDialog = true
        checkingToken = false
        downloadStarted = false
      }
      return@launch
    }
    withContext(Dispatchers.Main) { startTokenExchange() }
  }
}
```

#### 3. Added Dialog Composable (Line ~550)
```kotlin
if (showTokenSetupDialog) {
  AlertDialog(
    onDismissRequest = {
      showTokenSetupDialog = false
      downloadStarted = false
    },
    title = { Text("Authentication Required") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Some models require HuggingFace authentication.")
        Text(
          "To download:",
          style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
          modifier = Modifier.padding(top = 8.dp)
        )
        Text("1. Open menu (☰) → Settings")
        Text("2. Enter your HuggingFace access token")
        Text("3. Get token from:")
        ClickableLink(
          text = "huggingface.co/settings/tokens",
          url = "https://huggingface.co/settings/tokens"
        )
      }
    },
    confirmButton = {
      TextButton(onClick = {
        showTokenSetupDialog = false
        downloadStarted = false
      }) {
        Text("OK")
      }
    }
  )
}
```

---

## 🎯 How It Works

### User Flow - First Time (No Token):

1. User clicks "Download" on a gated model (e.g., Gemma-3n-E2B-it)
2. App checks if model requires authentication (401 response)
3. App checks if user has a token stored
4. **Dialog appears:** "Authentication Required" with step-by-step instructions
5. User clicks "OK"
6. User opens hamburger menu (☰) → Settings
7. User scrolls to "HuggingFace access token" section
8. User enters token in the text field
9. User clicks checkmark to save
10. User returns to model and clicks "Download" again
11. Download proceeds with the saved token

### User Flow - After Token is Set:

1. User clicks "Download" on a gated model
2. **No dialog appears** - download proceeds directly with saved token
3. Model downloads successfully

---

## 🔍 Technical Details

### When Dialog Shows:
- Only when `TokenStatus.NOT_STORED` (no token in DataStore)
- Only for HuggingFace URLs that return 401 (Unauthorized)
- Only once per download attempt

### When Dialog Doesn't Show:
- Token is already configured (even if expired - OAuth flow handles that)
- Model doesn't require authentication (public models)
- Non-HuggingFace URLs

### Dialog Features:
- Clear title: "Authentication Required"
- Concise message about authentication need
- Step-by-step instructions (3 steps)
- Clickable link to HuggingFace token page
- Single "OK" button to dismiss
- Resets download state on dismiss

---

## 🧪 Testing

### Test Case 1: First Download Without Token
1. Fresh install or clear app data
2. Try to download "Gemma-3n-E2B-it"
3. **Expected:** Dialog appears with instructions
4. Click "OK"
5. **Expected:** Dialog closes, download cancelled

### Test Case 2: Download With Token
1. Set token in Settings
2. Try to download "Gemma-3n-E2B-it"
3. **Expected:** No dialog, download proceeds

### Test Case 3: Public Model
1. No token configured
2. Try to download "Gemma-4-E2B-it" (public)
3. **Expected:** No dialog, download proceeds

### Test Case 4: Dialog Dismissal
1. Trigger dialog
2. Click outside dialog or press back
3. **Expected:** Dialog closes, download state resets

---

## 📊 Affected Models

### Models That Will Show Dialog (if no token):
- Gemma-3n-E2B-it
- Gemma-3n-E4B-it
- Gemma3-1B-IT
- TinyGarden-270M
- MobileActions-270M

### Models That Won't Show Dialog:
- Gemma-4-E2B-it (public)
- Gemma-4-E4B-it (public)
- Qwen2.5-1.5B-Instruct (public)
- DeepSeek-R1-Distill-Qwen-1.5B (public)

---

## 🎨 UI/UX Considerations

### Design Decisions:
- **Minimal:** Only shows when absolutely necessary
- **Clear:** Simple language, no technical jargon
- **Actionable:** Provides exact steps to resolve
- **Non-blocking:** User can dismiss and try later
- **Helpful:** Includes direct link to token page

### User Experience:
- No numbers about model counts (future-proof)
- No scary error messages
- Guides to existing Settings UI (no new screens)
- One-time setup (never shows again after token is set)

---

## 🔧 Build & Deploy

### To Build:
```bash
cd Android/src
./gradlew clean assembleDebug
```

### To Install:
```bash
./gradlew installDebug
```

### To Test:
1. Clear app data: `adb shell pm clear com.google.aiedge.gallery`
2. Launch app
3. Try downloading a gated model
4. Verify dialog appears

---

## 📚 Related Files

### Existing Components Used:
- `SettingsDialog.kt` - Existing settings UI with token input
- `ModelManagerViewModel.kt` - Token management methods
- `DataStoreRepository.kt` - Token storage
- `ClickableLink.kt` - Clickable link component

### No New Files Created:
All functionality added to existing `DownloadAndTryButton.kt`

---

## ✨ Summary

**Total Changes:**
- 1 file modified
- ~40 lines added
- 0 new files
- 0 breaking changes

**Result:**
Users are now guided to set up HuggingFace authentication when needed, with clear instructions and minimal friction. The dialog only appears when necessary and never shows again once a token is configured.

# MAX AI — Gemini BYOK Android Starter v1.1

This is a native Kotlin + Jetpack Compose starter with an actual Gemini REST text request. It is not yet the full hands-free assistant.

## Included
- Native Android UI (no WebView)
- User-entered Gemini API key, kept in app memory only
- Gemini REST `generateContent` text request with editable model ID
- Android system TextToSpeech output
- Overlay settings flow and floating MAX bubble
- Accessibility service declaration and user-requested visible-text helper
- App launcher shortcuts: WhatsApp, ChatGPT, Termux
- Android BiometricPrompt owner check

## Build
Requires JDK 17 and Android SDK Platform 35. Set `sdk.dir` in `local.properties`, then:
```bash
gradle :app:assembleDebug
```
APK: `app/build/outputs/apk/debug/app-debug.apk`

Termux does not automatically include the Android SDK. Android Studio or a Linux build environment may be needed.

## API key warning
This is BYOK (bring your own key), without Firebase. The key is not hardcoded and is only held in memory in this starter. However, any direct mobile-client API key can potentially be inspected while in use. Do not distribute a shared developer key inside an APK. Restrict it to Gemini API and monitor usage.

## Not implemented yet
Gemini Live real-time audio-to-audio, Gemini prebuilt voice selector, wake word/background listening, complete call announce/answer flow, full cross-app accessibility action planner, WhatsApp sending, image generation integration, code editor/terminal execution, website builder, screenshot pipeline and conversation history.

## Android safety and platform limits
Android has no universal grant-all button. Accessibility and overlay are separately enabled in Settings. Call handling depends on default-dialer/call-screening roles and OS/OEM. Capture and sending actions must remain visible and user-confirmed. No covert photography, hidden forwarding, or bypassing Android security.

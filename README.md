# MAX AI (Android, Kotlin + Jetpack Compose)

## Status (honest)
- Source project is complete for the features listed below. It has NOT been compiled or run: the authoring environment had no Android SDK or internet. Expect to fix a small compile error or two on the first build. Paste the GitHub Actions log and it can be fixed.
- No APK is included. Build it with GitHub Actions (below), which needs no PC.

## Implemented
- Setup screen with real Android permission requests: microphone, overlay, accessibility, contacts, call, notifications, battery optimisation.
- "Hello MAX" wake word using Android SpeechRecognizer in a foreground service (microphone notification is always shown, Stop button included). Bare "MAX" also triggers it.
- Neon cyberpunk overlay (cyan/pink/violet) with listening, thinking and speaking animations that react to the voice level.
- Gemini brain: Hindi, Hinglish and English, short conversation context, Google Search grounded answers (weather, news, prices, general knowledge), translation, calculations, coding help.
- Sweet, soft female voice via Gemini TTS (default Leda; also Aoede, Zephyr, Achernar, Sulafat, Kore). Gemini models are tried in order (2.5 Flash, 2.5 Flash-Lite, 2.0 Flash for chat; 2.5 Flash TTS, 2.5 Pro TTS for voice), so if one is unavailable on the free tier the next is used. Android TTS only as a last fallback.
- Phone control: open apps, call, WhatsApp message, Chrome search or URL, notes, saved contacts, flashlight, volume, Back/Home/Recents/screenshot/lock, tap/type/scroll by text, read the screen (accessibility, needs your permission).
- Calls and WhatsApp messages are never sent silently: MAX asks "haan ya na" out loud first.
- Website builder: code streams live onto a transparent panel, the site is saved and opened in Chrome through a localhost server.
- Compose UI with the MAX logo, Yaadein (contacts), Notes and Settings tabs.

## NOT implemented
- Streaming audio-to-audio (Gemini Live) and interrupting MAX by speaking. You can tap the overlay to stop her.
- Groq provider. Gemini only.
- Camera scene understanding, OCR, image understanding.
- Notification reading, incoming call announcements, device info, long-term memory with consent controls.
- Room, DataStore, ViewModel, Retrofit. Data is in SharedPreferences, the API key is stored unencrypted on the device.
- Offline wake-word engine. The speech recognizer needs internet on most phones and some phones stop it in the background.

## API key
Get a free key at https://aistudio.google.com/apikey and paste it on the first screen. It is sent only to generativelanguage.googleapis.com.

## Build the APK with GitHub Actions (phone only)
1. Create a free account and an empty repository at github.com (for example max-ai).
2. Settings > Developer settings > Personal access tokens > Tokens (classic) > generate one with the "repo" and "workflow" scopes.
3. In Termux:
```
pkg update -y && pkg upgrade -y
pkg install -y git unzip curl
termux-setup-storage
cd ~/storage/downloads
unzip MAX-AI-Complete.zip
cd MAX-AI
git config --global user.name "YOUR_NAME"
git config --global user.email "YOUR_EMAIL"
git init -b main
git add .
git commit -m "MAX AI"
git remote add origin https://github.com/YOUR_USER/max-ai.git
git push -u origin main
```
Use your GitHub username and the token as the password.
4. Open the repo > Actions tab. The build takes about 5 to 10 minutes. When it is green, open Releases and download MAX-AI-debug.apk.
5. Or download and install straight from Termux:
```
curl -L -o ~/storage/downloads/MAX-AI-debug.apk https://github.com/YOUR_USER/max-ai/releases/latest/download/MAX-AI-debug.apk
termux-open --view ~/storage/downloads/MAX-AI-debug.apk
```
Android will ask to allow installs from Termux. Allow it, then tap Install.

Build on a PC instead: open the MAX-AI folder in Android Studio (Koala or newer), let Gradle sync, then Build > Build APK(s).
Termux cannot reliably build this project because it needs the full Android SDK and AAPT2 for Android.

## After installing
Open MAX, paste the key, press Allow on each card, then "Ho gaya". On the Accessibility screen open "MAX" and switch it on. On some phones also set Autostart and Battery to "No restrictions" for MAX.

## Troubleshooting
- Actions build red: open the failed step, copy the red error lines and send them over.
- "App not installed": uninstall any older MAX build first, then retry. If it still fails, free some storage.
- "Blocked / install unknown apps": Settings > Apps > Termux (or your browser) > Install unknown apps > Allow.
- `git push` asks again and again or says authentication failed: you must paste the token, not your password.
- `termux-setup-storage` shows no prompt: Settings > Apps > Termux > Permissions > Files > Allow, then run it again.
- Wake word does nothing: check mic permission, that the notification "MAX jaag rahi hai" is visible, and that battery is set to No restrictions. Tap the orb as a fallback.
- No voice or "Kuch gadbad ho gayi": wrong or expired Gemini key, or the free-tier rate limit. Settings tab > save the key again.
- WhatsApp message opens but is not sent: accessibility service is off, or WhatsApp changed its Send button. Tap Send once manually.


## Update (latest build)
- Gemini: uses gemini-2.5-flash and gemini-2.5-flash-lite for chat. gemini-2.0-flash has been retired.
- Wake word "MAX": quiet listening with a pause between rounds, so the mic does not loop or beep constantly.
- Overlay permission on: a Siri-style glowing rim appears around the screen, with the MAX orb in the middle.
- Voices: female (Leda, Aoede, Kore, Zephyr, Sulafat, Achernar, Callirrhoe, Pulcherrima) and male (Puck, Charon, Fenrir, Orus).
- Calls: MAX announces incoming calls and asks to pick up. Pick up and hang up need the ANSWER_PHONE_CALLS permission, and some phones block this.
- Photo opens the camera app. Android does not allow silent photos from another app.
- Screenshot works through the accessibility service (Android 9 and above).
- Yaadein shows every phone contact.
- Settings: light/dark mode, and an option to send WhatsApp messages and calls without asking (off by default).
- Wake word: after "MAX" only the glowing screen rim shows, no orb. MAX does not listen to its own voice, so it does not loop or cut itself off.
- After each reply MAX keeps listening for a follow-up, so you can talk without saying "MAX" every time.
- Emergency: mark contacts with SOS in Yaadein, then say "emergency SMS bhejo". MAX asks first, unless the "bina pooche" setting is on. Needs SEND_SMS permission.

## Update: Live video, second UI
- Live voice (Settings, beta, off by default) can now also stream back-camera frames into the same Gemini Live session every 2 seconds, when "Live video" is also turned on. MAX can then talk about what she sees while listening and speaking live. This depends entirely on your API key having Gemini Live access; if it doesn't, MAX falls back to the normal reliable voice flow and says so out loud.
- A second Home screen style ("Classic") is now available in Settings > UI style: a darker "Good morning, Boss" layout with a big clock, the MAX wordmark, a Tap to start button, and the real battery percentage. The original Neon Home screen stays the default.
- Declined this round: a 60+ feature Windows-PC automation list (Spotify, Excel, VSCode, PC temperature, Power Manager) that does not apply to an Android phone, and a full face-recognition "Owner Guard" biometric lock system — both are large, separate projects that need their own careful, tested pass rather than being bolted on blind.

## Critical fix: MAX stopped responding to everything
Two real bugs found by careful code review (not guessed):
1. Sending a plain typed message required microphone permission to even reach the service — if mic permission was ever missing or revoked, typing and pressing send did nothing at all, silently.
2. On Android 14+, starting the foreground service with the microphone type while that permission was missing would crash the service outright — again silently, from the user's point of view.
3. The boss-password guard was checked on every single message, not just sensitive ones (call, WhatsApp, emergency SMS). If a boss password was ever set and 3 wrong attempts happened, MAX would refuse to answer anything at all, including plain chat — this is almost certainly why nothing was getting a real reply. The guard is now scoped to only the three sensitive actions.

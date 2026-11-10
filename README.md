# Aegires

A lightweight, unofficial Discord client for Android, written in Java. Aegires talks directly to Discord's REST API and Gateway WebSocket, and builds its entire UI in code without XML layouts.

> Aegires is not affiliated with or endorsed by Discord Inc. Using a user token with third-party clients or automation may violate Discord's Terms of Service. Use at your own risk.

## Features

- Guild rail with categories, text channels, listed voice/forum channels, and direct messages
- Live message feed with history loading, gateway updates, typing indicators, and message grouping
- Discord-style markdown: bold, italics, underline, strikethrough, spoilers, inline code, code blocks, quotes, headings, and lists
- Custom emoji, user/role/channel mentions, timestamps, and slash-command rendering
- Replies, editing, deleting, copying message text, and copying message links
- Rich embeds, images, videos, file attachments, and link previews
- Forum channel browser with search, tags, pinned threads, activity sorting, and thread creation
- Member list, user profiles, roles, join dates, and about sections
- Mentions inbox for `@mentions`, `@everyone`, and `@here`
- Optional end-to-end encryption for messages and attachments
- Memory and disk caching for messages, media, channels, guilds, and member data
- Conscrypt-backed TLS for modern cipher support on older Android versions

## Requirements

- Android 7.0 (API 24) or newer
- JDK 17
- Android SDK Platform 33
- Gradle is provided through the Gradle wrapper

## Build

Clone the repository and build a debug APK:

```bash
git clone https://github.com/Curione23/Aegires.git
cd Aegires
./gradlew assembleDebug
```

If the Android SDK is not discovered automatically, create a `local.properties` file in the project root:

```properties
sdk.dir=/path/to/Android/Sdk
```

Build outputs:

- Debug APK: `app/build/outputs/apk/debug/`
- Minified debug build: `./gradlew assembleDebugr8` → `app/build/outputs/apk/debugr8/`

The build produces separate APKs for `arm64-v8a`, `armeabi-v7a`, and `x86_64`. Install the APK matching your device:

```bash
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

## Usage

1. Launch Aegires.
2. Paste a Discord token into the login field and tap **Log in**.
3. Choose a server or open DMs from the left rail.
4. Tap a channel to open it. Long-press a message for reply, copy, edit, and delete actions.

### End-to-end encryption

Encryption is optional and is configured per session:

1. Open the **Session** menu with the gear icon.
2. Choose **Set encryption key** and enter a password.
3. Pick an encryption preset (`AES-128-GCM`, `AES-192-GCM`, or `AES-256-GCM`).
4. Toggle the lock pill in the message composer before sending.

Everyone in the conversation must use the same password. This is Aegires' own encryption layer and is not Discord's official end-to-end encryption; other clients will only see the encrypted payload.

## Project Structure

```text
app/src/main/java/a/b/
├── AegiresApplication.java   # Application setup and Conscrypt provider
├── MainActivity.java         # Navigation, session state, gateway and cache wiring
├── api/
│   ├── ApiClient.java        # Discord REST API client
│   └── GatewayClient.java    # Gateway WebSocket client
├── model/                    # Discord payload models
├── screen/                   # Screens, adapters, renderers, and view factories
└── util/                     # Crypto, markdown, caching, media, storage, and UI helpers
```

The UI is built programmatically in Java. There are no XML layout resources. Shared visual tokens and small view helpers live in `util/Ui.java`, while each screen is a `Screen` implementation managed by `MainActivity`.

## How It Works

- **REST:** `ApiClient` handles authentication, guilds, channels, messages, members, profiles, mentions, forums, and uploads using OkHttp.
- **Gateway:** `GatewayClient` maintains a compressed WebSocket connection with heartbeats, resume support, guild/channel subscriptions, and automatic reconnect with backoff.
- **Caching:** messages, channel state, media, and member lists are cached in memory and on disk to keep screen changes fast.
- **Rendering:** `DiscordMarkdown` converts Discord's markdown into Android spans; `DiscordMessageRenderer` resolves mentions, emoji, timestamps, and links.
- **Encryption:** keys are derived with Argon2id, then per-message and per-file keys are produced with HKDF-SHA256. Payloads are encrypted with AES-GCM, padded, and bound to their channel, sender, and encryption preset.

## Notes and Limitations

- A Discord token is required; there is no OAuth login flow.
- The token is stored in app-private `SharedPreferences`. Use a dedicated token and log out when finished.
- Mention notifications are shown in-app; the app does not register for system push notifications.
- Forum, member-list, and mention features depend on the permissions and capabilities of the logged-in account.
- Voice channels are listed in the channel tree, but voice connections are not implemented.
- Aegires targets Android 13 (API 33) and runs on Android 7.0 and newer.

## Code Style

Java sources use 4-space AOSP-style indentation. Formatting and import sorting are handled with `google-java-format` in AOSP mode:

```bash
java -jar google-java-format-1.22.0-all-deps.jar \
  --aosp --skip-reflowing-long-strings --replace \
  $(find app/src/main/java -name '*.java')
```

## License

Copyright (c) Curione23. All rights reserved. See [LICENSE](LICENSE).

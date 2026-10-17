# GroupMe (native Android)

A native Kotlin + Jetpack Compose client for GroupMe. No WebView: it talks to GroupMe's REST API
and its Faye push service directly.

## Features
- Sign in with an access token (dev.groupme.com) or browser OAuth
- Groups and direct chats, message history with paging, send text and images, likes
- Live delivery via a foreground service (Faye over WebSocket), with 60 s polling fallback and catch-up on reconnect
- Notifications with inline Reply and Mark-as-read, per-chat mute, unread badges
- Local search over messages you have loaded (Room cache)
- Auto-start after reboot

## D-pad / small-screen use
Built for phones with no touchscreen and screens as small as 240x320:
- Everything is reachable with Up/Down/Left/Right, OK/Enter and Back; the focused item gets a visible highlight ring.
- Chat list: OK opens a chat; the top-bar icons refresh, search and open settings (no pull-to-refresh needed).
- Chat: Up/Down move between messages, OK opens an actions dialog (Like, Open image, Copy text). Enter in the
  message field sends. The paperclip icon attaches an image through the system picker.
- On screens narrower than 360dp or shorter than 480dp the UI switches to a compact layout: slimmer bars, smaller
  text, no avatars in chats, tighter spacing.
- The manifest declares the touchscreen as not required.
- Browser sign-in and image viewing need a browser app on the phone; token sign-in does not.

## Build
**Android Studio:** open this folder. If the Gradle wrapper jar is missing, run `gradle wrapper --gradle-version 8.9` once
(or let Studio use its bundled Gradle). Run the `app` configuration.

**GitHub Actions:** the workflow is manual only. Run "Build and Release" from the Actions tab (or `gh workflow run build.yml -f tag=v1.0.0`); it builds the APK and publishes a GitHub release with auto-generated notes.

Requires JDK 17, compileSdk 34, minSdk 26.

## OAuth (optional)
"Sign in with GroupMe" opens GroupMe's authorize page in an in-app WebView (not an external browser). When GroupMe
redirects to your callback URL with `?access_token=...`, the app catches that redirect itself and signs in; the
callback page is never loaded.

1. Create an application at https://dev.groupme.com/applications. GroupMe requires an HTTPS callback URL, but the
   app intercepts it, so any HTTPS URL works, for example `https://jmtdi.github.io/GroupMe/oauth/`.
2. Build the application's Client ID into the app (a public value, not a secret; there is no field to type it in): set `GROUPME_CLIENT_ID` as an env var or gradle property (`-PGROUPME_CLIENT_ID=...` or
   `~/.gradle/gradle.properties`), or for GitHub Actions add a repository *variable* named `GROUPME_CLIENT_ID`
   (Settings -> Secrets and variables -> Actions -> Variables). The "Sign in with GroupMe" button only appears when an ID is built in.
3. Tap "Sign in with GroupMe", log in inside the app; Back steps back through pages and then closes the window.

`docs/oauth/index.html` is an optional forwarding page (opens the app via `groupmeclient://oauth`) for use with an
external browser; the WebView flow does not need it. Token paste (no setup) always works.

## Notes and caveats
This was written without being compiled or run, so expect to fix a few build errors on the first build.
- The Faye push protocol is unofficial. Handshake/subscribe follow GroupMe's known behaviour, but payload shapes
  for `direct_message.create` and `favorite` events are best-effort.
- DM like/unlike uses conversation id `<lowerUserId>+<higherUserId>`; verify against live behaviour.
- Search only covers messages already downloaded.
- Unread badges count messages received while the service was connected or polled, not server-side read state.
- Some phones kill background services; exclude the app from battery optimisation (Settings screen has a shortcut).
- Signed release builds: add repo secrets `KEYSTORE_BASE64` (base64 keystore with private key), `KEYSTORE_PASSWORD`,
  `KEY_ALIAS`, `KEY_PASSWORD`. The release is then signed with your keystore. Without the
  secrets the release contains the debug-signed APK.

## Custom CA certificate
`res/raw/compass_tls_certificate.crt` is trusted in addition to the system CAs (see `res/xml/network_security_config.xml`),
for networks that inspect TLS. To use a different certificate, replace that file (keep the name) or edit the config.

## Layout
- `data/` Retrofit API, Room cache, DataStore prefs, `Repository`, `FayeClient`
- `service/` `PushService`, `Notifier`, notification action and boot receivers
- `ui/` Compose screens and view models; `MainActivity.kt` hosts navigation

# Stockfish for Android

A small chess app that bundles the Stockfish engine from this repository. You play against the
engine on your phone; the engine runs as a separate native process, exactly like Stockfish does on
a desktop, so every change under `src/` ends up in the app.

* Needs a 64-bit ARM phone with **Android 10 or newer**.
* The engine is the `arm64-universal` build (one binary that uses the faster dot-product code path
  automatically on phones that support it), compiled with the Android NDK and profile-guided
  optimization, like the official Android builds.

## Getting the APK

The workflow `.github/workflows/android_apk.yml` builds the APK automatically:

* **On every push, pull request and manual run** the APK is attached to the run
  (Actions > the run > *Stockfish-APK*; GitHub wraps it in a zip).
* **On pushes to the default branch** (or a manual run with *publish* ticked) the same APK is also
  published as the pre-release **android-latest** on the Releases page. That is the easiest place to
  download it on a phone: open the release and tap the `.apk`.

The first run is slow because it builds the engine. The engine is cached, so later runs that only
change the app (`android/**`) reuse it and finish much faster. Changing anything under `src/`
rebuilds the engine. Pushes to the default branch build it with profile-guided optimization (the
slowest, fastest-playing variant); other branches and pull requests get a quicker plain optimized
build. A manual run lets you choose.

### Signing

Without any setup the APK is signed with the standard Android *debug* key
(`android/signing/debug.keystore.b64`). It installs fine, and later builds can be installed over it
because the key never changes. To sign with your own key, add these repository secrets
(Settings > Secrets and variables > Actions): `ANDROID_KEYSTORE_BASE64` (the keystore file, base64
encoded), `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD`. Android will
not install an update signed with a different key over an existing install: uninstall first.

## Using the app

Tap or drag to move. **New** starts a game (pick your colour), **Undo** takes back your last move
and the reply, **Hint** shows the engine's suggestion, **Flip** turns the board, and **Settings**
has:

* opponent strength: from 1320 Elo up to the engine's full strength,
* time per move (0.25 s to 30 s),
* engine threads and hash memory (automatic by default: only the fast CPU cores are used, and the
  hash size follows the phone's RAM),
* the evaluation bar, sharing the game as PGN and copying the position as FEN.

The game is saved when the app goes to the background. The engine process is stopped then and
started again when you come back.

## Building by hand

1. Build the engine with the Android NDK (r28 or newer, `aarch64-linux-android29-clang++` on PATH):

   ```
   cd src
   make net
   LDFLAGS="-static -Wno-unused-command-line-argument" \
     make -j profile-build ARCH=arm64-universal COMP=ndk RUN_PREFIX=qemu-aarch64
   LDFLAGS="-static" make strip ARCH=arm64-universal COMP=ndk
   ```

   (`RUN_PREFIX=qemu-aarch64` runs the training step under the qemu emulator. For a quick build use
   `make -j build ...` instead of `profile-build`.)
2. Copy `src/stockfish` to `android/app/src/main/jniLibs/arm64-v8a/libstockfish.so`.
3. `cd android && gradle :app:assembleRelease` (Gradle 8.10, JDK 17, Android SDK 35), or open
   `android/` in Android Studio.

## Tests

The chess rules and the engine protocol code have no Android dependencies and are tested with plain
`java`; the workflow runs both on every build:

```
cd android
javac -d /tmp/t app/src/main/java/dev/stuckfih/chess/core/*.java tests/ChessCoreTest.java
java -cp /tmp/t ChessCoreTest            # standard perft suite, SAN, draw rules (add "full" for deeper perft)

javac -d /tmp/u app/src/main/java/dev/stuckfih/chess/core/*.java \
  app/src/main/java/dev/stuckfih/chess/UciProcess.java \
  app/src/main/java/dev/stuckfih/chess/EngineSession.java tests/EngineIntegrationTest.java
java -cp /tmp/u EngineIntegrationTest /path/to/a/uci/engine
```

## Credits and licenses

Stockfish is free software under the GNU GPL v3 (see `Copying.txt` in the repository root).
Chess piece artwork by Colin M.L. Burnett (GPL v2 or later), taken from the lichess.org
collection.

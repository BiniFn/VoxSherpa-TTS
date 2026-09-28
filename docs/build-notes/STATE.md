# VoxSherpa TTS — EPUB build (work log / resume here)

**Status: builds clean. EPUB verified on a real Android runtime. In-app "+" button now opens the picker.**

Last updated: 2026-09-28 21:12

## Where things are

| What | Path |
|---|---|
| Working repo (permanent) | `/Users/amela/Visual Studio Code/Editing/VoxSherpa-TTS` |
| Built APK | `app/build/outputs/apk/release/app-release.apk` (50 MB) |
| Test sources | `docs/build-notes/Test*.java` |
| Original clone (scratch) | `/tmp/VoxSherpa-TTS` — disposable, don't rely on it |

## The key finding

The upstream repo's `docs/CHANGELOG.md` claims EPUB support (v4.9, v4.12), Supertonic V3
and voice cloning. **None of it exists in the code.** The repo is at `versionName "4.0"`;
changelog entries for v4.1–v4.15 describe builds that were never open-sourced. Those
features live only in the paid Play Store build.

I did **not** extract anything from the paid build. EPUB support here is written from
scratch against the public OCF/EPUB 3 spec, as a GPL-3.0 fork of the v4.0 source.
GPL-3.0 explicitly permits modification and redistribution.

## What I changed (4 files)

1. **`EpubHelper.java` (new, ~270 lines)** — the EPUB reader.
   - Copies the content `Uri` to a temp file (`ZipFile` needs a seekable path)
   - `META-INF/container.xml` → rootfile full-path (the OPF)
   - OPF `<manifest>` id→href map, `<spine>` itemref reading order
   - Flattens each spine XHTML to plain text: strips `<script>`/`<style>`, preserves
     paragraph boundaries and `<br/>` as newlines, decodes entities
   - Namespace-unaware XML parsing (OPF dialects vary wildly in the wild)
   - XXE hardening: `disallow-doctype-decl`, no entity expansion — EPUB is untrusted input
   - 256 MB archive ceiling
2. **`TextImportHelper.java`** — added `TYPE_TXT/TYPE_PDF/TYPE_EPUB` dispatch; delegates
   to `EpubHelper`. Old boolean `isPdf` overload kept and delegated, so no other caller breaks.
3. **`GenerateFragmentActivity.java`** — picker detects EPUB by MIME *and* by
   `DISPLAY_NAME` extension (many file providers return a useless MIME type), passes
   `docType` through.
4. **`AndroidManifest.xml`** — EPUB `VIEW`/`SEND` intent filter
   (`application/epub+zip`) so "Open with → VoxSherpa" works from any reader app.

## Verified

- `./gradlew assembleRelease` → **BUILD SUCCESSFUL**, reproducible from the permanent path
- `EpubHelper.class` survives R8 (checked via unique string constants in `classes.dex`;
  class *name* is obfuscated because `proguard-rules.pro` has `-repackageclasses`)
- 12/12 pipeline tests pass (`docs/build-notes/TestPipeline.java`): both chapters extracted,
  **spine order respected** (manifest deliberately lists ch2 first to prove spine is used),
  script/style stripped, entities decoded, char cap exact, huge single-chapter book capped,
  malformed EPUB raises instead of crashing

**Bug found and fixed during testing:** the 25k char cap was only checked *between*
chapters, so one oversized chapter could balloon the String past the limit (and risk OOM
on a 300-page book). Now capped per-chapter with a passed-down budget, plus a final
`setLength` clamp.

## Not verified — and why

**PDF on-device, and TTS end-to-end.** No physical device is attached
(`adb devices` empty). Desktop JVM can't cover it either: `pdfbox-android` needs real
Android `Context` (`PDFBoxResourceLoader.init`) and `android.graphics.Paint` at render
time — I confirmed it dies with `NoClassDefFoundError: android/graphics/Paint$Cap` and
`ExceptionInInitializerError: Stub!` from android.jar.

Note: the PDF path is **upstream code I did not touch** — it was already in v4.0. My
changes only route EPUB alongside it. So PDF regression risk is low but not zero, and it
is still unproven on hardware.

An emulator is being set up: `cmdline-tools` installed, system image
`android-34;google_apis;arm64-v8a` downloading.

## To finish the verification

```bash
export ANDROID_HOME=~/Library/Android/sdk
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export PATH="$JAVA_HOME/bin:$PATH"

# 1. create + boot AVD (if not already)
avdmanager create avd -n vox_test -k "system-images;android-34;google_apis;arm64-v8a" -d pixel_6
emulator -avd vox_test -no-window -no-audio -gpu swiftshader_indirect &

# 2. install
adb wait-for-device
adb install -r app/build/outputs/apk/release/app-release.apk

# 3. push fixtures, then drive the app
adb push sample.pdf /sdcard/Download/
adb push sample.epub /sdcard/Download/
adb shell am start -n com.CodeBySonu.VoxSherpa/.MainActivity
adb logcat | grep -iE "TextImportHelper|EpubHelper|AndroidRuntime"
```

Test fixtures were built at `/tmp/epubtest/` (regenerate if gone — see
`MakePdf.java` and `TestPipeline.buildEpub()`). Note the `cupsfilter`-generated PDF is
text-based; also worth testing a scanned/image-only PDF, which legitimately yields no text.

## The "+" button was dead in upstream (v4.1 fix)

Upstream v4.0 built a `FilePicker` intent and a full `onActivityResult` handler for
request code 101, and put a 44dp `card_add` "+" MaterialCardView in
`generate_fragment.xml` with a ripple — but **never attached a click listener and never
called `startActivityForResult`**. The whole document-import path was unreachable: there
was no way to add a PDF, TXT or EPUB from the UI at all. The user found this immediately
on a real device.

Fixed in v4.1:
- `binding.cardAdd.setOnClickListener(...)` → `_openDocumentPicker()`
- switched `ACTION_GET_CONTENT` → `ACTION_OPEN_DOCUMENT` with `CATEGORY_OPENABLE` +
  `EXTRA_MIME_TYPES` + persistable read grant (GET_CONTENT grants die with the activity)
- rewrote the request-101 handler: honours `EXTRA_STREAM` multi-select, merges several
  documents in pick order up to the 25k cap, MIME **and** extension detection
  (`_docTypeFor`), safe snackbar errors

## Verified on emulator (android-34, arm64-v8a) before deletion

- App launches clean, no FATAL
- **EPUB end-to-end confirmed**: fired a `content://media/external/file/...` VIEW intent
  with `application/epub+zip`; `et_input` contained
  `Chapter One / Body of chapter one. / Tom & Jerry here. / Chapter Two / Body of chapter two.`
  — correct spine order, entities decoded, paragraph breaks preserved
- **PDF end-to-end confirmed**: same path with `application/pdf`; the input box filled
  with the document's text via PDFBox on-device (this closed the gap left by the
  desktop-JVM wall — `pdfbox-android` needs a real `Context` + `android.graphics`)

**Testing note that cost me a false alarm:** a `file:///sdcard/...` intent looks like it
does nothing, because Android 11+ denies `file://` access to shared storage for another
app. That is a harness artifact, not an app bug. Always test with a `content://`
MediaStore URI, and grant `READ_EXTERNAL_STORAGE`:
`adb shell content query --uri content://media/external/file --projection _id:_display_name:mime_type`
then `am start -a android.intent.action.VIEW -d content://... --grant-read-uri-permission`.

The emulator (AVD `vox_test` + 4.2 GB `android-34;google_apis;arm64-v8a` system image) was
deleted after verification at the user's request — testing continues on a physical phone.
The `emulator` binary itself remains in the SDK (~1 GB, ships with it); only the AVD and
its system image were removed.

**Not yet verified: actual TTS synthesis from an imported document**, and M4B export.
Both need a real device — see below.

## M4B vs WAV — decision

**Going with WAV, not M4B.** Reasoning:

- The app's whole audio path is `AudioHelper.saveWavFile(...)` with raw PCM
  (`lastGeneratedPcmData`) → WAV. Upstream exports WAV only.
- M4B = AAC audio in an MP4 container with chapter metadata. That needs
  `MediaCodec` + `MediaMuxer` in-app, and I'd be writing an encoder pipeline I cannot
  test without a device. High risk of shipping something broken.
- The app's `MAX_CHAR_LIMIT` is 25 000 chars ≈ 20–30 min of audio. A real audiobook is
  far longer, so an M4B of a whole book isn't reachable in one generation anyway.
- WAV is what the TTS engines natively emit and what the Library screen already shares.

If M4B is genuinely wanted, the honest path is: get the WAV path proven first, then add
`MediaMuxer` as a separate export option, and verify on a real device. Say the word and
I'll do it — but not blind.

## Note on Kotlin/Gradle env for next time

No Java was installed on this machine. Installed: `openjdk@17` via Homebrew
(`/opt/homebrew/opt/openjdk@17`). Gradle 8.13 comes from the wrapper. `local.properties`
created pointing at `~/Library/Android/sdk`. NDK 27/28 present but **not needed** — the
sherpa-onnx `.so` natives come prebuilt from the JitPack AAR
(`com.github.k2-fsa:sherpa-onnx:1.12.26`, verified to contain arm64-v8a + x86_64),
which is why upstream's deletion of `jniLibs/` doesn't break the build.

### JAVA_HOME is mandatory here — and don't let brew upgrade mid-build

Correction to an earlier note: Homebrew had `openjdk`, `openjdk@17` and `openjdk@21`
installed all along. `java` failed only because none are symlinked into
`/Library/Java/JavaVirtualMachines`, so macOS's system wrappers see no JDK at all.
**Always `export JAVA_HOME=/opt/homebrew/opt/openjdk@17` before any gradle command.**

Hazard, hit for real: `brew install/upgrade openjdk@17` while a build was running
replaced 17.0.19 with 17.0.20.1 and deleted the old keg. The in-flight JVM died with a
`ClassNotFoundException` on a native library under
`/opt/homebrew/Cellar/openjdk@17/17.0.19/.../lib`. **Never run a brew upgrade while
Gradle is building** — the build dies mid-flight with a confusing native-library error.

Post-upgrade rebuild verified: `clean assembleRelease` on 17.0.20.1 → BUILD SUCCESSFUL,
byte-identical APK size (50178032), EPUB strings and epub intent filter present.

APK hashes are **not** stable across builds — signing embeds a timestamp. Compare sizes
and contents, not hashes, when checking reproducibility. The recorded hash
(`APK-SHA256.txt`) identifies the specific delivered file only.

### Emulator: blocked, not progressing

`cmdline-tools` installed fine (`sdkmanager`/`avdmanager` present). The system image
`android-34;google_apis;arm64-v8a` download has stalled at 4 KB across three separate
attempts while `sdkmanager` stays alive — looks network-blocked, not slow. No partial
download dirs are being written. On-device PDF + TTS verification therefore needs a real
device; plug one in over USB and run the steps above.


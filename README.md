# multistream

One Android app (phone/tablet **and** Android TV / Google TV) that federates the catalogs of
several installed streaming apps: search across them, see show information, **launch directly** into
the right app at the right title, and track **locally** what you have watched and where you are in a
series.

The five services: **Netflix**, **Disney+**, **Prime Video**, **Molotov**, **Zattoo**.

## Design in one paragraph

Launch + local watch-tracking is the always-works spine; catalog search is a best-effort, per-
provider capability layered on top. Each provider is a self-contained leaf module that advertises
`ProviderCapabilities` (can it search? deep-link a title? an episode? is it live TV?), and the UI
reads those flags and degrades gracefully — a provider that cannot search still launches and tracks.
There is no DI framework: a small hand-written `AppGraph` wires everything and composes the five
providers into a registry.

## Current status — M0 spine + search for all 5 services (M1–M3)

| Capability | State |
|---|---|
| Deep-link **launch** into all 5 apps | ✅ (title page; Zattoo opens the app, see notes) |
| **Local** watch tracking (watched/unwatched, series next-episode, watchlist, continue-watching) | ✅ series episode lists come from Disney+ / Netflix detail |
| Per-provider **region** setting + **login** | ✅ |
| Phone + Android-TV adaptive shell | ✅ form-factor detection; poster art (Coil), incremental search, results badged by service with LIVE/REPLAY labels (TV-optimized leanback UI still later) |
| Catalog **search** — Molotov, Zattoo, Disney+ | ✅ implemented (M1–M2); needs live verification on a device with your accounts |
| Catalog **search** — Netflix, Prime | ✅ best-effort web search via WebView login (M3); Netflix is fragile, Prime the most fragile/unverified |

A small built-in sample catalog remains so the flow is demonstrable offline; remove it once live
search is confirmed. Search providers need login (Settings → Log in) and run only on a device with
network — see the verification note below.

Per-provider rollout (search): **Molotov, Zattoo** (M1), **Disney+** (M2), and best-effort
**Netflix/Prime** (M3) done — all five now search. See the approved plan in `/home/r/.claude/plans/`
for the full reverse-engineering methodology.

### M1–M3 reverse-engineering notes

- **Molotov** is now a Fubo app, but its front API is still `https://fapi.molotov.tv/`. The client
  is modeled on the maintained Home Assistant integration
  (github.com/renaudallard/homeassistant_molotov_tv): `POST v3.1/auth/login` (bearer + refresh),
  the `X-Molotov-Agent` identity header, and `POST v2/search`. Premium account required.
- **Zattoo** is a React Native app using the classic zapi (per the Kodi `pvr.zattoo` addon):
  app token from the homepage → `session/hello` → `v2/account/login` → `v2/session`
  (`power_guide_hash`). Search filters the program guide (`power_guide`) by title.
- **Disney+** uses the bamgrid GraphQL API (per the `pydisney` wrapper): client API key from the
  homepage → `registerDevice` → `login` → `switchProfile` for tokens, then `GET /explore/v1.7/search`.
  Full GraphQL query text is sent (no persisted-hash issue). PIN-protected profiles are skipped, and
  tokens are cached/refreshed because repeated logins can trigger account blocks. Series episode
  lists come from the entity page plus one call per season.
- **Netflix** app API is MSL-encrypted, so search uses the website (Kodi CastagnaIT addon): a WebView
  login captures cookies, the page's `reactContext` yields the member API base + authURL, a
  `pathEvaluator` Falcor request returns the matched titles, and `/metadata` returns full
  seasons/episodes. Fragile (BUILD_ID rotation, bot defenses).
- **Prime Video** (most fragile, best-effort) uses the primevideo.com web search with cookies from a
  WebView login, walking the embedded `text/template` JSON for titles (as the Kodi amazon addon's
  GrabJSON does). Unverified; expect to adjust it against live traffic.

Netflix and Prime authenticate with a one-time **WebView login** (Settings → "Log in (browser)") that
captures cookies into the encrypted secret store; the other three use an email/password form.

### Deep-link notes (verified from each app's manifest)

- **Netflix** `https://www.netflix.com/title/<id>` (+ `nflx://`), plus an in-app search deep link.
- **Disney+** `https://www.disneyplus.com/...` (auto-verified app links).
- **Prime** `https://app.primevideo.com/detail?gti=<ASIN>`. The bundled APK is the TV
  ("living-room") build; on phones the mobile package `com.amazon.avod.thirdpartyclient` is tried.
- **Molotov** `molotov://` / `app.molotov.tv` app links (carried as a deep-link hint).
- **Zattoo** the manifest exposes only `zattoo://zattoo.com` with no title path, so v1 opens the app
  (search still works once wired); title-level deep links are deferred until reverse-engineered.

## Modules

```
app                     UI (Compose + Compose-for-TV), nav, hand-written AppGraph, sample catalog
core/model              pure Kotlin: Title/Season/Episode/Availability/ProviderRef/TitleKey,
                        normalizeTitle(), mergeResults(), computeNextEpisode()
core/data               Room (tracking + disposable cache), DataStore settings, encrypted secrets
provider/api            StreamingProvider interface, ProviderCapabilities, Launcher, DeepLinks
provider/{netflix,disney,prime,molotov,zattoo}   one leaf module per service
```

`feature/*` and `core/*` never depend on a concrete provider — only `app` wires them, so a flaky
provider stays contained.

## Build & run

Prerequisites on this machine: **JDK 21** (`/usr/lib/jvm/java-21-openjdk-arm64`) and the **Android
SDK** at `~/Android/Sdk` (platform `android-35`, build-tools 35). The system `gradle` is too old —
always use the wrapper.

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64
./gradlew assembleDebug      # builds app/build/outputs/apk/debug/multistream-debug.apk
./gradlew test               # runs the JVM unit tests
./gradlew installDebug       # installs to a connected device/emulator (adb)
```

Release build (signed + R8-shrunk):

```bash
# Signing creds live in keystore.properties (git-ignored): storeFile, storePassword, keyAlias, keyPassword.
# A dev key (multistream-release.keystore) is used by default; swap in your own for Play distribution.
./gradlew :app:assembleRelease   # -> app/build/outputs/apk/release/multistream.apk  (~2.3 MB, v2-signed)
./gradlew :app:bundleRelease     # -> app/build/outputs/bundle/release/multistream-release.aab (Play upload)
```

`local.properties` (git-ignored) points Gradle at the SDK: `sdk.dir=/home/r/Android/Sdk`.

### Installing the target streaming apps (for deep-link testing)

They live in `apks/`. Netflix is a plain APK; the others are split `.xapk` bundles, so unzip and use
`install-multiple`:

```bash
adb install "apks/Netflix_9.65.0+build+9+64253_APKPure.apk"
# for each .xapk: unzip it, then
adb install-multiple <pkg>.apk config.*.apk
```

Verify a deep link directly:

```bash
adb shell am start -a android.intent.action.VIEW -d "https://www.netflix.com/title/80057281" com.netflix.mediaclient
```

## Testing & verification

JVM unit tests (run anywhere):

- `core/model` — title reconciliation/merge (year tolerance, type guard, external-id match) and the
  next-episode computation.
- `provider/api` — the deep-link URL formats (`DeepLinks`).
- `provider/{molotov,zattoo,disney,netflix,prime}` — the API clients (login/session + search parsing)
  replayed against OkHttp `MockWebServer` (plain HTTP, no Android runtime needed).

Room DAO SQL is validated at compile time by the Room KSP processor.

**Environment limitation (this host):** it is headless **aarch64 with no `/dev/kvm`**, so the Android
emulator cannot run, and Robolectric cannot run either (Conscrypt ships no `linux-aarch_64` native).
Android-runtime tests (Room integration, intent resolution) and on-device runs must therefore be done
on an **x86_64 machine, a KVM-enabled host, or a physical device** over `adb`. Everything that does
not need an Android runtime is verified here (build to a working APK + the JVM tests above).

## Reverse-engineering inputs

`tmp/` (git-ignored) holds the decompiled manifests and extracted inner APKs used to verify deep
links and, in later milestones, the catalog APIs. `jadx` and `mitmproxy` are installed on demand when
the search milestones begin.

## Legal / personal use

For personal use with your own accounts. Reverse-engineering these services' private APIs may violate
their terms of service. The app never bypasses DRM — playback always happens inside the official app;
multistream only queries catalogs and fires a deep-link intent.

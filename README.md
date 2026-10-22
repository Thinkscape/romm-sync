<div align="center">

# 🎮 RomM Sync — Android & Linux

🌐 **English** · [Español](README.es.md)

**Download and sync your [RomM](https://github.com/rommapp/romm) library on Android and Linux.**

Cross-platform client for RomM servers: browse your library, download ROMs straight into the folder structure of your favorite frontend, and keep save files synced across all your devices — Android handheld, Steam Deck, PC.

- **Android** (Kotlin + Jetpack Compose): native APK, optimized for handheld consoles (Anbernic, Retroid Pocket...) with a touchscreen: dark UI, large buttons and finger-friendly navigation.
- **Linux** (Compose Desktop): self-contained AppImage for **x86_64** (SteamOS, Steam Deck, PCs) and **aarch64** (Raspberry Pi 5, ARM handhelds) with the Java runtime bundled in — nothing to install.

</div>

> **Fork notice.** This is `Thinkscape/romm-sync`, a fork of [davidadrianrg/romm-sync](https://github.com/davidadrianrg/romm-sync) (MIT, © David RG) maintained by Thinkscape. It adds RomM device pairing (`/api/auth/device/*`), push-install from the RomM web UI ("Install on device") via the `/devices` socket and install claims, a headless Linux daemon (`romm-sync --daemon`, `--pair`), a RomM-slug → ES-DE/Cocoon folder map (`core/src/main/resources/platform_folders.json`) and slot-based save/state sync. Cocoon has no public rescan intent, so the Android app's accessibility service "RomM Sync: Cocoon and ES-DE auto-import" (`install/CocoonRescanService.kt`) runs Cocoon → Settings → Library & data → Rescan Games itself after a push batch, only while Cocoon's home screen is in front (never over a game), and otherwise posts a manual-rescan notification (Settings toggle "Cocoon automation", default on; off stops every Cocoon flow while the same service keeps refreshing ES-DE on Android); the Linux daemon restarts an idle ES-DE (see below).
>
> **Platform folders.** Every install path (push, collection sync, uninstall fallback, hand downloads, library scan, metadata export) resolves the device folder with `PlatformFolders.folderFor(slug, fsSlug)` (`core/.../download/PlatformFolders.kt`): first `platform_fs_folders.json` (RomM slug → platform `fs_slug` → folder), then `platform_folders.json` (RomM slug → folder). RomM's `arcade` slug installs into `arcade`; arcade platforms whose library folder is `naomi`, `atomiswave`, `daphne` or `triforce` go to the ES-DE system of the same name. The `fs_slug` comes from the rom's `platform_fs_slug` (push/download) or the platform's `fs_slug` (scan/export).
>
> **Media on push.** After each pushed install (`done` or `already_installed`, reported first), the client writes RomM's scraped media and metadata in ES-DE's layout (`core/.../metadata/EsdeMediaExporter.kt`, shared with the manual "Export to ES-DE" actions): media to `<MediaDirectory>/<system>/{covers,screenshots,titlescreens,marquees,fanart,3dboxes,backcovers,physicalmedia,miximages,videos,manuals}/<rom stem>.<ext>`, and the game's entry (name, desc, rating, releasedate, developer, publisher, genre, players) upserted into `<ES-DE dir>/gamelists/<system>/gamelist.xml`, keeping other games and ES-DE's own fields. Linux daemon: ES-DE dir `~/ES-DE` (setting `esde_data_dir`), `MediaDirectory` from its `settings/es_settings.xml`, off with pref `esde.media_on_install=false`. Android: setting "ES-DE / Cocoon metadata folder" (default `<ROMs root parent>/ES-DE` on removable storage) with `downloaded_media/` inside; in Cocoon select it under Settings → Library & Data → ES-DE Integration and enable startup checks.
>
> **ES-DE refresh on push (Linux daemon).** ES-DE has no reload/IPC and only scans ROMs and gamelists at startup or on Utilities → Rescan ROM directory, so ~5 s after a batch of `done` installs (after their media export) the daemon restarts a running ES-DE that sits idle in its UI (`desktop/.../LauncherRefresh.kt`): never while ES-DE has a child process (a game) or another Steam game runs (it waits and retries), clean SIGTERM, then the same AppImage/arguments via `systemd-run --user` (Desktop Mode) or `steam steam://rungameid/<SteamGameId>` when Steam launched it (Game Mode). ES-DE returns to its start view, like a rescan. Logs `launcher: es-de refreshed (<method>)` / `launcher: skipped (<reason>)`; off with pref `launcher.refresh_on_install=false`.
>
> **Uninstall and collection sync.** The client advertises `remote_uninstall` and `collection_sync`. Install requests with `action=uninstall` delete exactly the files recorded at install (fallback: RomM's file list at the install placement) plus folders left empty — never saves/states or foreign files — then clean the launcher (`LauncherGameRemover`) and report `done`/`not_installed`/`failed`. Collections marked "Sync to device" in RomM (`GET /api/devices/{id}/collections`, socket `collections:changed`) are reconciled by `core/.../collections/CollectionReconciler.kt`: missing members are installed through the push pipeline, each rom carries provenance markers (`manual` for pushes, hand downloads and anything already present; `collection:<kind>:<id>` per collection) and is uninstalled only when its last marker goes. On Linux the launcher collection is an ES-DE custom collection (`desktop/.../EsdeCollections.kt`: `custom-<name>.cfg` + `CollectionSystemsCustom`, edited only while ES-DE is closed); on Android a Cocoon collection.

---

## 📥 Installation

### Option A — Android (recommended)

1. Download `RomM-Sync-vX.Y.Z-android.apk` from [GitHub Releases](https://github.com/Thinkscape/romm-sync/releases/latest).
2. Configure your server (see below).
3. In **Settings → Updates**, tap *Check for updates* whenever you want to check for a new version. The app downloads the APK and launches the Android installer — without leaving the app.

> ⚠️ The first time, Android will ask you to grant RomM Sync the **"Install unknown apps"** permission. It is the standard permission for any app to self-update outside the Play Store.

### Option B — Linux (AppImage)

1. Download the AppImage for your architecture from [GitHub Releases](https://github.com/Thinkscape/romm-sync/releases/latest):
   - `RomM-Sync-vX.Y.Z-linux-x86_64.AppImage` — SteamOS, Steam Deck, most PCs
   - `RomM-Sync-vX.Y.Z-linux-aarch64.AppImage` — Raspberry Pi 5, ARM handhelds
2. Give it execute permission and run it:
   ```bash
   chmod +x RomM-Sync-v*-linux-*.AppImage
   ./RomM-Sync-v*-linux-x86_64.AppImage
   ```
3. The first time it asks for the server URL, API Key and ROMs folder; it is saved for next time.

> The AppImage bundles the jlinked Java runtime inside (~70 MB): you don't need to install Java or anything else.

### Option C — Build from source

```bash
git clone https://github.com/Thinkscape/romm-sync.git
cd romm-sync
./gradlew assembleDebug
# APK → app/build/outputs/apk/debug/app-debug.apk
```

Requirements: JDK 17 and Android SDK (API 35). For the desktop: just JDK 17 (`./gradlew :desktop:packageReleaseUberJarForCurrentOS`). To sign your own release, create `keystore.properties` at the root (it's in `.gitignore`):

```properties
storeFile=/path/to/your.keystore
storePassword=***
keyAlias=***
keyPassword=***
```

## ⚙️ Initial setup (2 minutes)

| What | Value |
|---|---|
| **Server URL** | `https://romm.yourdomain.com` (your RomM 4.9+ instance for sync) |
| **API Key** | `rmm_...` token from *RomM → User → API Keys* |
| **ROMs directory** | `/storage/emulated/0/ROMs` (or your microSD) |

The app requests the **"All files access"** permission — it needs it to write ROMs into the folders your emulator/frontend expects, without copying or moving anything manually.

<details>
<summary><b>Why an API Key and not username/password?</b></summary>

RomM supports OAuth/OIDC login behind proxies (Authentik, Authelia...), which usually breaks non-web clients with redirects and CSRF tokens. The API Key is a long per-user token that always works, whatever your web authentication setup.
</details>

## ✨ What the app does

- **📚 Library** — Browse your RomM server with cover art, search and filters (all / missing / downloaded). Detects games already present on disk with *Scan library*.
- **⬇️ Downloads** — Queue with configurable parallel downloads (1–5), automatic resume if the network drops, hash-based integrity verification and an out-of-space warning. *WiFi-only* option to avoid using data.
- **💾 Save sync** — Your save files always up to date on every device, using RomM's [Device Sync Protocol](https://docs.romm.app/latest/developers/device-sync-protocol/) (v4.9+): upload what changed on the handheld, download what you played on the PC. Resolves conflicts explicitly instead of overwriting.
- **🗂️ Metadata export** — Generates ES-DE `gamelist.xml` files or copies the media (covers, fanart, logos, screenshots) into the RetroHRAI structure.
- **🔄 Auto-update** — Checks, downloads and installs new versions from GitHub Releases without uninstalling anything (see Installation).
- **🖥️ Linux on par with Android** — The desktop app shares the theme ("Midnight Arcade"), library with cover art and full game details (screenshots, IGDB metadata), download queue with cancel/retry/resume, save sync with conflict resolution and ES-DE export with non-destructive merge.
- **🌐 English & Spanish UI** — The interface is available in both English and Spanish.

### Emulators supported for save sync

| Platform | Emulator (default) |
|---|---|
| Retro (NES–N64, GBA, PSX...) | RetroArch |
| Nintendo DS | melonDS |
| PSP | PPSSPP |
| PS2 | AetherSX2 / NetherSX2 |
| GameCube / Wii | Dolphin |
| 3DS | Azahar |
| Wii U | Cemu |
| Switch | Eden |
| Native Android games | per-game configurable path |

> 💡 PS2 requires enabling **Folder Memory Card** in the emulator to sync per game.

## 🖼️ Permissions it uses and why

| Permission | Reason |
|---|---|
| All files access | Write ROMs/metadata into the folders the emulators read |
| Install unknown apps | Only if you use auto-update: it installs the APK it downloads itself |
| Notifications | Download progress and background sync |
| Ignore battery optimization | So the periodic sync isn't killed by the system |
| Internet | Talk to your RomM server and to GitHub (check for updates) |

No telemetry, no analytics, no accounts: your data goes from your device to **your** server.

## 🏗️ Architecture (for contributors)

Monorepo with three Gradle modules that share all the business logic:

```
core/src/main/kotlin/es/davidrg/rommsync/core/   # Pure JVM — shared Android + Desktop
├── remote/              # Retrofit + OkHttp (RomM API, Device Sync Protocol)
├── sync/platform/       # Per-emulator save handlers (RetroArch, melonDS, PS2, PS3, 3DS...)
├── download/            # PlatformFolders (RomM slug -> ES-DE/Cocoon folder)
├── auth/                # DevicePairing (RomM device pairing flow)
├── install/             # InstallClient (push-install / uninstall from RomM)
├── collections/         # CollectionReconciler ("Sync to device" collections, provenance)
└── util/                # RootShell, ROM header reading...

app/src/main/java/es/davidrg/rommsync/           # Android
├── data/local/          # Room + DataStore (persistent config)
├── data/repository/     # remote ↔ cache bridge
├── data/metadata/       # gamelist.xml / media export
├── data/update/         # Auto-update from GitHub Releases
├── download/            # WorkManager + resumable download
├── ui/                  # Compose Material3 (screens/viewmodel/components)
└── util/                # Android permissions

desktop/src/main/kotlin/es/davidrg/rommsync/desktop/  # Linux (Compose Desktop)
├── Main.kt / AppContent.kt   # UI (connection, library, downloads, sync)
├── DesktopSyncCoordinator.kt # Sync loop without Room/WorkManager
├── DownloadEngine.kt         # Download stream→disk (single file + zip-stream)
└── DesktopConfig.kt          # java.util.prefs (persistent config)
```

- **Common stack**: Kotlin · Retrofit/OkHttp · Moshi · Coroutines
- **Android**: Jetpack Compose (Material 3) · Room · WorkManager · DataStore · Coil
- **Desktop**: Compose Desktop · AppImage (jlinked runtime included)
- **UI languages**: the interface is available in English and Spanish.
- **Code language**: English in identifiers/logs; UI texts are localized.
- **Tests**: `./gradlew test` (unit tests in `core/src/test` and `app/src/test`).

## 🔁 CI/CD

Every push to `master`:

1. **APK** (`.github/workflows/build-release.yml`): signed build (R8) → GitHub Release as `RomM-Sync-vX.Y.Z-android.apk`.
2. **AppImage** (`.github/workflows/build-appimage.yml`): build for **x86_64** and **aarch64** → `RomM-Sync-vX.Y.Z-linux-<arch>.AppImage`, attached to the same release as the APK.

The tag and `versionName` match **exactly** the `versionName` in `app/build.gradle.kts` (e.g. `0.4.2` → `v0.4.2`); bump it there to publish a new version. The internal `versionCode` uses the workflow `run_number`, which always increases (Android requires it to update without uninstalling). Publishing the same version again replaces its release.

The release APK is signed with a fixed key (secrets `SIGNING_KEYSTORE_BASE64` and derivatives) so updates install over the previous version without uninstalling. **Keep a backup of the keystore**: if it is lost, users would have to uninstall in order to update.

## 🤝 Contributing

1. Fork + branch (`feat/my-thing`).
2. `./gradlew test` green.
3. PR to `master` — CI must pass.

Bugs, ideas and PRs welcome in [Issues](https://github.com/Thinkscape/romm-sync/issues).

## 📄 License

MIT

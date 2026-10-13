# Design: Save Game Synchronization

Design document for the bidirectional synchronization of saves and states
with the RomM server.

## Server protocol (Device Sync Protocol, RomM 4.9+)

Reference: https://docs.romm.app/latest/developers/device-sync-protocol/

### Flow

1. **Device registration** — `POST /api/devices`
   - Sent once, the `device_id` is cached locally.
   - Includes name, platform (`android`), hostname, local paths.

2. **Negotiation** — `POST /api/sync/negotiate`
   - The client sends the list of ROMs it has downloaded with their saves:
     `{ rom_id, saves: [{ file, mtime, sha1 }] }`
   - The server responds with operations: `upload`, `download`, `conflict`, `noop`.

3. **Operation execution**
   - `upload`: `POST /api/saves` (multipart)
   - `download`: `GET /api/saves/{id}/content`
   - `conflict`: requires user resolution (or the default policy).

4. **Session close** — `POST /api/sync/sessions/{session_id}/complete`

### Authentication

Bearer token (`rmm_...`), the same one we already use. Required scopes:
`assets.read`, `assets.write`, `devices.read`, `devices.write`.

---

## App architecture

### New components

```
data/sync/
├── SaveSyncWorker.kt              # CoroutineWorker (WorkManager)
├── SyncCoordinator.kt             # Orchestrates negotiate → execute → complete
├── SaveSyncManager.kt             # UI-facing API (trigger + observe state)
└── platform/                      # Per-platform/emulator handlers
    ├── SaveHandler.kt             # Base interface + LocalSave
    ├── SaveHandlerRegistry.kt     # Picks a handler by (platform, emulator)
    ├── RetroArchSaveHandler.kt    # Flat .srm/.state files per slug
    ├── MelonDsSaveHandler.kt      # DS: flat .sav per ROM name
    ├── PpssppSaveHandler.kt       # PSP: folders per disc-id (zip)
    ├── Ps2SaveHandler.kt          # PS2: folder memory card per serial (zip)
    ├── DolphinSaveHandler.kt      # GC: GCI per game-id; Wii: title/data (zip)
    └── SwitchSaveHandler.kt       # Switch (Eden): folder per title-id (zip)
```

> Status: implemented. The protocol DTOs live in
> `data/remote/dto/SyncDto.kt` and the endpoints in `RomMApiService`.

### SaveHandler interface

```kotlin
interface SaveHandler {
    /** Locates the save files for a given ROM. */
    suspend fun findSaves(rom: Rom, config: PlatformSyncConfig): List<LocalSave>

    /** Prepares a save for upload (zip if it's a folder, raw if it's a file). */
    suspend fun prepareForUpload(save: LocalSave): File

    /** Extracts a downloaded save to the correct destination on disk. */
    suspend fun extractDownload(tempFile: File, rom: Rom, config: PlatformSyncConfig): Boolean
}
```

---

## User configuration

### On the Platforms screen (new field per platform)

- **Saves path** (override): by default it is computed from the emulator,
  but the user can change it.
- **Emulator** (selector): determines which handler to use. Options depend on
  the platform (e.g. DS: melonDS / RetroArch).

### In General settings (new field)

- **RetroArch base path**: defaults to `/storage/emulated/0/RetroArch`

---

## Saves paths per platform

### RetroArch (general case, ~80% of platforms)

Structure with subfolders per platform slug (ES-DE style):

```
{retroarchBase}/saves/{platformSlug}/{romBaseName}.srm
{retroarchBase}/states/{platformSlug}/{romBaseName}.state
{retroarchBase}/states/{platformSlug}/{romBaseName}.state1
...
```

Where:
- `{retroarchBase}` = configurable path (default: `/storage/emulated/0/RetroArch`)
- `{platformSlug}` = platform slug in RomM/ES-DE (`gba`, `snes`, `n64`, `psx`...)
- `{romBaseName}` = ROM file name without extension

### DS — melonDS (priority 1)

```
{melondsBase}/saves/{romBaseName}.sav
```

Default: `/storage/emulated/0/melonDS/saves/`

### DS — RetroArch (priority 2)

Same as the general RetroArch case with slug `nds`.

### PSP — PPSSPP

```
{ppssppBase}/PSP/SAVEDATA/{discId}*/
```

Each game generates N folders prefixed with the disc-id (9 chars, e.g.
`ULUS10064`). All folders with the same prefix are grouped together, zipped
for upload, and unzipped on download.

Requires disc-id extraction from the ISO/CSO.

### PS2 — AetherSX2/NetherSX2 (Folder Memory Card)

```
{aetherBase}/memcards/{cardName}.ps2/{BAserial}/
```

- The emulator must be configured with "Folder Memory Card" enabled.
- `{BAserial}` = `BA` + normalized serial (e.g. `BASLUS-21050`).
- The serial's folder is zipped for upload.

Requires serial extraction from the ISO.

### GameCube — Dolphin

```
{dolphinBase}/GC/{region}/Card A/*.gci
```

Or in folder mode: subfolders per game-id. They are bundled as a zip.

### Switch — Eden (Yuzu fork)

```
{edenBase}/nand/user/save/{userId}/{profileId}/{titleId}/
```

Default base path on Android:
`/storage/emulated/0/Android/data/dev.eden.eden_emulator/files`

`{titleId}` = 16 hex chars (e.g. `0100000000010000`). `{userId}` and
`{profileId}` are 16 hex-char IDs assigned by the emulator. The title's
folder is zipped whole for sync.

---

## Conflict handling

When the server returns `"type": "conflict"`:

1. The user is notified (notification + badge on the sync tab).
2. In the UI, the user chooses: "Keep local", "Keep server",
   "Keep both".
3. Global option: configurable default policy (for those who don't want to
   intervene).

---

## Implementation priority

| Phase | Scope | Status |
|-------|-------|--------|
| A    | Infrastructure: device registration, SyncWorker, negotiate/execute/complete | Implemented |
| B    | RetroArch handler (saves + states by slug/name) | Implemented |
| C    | melonDS handler (DS, flat file) | Implemented |
| D    | Per-platform configuration (path override, emulator selector) | Implemented |
| E    | PPSSPP handler (folders per disc-id) | Implemented |
| F    | PS2 handler (folder memcard per serial) | Implemented |
| G    | Dolphin handler (GC GCI + Wii title/data) | Implemented |
| H    | Switch handler (Eden, folders per title-id) | Implemented |

### Pending / future improvements

- Extraction of title-id/serial from ISO/ROM headers (currently inferred from
  the file name). Would improve PSP, PS2, GC/Wii and Switch.
- Conflict resolution UI (currently they are logged and skipped).
- Scheduled periodic sync in addition to the manual one.

---

## Reference: how Argosy does it

Argosy (rommapp/argosy-launcher) uses a `PlatformSaveHandlerRegistry` with
specialized handlers:

- `RetroArchSaveHandler`: flat file in RA's saves folder.
- `DefaultSaveHandler`: generic fallback (flat file per ROM name).
- `FolderSaveHandler` (base): for Vita, Wii, Wii U (folder per title-id).
- `PspFolderHandler`: groups folders by disc-id prefix.
- `Ps2FolderHandler`: folder memcard with serial normalization (`BA`-prefix).
- `N3dsFolderHandler`: traversal of the id0/id1 tree.
- `SwitchSaveHandler`: folder per title-id.
- `GciSaveHandler`: GameCube GCI bundles.

It uses a submodule (`sigil`) to extract the title-id from the ROMs.
We will start without that (using the file name and the slug),
and add it when it becomes necessary for platforms with
folders per title-id.

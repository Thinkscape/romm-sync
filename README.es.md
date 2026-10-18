<div align="center">

# 🎮 RomM Sync — Android & Linux

🌐 [English](README.md) · **Español**

**Descarga y sincroniza tu biblioteca de [RomM](https://github.com/rommapp/romm) en Android y Linux.**

Cliente multiplataforma para servidores RomM: explora tu biblioteca, descarga ROMs directamente a la estructura de carpetas de tu frontend favorito y mantiene las partidas guardadas sincronizadas entre todos tus dispositivos — portátil Android, Steam Deck, PC.

- **Android** (Kotlin + Jetpack Compose): APK nativo, optimizado para consolas portátiles (Anbernic, Retroid Pocket...) con pantalla táctil: UI oscura, botones grandes y navegación pensada para dedos.
- **Linux** (Compose Desktop): AppImage autocontenido para **x86_64** (SteamOS, Steam Deck, PCs) y **aarch64** (Raspberry Pi 5, handhelds ARM) con runtime Java incluido — nada que instalar.

</div>

> **Aviso de fork.** Este es `Thinkscape/romm-sync`, un fork de [davidadrianrg/romm-sync](https://github.com/davidadrianrg/romm-sync) (MIT, © David RG) mantenido por Thinkscape. Añade el emparejamiento de dispositivos con RomM (`/api/auth/device/*`), la instalación remota desde la web de RomM ("Install on device") mediante el socket `/devices` y la reclamación de instalaciones, un demonio Linux sin interfaz (`romm-sync --daemon`, `--pair`), un mapa de slug de RomM → carpeta de ES-DE/Cocoon (`core/src/main/resources/platform_folders.json`) y sincronización de partidas/estados por ranuras. Cocoon no tiene un intent público de reescaneo: tras una instalación remota, usa Cocoon → Settings → Library & Data → Rescan Games (o reinicia Cocoon); el demonio Linux reinicia ES-DE cuando está inactivo (ver abajo).
>
> **Media al instalar.** Tras cada instalación remota (`done` o `already_installed`, que se notifica primero), el cliente escribe la media y los metadatos scrapeados por RomM con la estructura de ES-DE (`core/.../metadata/EsdeMediaExporter.kt`, compartido con las acciones manuales "Exportar a ES-DE"): la media en `<MediaDirectory>/<sistema>/{covers,screenshots,titlescreens,marquees,fanart,3dboxes,backcovers,physicalmedia,miximages,videos,manuals}/<nombre de la rom>.<ext>`, y la entrada del juego (name, desc, rating, releasedate, developer, publisher, genre, players) se inserta o actualiza en `<carpeta ES-DE>/gamelists/<sistema>/gamelist.xml`, conservando los demás juegos y los campos propios de ES-DE. Demonio Linux: carpeta ES-DE `~/ES-DE` (ajuste `esde_data_dir`), `MediaDirectory` leído de su `settings/es_settings.xml`, se desactiva con la preferencia `esde.media_on_install=false`. Android: ajuste "Carpeta de metadatos ES-DE / Cocoon" (por defecto `<padre de la raíz de ROMs>/ES-DE` en almacenamiento extraíble) con `downloaded_media/` dentro; en Cocoon selecciónala en Settings → Library & Data → ES-DE Integration y activa las comprobaciones al inicio.
>
> **Refresco de ES-DE al instalar (demonio Linux).** ES-DE no tiene recarga/IPC y solo escanea ROMs y gamelists al arrancar o con Utilidades → Reescanear directorio de ROMs, así que ~5 s después de un lote de instalaciones `done` (tras exportar su media) el demonio reinicia un ES-DE en ejecución que esté inactivo en su interfaz (`desktop/.../LauncherRefresh.kt`): nunca mientras ES-DE tenga un proceso hijo (un juego) u otro juego de Steam esté abierto (espera y reintenta), SIGTERM limpio y luego el mismo AppImage/argumentos vía `systemd-run --user` (modo escritorio) o `steam steam://rungameid/<SteamGameId>` si lo lanzó Steam (modo juego). ES-DE vuelve a su vista inicial, como al reescanear. Registra `launcher: es-de refreshed (<método>)` / `launcher: skipped (<motivo>)`; se desactiva con la preferencia `launcher.refresh_on_install=false`.

---

## 📥 Instalación

### Opción A — Android (recomendado)

1. Descarga `RomM-Sync-vX.Y.Z-android.apk` desde [GitHub Releases](https://github.com/Thinkscape/romm-sync/releases/latest).
2. Configura tu servidor (ver abajo).
3. En **Configuración → Actualizaciones**, pulsa *Buscar actualizaciones* cada vez que quieras comprobar si hay versión nueva. La app descarga el APK y lanza el instalador de Android — sin salir de la aplicación.

> ⚠️ La primera vez, Android pedirá conceder a RomM Sync el permiso **«Instalar apps desconocidas»**. Es el permiso estándar para auto-actualizarse cualquier app fuera de Play Store.

### Opción B — Linux (AppImage)

1. Descarga el AppImage de tu arquitectura desde [GitHub Releases](https://github.com/Thinkscape/romm-sync/releases/latest):
   - `RomM-Sync-vX.Y.Z-linux-x86_64.AppImage` — SteamOS, Steam Deck, la mayoría de PCs
   - `RomM-Sync-vX.Y.Z-linux-aarch64.AppImage` — Raspberry Pi 5, handhelds ARM
2. Dale permisos de ejecución y ejecútalo:
   ```bash
   chmod +x RomM-Sync-v*-linux-*.AppImage
   ./RomM-Sync-v*-linux-x86_64.AppImage
   ```
3. La primera vez pide URL del servidor, API Key y carpeta de ROMs; se guarda para la siguiente.

> El AppImage lleva el runtime Java jlinkeado dentro (~70 MB): no necesitas instalar Java ni nada más.

### Opción C — Compilar desde código

```bash
git clone https://github.com/Thinkscape/romm-sync.git
cd romm-sync
./gradlew assembleDebug
# APK → app/build/outputs/apk/debug/app-debug.apk
```

Requisitos: JDK 17 y Android SDK (API 35). Para el desktop: solo JDK 17 (`./gradlew :desktop:packageReleaseUberJarForCurrentOS`). Para firmar tu propio release, crea `keystore.properties` en la raíz (está en `.gitignore`):

```properties
storeFile=/ruta/a/tu.keystore
storePassword=***
keyAlias=***
keyPassword=***
```

## ⚙️ Configuración inicial (2 minutos)

| Qué | Valor |
|---|---|
| **URL del servidor** | `https://romm.tudominio.com` (tu instancia RomM 4.9+ para sync) |
| **API Key** | Token `rmm_...` desde *RomM → User → API Keys* |
| **Directorio de ROMs** | `/storage/emulated/0/ROMs` (o tu microSD) |

La app pide el permiso **«Acceso a todos los archivos»** — lo necesita para escribir las ROMs en las carpetas que espera tu emulador/frontend, sin copiar ni mover nada manualmente.

<details>
<summary><b>¿Por qué API Key y no usuario/contraseña?</b></summary>

RomM admite login OAuth/OIDC tras proxys (Authentik, Authelia...), que suele romper clientes no-web con redirecciones y tokens CSRF. La API Key es un token largo por usuario que funciona siempre, sea cual sea tu setup de autenticación web.
</details>

## ✨ Qué hace la app

- **📚 Biblioteca** — Navega por tu servidor RomM con carátulas, búsqueda y filtros (todos / faltantes / descargados). Detecta juegos ya presentes en disco con *Escanear biblioteca*.
- **⬇️ Descargas** — Cola con descargas paralelas configurables (1–5), reanudación automática si se corta la red, verificación de integridad por hash y aviso de espacio insuficiente. Opción *solo WiFi* para no gastar datos.
- **💾 Sincronización de saves** — Tus partidas guardadas siempre al día en todos los dispositivos, con el [Device Sync Protocol](https://docs.romm.app/latest/developers/device-sync-protocol/) de RomM (v4.9+): sube lo que cambió en el portátil, bájate lo que jugaste en el PC. Resuelve conflictos explícitamente en vez de sobrescribir.
- **🗂️ Exportación de metadatos** — Genera los `gamelist.xml` de ES-DE o copia la media (covers, fanart, logos, screenshots) a la estructura de RetroHRAI.
- **🔄 Auto-actualización** — Comprueba, descarga e instala nuevas versiones desde GitHub Releases sin desinstalar nada (ver Instalación).
- **🖥️ Linux a la par de Android** — La app de escritorio comparte tema ("Midnight Arcade"), biblioteca con carátulas y detalle de juego completo (screenshots, metadatos IGDB), cola de descargas con cancelar/reintentar/reanudar, sync de saves con resolución de conflictos y exportación ES-DE con merge no destructivo.

### Emuladores soportados para sync de saves

| Plataforma | Emulador (por defecto) |
|---|---|
| Retro (NES–N64, GBA, PSX...) | RetroArch |
| Nintendo DS | melonDS |
| PSP | PPSSPP |
| PS2 | AetherSX2 / NetherSX2 |
| GameCube / Wii | Dolphin |
| 3DS | Azahar |
| Wii U | Cemu |
| Switch | Eden |
| Juegos nativos Android | ruta configurable por juego |

> 💡 PS2 requiere activar **Folder Memory Card** en el emulador para sincronizar por juego.

## 🖼️ Permisos que usa y por qué

| Permiso | Motivo |
|---|---|
| Acceso a todos los archivos | Escribir ROMs/metadata en las carpetas que leen los emuladores |
| Instalar apps desconocidas | Solo si usas la auto-actualización: instala el APK que ella misma descarga |
| Notificaciones | Progreso de descargas y sincronización en segundo plano |
| Ignorar optimización de batería | Que la sincronización periódica no la mate el sistema |
| Internet | Hablar con tu servidor RomM y con GitHub (comprobar updates) |

Sin telemetría, sin analytics, sin cuentas: tus datos van de tu dispositivo a **tu** servidor.

## 🏗️ Arquitectura (para contribuir)

Monorepo con tres módulos Gradle que comparten todo el código de negocio:

```
core/src/main/kotlin/es/davidrg/rommsync/core/   # JVM puro — compartido Android + Desktop
├── remote/              # Retrofit + OkHttp (API RomM, Device Sync Protocol)
├── sync/platform/       # Handlers de saves por emulador (RetroArch, melonDS, PS2, PS3, 3DS...)
├── download/            # PlatformFolders (slug de RomM -> carpeta ES-DE/Cocoon)
├── auth/                # DevicePairing (emparejamiento de dispositivo con RomM)
├── install/             # InstallClient (instalación remota desde RomM)
└── util/                # RootShell, lectura de cabeceras ROM...

app/src/main/java/es/davidrg/rommsync/           # Android
├── data/local/          # Room + DataStore (config persistente)
├── data/repository/     # Puente remote ↔ cache
├── data/metadata/       # Exportación gamelist.xml / media
├── data/update/         # Auto-actualización desde GitHub Releases
├── download/            # WorkManager + descarga con reanudación
├── ui/                  # Compose Material3 (screens/viewmodel/components)
└── util/                # Permisos Android

desktop/src/main/kotlin/es/davidrg/rommsync/desktop/  # Linux (Compose Desktop)
├── Main.kt / AppContent.kt   # UI (conexión, biblioteca, descargas, sync)
├── DesktopSyncCoordinator.kt # Ciclo de sync sin Room/WorkManager
├── DownloadEngine.kt         # Descarga stream→disco (fichero único + zip-stream)
└── DesktopConfig.kt          # java.util.prefs (config persistente)
```

- **Stack común**: Kotlin · Retrofit/OkHttp · Moshi · Coroutines
- **Android**: Jetpack Compose (Material 3) · Room · WorkManager · DataStore · Coil
- **Desktop**: Compose Desktop · AppImage (jlink runtime incluido)
- **Idioma del código**: inglés en identificadores/logs; español en textos de UI.
- **Tests**: `./gradlew test` (unit tests en `core/src/test` y `app/src/test`).

## 🔁 CI/CD

Cada push a `master`:

1. **APK** (`.github/workflows/build-release.yml`): build firmado (R8) → GitHub Release como `RomM-Sync-vX.Y.Z-android.apk`.
2. **AppImage** (`.github/workflows/build-appimage.yml`): build para **x86_64** y **aarch64** → `RomM-Sync-vX.Y.Z-linux-<arch>.AppImage`, adjuntos a la misma release que el APK.

El tag y el `versionName` coinciden **exactamente** con `versionName` de `app/build.gradle.kts` (p. ej. `0.4.2` → `v0.4.2`); búmpalo ahí para publicar una versión nueva. El `versionCode` interno usa el `run_number` del workflow, que siempre crece (Android lo exige para actualizar sin desinstalar). Repetir una versión reemplaza su release.

El APK de release se firma con una clave fija (secrets `SIGNING_KEYSTORE_BASE64` y derivados) para que las actualizaciones se instalen sobre la versión anterior sin desinstalar. **Guarda copia del keystore**: si se pierde, los usuarios tendrían que desinstalar para actualizar.

## 🤝 Contribuir

1. Fork + rama (`feat/mi-cosa`).
2. `./gradlew test` en verde.
3. PR a `master` — CI debe pasar.

Errores, ideas y PRs bienvenidos en [Issues](https://github.com/Thinkscape/romm-sync/issues).

## 📄 Licencia

MIT

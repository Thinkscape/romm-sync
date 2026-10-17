package es.davidrg.rommsync.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import es.davidrg.rommsync.core.i18n.tr
import es.davidrg.rommsync.data.sync.SafTreeStore
import es.davidrg.rommsync.util.hasAllFilesAccess
import java.io.File

/**
 * Límite superior de navegación. No se permite subir por encima de `/storage`,
 * que es la raíz que agrupa el almacenamiento interno y las tarjetas SD.
 */
private const val NAV_ROOT = "/storage"
private const val EMULATED_ROOT = "/storage/emulated"
private const val DEFAULT_START = "/storage/emulated/0"

/** A mounted storage volume shown at the top of the picker (internal storage, SD cards, USB). */
private data class StorageRoot(val dir: File, val label: String, val removable: Boolean)

/**
 * Explorador de carpetas propio basado en [java.io.File].
 *
 * Navega con acceso directo al sistema de ficheros (MANAGE_EXTERNAL_STORAGE).
 * Como Android bloquea `Android/data` incluso con ese permiso, si la carpeta
 * no es legible se ofrece «Otorgar acceso» vía ACTION_OPEN_DOCUMENT_TREE (el
 * mecanismo de ZArchiver en Android 11/12, sin root): la concesión se
 * persiste por ruta y el sync la usa vía staging (SafStaging).
 *
 * Si el permiso aún no está concedido se muestra un aviso con botón para
 * pedirlo, y al volver de Ajustes se relista el directorio actual. También se
 * distingue "carpeta ilegible" (`listFiles() == null`) de "carpeta vacía", y
 * se muestran los ficheros (en gris, no navegables) junto a las carpetas.
 *
 * `/storage` and `/storage/emulated` are not listable by apps, so going up
 * from a volume root (or into those folders) shows the mounted storage volumes
 * instead; that is how SD cards (`/storage/<UUID>`) are reached.
 *
 * @param initialPath ruta inicial; si no existe, se sube al primer ancestro
 *   existente.
 * @param onDismiss se invoca al cancelar.
 * @param onSelect se invoca con la ruta absoluta de la carpeta elegida.
 */
@Composable
fun FolderPickerDialog(
    initialPath: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val context = LocalContext.current
    var refreshTick by remember { mutableStateOf(0) }
    var hasAccess by remember { mutableStateOf(hasAllFilesAccess()) }
    val roots = remember(refreshTick) { storageRoots(context) }
    // null = the storage volumes list.
    var currentDir by remember {
        mutableStateOf(existingAncestorOrDefault(initialPath).takeUnless { isVolumesPlaceholder(it) })
    }

    // Al volver de la pantalla de permisos de Ajustes, el callback del launcher
    // reevalúa el permiso y fuerza un relistado del directorio actual.
    val grantLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        hasAccess = hasAllFilesAccess()
        refreshTick++
    }

    // Selector del sistema (SAF): en Android 11/12 permite elegir dentro de
    // Android/data (mecanismo de ZArchiver sin root). La concesión se persiste
    // por ruta (SafTreeStore) y el sync la usa vía staging (SafStaging).
    val safLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            SafTreeStore.pathFromTreeUri(uri)?.let { path ->
                SafTreeStore(context).put(path, uri.toString())
                currentDir = File(path)
            }
        }
        refreshTick++
    }

    val dir = currentDir
    val listing = remember(dir?.path, refreshTick) { dir?.listFiles() }
    val subDirs = remember(listing) {
        listing?.filter { it.isDirectory }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    }
    val files = remember(listing) {
        listing?.filter { it.isFile }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("common.select_folder")) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    dir?.path ?: tr("android.folder.volumes_title"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                if (!hasAccess) {
                    Spacer(modifier = Modifier.size(8.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(
                                tr("android.folder.no_all_files_permission"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = {
                                    grantLauncher.launch(
                                        Intent(
                                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                            Uri.parse("package:${context.packageName}"),
                                        ),
                                    )
                                },
                            ) { Text(tr("android.folder.grant")) }
                        }
                    }
                }

                Spacer(modifier = Modifier.size(8.dp))
                HorizontalDivider()

                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    if (dir == null) {
                        items(roots, key = { "v:${it.dir.path}" }) { root ->
                            FolderRow(
                                name = root.label,
                                subtitle = root.dir.path,
                                icon = { VolumeIcon(root.removable) },
                                onClick = { currentDir = root.dir },
                            )
                        }
                        return@LazyColumn
                    }
                    if (listing == null) {
                        item {
                            val granted = remember(dir.path, refreshTick) {
                                SafTreeStore(context).hasGrantFor(dir.path)
                            }
                            if (granted) {
                                Text(
                                    tr("android.folder.saf_granted"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 12.dp),
                                )
                            } else {
                                Text(
                                    if (hasAccess &&
                                        (dir.path.contains("/Android/data") ||
                                            dir.path.contains("/Android/obb"))
                                    ) {
                                        tr("android.folder.android_data_blocked")
                                    } else {
                                        tr("android.folder.unreadable")
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(top = 12.dp),
                                )
                                Spacer(modifier = Modifier.size(4.dp))
                                TextButton(
                                    onClick = {
                                        safLauncher.launch(
                                            SafTreeStore.initialTreeUri(dir.path),
                                        )
                                    },
                                ) { Text(tr("android.folder.grant_access")) }
                            }
                        }
                    }
                    item {
                        FolderRow(
                            name = "..",
                            icon = { UpIcon() },
                            onClick = { currentDir = parentOrVolumes(dir, roots) },
                        )
                    }
                    items(subDirs, key = { "d:${it.path}" }) { sub ->
                        FolderRow(
                            name = sub.name,
                            icon = { FolderIcon() },
                            onClick = { currentDir = sub },
                        )
                    }
                    // Ficheros visibles (no navegables) para poder comprobar el
                    // contenido real de la carpeta, igual que hace ZArchiver.
                    items(files, key = { "f:${it.path}" }) { file ->
                        FileRow(name = file.name)
                    }
                    if (listing != null && subDirs.isEmpty() && files.isEmpty()) {
                        item {
                            Text(
                                tr("android.folder.empty"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { dir?.let { onSelect(it.path) } }, enabled = dir != null) {
                Text(tr("android.folder.use_this_folder"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(tr("common.cancel"))
            }
        },
    )
}

@Composable
private fun FolderRow(
    name: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon()
        Column {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Fila de fichero: visible pero no navegable (el diálogo selecciona carpetas). */
@Composable
private fun FileRow(name: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Filled.Description,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(
            name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FolderIcon() {
    Icon(
        Icons.Filled.Folder,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(22.dp),
    )
}

@Composable
private fun VolumeIcon(removable: Boolean) {
    Icon(
        if (removable) Icons.Filled.SdCard else Icons.Filled.PhoneAndroid,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(22.dp),
    )
}

@Composable
private fun UpIcon() {
    Icon(
        Icons.Filled.KeyboardArrowUp,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(22.dp),
    )
}

/** `/storage` and `/storage/emulated` cannot be listed by apps: show the volumes list instead. */
private fun isVolumesPlaceholder(dir: File): Boolean =
    dir.path == NAV_ROOT || dir.path == EMULATED_ROOT || !dir.path.startsWith("$NAV_ROOT/")

/**
 * Where ".." leads: the parent folder, or the volumes list when [dir] is a
 * volume root or the parent is `/storage`, `/storage/emulated` or unreadable.
 */
private fun parentOrVolumes(dir: File, roots: List<StorageRoot>): File? {
    if (roots.any { it.dir.path == dir.path }) return null
    val parent = dir.parentFile ?: return null
    if (isVolumesPlaceholder(parent) || parent.listFiles() == null) return null
    return parent
}

/**
 * Mounted storage volumes (internal storage first, then SD cards / USB),
 * labelled with the system description plus "Internal storage" / "SD card".
 */
private fun storageRoots(context: Context): List<StorageRoot> {
    val storageManager = context.getSystemService(StorageManager::class.java)
    val roots = storageManager?.storageVolumes.orEmpty()
        .filter { it.state == Environment.MEDIA_MOUNTED || it.state == Environment.MEDIA_MOUNTED_READ_ONLY }
        .mapNotNull { volume ->
            val dir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                volume.directory
            } else {
                // Pre-R: derive the root from this app's external files dir on that volume.
                context.getExternalFilesDirs(null).filterNotNull()
                    .firstOrNull { storageManager?.getStorageVolume(it) == volume }
                    ?.path?.substringBefore("/Android/")?.let(::File)
            } ?: return@mapNotNull null
            val kind = if (volume.isPrimary && !volume.isRemovable) {
                tr("android.folder.volume_internal")
            } else {
                tr("android.folder.volume_removable")
            }
            val description = volume.getDescription(context).orEmpty()
            val label = if (description.isBlank() || description.equals(kind, ignoreCase = true)) {
                kind
            } else {
                "$description · $kind"
            }
            StorageRoot(dir, label, removable = volume.isRemovable)
        }
        .sortedBy { if (it.removable) 1 else 0 }
    return roots.ifEmpty { listOf(StorageRoot(File(DEFAULT_START), tr("android.folder.volume_internal"), false)) }
}

/**
 * Devuelve la propia ruta si existe como directorio; si no, sube hasta el
 * primer ancestro existente. Como último recurso devuelve el almacenamiento
 * interno principal.
 */
private fun existingAncestorOrDefault(path: String): File {
    var file = File(path)
    while (!(file.exists() && file.isDirectory)) {
        file = file.parentFile ?: return File(DEFAULT_START)
    }
    return file
}

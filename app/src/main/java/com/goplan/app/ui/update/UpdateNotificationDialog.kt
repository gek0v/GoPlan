package com.goplan.app.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.goplan.app.BuildConfig
import com.goplan.app.data.update.UpdateDownloadState
import com.goplan.app.data.update.UpdateInfo
import java.io.File
import java.util.Locale

@Composable
fun UpdateNotificationDialog(
    updateInfo: UpdateInfo,
    downloadState: UpdateDownloadState,
    onDismissRequest: () -> Unit,
    onDownloadClick: (UpdateInfo) -> Unit,
    onInstallClick: (File) -> Unit
) {
    val currentVersion = BuildConfig.VERSION_NAME

    AlertDialog(
        onDismissRequest = {
            if (downloadState !is UpdateDownloadState.Downloading) {
                onDismissRequest()
            }
        },
        icon = {
            val icon = when (downloadState) {
                is UpdateDownloadState.Error -> Icons.Default.ErrorOutline
                is UpdateDownloadState.Downloading -> Icons.Default.Download
                else -> Icons.Default.SystemUpdate
            }
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (downloadState is UpdateDownloadState.Error) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = when (downloadState) {
                    is UpdateDownloadState.Downloading -> "Descargando actualización..."
                    is UpdateDownloadState.ReadyToInstall -> "Actualización lista para instalar"
                    is UpdateDownloadState.Error -> "Error en la actualización"
                    else -> "Nueva versión disponible"
                },
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                // Version Badges
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        Text(
                            text = "Actual: v$currentVersion",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.padding(start = 4.dp)
                    ) {
                        Text(
                            text = "Nueva: v${updateInfo.versionName}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                if (updateInfo.apkSize > 0) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Tamaño del archivo: ${formatApkSize(updateInfo.apkSize)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                when (downloadState) {
                    is UpdateDownloadState.Idle -> {
                        Text(
                            text = "Novedades y cambios:",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 180.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .padding(8.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = updateInfo.releaseNotes.ifBlank { "Mejoras de rendimiento y corrección de errores." },
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    is UpdateDownloadState.Downloading -> {
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { downloadState.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            val percentage = (downloadState.progress * 100).toInt()
                            Text(
                                text = "$percentage%",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (downloadState.totalBytes > 0) {
                                Text(
                                    text = "${formatApkSize(downloadState.bytesDownloaded)} / ${formatApkSize(downloadState.totalBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    is UpdateDownloadState.ReadyToInstall -> {
                        Text(
                            text = "El archivo APK se ha descargado y verificado. Pulsa el botón de abajo para iniciar la instalación.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    is UpdateDownloadState.Error -> {
                        Text(
                            text = downloadState.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (downloadState) {
                is UpdateDownloadState.Idle -> {
                    Button(onClick = { onDownloadClick(updateInfo) }) {
                        Text("Descargar e Instalar")
                    }
                }

                is UpdateDownloadState.Downloading -> {
                    // Downloading in progress, no button or disabled
                }

                is UpdateDownloadState.ReadyToInstall -> {
                    Button(onClick = { onInstallClick(File(downloadState.apkPath)) }) {
                        Text("Instalar Ahora")
                    }
                }

                is UpdateDownloadState.Error -> {
                    Button(onClick = { onDownloadClick(updateInfo) }) {
                        Text("Reintentar")
                    }
                }
            }
        },
        dismissButton = {
            if (downloadState !is UpdateDownloadState.Downloading) {
                OutlinedButton(onClick = onDismissRequest) {
                    Text(if (downloadState is UpdateDownloadState.ReadyToInstall) "Más tarde" else "Cancelar")
                }
            }
        }
    )
}

private fun formatApkSize(bytes: Long): String {
    if (bytes <= 0) return "Desconocido"
    val mb = bytes.toDouble() / (1024.0 * 1024.0)
    return String.format(Locale.getDefault(), "%.1f MB", mb)
}

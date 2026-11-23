package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.data.update.AppUpdateInfo
import com.example.data.update.AppUpdateStatus
import com.example.ui.theme.LiveEmerald
import com.example.ui.theme.MonoFontFamily

@Composable
fun AppUpdateDialog(
    updateInfo: AppUpdateInfo,
    onInstallUpdate: (String) -> Unit,
    onOpenReleasePage: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Filled.SystemUpdate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(30.dp)
            )
        },
        title = {
            Text(
                text = "Mise à jour disponible (v${updateInfo.versionName})",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Une nouvelle version d'EncanLive est prête (v${BuildConfig.VERSION_NAME} → v${updateInfo.versionName}). Vos favoris et recherches sauvegardées sont conservés lors de la mise à jour.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (updateInfo.releaseNotes.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Nouveautés${if (updateInfo.publishedAt.isNotBlank()) " • ${updateInfo.publishedAt}" else ""}",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = updateInfo.releaseNotes,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onInstallUpdate(updateInfo.downloadUrl) },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("update_dialog_install_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.SystemUpdate,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Mettre à jour")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = { onOpenReleasePage(updateInfo.releasePageUrl) },
                    modifier = Modifier.testTag("update_dialog_release_button")
                ) {
                    Text("Notes")
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("update_dialog_later_button")
                ) {
                    Text("Plus tard")
                }
            }
        }
    )
}

@Composable
fun AppUpdateCard(
    status: AppUpdateStatus,
    configuredRepo: String,
    onSaveRepoAndCheck: (String) -> Unit,
    onCheckNow: () -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var repoInput by remember(configuredRepo) { mutableStateOf(configuredRepo) }
    var editingRepo by remember(configuredRepo) { mutableStateOf(configuredRepo.isBlank()) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("app_update_card"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            width = 1.dp,
            color = if (status is AppUpdateStatus.UpdateAvailable) {
                LiveEmerald.copy(alpha = 0.6f)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.SystemUpdate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Mises à jour automatiques",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Text(
                        text = "v${BuildConfig.VERSION_NAME} (#${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = MonoFontFamily,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            when (status) {
                AppUpdateStatus.Checking -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            text = "Vérification de la dernière version sur GitHub…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                is AppUpdateStatus.UpdateAvailable -> {
                    Surface(
                        color = LiveEmerald.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, LiveEmerald.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Nouvelle version disponible : v${status.info.versionName} (build ${status.info.versionCode})",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                color = LiveEmerald
                            )
                            if (status.info.releaseNotes.isNotBlank()) {
                                Text(
                                    text = status.info.releaseNotes,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Button(
                                onClick = { onOpenUrl(status.info.downloadUrl) },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = LiveEmerald,
                                    contentColor = Color.White
                                ),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                modifier = Modifier.testTag("sources_install_update_button")
                            ) {
                                Icon(Icons.Filled.SystemUpdate, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Installer v${status.info.versionName}")
                                Spacer(modifier = Modifier.width(5.dp))
                                Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(15.dp))
                            }
                        }
                    }
                }
                is AppUpdateStatus.UpToDate -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = LiveEmerald,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Votre application est à jour (dépôt ${status.repoSlug}).",
                            style = MaterialTheme.typography.bodySmall,
                            color = LiveEmerald
                        )
                    }
                }
                is AppUpdateStatus.Error -> {
                    Text(
                        text = status.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                AppUpdateStatus.NotConfigured, AppUpdateStatus.Idle -> {
                    Text(
                        text = "Lorsque l'APK est compilé via GitHub Actions, le dépôt est lié automatiquement et vous recevez une alerte dès l'ouverture lorsqu'une nouvelle version est publiée.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (editingRepo || configuredRepo.isBlank()) {
                OutlinedTextField(
                    value = repoInput,
                    onValueChange = { repoInput = it },
                    label = { Text("Dépôt GitHub (ex : utilisateur/EncanLive)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("update_repo_input")
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            onSaveRepoAndCheck(repoInput)
                            if (repoInput.isNotBlank()) editingRepo = false
                        },
                        enabled = repoInput.isNotBlank(),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("update_repo_save_button")
                    ) {
                        Text("Enregistrer & vérifier")
                    }
                    if (configuredRepo.isNotBlank()) {
                        TextButton(onClick = {
                            repoInput = configuredRepo
                            editingRepo = false
                        }) {
                            Text("Annuler")
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onCheckNow,
                        enabled = status !is AppUpdateStatus.Checking,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("check_updates_now_button")
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Vérifier maintenant")
                    }
                    TextButton(onClick = { editingRepo = true }) {
                        Text("Changer dépôt")
                    }
                }
            }
        }
    }
}

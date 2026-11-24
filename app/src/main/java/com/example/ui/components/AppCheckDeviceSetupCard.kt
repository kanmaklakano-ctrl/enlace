package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.VerifiedUser
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.ai.activateAndVerifyPersonalAppCheckToken
import com.example.data.ai.appCheckInitSummary
import com.example.data.ai.clearPersonalAppCheckToken
import com.example.data.ai.getFirebaseAppCheckConsoleUrl
import com.example.data.ai.getOrCreateDeviceAppCheckToken
import com.example.data.ai.readActivePersonalAppCheckToken
import com.example.ui.theme.LiveEmerald
import com.example.ui.theme.MonoFontFamily
import kotlinx.coroutines.launch

/**
 * Interactive 2-step assistant to authorize a personal sideloaded Android phone
 * (installed outside Google Play Store) in Firebase App Check using an official Debug Token.
 */
@Composable
fun AppCheckDeviceSetupCard(
    compact: Boolean = false,
    onActivated: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var deviceToken by remember { mutableStateOf(getOrCreateDeviceAppCheckToken(context)) }
    var isActive by remember { mutableStateOf(readActivePersonalAppCheckToken(context) != null) }
    var editingCustomToken by remember { mutableStateOf(false) }
    var isVerifying by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }

    val consoleAppsUrl = remember { getFirebaseAppCheckConsoleUrl(context) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("app_check_device_setup_card"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (compact) {
                MaterialTheme.colorScheme.surfaceContainerLow
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (isActive && !statusIsError) {
                LiveEmerald.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (isActive && !statusIsError) Icons.Filled.VerifiedUser else Icons.Filled.Key,
                    contentDescription = null,
                    tint = if (isActive && !statusIsError) LiveEmerald else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (compact) {
                            "Débloquer l'IA sur ce téléphone (1 seule fois)"
                        } else {
                            "Autorisation IA sur cet appareil"
                        },
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = appCheckInitSummary,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                text = "Les APK installés depuis GitHub (hors Google Play) doivent être autorisés une seule fois dans Firebase App Check. Vos futures mises à jour conserveront automatiquement cette autorisation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Copyable device token box
            if (!editingCustomToken) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { copyTokenToClipboard(context, deviceToken) }
                        .testTag("app_check_token_box")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "CODE DE CET APPAREIL (DEBUG TOKEN)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = deviceToken,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontFamily = MonoFontFamily,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = "Copier le jeton",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            } else {
                OutlinedTextField(
                    value = deviceToken,
                    onValueChange = { deviceToken = it },
                    label = { Text("Jeton Firebase App Check (UUID)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("app_check_custom_token_input")
                )
            }

            Text(
                text = "Étape 1 : Copiez ce code et ouvrez Firebase Console → App Check → Apps → EncanLive → ⋮ → Manage debug tokens → Add debug token → Coller → Save.\nÉtape 2 : Revenez ici et appuyez sur « Activer & tester l'IA ».",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Step 1 Button: Copy + Open exact Firebase Console App Check page
            OutlinedButton(
                onClick = {
                    copyTokenToClipboard(context, deviceToken)
                    BrowserLauncher.openAuctionUrl(context, consoleAppsUrl)
                },
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("app_check_copy_open_firebase_button")
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("1. Copier le code & ouvrir Firebase")
                Spacer(modifier = Modifier.width(5.dp))
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(15.dp))
            }

            // Step 2 Button: Activate & live-verify against Firebase App Check
            Button(
                onClick = {
                    if (isVerifying) return@Button
                    isVerifying = true
                    statusMessage = null
                    statusIsError = false
                    scope.launch {
                        val result = activateAndVerifyPersonalAppCheckToken(context, deviceToken)
                        isVerifying = false
                        result.onSuccess { okMsg ->
                            isActive = true
                            editingCustomToken = false
                            statusMessage = okMsg
                            statusIsError = false
                            onActivated?.invoke()
                        }.onFailure { err ->
                            statusMessage = err.localizedMessage ?: "Échec de vérification du jeton."
                            statusIsError = true
                        }
                    }
                },
                enabled = !isVerifying && deviceToken.isNotBlank(),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = LiveEmerald,
                    contentColor = Color.White
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("app_check_activate_verify_button")
            ) {
                if (isVerifying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Vérification avec Firebase…")
                } else {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (compact) "2. Activer & relancer l'étude IA" else "2. Activer & tester l'IA"
                    )
                }
            }

            // Status feedback
            statusMessage?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = if (statusIsError) MaterialTheme.colorScheme.error else LiveEmerald
                )
            }

            // Secondary actions: paste custom token or revert to reCAPTCHA
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { editingCustomToken = !editingCustomToken },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    Text(
                        text = if (editingCustomToken) "Masquer la saisie" else "Coller un autre jeton",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                if (isActive) {
                    TextButton(
                        onClick = {
                            clearPersonalAppCheckToken(context)
                            isActive = false
                            statusMessage = "Mode reCAPTCHA rétabli."
                            statusIsError = false
                        },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                    ) {
                        Text(
                            text = "Mode reCAPTCHA",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    }
}

private fun copyTokenToClipboard(context: Context, token: String) {
    runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("Firebase App Check Token", token))
        Toast.makeText(
            context,
            "Code copié ! Collez-le dans Firebase Console → Manage debug tokens.",
            Toast.LENGTH_LONG
        ).show()
    }
}

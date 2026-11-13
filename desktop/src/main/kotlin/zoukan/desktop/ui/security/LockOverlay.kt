package zoukan.desktop.ui.security

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.compat.localized
import zoukan.desktop.security.AppLock
import zoukan.i18n.desktop.DMR

/** Covers the app while it is locked, as Android's UnlockActivity, and asks for the password. */
@Composable
fun LockOverlay() {
    val lock = remember { Injekt.get<AppLock>() }
    val locked by lock.locked.collectAsState()
    if (!locked) return
    var password by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    // The secret store failed (for example, access denied): said here, since toasts wait for the unlock
    var storeError by remember { mutableStateOf<String?>(null) }
    fun tryUnlock() {
        if (checking) return
        val attempt = password
        password = ""
        checking = true
        storeError = null
        // The password hash is slow on purpose; keep it off the UI thread
        scope.launch {
            try {
                wrong = !withContext(Dispatchers.Default) { lock.unlock(attempt) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logcat(LogPriority.WARN, e) { "Unlocking failed" }
                wrong = false
                storeError = secretStoreError(e)
            } finally {
                checking = false
            }
        }
    }
    Surface(
        Modifier.fillMaxSize().testTag("app-lock")
            // Nothing behind the lock takes clicks
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.primary)
                Text(localized(DMR.strings.zoukan_is_locked), style = MaterialTheme.typography.headlineSmall)
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        wrong = false
                        storeError = null
                    },
                    label = { Text(localized(MR.strings.password)) },
                    singleLine = true,
                    isError = wrong || storeError != null,
                    supportingText = when {
                        wrong -> ({ Text(localized(DMR.strings.wrong_password)) })
                        storeError != null -> ({ Text(storeError.orEmpty()) })
                        else -> null
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { tryUnlock() }),
                    modifier = Modifier.width(320.dp).focusRequester(focus),
                )
                Button(onClick = ::tryUnlock, enabled = !checking) { Text(localized(DMR.strings.unlock)) }
            }
        }
    }
}

/** The message for a secret store call that failed: what failed, then the store's own reason. */
internal fun secretStoreError(e: Throwable): String =
    "${localized(DMR.strings.could_not_use_the_secret_store)}: ${e.message ?: e::class.simpleName}"

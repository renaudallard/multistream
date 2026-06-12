package it.allard.multistream.feature.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import it.allard.multistream.R
import it.allard.multistream.WebLoginActivity
import it.allard.multistream.core.model.Region
import it.allard.multistream.di.LocalAppGraph
import it.allard.multistream.provider.api.ProviderCapabilities
import it.allard.multistream.provider.api.StreamingProvider
import it.allard.multistream.provider.api.WebLoginSpec
import it.allard.multistream.ui.appViewModel

@Composable
fun SettingsScreen() {
    val graph = LocalAppGraph.current
    val appContext = LocalContext.current.applicationContext
    val viewModel = appViewModel { SettingsViewModel(graph.registry, graph.settings, appContext) { graph.secrets } }
    val rows by viewModel.rows.collectAsState()
    val loggedIn by viewModel.loggedIn.collectAsState()
    val linkPrompt by viewModel.linkPrompt.collectAsState()
    val message by viewModel.message.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeMessage()
        }
    }

    // Re-read the login state each time the screen resumes: a provider may have cleared its own
    // session during a search elsewhere, so a stale "Logged in" should correct itself here.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshLoginState()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(rows, key = { it.provider.id.name }) { row ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(row.provider.displayName, style = MaterialTheme.typography.titleMedium)
                        Switch(checked = row.enabled, onCheckedChange = { viewModel.setEnabled(row.provider, it) })
                    }
                    Text(
                        capabilitySummary(row.provider.capabilities),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (row.provider.capabilities.requiresRegion) {
                        Spacer(Modifier.height(4.dp))
                        RegionSelector(
                            current = row.region,
                            options = regionOptions(row.provider),
                            onSelect = { viewModel.setRegion(row.provider, it) },
                        )
                    }
                    if (row.provider.capabilities.requiresAuth || row.provider.capabilities.optionalLogin) {
                        Spacer(Modifier.height(4.dp))
                        LoginSection(
                            isLoggedIn = loggedIn[row.provider.id] == true,
                            webLoginSpec = row.provider.webLoginSpec(),
                            userLabel = row.provider.capabilities.loginUserLabel,
                            passLabel = row.provider.capabilities.loginPassLabel,
                            optional = row.provider.capabilities.optionalLogin && !row.provider.capabilities.requiresAuth,
                            linkLogin = row.provider.capabilities.linkLogin,
                            linkPrompt = linkPrompt?.takeIf { it.providerId == row.provider.id },
                            onStartLink = { viewModel.startLink(row.provider) },
                            onCancelLink = { viewModel.cancelLink() },
                            onLogin = { email, password -> viewModel.login(row.provider, email, password) },
                            onWebCookies = { cookies -> viewModel.loginWithCookies(row.provider, cookies) },
                            onLogout = { viewModel.logout(row.provider) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LoginSection(
    isLoggedIn: Boolean,
    webLoginSpec: WebLoginSpec?,
    userLabel: String = stringResource(R.string.settings_label_email),
    passLabel: String = stringResource(R.string.settings_label_password),
    optional: Boolean = false,
    linkLogin: Boolean = false,
    linkPrompt: SettingsViewModel.LinkPrompt? = null,
    onStartLink: () -> Unit = {},
    onCancelLink: () -> Unit = {},
    onLogin: (String, String) -> Unit,
    onWebCookies: (String) -> Unit,
    onLogout: () -> Unit,
) {
    if (isLoggedIn) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_logged_in), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onLogout) { Text(stringResource(R.string.settings_log_out)) }
        }
        return
    }
    if (optional) {
        Text(stringResource(R.string.settings_login_optional), style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(4.dp))
    }
    if (linkLogin) {
        val context = LocalContext.current
        if (linkPrompt != null) {
            Text(stringResource(R.string.settings_link_approve), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            // A device without a browser (Android TV) has no handler for the URL; no crash on tap.
            Button(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(linkPrompt.url))) } }) {
                Text(stringResource(R.string.settings_link_open_signin))
            }
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.settings_link_waiting), style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = onCancelLink) { Text(stringResource(R.string.settings_link_cancel)) }
        } else {
            Button(onClick = onStartLink) { Text(stringResource(R.string.settings_link_account)) }
        }
        return
    }
    if (webLoginSpec != null) {
        val context = LocalContext.current
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.getStringExtra(WebLoginActivity.EXTRA_RESULT_COOKIES)
                    ?.takeIf { it.isNotBlank() }
                    ?.let(onWebCookies)
            }
        }
        Button(
            onClick = {
                launcher.launch(
                    WebLoginActivity.intent(
                        context,
                        webLoginSpec.loginUrl,
                        webLoginSpec.cookieUrl,
                        webLoginSpec.successCookie,
                        webLoginSpec.logoutUrl,
                        webLoginSpec.autoCapture,
                        webLoginSpec.tokenRedirectPrefix,
                        webLoginSpec.tokenFragmentKey,
                    ),
                )
            },
        ) {
            Text(stringResource(R.string.settings_log_in_browser))
        }
        return
    }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    OutlinedTextField(
        value = email,
        onValueChange = { email = it },
        label = { Text(userLabel) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(4.dp))
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text(passLabel) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(4.dp))
    Button(onClick = { onLogin(email, password) }) { Text(stringResource(R.string.settings_log_in)) }
}

@Composable
private fun capabilitySummary(capabilities: ProviderCapabilities): String = buildList {
    add(stringResource(if (capabilities.canSearch) R.string.capability_search else R.string.capability_launch_only))
    if (capabilities.canDeepLinkToTitle) add(stringResource(R.string.capability_deep_link))
    if (capabilities.isLiveTv) add(stringResource(R.string.capability_live_tv))
    if (capabilities.requiresAuth) add(stringResource(R.string.capability_login))
    if (capabilities.optionalLogin) add(stringResource(R.string.capability_login_optional))
}.joinToString(" · ")

private fun regionOptions(provider: StreamingProvider): List<Region> {
    val supported = provider.supportedRegions()
    return if (supported.isNotEmpty()) supported.toList() else listOf(Region.FR, Region.CH, Region.DE, Region.IT)
}

@Composable
private fun RegionSelector(current: Region?, options: List<Region>, onSelect: (Region) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(stringResource(R.string.settings_region, current?.code ?: stringResource(R.string.settings_region_not_set)))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { region ->
                DropdownMenuItem(
                    text = { Text(region.code) },
                    onClick = {
                        onSelect(region)
                        expanded = false
                    },
                )
            }
        }
    }
}

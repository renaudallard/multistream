package it.allard.multistream.feature.settings

import android.widget.Toast
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import it.allard.multistream.core.model.Region
import it.allard.multistream.di.LocalAppGraph
import it.allard.multistream.provider.api.ProviderCapabilities
import it.allard.multistream.provider.api.StreamingProvider
import it.allard.multistream.ui.appViewModel
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val graph = LocalAppGraph.current
    val viewModel = appViewModel { SettingsViewModel(graph.registry, graph.settings, graph.secrets) }
    val rows by viewModel.rows.collectAsState()
    val loggedIn by viewModel.loggedIn.collectAsState()
    val message by viewModel.message.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeMessage()
        }
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
                    if (row.provider.capabilities.requiresAuth) {
                        Spacer(Modifier.height(4.dp))
                        LoginSection(
                            isLoggedIn = loggedIn[row.provider.id] == true,
                            onLogin = { email, password -> viewModel.login(row.provider, email, password) },
                            onLogout = { viewModel.logout(row.provider) },
                        )
                    }
                }
            }
        }
        item {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        graph.cacheRepository.wipe()
                        Toast.makeText(context, "Catalog cache cleared", Toast.LENGTH_SHORT).show()
                    }
                },
            ) {
                Text("Clear catalog cache")
            }
        }
    }
}

@Composable
private fun LoginSection(isLoggedIn: Boolean, onLogin: (String, String) -> Unit, onLogout: () -> Unit) {
    if (isLoggedIn) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("Logged in ✓", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onLogout) { Text("Log out") }
        }
        return
    }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    OutlinedTextField(
        value = email,
        onValueChange = { email = it },
        label = { Text("Email") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(4.dp))
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("Password") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(4.dp))
    Button(onClick = { onLogin(email, password) }) { Text("Log in") }
}

private fun capabilitySummary(capabilities: ProviderCapabilities): String = buildList {
    add(if (capabilities.canSearch) "Search" else "Launch only")
    if (capabilities.canDeepLinkToTitle) add("Deep link")
    if (capabilities.isLiveTv) add("Live TV")
    if (capabilities.requiresAuth) add("Login")
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
            Text("Region: ${current?.code ?: "not set"}")
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

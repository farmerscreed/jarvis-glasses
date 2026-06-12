package com.echo.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.echo.app.HomeViewModel

@Composable
fun AppRoot() {
    val vm: HomeViewModel = hiltViewModel()
    HomeScreen(vm)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: HomeViewModel) {
    Scaffold(topBar = { TopAppBar(title = { Text("JARVIS") }) }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (vm.busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                Text(vm.status, style = MaterialTheme.typography.bodyMedium)
            }

            val cloudState = when {
                !vm.online -> "OFF-GRID"
                vm.tier == "lean" -> "online · slow"
                else -> "online"
            }
            Text(
                "Cloud: $cloudState" +
                    if (vm.pendingSync > 0) " · ${vm.pendingSync} to sync" else " · all synced",
                style = MaterialTheme.typography.bodySmall,
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Glasses audio (0C)", fontWeight = FontWeight.SemiBold)
                    Text(vm.audioStatus, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = vm::testAudio, enabled = !vm.busy) {
                        Text("Record 4s & play back")
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Glasses BLE (0D)", fontWeight = FontWeight.SemiBold)
                    Text(vm.bleStatus, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::runBleDiagnostic) { Text("Run BLE diagnostic") }
                        Button(onClick = { vm.capturePhoto() }) { Text("Capture photo") }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Sync media (Phase 2)", fontWeight = FontWeight.SemiBold)
                    Text(vm.syncStatus, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::syncGlasses, enabled = !vm.busy) { Text("Sync from glasses") }
                        Button(onClick = vm::lookAndAsk, enabled = !vm.busy) { Text("Look & Ask") }
                    }
                }
            }

            if (!vm.loggedIn) {
                Text(
                    "Personal Memory Index — dev console",
                    style = MaterialTheme.typography.titleMedium,
                )
                Button(onClick = vm::signIn, enabled = !vm.busy) { Text("Sign in (dev)") }
            } else {
                Button(
                    onClick = vm::talk,
                    enabled = !vm.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("🎙  Talk to Jarvis") }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Switch(checked = vm.handsFree, onCheckedChange = { vm.toggleHandsFree(it) })
                    Text("Hands-free — say “Jarvis”")
                }

                HorizontalDivider()

                Text("Remember something", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = vm.memoryText,
                    onValueChange = { vm.memoryText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Memory") },
                )
                Button(onClick = vm::remember, enabled = !vm.busy) { Text("Remember") }

                HorizontalDivider()

                Text("Ask Jarvis", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = vm.question,
                    onValueChange = { vm.question = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Question") },
                )
                Button(onClick = vm::ask, enabled = !vm.busy) { Text("Ask") }

                if (vm.answer.isNotEmpty()) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text("Answer", fontWeight = FontWeight.SemiBold)
                            Text(vm.answer, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                if (vm.recalled.isNotEmpty()) {
                    Text("Recalled memories", fontWeight = FontWeight.SemiBold)
                    vm.recalled.forEach { m ->
                        Text("• ${m.text}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

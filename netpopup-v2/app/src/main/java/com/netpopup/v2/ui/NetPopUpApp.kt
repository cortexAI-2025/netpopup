package com.netpopup.v2.ui

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.ui.ChatViewModel
import com.bitchat.android.R
import com.netpopup.v2.rooms.RoomCrypto
import com.netpopup.v2.rooms.RoomMessage
import com.netpopup.v2.rooms.RoomRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Interface NetPopUp V2 : accueil (état, pseudo, personnes proches, salons), discussion publique et salon. */
@Composable
fun NetPopUpApp(viewModel: ChatViewModel) {
    val rooms by RoomRepository.core.rooms.collectAsState()
    // "public" : discussion publique ; sinon clé d'un salon ; null : accueil
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val room = open?.let { key -> rooms.firstOrNull { it.key == key } }

    BackHandler(enabled = open != null) { open = null }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()) {
        when {
            open == PUBLIC -> PublicScreen(viewModel, onBack = { open = null })
            room != null -> RoomScreen(viewModel, room.key, room.displayName, onBack = { open = null })
            else -> HomeScreen(viewModel, onOpen = { open = it }, onOpenPublic = { open = PUBLIC })
        }
    }
}

private const val PUBLIC = "__public__"

@Composable
private fun rememberBluetoothEnabled(): Boolean {
    val context = LocalContext.current
    fun read(): Boolean = try {
        context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
    } catch (e: SecurityException) {
        false
    }
    var enabled by remember { mutableStateOf(read()) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { enabled = read() }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    return enabled
}

@Composable
private fun rememberLocationEnabled(): Boolean {
    val context = LocalContext.current
    fun read(): Boolean = try {
        LocationManagerCompat.isLocationEnabled(context.getSystemService(LocationManager::class.java))
    } catch (e: Exception) {
        true
    }
    var enabled by remember { mutableStateOf(read()) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { enabled = read() }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    return enabled
}

private fun openSettings(context: Context, action: String) {
    try {
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        // pas d'écran de réglages sur cet appareil : rien à faire
    }
}

private enum class DialogMode { CREATE, JOIN }

@Composable
private fun HomeScreen(viewModel: ChatViewModel, onOpen: (String) -> Unit, onOpenPublic: () -> Unit) {
    val rooms by RoomRepository.core.rooms.collectAsState()
    val unread by RoomRepository.core.unread.collectAsState()
    val peers by viewModel.connectedPeers.collectAsState()
    val nickname by viewModel.nickname.collectAsState()
    val names by viewModel.peerNicknames.collectAsState()
    val direct by viewModel.peerDirect.collectAsState()
    val bluetoothOn = rememberBluetoothEnabled()
    val locationOn = rememberLocationEnabled()
    val context = LocalContext.current
    var dialog by rememberSaveable { mutableStateOf<DialogMode?>(null) }
    var nickDraft by remember(nickname) { mutableStateOf(nickname) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        Text(
            stringResource(if (bluetoothOn) R.string.nup_bluetooth_on else R.string.nup_bluetooth_off),
            color = if (bluetoothOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        if (!bluetoothOn) {
            OutlinedButton(onClick = { openSettings(context, Settings.ACTION_BLUETOOTH_SETTINGS) }) { Text(stringResource(R.string.nup_open_bluetooth_settings)) }
        }
        if (!locationOn) {
            Text(stringResource(R.string.nup_location_off), color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { openSettings(context, Settings.ACTION_LOCATION_SOURCE_SETTINGS) }) { Text(stringResource(R.string.nup_open_location_settings)) }
        }
        Text(stringResource(R.string.nup_peers, peers.size))

        OutlinedTextField(
            value = nickDraft,
            onValueChange = { nickDraft = it.take(32) },
            label = { Text(stringResource(R.string.nup_nickname)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                if (nickDraft.isNotBlank() && nickDraft.trim() != nickname) {
                    TextButton(onClick = { viewModel.setNickname(nickDraft.trim()) }) { Text("OK") }
                }
            },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { dialog = DialogMode.CREATE }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.nup_create)) }
            OutlinedButton(onClick = { dialog = DialogMode.JOIN }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.nup_join)) }
        }

        Text(stringResource(R.string.nup_nearby), style = MaterialTheme.typography.titleMedium)
        if (peers.isEmpty()) {
            Text(stringResource(R.string.nup_nearby_none), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        } else {
            peers.forEach { id ->
                val label = names[id]?.takeIf { it.isNotBlank() } ?: id.take(8)
                val how = stringResource(if (direct[id] == true) R.string.nup_direct else R.string.nup_relayed)
                Text("$label  ·  $how", style = MaterialTheme.typography.bodyMedium)
            }
        }

        OutlinedButton(onClick = onOpenPublic, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.nup_public_chat)) }

        Text(stringResource(R.string.nup_rooms), style = MaterialTheme.typography.titleMedium)
        if (rooms.isEmpty()) {
            Text(stringResource(R.string.nup_no_rooms), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Column {
                rooms.forEach { room ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpen(room.key) }.padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(room.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        val n = unread[room.key] ?: 0
                        if (n > 0) Text(stringResource(R.string.nup_unread, n), color = MaterialTheme.colorScheme.primary)
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    dialog?.let { mode ->
        RoomDialog(mode, onDismiss = { dialog = null }, onDone = { room -> dialog = null; onOpen(room) })
    }
}

@Composable
private fun RoomDialog(mode: DialogMode, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var show by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val create = mode == DialogMode.CREATE
    val transform: VisualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation()

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(stringResource(if (create) R.string.nup_create else R.string.nup_join)) },
        text = {
            Column(Modifier.imePadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(if (create) R.string.nup_create_hint else R.string.nup_join_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(name, { name = it.take(40) }, label = { Text(stringResource(R.string.nup_room_name)) }, singleLine = true, enabled = !working)
                OutlinedTextField(
                    password, { password = it.take(128) }, label = { Text(stringResource(R.string.nup_room_password)) }, singleLine = true,
                    visualTransformation = transform, enabled = !working,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                if (create) {
                    OutlinedTextField(
                        confirm, { confirm = it.take(128) }, label = { Text(stringResource(R.string.nup_room_password_confirm)) }, singleLine = true,
                        visualTransformation = transform, enabled = !working,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = show, onCheckedChange = { show = it })
                    Text(stringResource(R.string.nup_show_password))
                }
                if (working) Text(stringResource(R.string.nup_working))
                error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !working, onClick = {
                error = when {
                    RoomCrypto.normalizeName(name).isEmpty() -> R.string.nup_err_name
                    password.isEmpty() -> R.string.nup_err_empty_password
                    create && password.length < 8 -> R.string.nup_err_short
                    create && password != confirm -> R.string.nup_err_mismatch
                    else -> null
                }
                if (error == null) {
                    working = true
                    scope.launch {
                        val room = RoomRepository.joinOrCreate(name, password)
                        working = false
                        if (room == null) error = R.string.nup_err_failed else onDone(room.key)
                    }
                }
            }) { Text(stringResource(if (create) R.string.nup_do_create else R.string.nup_do_join)) }
        },
        dismissButton = { TextButton(enabled = !working, onClick = onDismiss) { Text(stringResource(R.string.nup_cancel)) } },
    )
}

@Composable
private fun RoomScreen(viewModel: ChatViewModel, roomKey: String, title: String, onBack: () -> Unit) {
    val all by RoomRepository.core.messages.collectAsState()
    val messages = all[roomKey].orEmpty()
    val peers by viewModel.connectedPeers.collectAsState()
    val nickname by viewModel.nickname.collectAsState()
    var draft by rememberSaveable { mutableStateOf("") }
    var confirmLeave by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(roomKey, messages.size) {
        RoomRepository.core.markRead(roomKey)
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.nup_back)) }
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { confirmLeave = true }) { Text(stringResource(R.string.nup_leave)) }
        }
        Text(
            if (peers.isEmpty()) stringResource(R.string.nup_alone) else stringResource(R.string.nup_connected, peers.size),
            color = if (peers.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        HorizontalDivider(Modifier.padding(top = 4.dp))

        if (messages.isEmpty()) {
            Text(stringResource(R.string.nup_empty_room), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(16.dp))
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                items(messages, key = { it.id }) { m -> MessageRow(Line(m.id, m.sender + (m.peerId?.let { " #" + it.take(4) } ?: ""), m.timestampMs, m.text, m.mine)) }
            }
        }

        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                draft, { draft = it },
                placeholder = { Text(stringResource(R.string.nup_message_hint)) },
                modifier = Modifier.weight(1f),
                maxLines = 4,
            )
            Button(enabled = draft.isNotBlank(), onClick = {
                val packet = RoomRepository.core.compose(roomKey, nickname.ifBlank { "?" }, draft)
                if (packet != null) viewModel.broadcastRoomPacket(packet)
                draft = ""
            }) { Text(stringResource(R.string.nup_send)) }
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.nup_leave_title)) },
            text = { Text(stringResource(R.string.nup_leave_text)) },
            confirmButton = {
                TextButton(onClick = { confirmLeave = false; RoomRepository.core.leave(roomKey); onBack() }) { Text(stringResource(R.string.nup_leave)) }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.nup_cancel)) } },
        )
    }
}

private class Line(val id: String, val who: String, val timestampMs: Long, val text: String, val mine: Boolean)

@Composable
private fun PublicScreen(viewModel: ChatViewModel, onBack: () -> Unit) {
    val all by viewModel.messages.collectAsState()
    val peers by viewModel.connectedPeers.collectAsState()
    val nickname by viewModel.nickname.collectAsState()
    val myId = viewModel.meshService.myPeerID
    val lines = remember(all, nickname) {
        all.filter { !it.isPrivate && it.channel == null && it.type == BitchatMessageType.Message && it.sender != "system" && it.content.isNotBlank() }
            .map { Line(it.id, it.sender, it.timestamp.time, it.content, it.senderPeerID == myId) }
    }
    var draft by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1) }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.nup_back)) }
            Text(stringResource(R.string.nup_public_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        }
        Text(
            if (peers.isEmpty()) stringResource(R.string.nup_alone) else stringResource(R.string.nup_connected, peers.size),
            color = if (peers.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Text(stringResource(R.string.nup_public_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        HorizontalDivider()
        if (lines.isEmpty()) {
            Text(stringResource(R.string.nup_public_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(16.dp))
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                items(lines, key = { it.id }) { MessageRow(it) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(draft, { draft = it }, placeholder = { Text(stringResource(R.string.nup_message_hint)) }, modifier = Modifier.weight(1f), maxLines = 4)
            Button(enabled = draft.isNotBlank(), onClick = {
                // Un espace devant empêche qu'un texte commençant par « / » soit pris pour une commande interne.
                viewModel.sendMessage(" " + draft.trim())
                draft = ""
            }) { Text(stringResource(R.string.nup_send)) }
        }
    }
}

@Composable
private fun MessageRow(m: Line) {
    val time = remember(m.timestampMs) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(m.timestampMs)) }
    val who = if (m.mine) stringResource(R.string.nup_me) else m.who
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalAlignment = if (m.mine) Alignment.End else Alignment.Start,
    ) {
        Text("$who · $time", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(
            Modifier
                .background(
                    if (m.mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) { Text(m.text) }
        Spacer(Modifier.height(2.dp))
    }
}

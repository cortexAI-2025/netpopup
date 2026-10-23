package com.netpopup.ui.screen.rooms

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.netpopup.dtn.chat.AdmissionPolicy
import com.netpopup.dtn.chat.Conversation
import com.netpopup.dtn.chat.ConversationId
import com.netpopup.dtn.chat.ConversationKind
import com.netpopup.dtn.chat.JoinRequest
import androidx.compose.ui.text.style.TextOverflow
import com.netpopup.dtn.chat.PendingJoin
import com.netpopup.dtn.chat.RoomInvite
import com.netpopup.dtn.presentation.RoomsUiState
import com.netpopup.ui.component.NetPopUpTopBar
import com.netpopup.ui.theme.NetPopUpTheme
import com.netpopup.ui.theme.Outline
import com.netpopup.ui.theme.Primary
import com.netpopup.ui.theme.TextPrimary
import com.netpopup.ui.theme.TextSecondary

/** Actions de l'écran, séparées de l'état pour pouvoir le prévisualiser. */
class RoomsActions(
    val onTitleChanged: (String) -> Unit = {},
    val onAdmissionChanged: (AdmissionPolicy) -> Unit = {},
    val onCreate: () -> Unit = {},
    val onInviteLinkChanged: (String) -> Unit = {},
    val onContactLinkChanged: (String) -> Unit = {},
    val onAddContact: () -> Unit = {},
    val onRequestToJoin: () -> Unit = {},
    val onApprove: (String) -> Unit = {},
    val onReject: (String) -> Unit = {},
    val onOpenRoom: (ConversationId) -> Unit = {},
    val onLeave: (ConversationId) -> Unit = {},
    val onCopy: (String) -> Unit = {},
    val onDismissInvite: () -> Unit = {},
)

/**
 * Rooms privées : créer (nom et politique d'admission), demander l'accès avec un code, voir les
 * demandes en attente, accepter ou refuser celles qu'on reçoit.
 *
 * Un code ne donne aucun accès : la room n'apparaît dans la liste qu'une fois la demande acceptée.
 */
@Composable
fun RoomsScreen(
    onBack: () -> Unit,
    onJoinRoom: (roomId: String) -> Unit,
    viewModel: RoomsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(state.navigateTo) {
        state.navigateTo?.let { id ->
            viewModel.onNavigated()
            onJoinRoom(id.value)
        }
    }
    LaunchedEffect(state.error) {
        state.error?.let { snackbarHostState.showSnackbar(it); viewModel.clearError() }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(snackbarData = it) } },
        topBar = { NetPopUpTopBar(title = "Private Rooms", onBack = onBack) },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        RoomsContent(
            state = state,
            actions = RoomsActions(
                onTitleChanged = viewModel::onTitleChanged,
                onAdmissionChanged = viewModel::onAdmissionChanged,
                onCreate = viewModel::createRoom,
                onInviteLinkChanged = viewModel::onInviteLinkChanged,
                onContactLinkChanged = viewModel::onContactLinkChanged,
                onAddContact = viewModel::addContact,
                onRequestToJoin = viewModel::requestToJoin,
                onApprove = viewModel::approve,
                onReject = viewModel::reject,
                onOpenRoom = { onJoinRoom(it.value) },
                onLeave = viewModel::leave,
                onCopy = { clipboard.setText(AnnotatedString(it)) },
                onDismissInvite = viewModel::dismissCreatedInvite
            ),
            modifier = Modifier.padding(innerPadding)
        )
    }
}

@Composable
fun RoomsContent(state: RoomsUiState, actions: RoomsActions, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Créer ─────────────────────────────────────────────────────────────
        item { SectionTitle("Start a new room") }
        item {
            OutlinedTextField(
                value = state.title,
                onValueChange = actions.onTitleChanged,
                placeholder = { Text("Room name", color = TextSecondary) },
                singleLine = true,
                colors = fieldColors(),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )
        }
        item { Text("Who can accept new members?", color = TextSecondary, style = MaterialTheme.typography.bodySmall) }
        item {
            Column {
                PolicyRow("Any member", state.admission == AdmissionPolicy.ANY_MEMBER) {
                    actions.onAdmissionChanged(AdmissionPolicy.ANY_MEMBER)
                }
                PolicyRow("Only me (the creator)", state.admission == AdmissionPolicy.CREATOR_ONLY) {
                    actions.onAdmissionChanged(AdmissionPolicy.CREATOR_ONLY)
                }
            }
        }
        item {
            PrimaryButton("Create room", enabled = !state.isLoading, onClick = actions.onCreate, loading = state.isLoading)
        }
        state.createdInvite?.let { invite ->
            item { InviteCard(invite, actions) }
        }

        // ── Rejoindre ─────────────────────────────────────────────────────────
        item { SectionHeader("Ask to join a room") }
        item {
            OutlinedTextField(
                value = state.inviteLink,
                onValueChange = actions.onInviteLinkChanged,
                placeholder = { Text("Paste the invitation link", color = TextSecondary) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done
                ),
                colors = fieldColors(),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            PrimaryButton("Request access", enabled = !state.isLoading && state.inviteLink.isNotBlank(), onClick = actions.onRequestToJoin)
        }
        item {
            Text(
                "A link only lets you ask. You get access once a member accepts.",
                color = TextSecondary, style = MaterialTheme.typography.bodySmall
            )
        }

        // ── Contacts ──────────────────────────────────────────────────────────
        item { SectionHeader("Contacts") }
        state.myContactLink?.let { link ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Your contact link — share it in person.", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { actions.onCopy(link) }) { Text("Copy my link") }
                }
            }
        }
        item {
            OutlinedTextField(
                value = state.contactLink,
                onValueChange = actions.onContactLinkChanged,
                placeholder = { Text("Paste a contact link", color = TextSecondary) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                colors = fieldColors(),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            PrimaryButton("Add contact", enabled = !state.isLoading && state.contactLink.isNotBlank(), onClick = actions.onAddContact)
        }

        // ── Demandes en attente (envoyées) ────────────────────────────────────
        if (state.pendingJoins.isNotEmpty()) {
            item { SectionHeader("Waiting for approval") }
            items(state.pendingJoins, key = { "pending-" + it.inviteId }) { PendingRow(it) }
        }

        // ── Demandes reçues ───────────────────────────────────────────────────
        if (state.joinRequests.isNotEmpty()) {
            item { SectionHeader("Join requests") }
            items(state.joinRequests, key = { "request-" + it.id }) { request ->
                RequestRow(request, state.rooms.firstOrNull { it.id == request.conversationId }?.title, actions)
            }
        }

        // ── Mes rooms ─────────────────────────────────────────────────────────
        if (state.rooms.isNotEmpty()) {
            item { SectionHeader("Your rooms") }
            items(state.rooms, key = { "room-" + it.id.value }) { room -> RoomRow(room, actions) }
        }
    }
}

/** Plusieurs composables dans un même `item` se superposent : on les range dans une colonne. */
@Composable
private fun SectionHeader(text: String) {
    Column {
        Spacer(Modifier.height(12.dp))
        SectionTitle(text)
    }
}

@Composable
private fun SectionTitle(text: String) =
    Text(text, color = TextPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)

@Composable
private fun PolicyRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onSelect), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, color = TextPrimary)
    }
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean, onClick: () -> Unit, loading: Boolean = false) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Primary),
        modifier = Modifier.fillMaxWidth().height(52.dp)
    ) {
        if (loading) CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary)
        else Text(text, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Primary, unfocusedBorderColor = Outline, cursorColor = Primary
)

@Composable
private fun InviteCard(invite: RoomInvite, actions: RoomsActions) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Room created", color = TextPrimary, fontWeight = FontWeight.Bold)
        Text(
            invite.link, color = Primary, fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth()
        )
        Text(
            "Share this link in person. It only lets people ask to join; a member must accept them.",
            color = TextSecondary, style = MaterialTheme.typography.bodySmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { actions.onCopy(invite.link) }) { Text("Copy") }
            TextButton(onClick = actions.onDismissInvite) { Text("Done") }
        }
    }
}

@Composable
private fun PendingRow(pending: PendingJoin) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(pending.roomName, color = TextPrimary)
        Text("Pending…", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RequestRow(request: JoinRequest, roomTitle: String?, actions: RoomsActions) {
    Column(Modifier.fillMaxWidth()) {
        Text(request.displayName, color = TextPrimary, fontWeight = FontWeight.Bold)
        Text(
            "wants to join ${roomTitle ?: "a room"}",
            color = TextSecondary, style = MaterialTheme.typography.bodySmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { actions.onApprove(request.id) },
                colors = ButtonDefaults.buttonColors(containerColor = Primary)
            ) { Text("Accept", color = MaterialTheme.colorScheme.onPrimary) }
            OutlinedButton(onClick = { actions.onReject(request.id) }) { Text("Decline") }
        }
    }
}

@Composable
private fun RoomRow(room: Conversation, actions: RoomsActions) {
    Row(
        Modifier.fillMaxWidth().clickable { actions.onOpenRoom(room.id) },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(room.title, color = TextPrimary, fontWeight = FontWeight.Bold)
            if (room.keyRotationPending) {
                Text("Key rotation in progress", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
        TextButton(onClick = { actions.onLeave(room.id) }) { Text("Leave") }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF101014, heightDp = 900)
@Composable
private fun RoomsContentPreview() {
    val room = Conversation(ConversationId("r1"), ConversationKind.ROOM, "Voisins", AdmissionPolicy.ANY_MEMBER, keyRotationPending = true)
    NetPopUpTheme {
        RoomsContent(
            state = RoomsUiState(
                title = "Quartier",
                createdInvite = RoomInvite(ConversationId("r2"), "netpopup://room/AAAA"),
                pendingJoins = listOf(PendingJoin("H4P9TM", "Voisins", 0)),
                joinRequests = listOf(JoinRequest("j1", room.id, "peer-key", "Alice", 0)),
                rooms = listOf(room)
            ),
            actions = RoomsActions()
        )
    }
}

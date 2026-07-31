package com.example.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.db.ComplaintMessage
import com.example.ui.theme.*
import com.example.util.DateUtils
import com.example.viewmodel.NyayaViewModel

@Composable
fun ComplaintConversationSection(
    complaintId: String,
    currentRole: String, // "CITIZEN" or "AUTHORITY"
    viewModel: NyayaViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    
    // Subscribe to real-time SnapshotListener on complaints/{complaintId}/messages
    DisposableEffect(complaintId) {
        val listener = viewModel.listenToComplaintMessages(complaintId)
        viewModel.markComplaintMessagesRead(complaintId, currentRole)
        onDispose {
            listener?.remove()
        }
    }

    val messages by viewModel.getMessagesForComplaint(complaintId).collectAsState(initial = emptyList())
    var inputText by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Auto-scroll to latest message when message count increases
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, LightSlateBorder),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Live Complaint Conversation",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = AccentOrange
                )
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(SuccessGreen.copy(alpha = 0.2f))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "• Live Realtime",
                        color = SuccessGreen,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            HorizontalDivider(color = LightSlateBorder, thickness = 0.5.dp)

            // Conversation Messages List
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp, max = 320.dp)
                    .background(DarkIndigo, RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                if (messages.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = TextGray,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "No messages in conversation yet.",
                            color = TextGray,
                            fontSize = 12.sp
                        )
                        Text(
                            text = "Send a message below to start two-way real-time communication.",
                            color = TextGray.copy(alpha = 0.7f),
                            fontSize = 11.sp
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(messages, key = { it.messageId }) { msg ->
                            MessageBubbleItem(
                                msg = msg,
                                isMe = msg.senderRole.equals(currentRole, ignoreCase = true)
                            )
                        }
                    }
                }
            }

            // Attachment Toolbar (Photo, Document, Location, Voice Note)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = {
                            Toast.makeText(context, "Photo attachment selected", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.AddAPhoto, contentDescription = "Attach Photo", tint = AccentOrange, modifier = Modifier.size(18.dp))
                    }
                    IconButton(
                        onClick = {
                            Toast.makeText(context, "Document attachment selected", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.AttachFile, contentDescription = "Attach Document", tint = TextDarkSlate, modifier = Modifier.size(18.dp))
                    }
                    IconButton(
                        onClick = {
                            val locMsg = "📍 Location shared: Lat 28.6139, Lng 77.2090"
                            viewModel.sendComplaintMessage(complaintId, locMsg, currentRole) { _, _ -> }
                            Toast.makeText(context, "Location attached", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.LocationOn, contentDescription = "Share Location", tint = SuccessGreen, modifier = Modifier.size(18.dp))
                    }
                    IconButton(
                        onClick = {
                            Toast.makeText(context, "Voice note recorder ready", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = "Voice Note", tint = Color(0xFFA78BFA), modifier = Modifier.size(18.dp))
                    }
                }

                Text(
                    text = "${inputText.length}/2000",
                    color = if (inputText.length > 1800) WarningRed else TextGray,
                    fontSize = 10.sp
                )
            }

            // Two-Way Reply Input Box
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = {
                        if (it.length <= 2000) inputText = it
                    },
                    placeholder = {
                        Text(
                            text = if (currentRole == "AUTHORITY") "Reply to Citizen..." else "Reply to Authority...",
                            color = TextGray,
                            fontSize = 12.sp
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("conversation_reply_input"),
                    singleLine = false,
                    maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = AccentOrange,
                        unfocusedBorderColor = LightSlateBorder,
                        focusedContainerColor = DarkIndigo,
                        unfocusedContainerColor = DarkIndigo
                    )
                )

                Button(
                    onClick = {
                        val textToSend = inputText.trim()
                        if (textToSend.isEmpty()) {
                            Toast.makeText(context, "Please enter a message.", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        isSending = true
                        viewModel.sendComplaintMessage(
                            complaintId = complaintId,
                            messageText = textToSend,
                            senderRole = currentRole
                        ) { success, resultMsg ->
                            isSending = false
                            if (success) {
                                inputText = ""
                            } else {
                                Toast.makeText(context, resultMsg, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    enabled = !isSending && inputText.trim().isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentOrange,
                        disabledContainerColor = AccentOrange.copy(alpha = 0.4f)
                    ),
                    modifier = Modifier
                        .height(52.dp)
                        .testTag("send_conversation_message_btn")
                ) {
                    if (isSending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color.Black,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            Icons.Default.Send,
                            contentDescription = "Send Reply",
                            tint = Color.Black,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MessageBubbleItem(
    msg: ComplaintMessage,
    isMe: Boolean
) {
    val formattedTime = DateUtils.formatTime(msg.createdAt)

    if (msg.messageType == "SYSTEM" || msg.senderRole == "SYSTEM") {
        // System Message Layout (Centered pill badge)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkIndigo.copy(alpha = 0.8f)),
                border = BorderStroke(1.dp, JusticeBlue.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = "System",
                        tint = LightJusticeBlue,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "${msg.message} • $formattedTime",
                        color = LightJusticeBlue,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    } else {
        // Chat Bubble Layout (Left/Right Alignment)
        val alignment = if (isMe) Alignment.CenterEnd else Alignment.CenterStart
        val bubbleColor = when {
            isMe && msg.senderRole == "AUTHORITY" -> SuccessGreen.copy(alpha = 0.25f)
            isMe -> AccentOrange.copy(alpha = 0.25f)
            msg.senderRole == "AUTHORITY" -> SuccessGreen.copy(alpha = 0.15f)
            else -> CardBackground
        }
        val borderColor = when {
            isMe && msg.senderRole == "AUTHORITY" -> SuccessGreen
            isMe -> AccentOrange
            msg.senderRole == "AUTHORITY" -> LightJusticeBlue
            else -> LightSlateBorder
        }

        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = alignment
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = bubbleColor),
                border = BorderStroke(1.dp, borderColor),
                shape = RoundedCornerShape(
                    topStart = 12.dp,
                    topEnd = 12.dp,
                    bottomStart = if (isMe) 12.dp else 2.dp,
                    bottomEnd = if (isMe) 2.dp else 12.dp
                ),
                modifier = Modifier.widthIn(max = 280.dp)
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Sender Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isMe) "You (${msg.senderRole})" else "${msg.senderName} (${msg.senderRole})",
                            color = if (msg.senderRole == "AUTHORITY") LightJusticeBlue else AccentOrange,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }

                    // Message Text
                    Text(
                        text = msg.message,
                        color = Color.White,
                        fontSize = 13.sp
                    )

                    // Timestamp and Read Status
                    Row(
                        modifier = Modifier.align(Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = formattedTime,
                            color = TextGray,
                            fontSize = 9.sp
                        )
                        if (isMe) {
                            if (msg.isRead) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.DoneAll,
                                        contentDescription = "Seen",
                                        tint = SuccessGreen,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text("Seen", color = SuccessGreen, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                }
                            } else {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "Sent",
                                    tint = TextGray,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

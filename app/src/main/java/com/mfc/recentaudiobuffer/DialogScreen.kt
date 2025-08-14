/*
 * Auto Record Moments
 * Copyright (C) 2025 Morten Fjord Christensen
 * ... (license header) ...
 */

package com.mfc.recentaudiobuffer

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.MenuItemColors
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

// --- Reusable Dialog Components ---

/**
 * A custom TextButton for dialogs that uses the app's accent color.
 */
@Composable
private fun DialogTextButton(
    text: String, onClick: () -> Unit
) {
    TextButton(onClick = onClick) {
        Text(
            text, fontWeight = FontWeight.Bold, color = colorResource(id = R.color.purple_accent)
        )
    }
}

/**
 * A custom filled Button for dialogs that uses the app's accent color.
 */
@Composable
private fun DialogButton(
    text: String, onClick: () -> Unit, enabled: Boolean = true
) {
    Button(
        onClick = onClick, enabled = enabled, colors = ButtonDefaults.buttonColors(
            containerColor = colorResource(id = R.color.purple_accent),
            contentColor = colorResource(id = R.color.white)
        )
    ) {
        Text(
            text, fontWeight = FontWeight.Bold
        )
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomAlertDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)? = null
) {
    BasicAlertDialog(onDismissRequest = onDismissRequest) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 2.dp,
                    color = colorResource(id = R.color.purple_accent),
                    shape = RoundedCornerShape(16.dp)
                ),
            shape = RoundedCornerShape(16.dp),
            color = colorResource(id = R.color.teal_100),
            shadowElevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                title()
                Spacer(modifier = Modifier.height(16.dp))
                text()
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End
                ) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }
}

@Composable
fun DirectoryPickerDialog(onDismiss: () -> Unit) {
    CustomAlertDialog(onDismissRequest = onDismiss, title = {
        Text(
            text = "Select Save Directory",
            color = colorResource(id = R.color.teal_900),
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp
        )
    }, text = {
        Text(
            text = "To save recordings, it is required to grant permission to a directory. This will also be the default directory for quick saves from the notification.",
            color = colorResource(id = R.color.teal_900),
            style = MaterialTheme.typography.bodyMedium.copy(
                textAlign = TextAlign.Justify,
                hyphens = Hyphens.Auto,
                lineBreak = LineBreak.Paragraph,
            ),
            fontSize = 16.sp
        )
    }, confirmButton = {
        DialogTextButton(text = "OK", onClick = onDismiss)
    })
}

@Composable
fun SignInErrorDialog(errorMessage: String, onDismiss: () -> Unit) {
    CustomAlertDialog(onDismissRequest = onDismiss, title = {
        Text(
            "Sign-In Failed",
            color = colorResource(id = R.color.teal_900),
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp
        )
    }, text = {
        Text(
            text = errorMessage, color = colorResource(id = R.color.teal_900), fontSize = 16.sp
        )
    }, confirmButton = {
        DialogTextButton(text = "OK", onClick = onDismiss)
    })
}

@Composable
fun RecordingErrorDialog(message: String, onDismiss: () -> Unit) {
    CustomAlertDialog(onDismissRequest = onDismiss, title = {
        Text(
            "Recording Error",
            color = colorResource(id = R.color.teal_900),
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyMedium.copy(
                textAlign = TextAlign.Justify,
                hyphens = Hyphens.Auto,
                lineBreak = LineBreak.Paragraph,
            ),
            fontSize = 20.sp
        )
    }, text = {
        Text(
            text = message, color = colorResource(id = R.color.teal_900), fontSize = 16.sp,
            style = MaterialTheme.typography.bodyMedium.copy(
                textAlign = TextAlign.Justify,
                hyphens = Hyphens.Auto,
                lineBreak = LineBreak.Paragraph,
            ),
        )
    }, confirmButton = {
        DialogTextButton(text = "OK", onClick = onDismiss)
    })
}

@Composable
fun FileSaveDialog(
    suggestedName: String, onDismiss: () -> Unit, onSave: (String) -> Unit
) {
    // Remember the base name without the extension for the text field
    val baseName = remember { suggestedName.removeSuffix(".wav") }
    var text by remember { mutableStateOf(baseName) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.border(
                width = 2.dp,
                color = colorResource(id = R.color.purple_accent),
                shape = RoundedCornerShape(16.dp)
            ),
            shape = RoundedCornerShape(16.dp),
            color = colorResource(id = R.color.teal_100),
            tonalElevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(all = 24.dp)) {
                // 1. Title
                Text(
                    text = "Save Recording",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = colorResource(id = R.color.teal_900)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 2. Text Input
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        label = { Text("File name") },
                        modifier = Modifier.weight(1f), // Allow the text field to grow
                        colors = appTextFieldColors()
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = ".wav",
                        style = MaterialTheme.typography.bodyLarge,
                        color = colorResource(id = R.color.teal_900)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 3. Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End
                ) {
                    // --- CHANGE: Using the new custom DialogTextButton ---
                    DialogTextButton(text = "CANCEL", onClick = onDismiss)

                    Spacer(modifier = Modifier.width(8.dp))

                    // --- CHANGE: Using the new custom DialogButton ---
                    DialogButton(
                        text = "SAVE", onClick = {
                            if (text.isNotBlank()) {
                                onSave(text + ".wav")
                            }
                        }, enabled = text.isNotBlank()
                    )
                }
            }
        }
    }
}

@Composable
fun DeleteAccountConfirmationDialog(
    onDismissRequest: () -> Unit, onConfirm: () -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    val isConfirmEnabled = inputText == "DELETE"

    CustomAlertDialog(onDismissRequest = onDismissRequest, title = {
        Text(
            "Are you sure?",
            fontWeight = FontWeight.Bold,
            color = colorResource(id = R.color.red),
            fontSize = 22.sp
        )
    }, text = {
        Column {
            Text(
                "This action is permanent and cannot be undone. All your synced settings will be lost. To proceed, please type DELETE in the box below.",
                color = colorResource(id = R.color.teal_900),
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                label = { Text("Type DELETE to confirm") },
                singleLine = true,
                keyboardActions = KeyboardActions(onDone = { if (isConfirmEnabled) onConfirm() }),
                modifier = Modifier.fillMaxWidth(), colors = appTextFieldColors()
            )
        }
    }, dismissButton = {
        DialogTextButton(text = "CANCEL", onClick = onDismissRequest)
    }, confirmButton = {
        Button(
            onClick = onConfirm, enabled = isConfirmEnabled, colors = ButtonDefaults.buttonColors(
                containerColor = colorResource(id = R.color.red),
                disabledContainerColor = colorResource(id = R.color.red).copy(alpha = 0.5f)
            )
        ) {
            Text("CONFIRM DELETE")
        }
    })
}

@Composable
fun PrivacyInfoDialog(onDismissRequest: () -> Unit) {
    CustomAlertDialog(onDismissRequest = onDismissRequest, title = {
        Text(
            "Privacy Info",
            fontWeight = FontWeight.Bold,
            color = colorResource(id = R.color.teal_900),
            fontSize = 20.sp
        )
    }, text = {
        Text(
            "This app continuously records audio to a ring-buffer (limited size) in your phone's memory (RAM). No audio data is saved or sent anywhere unless you explicitly press the 'Save' button, which only processes and stores it on your local device. Clearing the buffer or closing the persistent notification will discard the buffered audio.",
            style = MaterialTheme.typography.bodyMedium.copy(
                textAlign = TextAlign.Justify,
                hyphens = Hyphens.Auto,
                lineBreak = LineBreak.Paragraph,
            ),
            color = colorResource(id = R.color.teal_900),
            lineHeight = 20.sp,
        )
    }, confirmButton = {
        DialogTextButton(text = "GOT IT", onClick = onDismissRequest)
    })
}


@Composable
fun LockScreenInfoDialog(
    onDismissRequest: () -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit
) {
    CustomAlertDialog(onDismissRequest = onDismissRequest, title = {
        Text(
            "Lock Screen Controls",
            color = colorResource(id = R.color.teal_900),
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp
        )
    }, text = {
        Text(
            text = "To use the controls from the lock screen, your system needs to show detailed notifications. The steps vary by device.\n\n" + "1. Tap 'Open Settings'.\n" + "2. Find 'Lock screen' and set it to 'Show all notification content' (or similar wording like 'Show details' or 'Show sensitive content').\n" + "3. Some devices have a 'Customize lock screen' option, so search for it in global Settings. If so, set the lock screen notifications are set to display as a 'List' or 'Details', not just icons.",
            color = colorResource(id = R.color.teal_900),
            fontSize = 16.sp,
            lineHeight = 22.sp,
            style = MaterialTheme.typography.bodyMedium.copy(
                textAlign = TextAlign.Justify,
                hyphens = Hyphens.Auto,
                lineBreak = LineBreak.Paragraph,
            )
        )
    }, dismissButton = {
        DialogTextButton(text = "LATER", onClick = onDismiss)
    }, confirmButton = {
        DialogButton(text = "OPEN SETTINGS", onClick = onConfirm)
    })
}


@Composable
fun BatteryOptimizationDialog(
    onDismissRequest: () -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit
) {
    CustomAlertDialog(onDismissRequest = onDismissRequest, title = {
        Text(
            "Improve Buffering Lifetime",
            color = colorResource(id = R.color.teal_900),
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp
        )
    }, text = {
        Text(
            text = "To prevent Android from stopping the recording service in the background after a while, please allow unrestricted battery usage for this app.\n\n" + "After tapping 'Open Settings', find the battery section, commonly 'App battery usage', and select 'Unrestricted'.",
            color = colorResource(id = R.color.teal_900),
            style = MaterialTheme.typography.bodyMedium.copy(
                textAlign = TextAlign.Justify,
                hyphens = Hyphens.Auto,
                lineBreak = LineBreak.Paragraph,
            ),
            fontSize = 16.sp,
            lineHeight = 22.sp
        )
    }, dismissButton = {
        DialogTextButton(text = "LATER", onClick = onDismiss)
    }, confirmButton = {
        DialogButton(text = "OPEN SETTINGS", onClick = onConfirm)
    })
}

@Composable
fun appTextFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = colorResource(R.color.purple_accent),
    unfocusedBorderColor = colorResource(R.color.purple_accent),
    focusedLabelColor = colorResource(R.color.purple_accent),
    unfocusedLabelColor = colorResource(R.color.purple_accent),
    focusedContainerColor = colorResource(R.color.teal_150).copy(alpha = 0.9f),
    unfocusedContainerColor = Color.White.copy(alpha = 0.35f),
    focusedTextColor = colorResource(R.color.teal_900),
    unfocusedTextColor = colorResource(R.color.teal_900),
    errorBorderColor = colorResource(id = R.color.red),
    errorSupportingTextColor = colorResource(id = R.color.red),
    errorContainerColor = colorResource(id = R.color.teal_150)
)

@Preview(showBackground = true)
@Composable
fun DialogsPreview() {
    SignInErrorDialog(
        errorMessage = "A network error occurred. Please check your connection and try again.",
        onDismiss = {})
}

@Preview(showBackground = true)
@Composable
fun RecordingErrorDialogPreview() {
    RecordingErrorDialog(
        message = "Not enough memory to start recording. Please reduce config values in settings.",
        onDismiss = {})
}

@Preview(showBackground = true)
@Composable
fun DirectoryPickerDialogPreview() {
    DirectoryPickerDialog(onDismiss = {})
}

@Preview(showBackground = true)
@Composable
fun LockScreenInfoDialogPreview() {
    LockScreenInfoDialog({}, {}, {})
}

@Preview(showBackground = true)
@Composable
fun BatteryOptimizationDialogPreview() {
    BatteryOptimizationDialog({}, {}, {})
}

@Preview(showBackground = true)
@Composable
fun FileSaveDialogPreview() {
    FileSaveDialog("yobro.wav", {}, {})
}

@Preview(showBackground = true)
@Composable
fun DeleteAccountConfirmationDialogPreview() {
    DeleteAccountConfirmationDialog({}, {})
}

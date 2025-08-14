/*
 * Auto Record Moments
 * Copyright (C) 2025 Morten Fjord Christensen
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.mfc.recentaudiobuffer

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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


/**
 * The base layout for all custom dialogs in the app. This composable handles the
 * Surface, border, shape, and button arrangement.
 * It provides a slot for custom content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomDialogBase(
    onDismissRequest: () -> Unit,
    title: String,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
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
                // 1. Title (Styled consistently)
                Text(
                    text = title,
                    color = colorResource(id = R.color.teal_900),
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
                Spacer(modifier = Modifier.height(16.dp))

                // 2. Content (with default text styling)
                // This allows passing simple Text() or complex layouts.
                val dialogBodyStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = colorResource(id = R.color.teal_900),
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    textAlign = TextAlign.Justify,
                    hyphens = Hyphens.Auto,
                    lineBreak = LineBreak.Paragraph,
                )
                CompositionLocalProvider(LocalTextStyle provides dialogBodyStyle) {
                    content()
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 3. Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    dismissButton?.invoke()
                    Spacer(modifier = Modifier.width(8.dp))
                    confirmButton()
                }
            }
        }
    }
}

/**
 * A standardized dialog for displaying a title and a simple text message.
 * This is the most common dialog type.
 */
@Composable
fun CustomAlertDialog(
    onDismissRequest: () -> Unit,
    title: String,
    text: String,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)? = null,
) {
    CustomDialogBase(
        onDismissRequest = onDismissRequest,
        title = title,
        confirmButton = confirmButton,
        dismissButton = dismissButton
    ) {
        // The content is a simple Text composable.
        Text(text = text)
    }
}

/**
 * A more flexible version of the dialog that accepts any composable content
 * for its body, useful for dialogs with input fields or complex layouts.
 */
@Composable
fun CustomAlertDialog(
    onDismissRequest: () -> Unit,
    title: String,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    CustomDialogBase(
        onDismissRequest = onDismissRequest,
        title = title,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        content = content
    )
}

// --- Specific Dialog Implementations ---

@Composable
fun DirectoryPickerDialog(onDismiss: () -> Unit) {
    CustomAlertDialog(
        onDismissRequest = onDismiss,
        title = "Select Save Directory",
        text = "To save recordings, it is required to grant permission to a directory. This will also be the default directory for quick saves from the notification.",
        confirmButton = {
            DialogTextButton(text = "OK", onClick = onDismiss)
        }
    )
}

@Composable
fun SignInErrorDialog(errorMessage: String, onDismiss: () -> Unit) {
    CustomAlertDialog(
        onDismissRequest = onDismiss,
        title = "Sign-In Failed",
        text = errorMessage,
        confirmButton = {
            DialogTextButton(text = "OK", onClick = onDismiss)
        }
    )
}

@Composable
fun NoAccountsFoundDialog(onDismissErrorDialog: () -> Unit) {
    val context = LocalContext.current
    CustomAlertDialog(
        onDismissRequest = onDismissErrorDialog,
        title = "No Accounts Found",
        text = "To sign in, please add a Google account to this device first.",
        dismissButton = { DialogTextButton(text = "CANCEL", onClick = onDismissErrorDialog) },
        confirmButton = {
            DialogButton(text = "ADD ACCOUNT", onClick = {
                context.startActivity(Intent(Settings.ACTION_ADD_ACCOUNT))
                onDismissErrorDialog()
            })
        }
    )
}


@Composable
fun RecordingErrorDialog(message: String, onDismiss: () -> Unit) {
    CustomAlertDialog(
        onDismissRequest = onDismiss,
        title = "Recording Error",
        text = message,
        confirmButton = {
            DialogTextButton(text = "OK", onClick = onDismiss)
        }
    )
}

@Composable
fun FileSaveDialog(
    suggestedName: String, onDismiss: () -> Unit, onSave: (String) -> Unit
) {
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
                Text(
                    text = "Save Recording",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = colorResource(id = R.color.teal_900)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        label = { Text("File name") },
                        modifier = Modifier.weight(1f),
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
                Row(
                    modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End
                ) {
                    DialogTextButton(text = "CANCEL", onClick = onDismiss)
                    Spacer(modifier = Modifier.width(8.dp))
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

    CustomAlertDialog(
        onDismissRequest = onDismissRequest,
        title = "Are you sure?",
        dismissButton = {
            DialogTextButton(text = "CANCEL", onClick = onDismissRequest)
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = isConfirmEnabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colorResource(id = R.color.red),
                    disabledContainerColor = colorResource(id = R.color.red).copy(alpha = 0.5f)
                )
            ) {
                Text("CONFIRM DELETE")
            }
        }
    ) {
        // This dialog uses the flexible content slot for custom layout
        Text(
            "This action is permanent and cannot be undone. All your synced settings will be lost. To proceed, please type DELETE in the box below.",
            color = colorResource(id = R.color.teal_900) // Override default style for normal justification
        )
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = inputText,
            onValueChange = { inputText = it },
            label = { Text("Type DELETE to confirm") },
            singleLine = true,
            keyboardActions = KeyboardActions(onDone = { if (isConfirmEnabled) onConfirm() }),
            modifier = Modifier.fillMaxWidth(),
            colors = appTextFieldColors()
        )
    }
}

@Composable
fun PrivacyInfoDialog(onDismissRequest: () -> Unit) {
    CustomAlertDialog(
        onDismissRequest = onDismissRequest,
        title = "Privacy Info",
        text = "This app continuously records audio to a ring-buffer (limited size) in your phone's memory (RAM). No audio data is saved or sent anywhere unless you explicitly press the 'Save' button, which only processes and stores it on your local device. Clearing the buffer or closing the persistent notification will discard the buffered audio.",
        confirmButton = {
            DialogTextButton(text = "GOT IT", onClick = onDismissRequest)
        }
    )
}


@Composable
fun LockScreenInfoDialog(
    onDismissRequest: () -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit
) {
    CustomAlertDialog(
        onDismissRequest = onDismissRequest,
        title = "Lock Screen Controls",
        text = "To use the controls from the lock screen, your system needs to show detailed notifications. The steps vary by device.\n\n" + "1. Tap 'Open Settings'.\n" + "2. Find 'Lock screen' and set it to 'Show all notification content' (or similar wording like 'Show details' or 'Show sensitive content').\n" + "3. Some devices have a 'Customize lock screen' option, so search for it in global Settings. If so, set the lock screen notifications are set to display as a 'List' or 'Details', not just icons.",
        dismissButton = {
            DialogTextButton(text = "LATER", onClick = onDismiss)
        },
        confirmButton = {
            DialogButton(text = "OPEN SETTINGS", onClick = onConfirm)
        }
    )
}


@Composable
fun BatteryOptimizationDialog(
    onDismissRequest: () -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit
) {
    CustomAlertDialog(
        onDismissRequest = onDismissRequest,
        title = "Improve Buffering Lifetime",
        text = "To prevent Android from stopping the recording service in the background after a while, please allow unrestricted battery usage for this app.\n\n" + "After tapping 'Open Settings', find the battery section, commonly 'App battery usage', and select 'Unrestricted'.",
        dismissButton = {
            DialogTextButton(text = "LATER", onClick = onDismiss)
        },
        confirmButton = {
            DialogButton(text = "OPEN SETTINGS", onClick = onConfirm)
        }
    )
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

// --- Previews ---

@Preview(showBackground = true)
@Composable
fun NoAccountsFoundDialogPreview() {
    NoAccountsFoundDialog(onDismissErrorDialog = {})
}


@Preview(showBackground = true)
@Composable
fun SignInErrorDialogPreview() {
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

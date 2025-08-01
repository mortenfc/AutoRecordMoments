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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

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
            text = stringResource(id = R.string.select_directory),
            color = colorResource(id = R.color.teal_900),
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp
        )
    }, text = {
        Text(
            text = "To save recordings, please grant permission to a directory. You will be prompted to select one now.",
            color = colorResource(id = R.color.teal_900),
            fontSize = 16.sp
        )
    }, confirmButton = {
        TextButton(onClick = onDismiss) { Text("OK") }
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
        TextButton(onClick = onDismiss) { Text("OK") }
    })
}

@Composable
fun RecordingErrorDialog(message: String, onDismiss: () -> Unit) {
    CustomAlertDialog(onDismissRequest = onDismiss, title = {
        Text(
            "Recording Error",
            color = colorResource(id = R.color.teal_900),
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp
        )
    }, text = {
        Text(text = message, color = colorResource(id = R.color.teal_900), fontSize = 16.sp)
    }, confirmButton = {
        TextButton(onClick = onDismiss) { Text("OK") }
    })
}

@Composable
fun FileSaveDialog(
    suggestedName: String, onDismiss: () -> Unit, onSave: (fileName: String) -> Unit
) {
    var text by remember { mutableStateOf(suggestedName) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = colorResource(id = R.color.teal_100),
            modifier = Modifier.border(
                width = 2.dp,
                color = colorResource(id = R.color.purple_accent),
                shape = RoundedCornerShape(16.dp)
            )
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    "Save Recording",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = colorResource(id = R.color.teal_900)
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Filename") },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("CANCEL") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { onSave(text) }) { Text("SAVE") }
                }
            }
        }
    }
}

@Composable
fun DeleteAccountConfirmationDialog(
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    val isConfirmEnabled = inputText == "DELETE"

    CustomAlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                "Are you sure?",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error,
                fontSize = 22.sp
            )
        },
        text = {
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
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("CANCEL")
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = isConfirmEnabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    disabledContainerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                )
            ) {
                Text("CONFIRM DELETE")
            }
        }
    )
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
        TextButton(onClick = onDismissRequest) {
            Text(
                "GOT IT",
                fontWeight = FontWeight.Bold,
                color = colorResource(id = R.color.purple_accent)
            )
        }
    })
}

@Preview(showBackground = true)
@Composable
fun DialogsPreview() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SignInErrorDialog(
            errorMessage = "A network error occurred. Please check your connection and try again.",
            onDismiss = {})
        DirectoryPickerDialog(onDismiss = {})
        RecordingErrorDialog(
            message = "Not enough memory to start recording. Please reduce config values in settings.",
            onDismiss = {})
    }
}

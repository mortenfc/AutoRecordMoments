package com.mfc.recentaudiobuffer

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileSaveDialog(
    suggestedName: String, onDismiss: () -> Unit, onSave: (String) -> Unit
) {
    val context = LocalContext.current
    var filename by remember { mutableStateOf(suggestedName) }

    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 2.5.dp,
                    color = colorResource(id = R.color.purple_accent),
                    shape = RoundedCornerShape(16.dp)
                ), shape = RoundedCornerShape(16.dp), color = colorResource(id = R.color.teal_100)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(id = R.string.enter_filename),
                    color = colorResource(id = R.color.teal_900),
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
                Spacer(modifier = Modifier.padding(8.dp))
                OutlinedTextField(
                    value = filename,
                    onValueChange = { filename = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(id = R.string.enter_filename)) },
                    singleLine = true
                )
                Spacer(modifier = Modifier.padding(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End
                ) {
                    MainButton(
                        text = stringResource(id = R.string.cancel),
                        onClick = onDismiss,
                        icon = R.drawable.baseline_cancel_24,
                        width = 100.dp,
                        contentPadding = 4.dp
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    MainButton(
                        text = stringResource(id = R.string.ok),
                        icon = R.drawable.baseline_save_alt_24,
                        onClick = {
                            onSave(filename)
                        },
                        width = 80.dp,
                        contentPadding = 4.dp
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirectoryPickerDialog(
    onDismiss: () -> Unit
) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 2.5.dp,
                    color = colorResource(id = R.color.purple_accent),
                    shape = RoundedCornerShape(16.dp)
                ),
            shape = RoundedCornerShape(16.dp),
            color = colorResource(id = R.color.teal_100),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(id = R.string.select_directory),
                    color = colorResource(id = R.color.teal_900),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        hyphens = Hyphens.Auto,
                        lineBreak = LineBreak.Paragraph,
                        textAlign = TextAlign.Justify,
                    ),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Spacer(modifier = Modifier.padding(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End
                ) {
                    MainButton(
                        text = stringResource(id = R.string.ok),
                        onClick = onDismiss,
                        icon = R.drawable.baseline_playlist_add_check_24,
                        width = 80.dp,
                        contentPadding = 4.dp
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun FileSaveDialogPreview() {
    FileSaveDialog(suggestedName = "yeaaahBoi.wav", onDismiss = {}, onSave = {})
}

@Preview(showBackground = true)
@Composable
fun DirectoryPickerDialogPreview() {
    DirectoryPickerDialog(onDismiss = {})
}
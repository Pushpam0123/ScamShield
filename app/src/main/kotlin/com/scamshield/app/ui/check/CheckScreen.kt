package com.scamshield.app.ui.check

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scamshield.app.R

/**
 * `design.md` section 10.1's Check screen: a paste box, a paste-from-clipboard shortcut, and
 * the Check CTA, with an empty-state hint explaining how to get here from WhatsApp or SMS.
 */
@Composable
fun CheckScreen(
    messageText: String,
    isAnalyzing: Boolean,
    errorMessageRes: Int?,
    onTextChanged: (String) -> Unit,
    onCheck: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboardManager = LocalClipboardManager.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text(stringResource(R.string.check_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = messageText,
            onValueChange = onTextChanged,
            label = { Text(stringResource(R.string.check_input_label)) },
            placeholder = { Text(stringResource(R.string.check_input_placeholder)) },
            minLines = 5,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 160.dp),
        )
        Spacer(Modifier.height(8.dp))

        Row {
            TextButton(
                onClick = {
                    val clipped = clipboardManager.getText()?.text
                    if (clipped.isNullOrBlank()) return@TextButton
                    onTextChanged(clipped)
                },
                // §10.3: every touch target >= 48 dp; TextButton's default already satisfies this.
            ) {
                Text(stringResource(R.string.check_paste_button))
            }
            if (messageText.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onClear) {
                    Text(stringResource(R.string.check_clear))
                }
            }
        }

        if (errorMessageRes != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(errorMessageRes),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (messageText.isEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.check_empty_state),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(32.dp))

        Button(
            onClick = onCheck,
            enabled = !isAnalyzing,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp), // §10.3: >= 48 dp touch target
        ) {
            if (isAnalyzing) {
                CircularProgressIndicator(
                    modifier = Modifier.height(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(stringResource(R.string.check_cta), fontSize = 18.sp)
            }
        }
    }
}

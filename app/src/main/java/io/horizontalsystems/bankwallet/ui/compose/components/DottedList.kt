package io.horizontalsystems.bankwallet.ui.compose.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.horizontalsystems.bankwallet.ui.compose.ComposeAppTheme

@Composable
fun DottedList(
    modifier: Modifier,
    items: List<String>,
    textColor: Color = ComposeAppTheme.colors.grey
) {
    Column(
        modifier = modifier
    ) {
        items.forEach { item ->
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                Text(
                    text = "\u2022", // bullet symbol
                    modifier = Modifier.padding(end = 8.dp),
                    style = ComposeAppTheme.typography.body,
                    color = textColor
                )
                Text(
                    text = item,
                    style = ComposeAppTheme.typography.body,
                    color = textColor
                )
            }
        }
    }
}
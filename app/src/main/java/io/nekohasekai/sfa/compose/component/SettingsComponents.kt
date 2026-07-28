package io.nekohasekai.sfa.compose.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.R

/**
 * Единый заголовок секции настроек. Раньше по экранам было 2+ стиля заголовков и разный padding
 * (labelLarge/primary vs titleSmall/onSurface, отступ 8 vs 16) — вертикальный ритм «прыгал».
 */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 32.dp, end = 32.dp, top = 16.dp, bottom = 8.dp),
    )
}

/**
 * Единая карточка-контейнер для группы строк настроек (surfaceContainer, скругление 12dp).
 * Card сам обрезает содержимое по своей форме, поэтому строкам внутри НЕ нужен per-row `.clip(...)`.
 */
@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(content = content)
    }
}

/**
 * Единая нижняя кнопка «Сохранить» + инлайн-подтверждение для экранов настроек с текстовым
 * ВВОДОМ (DNS, split-tunnel и т.п.). Раньше каждый такой экран рисовал свой Button+статус —
 * разъезжались отступы/цвета. Тумблеры/выбор применяются мгновенно и SaveBar не используют.
 */
@Composable
fun SaveBar(
    savedMessage: String?,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    saveLabel: String = stringResource(R.string.save),
) {
    Spacer(modifier = Modifier.height(16.dp))
    Button(
        onClick = onSave,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(saveLabel)
    }
    if (savedMessage != null) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = savedMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

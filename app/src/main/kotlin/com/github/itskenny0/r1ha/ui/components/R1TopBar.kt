package com.github.itskenny0.r1ha.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.theme.R1

/**
 * Shared top-bar for sub-screens: chevron-back + screen title + 1dp hairline divider.
 * Identical across Settings, Favorites Picker, About, and Theme Picker — pull it out so
 * the four screens stay aligned to the pixel and any future restyling lands in one place.
 *
 * The 44dp chevron lives flush-left at x=4dp so its visual centre lines up with the
 * 22dp content gutter used by the rows below. [title] is rendered in [R1.screenTitle]
 * (deliberately not uppercase here — callers pass an already-uppercase string when they
 * want all-caps).
 */
@Composable
fun R1TopBar(title: String, onBack: () -> Unit) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 22.dp, top = 6.dp, bottom = 6.dp),
        ) {
            ChevronBack(onClick = onBack)
            Spacer(Modifier.width(4.dp))
            Text(title, style = R1.screenTitle, color = R1.Ink)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(R1.Hairline),
        )
    }
}

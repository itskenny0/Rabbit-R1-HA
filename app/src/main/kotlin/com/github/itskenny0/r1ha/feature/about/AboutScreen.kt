package com.github.itskenny0.r1ha.feature.about

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Placeholder — replaced in M14. */
@Composable
fun AboutScreen(
    onBack: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Text("About — coming soon", modifier = Modifier.wrapContentSize())
    }
}

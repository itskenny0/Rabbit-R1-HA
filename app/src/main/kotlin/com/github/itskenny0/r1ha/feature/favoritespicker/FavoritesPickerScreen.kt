package com.github.itskenny0.r1ha.feature.favoritespicker

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.ui.components.ChevronBack

@Composable
fun FavoritesPickerScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val vm: FavoritesPickerViewModel = viewModel(
        factory = FavoritesPickerViewModel.factory(
            repo = haRepository,
            settings = settings,
        )
    )
    val ui by vm.ui.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            ChevronBack(onClick = onBack)
            Text(
                text = "Favourites",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        when {
            ui.loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            ui.error != null -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    ui.error ?: "Error",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.padding(top = 8.dp))
                Text(
                    text = "Tap the back arrow, open Settings (⚙), then Sign out & reconnect.",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
            ) {
                items(ui.rows, key = { row: FavoritesPickerViewModel.Row -> row.state.id.value }) { row ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                    ) {
                        Checkbox(
                            checked = row.isFavorite,
                            onCheckedChange = { vm.toggle(row.state.id.value) },
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp),
                        ) {
                            Text(
                                text = row.state.friendlyName,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = row.state.id.value,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                            )
                        }
                        if (row.isFavorite) {
                            IconButton(onClick = { vm.moveUp(row.state.id.value) }) {
                                Icon(
                                    imageVector = Icons.Filled.KeyboardArrowUp,
                                    contentDescription = "Move up",
                                )
                            }
                            IconButton(onClick = { vm.moveDown(row.state.id.value) }) {
                                Icon(
                                    imageVector = Icons.Filled.KeyboardArrowDown,
                                    contentDescription = "Move down",
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

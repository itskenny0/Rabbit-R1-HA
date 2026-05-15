package com.github.itskenny0.r1ha.feature.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.PersistentNotification
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Drives the HA Notifications (persistent_notification.*) surface.
 * Pulls notifications via [HaRepository.listPersistentNotifications]
 * and dispatches dismiss actions via
 * [HaRepository.dismissPersistentNotification]. After a successful
 * dismiss we optimistically remove the row from the in-memory list so
 * the UI updates without waiting for the next refresh — HA's own
 * persistent_notification.dismiss is essentially synchronous so we
 * shouldn't see ghost entries.
 */
class NotificationsViewModel(
    private val haRepository: HaRepository,
) : ViewModel() {

    @androidx.compose.runtime.Stable
    data class UiState(
        val loading: Boolean = true,
        val notifications: List<PersistentNotification> = emptyList(),
        val error: String? = null,
        /** Set of notification IDs whose dismiss is in flight — drives
         *  per-row 'DISMISSING…' affordance and prevents double-tap. */
        val pendingDismiss: Set<String> = emptySet(),
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            haRepository.listPersistentNotifications().fold(
                onSuccess = { notifications ->
                    R1Log.i("Notifications", "loaded ${notifications.size}")
                    _ui.value = _ui.value.copy(
                        loading = false,
                        notifications = notifications,
                        error = null,
                    )
                },
                onFailure = { t ->
                    R1Log.w("Notifications", "list failed: ${t.message}")
                    Toaster.error("Notifications load failed: ${t.message ?: "unknown"}")
                    _ui.value = _ui.value.copy(
                        loading = false,
                        error = t.message ?: "Failed to load",
                    )
                },
            )
        }
    }

    /** Bulk dismiss every loaded notification. Fires one
     *  persistent_notification.dismiss per row in parallel; each one
     *  shows up in `pendingDismiss` while in flight so the UI greys
     *  the row + button consistently with the single-dismiss path. */
    fun dismissAll() {
        val all = _ui.value.notifications.map { it.notificationId }
        if (all.isEmpty()) return
        _ui.value = _ui.value.copy(pendingDismiss = _ui.value.pendingDismiss + all.toSet())
        viewModelScope.launch {
            // Fire all in parallel. Optimistic UI removal happens per
            // success; failures restore the row + surface a toast.
            kotlinx.coroutines.coroutineScope {
                for (id in all) {
                    launch {
                        haRepository.dismissPersistentNotification(id).fold(
                            onSuccess = {
                                _ui.value = _ui.value.copy(
                                    notifications = _ui.value.notifications.filterNot {
                                        it.notificationId == id
                                    },
                                    pendingDismiss = _ui.value.pendingDismiss - id,
                                )
                            },
                            onFailure = { t ->
                                R1Log.w("Notifications", "dismiss $id failed: ${t.message}")
                                _ui.value = _ui.value.copy(
                                    pendingDismiss = _ui.value.pendingDismiss - id,
                                )
                            },
                        )
                    }
                }
            }
            Toaster.show("Dismissed ${all.size} notification${if (all.size == 1) "" else "s"}")
        }
    }

    fun dismiss(notification: PersistentNotification) {
        if (notification.notificationId in _ui.value.pendingDismiss) return
        _ui.value = _ui.value.copy(
            pendingDismiss = _ui.value.pendingDismiss + notification.notificationId,
        )
        viewModelScope.launch {
            haRepository.dismissPersistentNotification(notification.notificationId).fold(
                onSuccess = {
                    R1Log.i("Notifications", "dismissed ${notification.notificationId}")
                    // Optimistic remove — HA's dismiss is synchronous so the row
                    // really is gone by now.
                    _ui.value = _ui.value.copy(
                        notifications = _ui.value.notifications.filterNot {
                            it.notificationId == notification.notificationId
                        },
                        pendingDismiss = _ui.value.pendingDismiss - notification.notificationId,
                    )
                },
                onFailure = { t ->
                    R1Log.w("Notifications", "dismiss failed: ${t.message}")
                    Toaster.error("Dismiss failed: ${t.message ?: "unknown"}")
                    _ui.value = _ui.value.copy(
                        pendingDismiss = _ui.value.pendingDismiss - notification.notificationId,
                    )
                },
            )
        }
    }

    companion object {
        fun factory(haRepository: HaRepository) = viewModelFactory {
            initializer { NotificationsViewModel(haRepository) }
        }
    }
}

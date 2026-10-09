package io.healthassistant.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.healthassistant.bridge.NotificationItem
import io.healthassistant.shared.data.repository.NotificationRepository
import io.healthassistant.shared.data.repository.RefreshOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Phase I — UI state for the Inbox screen. */
data class InboxUiState(
    val loading: Boolean = true,
    val items: List<NotificationItem> = emptyList(),
    val total: Int = 0,
    val unreadCount: Int = 0,
    val error: String? = null,
    val selected: NotificationItem? = null,
)

/**
 * Phase I — owns the Inbox data flow: the owner's notification inbox + the
 * mark-read / mark-dismissed / mark-all-read actions.
 *
 * **Offline-first (M6):** the list + the unread badge read from the
 * notification cache via [NotificationRepository.observeAll] /
 * [observeUnreadCount] (Room-backed, reactive, offline). [reload] only
 * refreshes the cache; offline or a failed refresh keeps the saved inbox on
 * screen (error only when the cache is empty AND the refresh can't succeed).
 * The mark ops apply their cache update only after the bridge call succeeds —
 * the UI updates instantly, without a refetch round-trip.
 */
class InboxViewModel(
    private val repo: NotificationRepository,
) : ViewModel() {
    private val refreshOutcome = MutableStateFlow<RefreshOutcome?>(null)
    private val selectedId = MutableStateFlow<String?>(null)

    val state: StateFlow<InboxUiState> =
        combine(repo.observeAll(), repo.observeUnreadCount(), refreshOutcome, selectedId) { items, unread, outcome, selId ->
            val selected = selId?.let { id -> items.firstOrNull { it.recipientId == id } }
            when {
                items.isNotEmpty() || outcome == RefreshOutcome.REFRESHED ->
                    InboxUiState(
                        loading = false,
                        items = items,
                        total = items.size,
                        unreadCount = unread,
                        selected = selected,
                    )
                outcome == null -> InboxUiState(loading = true, unreadCount = unread)
                else -> InboxUiState(loading = false, error = "load failed", unreadCount = unread)
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, InboxUiState())

    init {
        reload()
    }

    /** Refresh the inbox cache from the bridge (no-op on the network when
     *  offline — the cache keeps rendering the saved inbox), then reconcile
     *  the unread badge with the server count (re-pulls a wider page when
     *  the server knows more unread than the cache). */
    fun reload() {
        viewModelScope.launch {
            refreshOutcome.value = repo.refresh()
            repo.reconcileUnreadCount()
        }
    }

    /** Open the full-content detail sheet for one notification. Marks an
     *  unread item read on open. */
    fun openDetail(item: NotificationItem) {
        selectedId.value = item.recipientId
        if (item.status == "unread") markRead(item.recipientId)
    }

    fun closeDetail() {
        selectedId.value = null
    }

    /** Dismiss from the detail sheet + close it. */
    fun dismissSelected() {
        val id = selectedId.value ?: return
        viewModelScope.launch {
            repo.markDismissed(id)
            selectedId.value = null
        }
    }

    fun markRead(recipientId: String) {
        viewModelScope.launch { repo.markRead(recipientId) }
    }

    fun markDismissed(recipientId: String) {
        viewModelScope.launch { repo.markDismissed(recipientId) }
    }

    fun markAllRead() {
        viewModelScope.launch { repo.markAllRead() }
    }

    companion object {
        fun factory(repo: NotificationRepository) =
            viewModelFactory {
                initializer { InboxViewModel(repo) }
            }
    }
}

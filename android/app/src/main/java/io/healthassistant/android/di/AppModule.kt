package io.healthassistant.android.di

import androidx.room.Room
import io.healthassistant.android.alerts.AlertEngine
import io.healthassistant.android.alerts.AlertEngineHost
import io.healthassistant.android.alerts.AlertRulesRepository
import io.healthassistant.android.alerts.AndroidAlertNotifier
import io.healthassistant.android.data.AppLockManager
import io.healthassistant.android.data.ConnectionRepository
import io.healthassistant.android.data.ConnectivityRepository
import io.healthassistant.android.data.CredentialStore
import io.healthassistant.android.data.DatabaseKeyProvider
import io.healthassistant.android.data.MedicationReminderRepository
import io.healthassistant.android.data.PullSyncRepository
import io.healthassistant.android.data.RoomOutboxStore
import io.healthassistant.android.data.ServerReachabilityMonitor
import io.healthassistant.android.data.cache.CacheMigrations
import io.healthassistant.android.data.cache.FileDocumentByteStore
import io.healthassistant.android.data.cache.ObservationDatabase
import io.healthassistant.android.monitoring.SourceDescriptor
import io.healthassistant.android.monitoring.SyncMonitorRepository
import io.healthassistant.android.settings.DashboardPrefsRepository
import io.healthassistant.android.settings.OnboardingPrefsRepository
import io.healthassistant.android.settings.SyncSettingsRepository
import io.healthassistant.android.settings.UiPreferencesRepository
import io.healthassistant.android.source.HealthConnectSource
import io.healthassistant.android.source.SourceRegistry
import io.healthassistant.shared.data.cache.DocumentByteStore
import io.healthassistant.shared.data.repository.ConnectivityProvider
import io.healthassistant.shared.source.ManualEntrySource
import io.healthassistant.shared.sync.OutboxStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.dsl.module

val appModule: Module =
    module {
        single { CredentialStore(get()) }
        single { ConnectionRepository() }
        // Offline-first M0 — the single process-wide connectivity source.
        // Bound both as the concrete type (the Compose `rememberConnectivity`
        // reader) and as the pure-Kotlin [ConnectivityProvider] interface that
        // repositories inject to short-circuit network refreshes when offline.
        single { ConnectivityRepository(get()) }
        single<ConnectivityProvider> { get<ConnectivityRepository>() }
        // Server-reachability monitor — the honest "offline" signal for a
        // self-hosted app: probes GET /status of the active connection so the
        // banner distinguishes "no internet" from "server unreachable".
        single {
            ServerReachabilityMonitor(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                osOnline = get<ConnectivityRepository>().online,
                probeStatus = ServerReachabilityMonitor::defaultProbe,
            )
        }
        // Phase B.4 app-lock — singleton so the in-memory isLocked StateFlow
        // is shared between MainActivity (the gate) + the lock screen + the
        // Profile Security toggle.
        single { AppLockManager(get()) }
        single { SyncSettingsRepository(get()) }
        single { UiPreferencesRepository(get()) }
        single { OnboardingPrefsRepository(get()) }
        single { DashboardPrefsRepository(get()) }
        single<OutboxStore> { RoomOutboxStore(get<ObservationDatabase>().outboxDao()) }

        // Phase G — two-way incremental sync cursor store + the observable
        // "last pull" signal that Home/Today/Inbox collect to re-fetch on
        // PWA-side edits. Backed by DataStore Preferences.
        single { PullSyncRepository(get()) }

        // Phase I (medication reminders) — DataStore-backed reminder prefs +
        // the one-fire-per-day guard + the offline fallback med-name cache.
        single { MedicationReminderRepository(get()) }

        // M5 (local alerts) — DataStore-backed rules + the per-connection
        // engine host (foreground cache subscription; workers get one-shot
        // engines). The notifier posts on the ha_alert channel.
        single { AlertRulesRepository(get()) }
        single<AlertEngine.AlertNotifier> { AndroidAlertNotifier(get()) }
        single { AlertEngineHost(get(), get(), get()) }

        // M8 + Phase C: on-device observation cache. Room DB + DAOs + the
        // per-connection cache impls. Singleton so the same SQLite handle backs
        // every reactive read; fed by successful bridge reads (Home/Insights) +
        // local HC reads. Phase C wraps the connection in SQLCipher via
        // SupportFactory (key from DatabaseKeyProvider → Keystore-backed
        // EncryptedSharedPreferences). Offline-first M9: the v8→v9 migration
        // backfills `connection_id` from the ACTIVE connection (a pre-M9
        // install only ever cached the active one) — resolved once, here, at
        // DB construction.
        single {
            val ctx: android.content.Context = get()
            val activeConnectionId = get<CredentialStore>().load()?.integrationId.orEmpty()
            Room
                .databaseBuilder(ctx, ObservationDatabase::class.java, "ha_observations.db")
                .openHelperFactory(net.sqlcipher.database.SupportFactory(DatabaseKeyProvider.passphrase(ctx)))
                .addMigrations(*CacheMigrations.all(activeConnectionId))
                .build()
        }
        single { get<ObservationDatabase>().cacheDao() }
        single { get<ObservationDatabase>().outboxDao() }
        single { get<ObservationDatabase>().biomarkerDao() }
        single { get<ObservationDatabase>().examinationDao() }
        single { get<ObservationDatabase>().documentDao() }
        single { get<ObservationDatabase>().clinicalRecordDao() }
        single { get<ObservationDatabase>().notificationDao() }
        single { get<ObservationDatabase>().cacheMetaDao() }

        // Offline-first M4 — the document byte store (files under
        // filesDir/ha_docs, LRU-evicted by DocumentCacheEvictor). The byte
        // repository is built per active connection in the Routes; the evictor
        // + Settings consume this singleton directly.
        single<DocumentByteStore> { FileDocumentByteStore(get()) }

        // Offline-first M2–M9 — the per-domain caches are CONNECTION-SCOPED:
        // each Route/worker builds a `RoomCaches(db, connectionId)` bundle next
        // to its per-connection gateways (the M2 pattern), so a connection
        // switch swaps the whole cache scope and no query can cross the
        // patient boundary. The DAOs above remain singletons for direct
        // bookkeeping consumers (the byte-store evictor, Settings counts).

        // Phase E: pluggable source registry. Health Connect (Android adapter) +
        // Manual Entry (shared stub) register identically; the monitoring
        // dashboard + the SyncWorker discover them via the registry.
        single { HealthConnectSource(get()) }
        single { ManualEntrySource() }
        single { SourceRegistry(listOf(get<HealthConnectSource>(), get<ManualEntrySource>())) }
        single {
            SyncMonitorRepository(
                get(),
                get(),
                get<SourceRegistry>().all().map { SourceDescriptor(it.id, it.displayName) },
            )
        }

        // M4 (widgets) — the Glance widgets' cache-only read path (resolves the
        // active connection per call, exactly like the Routes/workers do).
        single {
            io.healthassistant.android.widget
                .WidgetDataRepository(get())
        }
    }

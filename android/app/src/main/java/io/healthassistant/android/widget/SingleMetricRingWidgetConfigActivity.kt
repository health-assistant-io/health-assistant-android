package io.healthassistant.android.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.lifecycle.lifecycleScope
import io.healthassistant.android.R
import io.healthassistant.android.ui.theme.HATheme
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * M4 — the Single metric widget's configuration activity (launched by the
 * launcher when the widget is placed): choose the target biomarker from the
 * cached catalog (data-first), persisted per widget instance in the Glance
 * Preferences state. Returns the appWidgetId result per the appwidget
 * contract — CANCELED when the user backs out (the widget is not added).
 */
class SingleMetricRingWidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId =
            intent
                ?.extras
                ?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        setResult(RESULT_CANCELED, resultIntent(appWidgetId))
        lifecycleScope.launch {
            val options = GlobalContext.get().get<WidgetDataRepository>().ringOptions()
            setContent {
                HATheme {
                    PickerScreen(
                        options = options,
                        onBack = { finish() },
                        onPick = { code -> pick(appWidgetId, code) },
                    )
                }
            }
        }
    }

    private fun pick(
        appWidgetId: Int,
        code: String,
    ) {
        lifecycleScope.launch {
            val context = applicationContext
            val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId)
            updateAppWidgetState(context, glanceId) { prefs -> prefs[SingleMetricRingWidget.CODE_KEY] = code }
            SingleMetricRingWidget().update(context, glanceId)
            setResult(RESULT_OK, resultIntent(appWidgetId))
            finish()
        }
    }

    private fun resultIntent(appWidgetId: Int): Intent = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerScreen(
    options: List<WidgetRingOption>,
    onBack: () -> Unit,
    onPick: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.widget_ring_pick_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            },
        )
        if (options.isEmpty()) {
            Text(
                stringResource(R.string.widget_ring_no_biomarkers),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(options, key = { it.code }) { option ->
                    Card(
                        shape = MaterialTheme.shapes.large,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .clickable { onPick(option.code) },
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(16.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(option.name, style = MaterialTheme.typography.bodyLarge)
                                if (option.valueText != null) {
                                    Text(
                                        "${option.valueText}${option.unit?.let { " $it" } ?: ""}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                }
            }
        }
    }
}

package com.ping.app.rollcall

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ping.app.R
import com.ping.app.utils.RequiredPermissions

/** Attendance by proximity: one phone takes the roll, every student's phone answers it. */
@Composable
fun RollCallScreen(onBack: () -> Unit, viewModel: RollCallViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val view = LocalView.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    LaunchedEffect(Unit) {
        val missing = RequiredPermissions.missing(context)
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }
    // Scanning and advertising both stall once the screen sleeps.
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            viewModel.stop()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { viewModel.stop(); tab = 0 }, text = { Text(stringResource(R.string.roll_tab_take)) })
            Tab(selected = tab == 1, onClick = { viewModel.stop(); tab = 1 }, text = { Text(stringResource(R.string.roll_tab_check_in)) })
        }
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (tab == 0) AdminPane(viewModel) else StudentPane(viewModel)
            if (tab == 0 && viewModel.openSession.collectAsStateWithLifecycle().value == null) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.action_close)) }
            }
        }
    }
}

@Composable
private fun ColumnScope.AdminPane(viewModel: RollCallViewModel) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val open by viewModel.openSession.collectAsStateWithLifecycle()
    val students by viewModel.students.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val attendance by viewModel.attendance.collectAsStateWithLifecycle()
    var label by rememberSaveable { mutableStateOf("") }

    if (open == null) {
        Text(stringResource(R.string.roll_admin_intro), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = label,
            onValueChange = { label = it.take(RollCallProtocol.MAX_NAME) },
            label = { Text(stringResource(R.string.roll_class_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { viewModel.startTaking(label) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.roll_start))
        }
        if (sessions.isNotEmpty()) Text(stringResource(R.string.roll_history), style = MaterialTheme.typography.titleSmall)
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(sessions, key = { it.id }) { s ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { viewModel.openSession.value = s.id }.padding(vertical = 10.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(s.label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            DateUtils.getRelativeTimeSpanString(s.startedAt).toString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(stringResource(R.string.roll_present_count, s.present))
                }
            }
        }
        return
    }

    val live = (state as? RollCallState.Taking)?.sessionId == open
    val title = (state as? RollCallState.Taking)?.label ?: sessions.firstOrNull { it.id == open }?.label.orEmpty()
    val marks = attendance.associateBy { it.roll }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.roll_present_of, marks.size, maxOf(students.size, marks.size)),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (live) CircularProgressIndicator()
    }
    if (live) Text(stringResource(R.string.roll_live_hint), style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (live) {
            Button(onClick = viewModel::stop) { Text(stringResource(R.string.roll_stop)) }
        } else {
            OutlinedButton(onClick = { viewModel.openSession.value = null }) { Text(stringResource(R.string.action_done)) }
        }
        OutlinedButton(onClick = { viewModel.exportCsv(context, title) }) { Text(stringResource(R.string.roll_export)) }
        if (!live) TextButton(onClick = viewModel::deleteOpenSession) { Text(stringResource(R.string.action_delete)) }
    }
    // Absent students first, so the ones still missing are on top while the roll fills in.
    val ordered = students.sortedWith(compareBy<Student> { it.roll in marks }.thenBy { it.roll })
    LazyColumn(modifier = Modifier.weight(1f)) {
        items(ordered, key = { it.roll }) { st ->
            val mark = marks[st.roll]
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Checkbox(checked = mark != null, onCheckedChange = { viewModel.setPresent(st.roll, it) })
                Column(modifier = Modifier.weight(1f)) {
                    Text("${st.roll} · ${st.name}", style = MaterialTheme.typography.bodyLarge)
                    if (!mark?.flag.isNullOrBlank()) {
                        Text(
                            mark!!.flag,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (mark.flag == Attendance.FLAG_OTHER_PHONE) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StudentPane(viewModel: RollCallViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var roll by remember { mutableStateOf(viewModel.roll) }
    var name by remember { mutableStateOf(viewModel.name) }
    var lastJoined by rememberSaveable { mutableStateOf<String?>(null) }
    val checking = state as? RollCallState.CheckingIn
    LaunchedEffect(checking?.joined) { checking?.joined?.let { lastJoined = it.label } }

    Text(stringResource(R.string.roll_student_intro), style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(
        value = roll,
        onValueChange = { roll = it.take(RollCallProtocol.MAX_ROLL); viewModel.roll = roll },
        label = { Text(stringResource(R.string.roll_number)) },
        singleLine = true,
        enabled = checking?.joined == null,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = name,
        onValueChange = { name = it.take(RollCallProtocol.MAX_NAME); viewModel.name = name },
        label = { Text(stringResource(R.string.contact_label_name)) },
        singleLine = true,
        enabled = checking?.joined == null,
        modifier = Modifier.fillMaxWidth(),
    )
    val ready = RollCallProtocol.cleanRoll(roll).isNotEmpty()
    when {
        checking?.joined != null -> {
            Text(
                stringResource(R.string.roll_checked_in, checking.joined.label),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
            Text(stringResource(R.string.roll_stay_hint), style = MaterialTheme.typography.bodySmall)
        }
        checking != null -> {
            if (checking.found.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.roll_searching))
                }
            }
            checking.found.forEach { ad ->
                Button(onClick = { viewModel.join(ad) }, enabled = ready, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.roll_join, ad.label))
                }
            }
        }
        else -> {
            lastJoined?.let { Text(stringResource(R.string.roll_last_done, it), style = MaterialTheme.typography.bodyMedium) }
            Button(onClick = viewModel::startCheckingIn, enabled = ready, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.roll_find))
            }
        }
    }
}

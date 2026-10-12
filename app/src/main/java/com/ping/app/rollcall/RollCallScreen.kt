package com.ping.app.rollcall

import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ping.app.R
import com.ping.app.ui.ConnectionState
import com.ping.app.ui.ConnectionStatusIndicator
import com.ping.app.ui.components.Avatar
import com.ping.app.ui.components.PingCard
import com.ping.app.ui.theme.BrandBrush
import com.ping.app.ui.theme.ErrorRed
import com.ping.app.ui.theme.PingCyan
import com.ping.app.ui.theme.SuccessGreen
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
    // Scanning and advertising both stall once the screen sleeps. The radio itself stops in
    // the ViewModel, so rotating the phone does not end a roll call.
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { viewModel.stop(); tab = 0 }, text = { Text(stringResource(R.string.roll_tab_take)) })
            Tab(selected = tab == 1, onClick = { viewModel.stop(); tab = 1 }, text = { Text(stringResource(R.string.roll_tab_check_in)) })
        }
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (tab == 0) AdminPane(viewModel, onBack) else StudentPane(viewModel)
        }
    }
}

private enum class Filter { ALL, MISSING, PRESENT, FLAGGED }

@Composable
private fun ColumnScope.AdminPane(viewModel: RollCallViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val open by viewModel.openSession.collectAsStateWithLifecycle()
    val students by viewModel.students.collectAsStateWithLifecycle()
    val rates by viewModel.rates.collectAsStateWithLifecycle()
    val sessionCount by viewModel.sessionCount.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Student?>(null) }

    LaunchedEffect(Unit) {
        viewModel.imported.collect { n ->
            val text = if (n < 0) context.getString(R.string.roll_import_failed)
            else context.resources.getQuantityString(R.plurals.roll_imported, n, n)
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }

    editing?.let { st ->
        EditStudentDialog(
            student = st,
            present = rates[st.roll] ?: 0,
            sessions = sessionCount,
            onSave = { viewModel.renameStudent(st.roll, it); editing = null },
            onRemove = { viewModel.removeStudent(st.roll); editing = null },
            onDismiss = { editing = null },
        )
    }

    if (open == null) SetupPane(viewModel, students, rates, sessionCount, onEdit = { editing = it }, onBack = onBack)
    else SessionPane(viewModel, open!!, students, rates, sessionCount, onEdit = { editing = it })
}

@Composable
private fun ColumnScope.SetupPane(
    viewModel: RollCallViewModel,
    students: List<Student>,
    rates: Map<String, Int>,
    sessionCount: Int,
    onEdit: (Student) -> Unit,
    onBack: () -> Unit,
) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    var label by rememberSaveable { mutableStateOf("") }
    var showRoster by rememberSaveable { mutableStateOf(false) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importRoster)
    }

    // Start card: the class name and one big button.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(BrandBrush)
            .padding(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.roll_admin_title), style = MaterialTheme.typography.titleLarge, color = Color.White)
            Text(stringResource(R.string.roll_admin_intro), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f))
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
        }
    }

    // Class list: import, count, and an optional editable list.
    PingCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.roll_class_list), style = MaterialTheme.typography.titleMedium)
                Text(
                    pluralStringResource(R.plurals.roll_students, students.size, students.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { importer.launch(arrayOf("text/*", "application/csv", "text/csv", "text/comma-separated-values")) }) {
                Text(stringResource(R.string.roll_import), color = PingCyan)
            }
            if (students.isNotEmpty()) {
                TextButton(onClick = { showRoster = !showRoster }) {
                    Text(stringResource(if (showRoster) R.string.roll_hide else R.string.roll_manage), color = PingCyan)
                }
            }
        }
        Text(stringResource(R.string.roll_import_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showRoster) {
            items(students, key = { "s-" + it.roll }) { st ->
                StudentRow(st, rates[st.roll] ?: 0, sessionCount, mark = null, onClick = { onEdit(st) }, onToggle = null)
            }
        } else {
            if (sessions.isNotEmpty()) {
                item { Text(stringResource(R.string.roll_history), style = MaterialTheme.typography.titleSmall) }
            }
            items(sessions, key = { it.id }) { s ->
                val total = maxOf(students.size, s.present, 1)
                PingCard(modifier = Modifier.clickable { viewModel.openSession.value = s.id }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(s.label, style = MaterialTheme.typography.titleMedium)
                            Text(
                                DateUtils.getRelativeTimeSpanString(s.startedAt).toString(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            stringResource(R.string.roll_fraction, s.present, total),
                            style = MaterialTheme.typography.titleMedium,
                            color = PingCyan,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { s.present.toFloat() / total },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                        color = rateColor(s.present.toFloat() / total),
                    )
                }
            }
            item { TextButton(onClick = onBack) { Text(stringResource(R.string.action_close)) } }
        }
    }
}

@Composable
private fun ColumnScope.SessionPane(
    viewModel: RollCallViewModel,
    sessionId: String,
    students: List<Student>,
    rates: Map<String, Int>,
    sessionCount: Int,
    onEdit: (Student) -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val attendance by viewModel.attendance.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var grid by rememberSaveable { mutableStateOf(true) }
    var query by rememberSaveable { mutableStateOf("") }

    val live = (state as? RollCallState.Taking)?.sessionId == sessionId
    val title = (state as? RollCallState.Taking)?.label ?: sessions.firstOrNull { it.id == sessionId }?.label.orEmpty()
    val marks = attendance.associateBy { it.roll }
    val present = students.count { it.roll in marks }
    val flagged = attendance.count { it.flag == Attendance.FLAG_OTHER_PHONE }
    val total = students.size

    // Header: progress ring, title, live dot and the three counts.
    PingCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(present, total, modifier = Modifier.size(104.dp))
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (live) LiveBadge()
                Text(
                    stringResource(R.string.roll_counts, present, total - present, flagged),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (live) {
                Button(onClick = viewModel::stop) { Text(stringResource(R.string.roll_stop)) }
            } else {
                Button(onClick = { viewModel.openSession.value = null }) { Text(stringResource(R.string.action_done)) }
            }
            OutlinedButton(onClick = { viewModel.exportCsv(context, title) }) { Text(stringResource(R.string.roll_export)) }
            if (!live) {
                TextButton(onClick = viewModel::deleteOpenSession) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Filter.values().forEach { f ->
            FilterChip(
                selected = filter == f,
                onClick = { filter = f },
                label = {
                    Text(
                        stringResource(
                            when (f) {
                                Filter.ALL -> R.string.roll_filter_all
                                Filter.MISSING -> R.string.roll_filter_missing
                                Filter.PRESENT -> R.string.roll_filter_present
                                Filter.FLAGGED -> R.string.roll_filter_flagged
                            },
                        ),
                    )
                },
            )
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { grid = !grid }) {
            Text(stringResource(if (grid) R.string.roll_view_list else R.string.roll_view_grid), color = PingCyan)
        }
    }

    if (!grid) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            placeholder = { Text(stringResource(R.string.roll_search)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    // Missing students first while the roll fills in, so the gaps stay on top.
    val shown = students
        .filter { st ->
            val m = marks[st.roll]
            when (filter) {
                Filter.ALL -> true
                Filter.MISSING -> m == null
                Filter.PRESENT -> m != null
                Filter.FLAGGED -> m?.flag == Attendance.FLAG_OTHER_PHONE
            }
        }
        .filter { query.isBlank() || grid || it.roll.contains(query, true) || it.name.contains(query, true) }
        .sortedWith(compareBy<Student> { live && it.roll in marks }.thenBy { it.roll })

    if (students.isEmpty()) {
        Text(
            stringResource(R.string.roll_empty_roster),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(24.dp),
        )
    } else if (grid) {
        Text(stringResource(R.string.roll_grid_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(64.dp),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(shown, key = { it.roll }) { st ->
                val m = marks[st.roll]
                SeatTile(
                    student = st,
                    mark = m,
                    onToggle = { viewModel.setPresent(st.roll, m == null) },
                    onLongPress = { onEdit(st) },
                )
            }
        }
    } else {
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shown, key = { it.roll }) { st ->
                val m = marks[st.roll]
                StudentRow(
                    st, rates[st.roll] ?: 0, sessionCount, mark = m,
                    onClick = { onEdit(st) },
                    onToggle = { viewModel.setPresent(st.roll, it) },
                )
            }
        }
    }
}

/** One seat in the class grid; it lights up the moment that student is marked. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SeatTile(student: Student, mark: Attendance?, onToggle: () -> Unit, onLongPress: () -> Unit) {
    val flagged = mark?.flag == Attendance.FLAG_OTHER_PHONE
    val fill by animateColorAsState(
        when {
            mark == null -> MaterialTheme.colorScheme.surfaceVariant
            flagged -> ErrorRed.copy(alpha = 0.35f)
            else -> SuccessGreen.copy(alpha = 0.85f)
        },
        animationSpec = tween(450),
        label = "seat",
    )
    val description = stringResource(
        if (mark != null) R.string.roll_seat_present else R.string.roll_seat_missing,
        student.name, student.roll,
    )
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .alpha(if (mark != null) 1f else 0.8f)
            .clip(MaterialTheme.shapes.medium)
            .background(fill)
            .border(
                BorderStroke(if (flagged) 2.dp else 1.dp, if (flagged) ErrorRed else MaterialTheme.colorScheme.outlineVariant),
                MaterialTheme.shapes.medium,
            )
            .combinedClickable(onClick = onToggle, onLongClick = onLongPress)
            .semantics { contentDescription = description }
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = student.roll.takeLast(4),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = if (mark != null && !flagged) Color.Black else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                text = student.name.substringBefore(' '),
                style = MaterialTheme.typography.labelSmall,
                color = if (mark != null && !flagged) Color.Black.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StudentRow(
    student: Student,
    present: Int,
    sessions: Int,
    mark: Attendance?,
    onClick: () -> Unit,
    onToggle: ((Boolean) -> Unit)?,
) {
    val noPhone = stringResource(R.string.roll_no_phone)
    PingCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(name = student.name, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(student.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    buildString {
                        append(student.roll)
                        if (sessions > 0) append(" · ").append(present * 100 / sessions).append('%')
                        if (student.device.isEmpty()) append(" · ").append(noPhone)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!mark?.flag.isNullOrBlank()) {
                    Text(
                        mark!!.flag,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (mark.flag == Attendance.FLAG_OTHER_PHONE) ErrorRed else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (onToggle != null) Checkbox(checked = mark != null, onCheckedChange = onToggle)
        }
    }
}

/** Big ring with "present / total" in the middle; the arc grows as students arrive. */
@Composable
private fun ProgressRing(present: Int, total: Int, modifier: Modifier = Modifier) {
    val fraction = if (total == 0) 0f else present.toFloat() / total
    val sweep by animateFloatAsState(fraction, animationSpec = tween(600), label = "ring")
    val track = MaterialTheme.colorScheme.outlineVariant
    val arc = rateColor(fraction)
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 12.dp.toPx()
            val inset = stroke / 2
            val box = Size(size.width - stroke, size.height - stroke)
            drawArc(track, -90f, 360f, false, Offset(inset, inset), box, style = Stroke(stroke))
            drawArc(arc, -90f, 360f * sweep, false, Offset(inset, inset), box, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$present", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold))
            Text("of $total", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LiveBadge() {
    val pulse by rememberInfiniteTransition(label = "live").animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "pulse",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).alpha(pulse).clip(CircleShape).background(ErrorRed))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.roll_live), style = MaterialTheme.typography.labelMedium, color = ErrorRed)
    }
}

/** Red under half, amber under three quarters, green above. */
private fun rateColor(fraction: Float): Color = when {
    fraction < 0.5f -> ErrorRed
    fraction < 0.75f -> Color(0xFFFBBF24)
    else -> SuccessGreen
}

@Composable
private fun EditStudentDialog(
    student: Student,
    present: Int,
    sessions: Int,
    onSave: (String) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(student.name) }
    var confirmRemove by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(student.roll) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(RollCallProtocol.MAX_NAME) },
                    label = { Text(stringResource(R.string.contact_label_name)) },
                    singleLine = true,
                )
                if (sessions > 0) {
                    Text(stringResource(R.string.roll_rate, present, sessions, present * 100 / sessions))
                    LinearProgressIndicator(
                        progress = { present.toFloat() / sessions },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                        color = rateColor(present.toFloat() / sessions),
                    )
                }
                TextButton(onClick = { if (confirmRemove) onRemove() else confirmRemove = true }) {
                    Text(
                        stringResource(if (confirmRemove) R.string.roll_remove_confirm else R.string.roll_remove),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun StudentPane(viewModel: RollCallViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var roll by remember { mutableStateOf(viewModel.roll) }
    var name by remember { mutableStateOf(viewModel.name) }
    var lastJoined by rememberSaveable { mutableStateOf<String?>(null) }
    val checking = state as? RollCallState.CheckingIn
    LaunchedEffect(checking?.joined) { checking?.joined?.let { lastJoined = it.label } }

    val indicator = when {
        checking?.confirmed == true -> ConnectionState.PAIRED
        checking?.joined != null -> ConnectionState.CONNECTING
        checking != null -> ConnectionState.SCANNING
        else -> ConnectionState.IDLE
    }
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        ConnectionStatusIndicator(state = indicator, size = 96)
    }

    if (checking?.confirmed == true) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Check, contentDescription = null, tint = SuccessGreen)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.roll_confirmed, checking.joined?.label.orEmpty()),
                style = MaterialTheme.typography.titleLarge,
                color = SuccessGreen,
            )
        }
        Button(onClick = viewModel::stop, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_done)) }
        return
    }

    PingCard {
        Text(stringResource(R.string.roll_student_intro), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
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
    }

    val ready = RollCallProtocol.cleanRoll(roll).isNotEmpty()
    when {
        checking?.joined != null -> {
            Text(
                stringResource(R.string.roll_checked_in, checking.joined.label),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.roll_stay_hint), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        checking != null -> {
            if (checking.found.isEmpty()) {
                Text(stringResource(R.string.roll_searching), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            checking.found.forEach { ad ->
                Button(onClick = { viewModel.join(ad) }, enabled = ready, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.roll_join, ad.label))
                }
            }
        }
        else -> {
            lastJoined?.let {
                Text(stringResource(R.string.roll_last_done, it), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            }
            Button(onClick = viewModel::startCheckingIn, enabled = ready, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(stringResource(R.string.roll_find))
            }
        }
    }
}

package com.mason.mercedesfaultlibrary

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.mason.mercedesfaultlibrary.data.*
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { App() } }
    }
}

private fun newPhoto(context: Context): Pair<File, Uri> {
    val folder = File(context.filesDir, "fault_photos").apply { mkdirs() }
    val file = File(folder, "fault_${System.currentTimeMillis()}.jpg")
    return file to FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

private fun faultCodes(text: String): List<String> =
    Regex("\\b(?:P|B|C|U)[0-9A-F]{4,6}\\b", RegexOption.IGNORE_CASE)
        .findAll(text.uppercase())
        .map { it.value }
        .distinct()
        .toList()

private fun codesIn(value: String): Set<String> = faultCodes(value).toSet()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App() {
    val context = LocalContext.current
    val dao = remember { FaultDatabase.get(context).faultDao() }
    val records by dao.observeAll().collectAsState(initial = emptyList())
    var editorInitial by remember { mutableStateOf<FaultRecord?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var pendingPhoto by remember { mutableStateOf<File?>(null) }
    var scanning by remember { mutableStateOf(false) }

    if (showEditor) {
        Editor(editorInitial, records) {
            editorInitial = null
            showEditor = false
        }
        return
    }

    fun openScannedPhoto(file: File) {
        scanning = true
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            .process(InputImage.fromFilePath(context, Uri.fromFile(file)))
            .addOnSuccessListener { result ->
                val detected = faultCodes(result.text)
                editorInitial = FaultRecord(
                    faultCodes = detected.joinToString(", "),
                    photoPaths = file.absolutePath
                )
                showEditor = true
                scanning = false
                if (detected.isEmpty()) {
                    Toast.makeText(context, "No fault code detected. You can enter it manually.", Toast.LENGTH_LONG).show()
                }
            }
            .addOnFailureListener {
                editorInitial = FaultRecord(photoPaths = file.absolutePath)
                showEditor = true
                scanning = false
                Toast.makeText(context, "The photo was saved, but the code could not be read.", Toast.LENGTH_LONG).show()
            }
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        pendingPhoto?.let { file ->
            if (ok) openScannedPhoto(file) else file.delete()
        }
        pendingPhoto = null
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val (file, uri) = newPhoto(context)
            pendingPhoto = file
            camera.launch(uri)
        }
    }
    fun scanFault() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            val (file, uri) = newPhoto(context)
            pendingPhoto = file
            camera.launch(uri)
        } else {
            permission.launch(Manifest.permission.CAMERA)
        }
    }

    var query by remember { mutableStateOf("") }
    val shown = records.filter { record ->
        query.isBlank() || listOf(
            record.registration, record.vin, record.model, record.engine,
            record.faultCodes, record.symptoms, record.tests, record.cause, record.repair
        ).any { it.contains(query, true) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Mercedes Fault Library") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { editorInitial = null; showEditor = true }) {
                Icon(Icons.Default.Add, "New repair")
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp)) {
            Button(
                onClick = ::scanFault,
                enabled = !scanning,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Icon(Icons.Default.CameraAlt, null)
                Text(if (scanning) "  Reading fault code…" else "  Scan fault code")
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                query,
                { query = it.uppercase() },
                label = { Text("Search code, vehicle, engine or cause") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            if (shown.isEmpty()) {
                Text(if (query.isBlank()) "No repairs recorded yet." else "No matching repairs.")
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(shown, key = { it.id }) { record ->
                        RepairCard(record) { editorInitial = record; showEditor = true }
                    }
                }
            }
        }
    }
}

@Composable
private fun RepairCard(record: FaultRecord, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(record.faultCodes.ifBlank { "No fault code" }, style = MaterialTheme.typography.titleMedium)
            Text(listOf(record.registration, record.model, record.engine).filter { it.isNotBlank() }.joinToString(" • "))
            Text(DateFormat.getDateInstance().format(Date(record.createdAt)))
            if (record.cause.isNotBlank()) Text("Cause: ${record.cause}")
            if (record.confirmed) Text("✓ Repair confirmed", color = MaterialTheme.colorScheme.primary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Editor(initial: FaultRecord?, allRecords: List<FaultRecord>, onBack: () -> Unit) {
    val context = LocalContext.current
    val dao = remember { FaultDatabase.get(context).faultDao() }
    val scope = rememberCoroutineScope()
    var record by remember(initial) { mutableStateOf(initial ?: FaultRecord()) }
    var pendingPhoto by remember { mutableStateOf<File?>(null) }
    val currentCodes = codesIn(record.faultCodes)
    val previousRepairs = allRecords.filter { previous ->
        previous.id != record.id && currentCodes.isNotEmpty() && codesIn(previous.faultCodes).any { it in currentCodes }
    }

    fun scan(file: File) {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            .process(InputImage.fromFilePath(context, Uri.fromFile(file)))
            .addOnSuccessListener { text ->
                val detected = faultCodes(text.text)
                if (detected.isNotEmpty()) {
                    record = record.copy(
                        faultCodes = (record.faultCodes.split(',', '\n') + detected)
                            .map { it.trim().uppercase() }
                            .filter { it.isNotBlank() }
                            .distinct()
                            .joinToString(", ")
                    )
                }
            }
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        pendingPhoto?.let { file ->
            if (ok) {
                record = record.copy(
                    photoPaths = (record.photoPaths.split('|').filter { it.isNotBlank() } + file.absolutePath)
                        .distinct()
                        .joinToString("|")
                )
                scan(file)
            } else file.delete()
        }
        pendingPhoto = null
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val (file, uri) = newPhoto(context)
            pendingPhoto = file
            camera.launch(uri)
        }
    }
    fun takePhoto() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            val (file, uri) = newPhoto(context)
            pendingPhoto = file
            camera.launch(uri)
        } else permission.launch(Manifest.permission.CAMERA)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (record.id == 0L) "New repair" else "Repair record") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { Field("Registration", record.registration) { record = record.copy(registration = it.uppercase()) } }
            item { Field("VIN", record.vin) { record = record.copy(vin = it.uppercase()) } }
            item { Field("Model", record.model) { record = record.copy(model = it) } }
            item { Field("Engine", record.engine) { record = record.copy(engine = it.uppercase()) } }
            item { Field("Mileage", record.mileage) { record = record.copy(mileage = it) } }
            item {
                Button(::takePhoto, Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.CameraAlt, null)
                    Text("  Photograph fault screen")
                }
                Text("${record.photoPaths.split('|').count { it.isNotBlank() }} photo(s) saved")
            }
            item { Field("Fault codes — check detected codes", record.faultCodes, 2) { record = record.copy(faultCodes = it.uppercase()) } }
            if (previousRepairs.isNotEmpty()) {
                item {
                    Text("Previous repairs for this fault", style = MaterialTheme.typography.titleLarge)
                    Text("${previousRepairs.size} previous record(s) found")
                }
                items(previousRepairs, key = { "previous_${it.id}" }) { previous ->
                    PreviousRepairCard(previous)
                }
            }
            item { Field("Symptoms", record.symptoms, 3) { record = record.copy(symptoms = it) } }
            item { Field("Tests and readings", record.tests, 3) { record = record.copy(tests = it) } }
            item { Field("Confirmed cause", record.cause, 3) { record = record.copy(cause = it) } }
            item { Field("Repair carried out / parts fitted", record.repair, 3) { record = record.copy(repair = it) } }
            item {
                Row {
                    Checkbox(record.confirmed, { record = record.copy(confirmed = it) })
                    Text("Repair confirmed", Modifier.padding(top = 12.dp))
                }
            }
            item {
                Button(
                    onClick = {
                        scope.launch {
                            if (record.id == 0L) dao.insert(record) else dao.update(record)
                            onBack()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Save repair record") }
            }
        }
    }
}

@Composable
private fun PreviousRepairCard(record: FaultRecord) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(record.faultCodes, style = MaterialTheme.typography.titleMedium)
            val vehicle = listOf(record.registration, record.model, record.engine).filter { it.isNotBlank() }.joinToString(" • ")
            if (vehicle.isNotBlank()) Text(vehicle)
            if (record.tests.isNotBlank()) Text("Tests carried out: ${record.tests}")
            if (record.cause.isNotBlank()) Text("Cause: ${record.cause}")
            if (record.repair.isNotBlank()) Text("Repair: ${record.repair}")
            if (record.confirmed) Text("✓ Confirmed fix", color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun Field(label: String, value: String, lines: Int = 1, change: (String) -> Unit) =
    OutlinedTextField(
        value,
        change,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        minLines = lines,
        maxLines = if (lines == 1) 1 else 6
    )

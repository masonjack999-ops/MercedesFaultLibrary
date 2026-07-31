package com.mason.mercedesfaultlibrary

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
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
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { MaterialTheme { App() } } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun App() {
    val context = LocalContext.current
    val dao = remember { FaultDatabase.get(context).faultDao() }
    val records by dao.observeAll().collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<FaultRecord?>(null) }
    var newRecord by remember { mutableStateOf(false) }
    if (editing != null || newRecord) {
        Editor(editing) { editing = null; newRecord = false }
        return
    }
    var query by remember { mutableStateOf("") }
    val shown = records.filter { r -> query.isBlank() || listOf(r.registration,r.vin,r.model,r.engine,r.faultCodes,r.symptoms,r.cause,r.repair).any { it.contains(query,true) } }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Mercedes Fault Library") }) },
        floatingActionButton = { FloatingActionButton(onClick = { newRecord = true }) { Icon(Icons.Default.Add,"New repair") } }
    ) { p ->
        Column(Modifier.padding(p).padding(16.dp)) {
            OutlinedTextField(query,{query=it},label={Text("Search code, vehicle, engine or cause")},singleLine=true,modifier=Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            if (shown.isEmpty()) Text(if(query.isBlank()) "No repairs recorded yet." else "No matching repairs.")
            else LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp)) { items(shown,key={it.id}) { r ->
                Card(Modifier.fillMaxWidth().clickable { editing=r }) { Column(Modifier.padding(16.dp)) {
                    Text(r.faultCodes.ifBlank { "No fault code" },style=MaterialTheme.typography.titleMedium)
                    Text(listOf(r.registration,r.model,r.engine).filter{it.isNotBlank()}.joinToString(" • "))
                    Text(DateFormat.getDateInstance().format(Date(r.createdAt)))
                    if(r.cause.isNotBlank()) Text("Cause: ${r.cause}")
                    if(r.confirmed) Text("✓ Repair confirmed",color=MaterialTheme.colorScheme.primary)
                }}
            }}
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun Editor(initial: FaultRecord?, onBack:()->Unit) {
    val context=LocalContext.current; val dao=remember{FaultDatabase.get(context).faultDao()}; val scope=rememberCoroutineScope()
    var r by remember(initial){mutableStateOf(initial?:FaultRecord())}; var pending by remember{mutableStateOf<File?>(null)}
    fun photo():Pair<File,Uri>{ val f=File(context.filesDir,"fault_photos").apply{mkdirs()}; val file=File(f,"fault_${System.currentTimeMillis()}.jpg"); return file to FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",file) }
    fun scan(file:File){
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(InputImage.fromFilePath(context,Uri.fromFile(file))).addOnSuccessListener { text ->
            val codes=Regex("\\b(?:P|B|C|U)[0-9A-F]{4,6}\\b",RegexOption.IGNORE_CASE).findAll(text.text.uppercase()).map{it.value}.distinct().toList()
            if(codes.isNotEmpty()) r=r.copy(faultCodes=(r.faultCodes.split(',', '\n')+codes).map{it.trim().uppercase()}.filter{it.isNotBlank()}.distinct().joinToString(", "))
        }
    }
    val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()){ok->pending?.let{f->if(ok){r=r.copy(photoPaths=(r.photoPaths.split('|').filter{it.isNotBlank()}+f.absolutePath).distinct().joinToString("|"));scan(f)}else f.delete()}}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){ok->if(ok){val(f,u)=photo();pending=f;camera.launch(u)}}
    fun take(){if(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED){val(f,u)=photo();pending=f;camera.launch(u)}else permission.launch(Manifest.permission.CAMERA)}
    Scaffold(topBar={TopAppBar(title={Text(if(initial==null)"New repair" else "Repair record")},navigationIcon={IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,"Back")}})}){p->
        LazyColumn(Modifier.fillMaxSize().padding(p).padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            item{Field("Registration",r.registration){r=r.copy(registration=it.uppercase())}}; item{Field("VIN",r.vin){r=r.copy(vin=it.uppercase())}}
            item{Field("Model",r.model){r=r.copy(model=it)}}; item{Field("Engine",r.engine){r=r.copy(engine=it.uppercase())}}; item{Field("Mileage",r.mileage){r=r.copy(mileage=it)}}
            item{Button(::take,Modifier.fillMaxWidth()){Icon(Icons.Default.CameraAlt,null);Text("  Photograph fault screen")};Text("${r.photoPaths.split('|').count{it.isNotBlank()}} photo(s) saved")}
            item{Field("Fault codes — check detected codes",r.faultCodes,2){r=r.copy(faultCodes=it.uppercase())}}
            item{Field("Symptoms",r.symptoms,3){r=r.copy(symptoms=it)}}; item{Field("Tests and readings",r.tests,3){r=r.copy(tests=it)}}
            item{Field("Confirmed cause",r.cause,3){r=r.copy(cause=it)}}; item{Field("Repair carried out / parts fitted",r.repair,3){r=r.copy(repair=it)}}
            item{Row{Checkbox(r.confirmed,{r=r.copy(confirmed=it)});Text("Repair confirmed",Modifier.padding(top=12.dp))}}
            item{Button({scope.launch{if(r.id==0L)dao.insert(r)else dao.update(r);onBack()}},Modifier.fillMaxWidth()){Text("Save repair record")}}
        }
    }
}

@Composable private fun Field(label:String,value:String,lines:Int=1,change:(String)->Unit)=OutlinedTextField(value,change,label={Text(label)},modifier=Modifier.fillMaxWidth(),minLines=lines,maxLines=if(lines==1)1 else 6)

package com.example.ui.screens

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.LocationService
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image

import com.example.ui.screens.scanner.ScannerScreen
import com.example.data.repository.DeliveryRepository
import com.example.domain.models.DeliveryTask
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class Screen(val route: String) {
    object Login : Screen("login")
    object BaseDashboard : Screen("base_dashboard")
    object Scanner : Screen("scanner")
    object RoutePlan : Screen("route_plan")
    object DeliveryDetail : Screen("delivery_detail")
    object SignatureCapture : Screen("signature_capture")
    object LiveTracking : Screen("live_tracking")
}

@Composable
fun RoutingNavigation() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val deliveryRepo = remember { DeliveryRepository() }
    
    var currentDelivery by remember { mutableStateOf<DeliveryTask?>(null) }
    var routeDeliveries by remember { mutableStateOf<List<DeliveryTask>>(emptyList()) }
    var allUnfilteredDeliveries by remember { mutableStateOf<List<DeliveryTask>>(emptyList()) }
    var currentDriverId by remember { mutableStateOf("") }
    val checkedBoxesMap = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(Unit) {
        deliveryRepo.getAllDeliveries().collect { tasks ->
            allUnfilteredDeliveries = tasks
        }
    }

    NavHost(navController = navController, startDestination = Screen.Login.route) {
        composable(Screen.Login.route) {
            LoginScreen(
                onLoginSuccess = { driverId ->
                    currentDriverId = driverId
                    val intent = Intent(context, LocationService::class.java).apply {
                        putExtra("DRIVER_ID", driverId)
                    }
                    androidx.core.content.ContextCompat.startForegroundService(context, intent)
                    
                    // Fetch assigned deliveries for driver
                    coroutineScope.launch {
                        deliveryRepo.getAssignedDeliveries(if (driverId.isBlank()) "driver_123" else driverId).collect { tasks ->
                            routeDeliveries = tasks
                        }
                    }
                    
                    navController.navigate(Screen.BaseDashboard.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                }
            )
        }
        composable(Screen.BaseDashboard.route) {
            BaseDashboardScreen(
                navController = navController, 
                driverId = currentDriverId, 
                deliveries = routeDeliveries,
                allUnfilteredDeliveries = allUnfilteredDeliveries
            )
        }
        composable(Screen.Scanner.route) {
            ScannerScreen(
                navController = navController,
                onBarcodeScanned = { qr ->
                    coroutineScope.launch {
                        deliveryRepo.logDriverAction(
                            driverId = currentDriverId,
                            action = "QR_SCANNED",
                            details = mapOf("qrCode" to qr)
                        )
                        val task = deliveryRepo.getDeliveryByQrCode(qr)
                        if (task != null) {
                            currentDelivery = task
                            deliveryRepo.logDriverAction(
                                driverId = currentDriverId,
                                action = "QR_MATCHED",
                                taskId = task.id,
                                details = mapOf("qrCode" to qr, "companyName" to task.companyName)
                            )
                            navController.navigate(Screen.DeliveryDetail.route)
                        } else {
                            deliveryRepo.logDriverAction(
                                driverId = currentDriverId,
                                action = "QR_NOT_FOUND",
                                details = mapOf("qrCode" to qr)
                            )
                        }
                    }
                }
            )
        }
        composable(Screen.RoutePlan.route) {
            RoutePlanScreen(
                navController = navController,
                deliveries = routeDeliveries,
                driverId = currentDriverId,
                deliveryRepo = deliveryRepo,
                onDeliveryClick = {
                    currentDelivery = it
                    coroutineScope.launch {
                        deliveryRepo.logDriverAction(
                            driverId = currentDriverId,
                            action = "STOP_OPENED",
                            taskId = it.id,
                            details = mapOf("companyName" to it.companyName, "address" to it.address)
                        )
                    }
                    navController.navigate(Screen.DeliveryDetail.route)
                }
            )
        }
        composable(Screen.DeliveryDetail.route) {
            DeliveryDetailScreen(
                navController = navController,
                delivery = currentDelivery ?: DeliveryTask(),
                allDeliveries = routeDeliveries,
                checkedMap = checkedBoxesMap,
                driverId = currentDriverId,
                deliveryRepo = deliveryRepo,
                onComplete = { skipSignature ->
                    if (skipSignature) {
                        coroutineScope.launch {
                            currentDelivery?.id?.let { id ->
                                deliveryRepo.updateDeliveryStatus(id, "DELIVERED", null, currentDelivery)
                                deliveryRepo.logDriverAction(
                                    driverId = currentDriverId,
                                    action = "DELIVERED",
                                    taskId = id,
                                    details = mapOf(
                                        "companyName" to (currentDelivery?.companyName ?: ""),
                                        "address" to (currentDelivery?.address ?: ""),
                                        "proofType" to "MANUAL_CLOSE"
                                    )
                                )
                            }
                            navController.popBackStack()
                        }
                    } else {
                        navController.navigate(Screen.SignatureCapture.route)
                    }
                }
            )
        }
        composable(Screen.SignatureCapture.route) {
            SignatureCaptureScreen(
                onComplete = { proofType, bitmap ->
                    coroutineScope.launch {
                        currentDelivery?.id?.let { id ->
                            deliveryRepo.updateDeliveryStatus(id, "DELIVERED", proofType, currentDelivery)
                            // Upload proof image if we have one
                            if (bitmap != null) {
                                deliveryRepo.uploadProofImage(id, bitmap, "photo")
                            }
                            deliveryRepo.logDriverAction(
                                driverId = currentDriverId,
                                action = "DELIVERED",
                                taskId = id,
                                details = mapOf(
                                    "companyName" to (currentDelivery?.companyName ?: ""),
                                    "address" to (currentDelivery?.address ?: ""),
                                    "proofType" to (proofType ?: ""),
                                    "hasPhoto" to (bitmap != null)
                                )
                            )
                        }
                        navController.popBackStack(Screen.RoutePlan.route, inclusive = false)
                    }
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }
        composable(Screen.LiveTracking.route) {
            LiveTrackingScreen(
                navController = navController,
                driverId = currentDriverId,
                deliveries = routeDeliveries,
                deliveryRepo = deliveryRepo
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignatureCaptureScreen(
    onComplete: (String, android.graphics.Bitmap?) -> Unit,
    onBack: () -> Unit
) {
    val paths = remember { androidx.compose.runtime.mutableStateListOf<Path>() }
    var currentPath by remember { mutableStateOf<Path?>(null) }
    var updateCount by remember { mutableStateOf(0) }
    var capturedImage by remember { mutableStateOf<Bitmap?>(null) }
    var showSignaturePad by remember { mutableStateOf(false) }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) {
            capturedImage = bitmap
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Proof of Delivery", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            )
        },
        bottomBar = {
            Button(
                onClick = { onComplete(if (capturedImage != null) "IMAGE" else "SIGNATURE", capturedImage) },
                modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp),
                enabled = capturedImage != null || paths.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Submit & Finish", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) 
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (capturedImage != null) {
                Text("Captured Photo:", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                Image(
                    bitmap = capturedImage!!.asImageBitmap(),
                    contentDescription = "Captured proof",
                    modifier = Modifier.weight(1f).fillMaxWidth().border(1.dp, Color.Gray, RoundedCornerShape(8.dp))
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(onClick = { cameraLauncher.launch(null) }) {
                    Text("Retake Photo")
                }
            } else if (!showSignaturePad) {
                Text("A photo is required for proof of delivery.", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 24.dp))
                Button(
                    onClick = { cameraLauncher.launch(null) },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Take Photo", fontSize = 18.sp)
                }
                Spacer(modifier = Modifier.height(24.dp))
                TextButton(onClick = { showSignaturePad = true }) {
                    Text("Emergency: Use Signature Instead", color = MaterialTheme.colorScheme.error)
                }
            } else {
                Text("Please sign below to confirm delivery/pickup:", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 16.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .border(1.dp, Color.Gray, RoundedCornerShape(8.dp))
                        .background(Color.White, RoundedCornerShape(8.dp))
                ) {
                    androidx.compose.foundation.Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        currentPath = Path().apply {
                                            moveTo(offset.x, offset.y)
                                        }
                                        paths.add(currentPath!!)
                                    },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        currentPath?.lineTo(change.position.x, change.position.y)
                                        updateCount++
                                    }
                                )
                            }
                    ) {
                        val trigger = updateCount
                        paths.forEach { path ->
                            drawPath(
                                path = path,
                                color = Color.Black,
                                style = Stroke(
                                    width = 5f,
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round
                                )
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { showSignaturePad = false }) {
                        Text("Back to Photo")
                    }
                    TextButton(onClick = { paths.clear(); currentPath = null }) {
                        Text("Clear Signature", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
@OptIn(com.google.accompanist.permissions.ExperimentalPermissionsApi::class)
@Composable
fun LoginScreen(onLoginSuccess: (String) -> Unit) {
    val context = LocalContext.current
    val sharedPreferences = remember { context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE) }
    
    var email by remember { mutableStateOf(sharedPreferences.getString("saved_email", "") ?: "") }
    var password by remember { mutableStateOf(sharedPreferences.getString("saved_password", "") ?: "") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    val permissionsToRequest = mutableListOf(
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.ACCESS_COARSE_LOCATION,
        android.Manifest.permission.CAMERA
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissionsToRequest.add(android.Manifest.permission.POST_NOTIFICATIONS)
    }
    
    val permissionsState = com.google.accompanist.permissions.rememberMultiplePermissionsState(
        permissions = permissionsToRequest
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .imePadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.LocalShipping,
            contentDescription = "App Logo",
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = "Driver Login",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(32.dp))
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Driver ID or Email") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(errorMessage!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = {
                if (!permissionsState.allPermissionsGranted) {
                    permissionsState.launchMultiplePermissionRequest()
                } else {
                    if (email.isNotBlank()) {
                         sharedPreferences.edit()
                             .putString("saved_email", email)
                             .putString("saved_password", password)
                             .apply()
                         
                         isLoading = true
                         errorMessage = null
                         val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
                         if (auth.currentUser == null) {
                             auth.signInAnonymously().addOnCompleteListener { task ->
                                 if (task.isSuccessful) {
                                     // Validate driver email against Firestore
                                     kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                         val deliveryRepo = com.example.data.repository.DeliveryRepository()
                                         val isValid = deliveryRepo.validateDriver(email.trim())
                                         kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                             isLoading = false
                                             if (isValid) {
                                                 onLoginSuccess(email.trim())
                                             } else {
                                                 errorMessage = "Driver not found. Contact your dispatcher."
                                             }
                                         }
                                     }
                                 } else {
                                     isLoading = false
                                     errorMessage = "Auth failed: ${task.exception?.message}"
                                     onLoginSuccess(email.trim()) // fallback
                                 }
                             }
                         } else {
                             isLoading = false
                             onLoginSuccess(email.trim())
                         }
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(12.dp),
            enabled = !isLoading
        ) {
            if (isLoading) {
                androidx.compose.material3.CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp))
            } else {
                Text(if (!permissionsState.allPermissionsGranted) "Grant Permissions & Login" else "Login to Dashboard", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BaseDashboardScreen(
    navController: NavController, 
    driverId: String = "Tasnim", 
    deliveries: List<DeliveryTask> = emptyList(),
    allUnfilteredDeliveries: List<DeliveryTask> = emptyList()
) {
    var showDiagnostics by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text("Hello", fontWeight = FontWeight.Bold)
                        Text(if (driverId.contains("@")) driverId.substringBefore("@") else driverId, style = MaterialTheme.typography.bodyMedium)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { /* Menu */ }) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu", modifier = Modifier.size(32.dp))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            var berlinTimeStr by remember { mutableStateOf("") }
            var berlinDateStr by remember { mutableStateOf("") }
            
            LaunchedEffect(Unit) {
                val timeSdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).apply {
                    timeZone = java.util.TimeZone.getTimeZone("Europe/Berlin")
                }
                val dateSdf = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault()).apply {
                    timeZone = java.util.TimeZone.getTimeZone("Europe/Berlin")
                }
                while(true) {
                    val now = java.util.Date()
                    berlinTimeStr = timeSdf.format(now)
                    berlinDateStr = dateSdf.format(now)
                    kotlinx.coroutines.delay(1000)
                }
            }
            
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Berlin Local Time", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(berlinTimeStr, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Berlin Date", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(berlinDateStr, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Route Plan", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall)
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Total Deliveries", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${deliveries.size}", fontWeight = FontWeight.Bold)
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Card(modifier = Modifier.weight(1f).height(80.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Timer, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text("Working time", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("2h 23min", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        Card(modifier = Modifier.weight(1f).height(80.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Map, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text("Distance", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("43.35km", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Card(modifier = Modifier.weight(1f).height(80.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text("Stops", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("${deliveries.size}", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        Card(modifier = Modifier.weight(1f).height(80.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.List, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text("Requirements", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                    Text("${deliveries.sumOf { it.numberOfBoxes }}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Button(
                        onClick = { navController.navigate(Screen.RoutePlan.route) },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A73E8))
                    ) {
                        Icon(Icons.Default.ChevronRight, contentDescription = "Go to Route Plan")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🔧 System Diagnostics", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { showDiagnostics = !showDiagnostics }) {
                            Text(if (showDiagnostics) "Hide" else "Show Raw DB (${allUnfilteredDeliveries.size})")
                        }
                    }
                    
                    if (showDiagnostics) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Current Berlin Date: ${
                                java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault()).apply {
                                    timeZone = java.util.TimeZone.getTimeZone("Europe/Berlin")
                                }.format(java.util.Date())
                            }", 
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        if (allUnfilteredDeliveries.isEmpty()) {
                            Text("No tasks found in Firestore database.", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                allUnfilteredDeliveries.forEach { task ->
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(8.dp)) {
                                            Text("ID: ${task.id}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                                            Text("Driver: ${task.driverId}", style = MaterialTheme.typography.bodySmall)
                                            Text("Date: ${task.date} | Status: ${task.status}", style = MaterialTheme.typography.bodySmall)
                                            Text("Company: ${task.companyName}", style = MaterialTheme.typography.bodySmall)
                                            Text("Address: ${task.address}", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutePlanScreen(
    navController: NavController,
    deliveries: List<DeliveryTask>,
    driverId: String = "",
    deliveryRepo: com.example.data.repository.DeliveryRepository? = null,
    onDeliveryClick: (DeliveryTask) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var showDelaySheet by remember { mutableStateOf(false) }
    var activeDelayMins by remember { mutableStateOf(0) }
    var delayConfirmed by remember { mutableStateOf(false) }

    val displayDeliveries = remember(deliveries) {
        deliveries.filter { it.status != "DELIVERED" }.sortedWith { a, b ->
            val aIsBase = a.companyName.contains("Base", ignoreCase = true) || 
                          a.address.contains("Base", ignoreCase = true) || 
                          a.companyName.contains("Pickup", ignoreCase = true) || 
                          a.companyName.contains("Bella Bona", ignoreCase = true) || 
                          a.address.contains("Bella Bona", ignoreCase = true) ||
                          a.companyName.contains("Berlin kitchen", ignoreCase = true) ||
                          a.address.contains("Berlin kitchen", ignoreCase = true)
                          
            val bIsBase = b.companyName.contains("Base", ignoreCase = true) || 
                          b.address.contains("Base", ignoreCase = true) || 
                          b.companyName.contains("Pickup", ignoreCase = true) || 
                          b.companyName.contains("Bella Bona", ignoreCase = true) || 
                          b.address.contains("Bella Bona", ignoreCase = true) ||
                          b.companyName.contains("Berlin kitchen", ignoreCase = true) ||
                          b.address.contains("Berlin kitchen", ignoreCase = true)
                          
            if (aIsBase && !bIsBase) {
                -1
            } else if (!aIsBase && bIsBase) {
                1
            } else {
                // Sort by sequence/order/routeOrder/time if present, otherwise by ID
                val cmpRouteOrder = a.routeOrder.compareTo(b.routeOrder)
                if (cmpRouteOrder != 0) {
                    cmpRouteOrder
                } else {
                    val cmpOrder = a.order.compareTo(b.order)
                    if (cmpOrder != 0) {
                        cmpOrder
                    } else {
                        val cmpSequence = a.sequence.compareTo(b.sequence)
                        if (cmpSequence != 0) {
                            cmpSequence
                        } else {
                            val cmpTime = a.time.compareTo(b.time)
                            if (cmpTime != 0) {
                                cmpTime
                            } else {
                                a.id.compareTo(b.id)
                            }
                        }
                    }
                }
            }
        }
    }

    val workingHours = remember(displayDeliveries) {
        val times = displayDeliveries.map { it.time }.filter { it.isNotEmpty() }
        if (times.size >= 2) {
            "${times.first()} - ${times.last()}"
        } else if (times.size == 1) {
            "${times.first()} - End"
        } else {
            "09:30 - 13:23" // Fallback to original working hours
        }
    }

    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dashboard", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.primary)
                    }
                },
                actions = {
                    // Live tracking map
                    IconButton(onClick = { navController.navigate(Screen.LiveTracking.route) }) {
                        Icon(Icons.Default.Map, contentDescription = "Live Tracking", tint = MaterialTheme.colorScheme.primary)
                    }
                    // Delay reporting — badge shows active delay
                    BadgedBox(badge = {
                        if (activeDelayMins > 0) Badge { Text("${activeDelayMins}m") }
                    }) {
                        IconButton(onClick = { showDelaySheet = true }) {
                            Icon(
                                Icons.Default.AccessTime,
                                contentDescription = "Report Delay",
                                tint = if (activeDelayMins > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = {
                        val firstStop = displayDeliveries.firstOrNull()
                        val addressString = firstStop?.let { "${it.address}, ${it.postalCode} ${it.companyName}" } ?: ""
                        if (addressString.isNotEmpty()) {
                            coroutineScope.launch {
                                deliveryRepo?.logDriverAction(
                                    driverId = driverId,
                                    action = "NAVIGATE_STARTED",
                                    taskId = firstStop?.id ?: "",
                                    details = mapOf("address" to addressString)
                                )
                            }
                            val uri = Uri.parse("geo:0,0?q=${Uri.encode(addressString)}")
                            val intent = Intent(Intent.ACTION_VIEW, uri)
                            context.startActivity(intent)
                        }
                    }, 
                    modifier = Modifier.weight(1f).height(56.dp), 
                    shape = RoundedCornerShape(12.dp), 
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A73E8))
                ) {
                    Text("Navigate")
                }
                OutlinedButton(onClick = { /* Start */ }, modifier = Modifier.weight(1f).height(56.dp), shape = RoundedCornerShape(12.dp)) {
                    Text("Start")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Working hours", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(workingHours, fontWeight = FontWeight.Bold)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Date", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val sdf = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault()).apply {
                        timeZone = java.util.TimeZone.getTimeZone("Europe/Berlin")
                    }
                    val currentDate = sdf.format(java.util.Date())
                    Text(currentDate, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Completed stops in %", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("0%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(modifier = Modifier.height(4.dp))
            LinearProgressIndicator(progress = { 0f }, modifier = Modifier.fillMaxWidth().height(8.dp), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceVariant)
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (displayDeliveries.isEmpty()) {
                        item {
                            Text(
                                "No deliveries assigned yet. Waiting for Dispatch.",
                                modifier = Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        itemsIndexed(displayDeliveries) { index, delivery ->
                            val isFirst = index == 0
                            val isLast = index == displayDeliveries.size - 1
                            
                            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(24.dp)) {
                                    Spacer(modifier = Modifier.height(24.dp))
                                    Icon(
                                        imageVector = Icons.Default.Circle,
                                        contentDescription = null,
                                        tint = if (isFirst) Color(0xFF1A73E8) else Color.LightGray,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    if (!isLast) {
                                        Box(modifier = Modifier.fillMaxHeight().width(2.dp).background(Color.LightGray))
                                    }
                                }
                                
                                Spacer(modifier = Modifier.width(8.dp))
                                
                                Card(
                                    onClick = { onDeliveryClick(delivery) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    border = if (isFirst) BorderStroke(2.dp, Color(0xFF1A73E8)) else BorderStroke(1.dp, Color.LightGray),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                                ) {
                                    Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(delivery.companyName, fontWeight = FontWeight.Bold)
                                            Text("${delivery.address}, ${delivery.postalCode}".trim(',', ' '), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.LocationOn, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(if (delivery.time.isNotEmpty()) delivery.time else "09:30", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                                Spacer(modifier = Modifier.width(16.dp))
                                                Icon(Icons.Default.Timer, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("5 min", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                            }
                                        }
                                        Box(modifier = Modifier.width(1.dp).height(48.dp).background(Color.LightGray))
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(delivery.numberOfBoxes.toString(), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Icon(Icons.Default.LocalShipping, contentDescription = null, tint = Color(0xFFE57373))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Delay Bottom Sheet ──────────────────────────────────────────────────
    if (showDelaySheet) {
        val delayOptions = listOf(5, 10, 15, 20, 30, 45, 60)
        var selectedDelay by remember { mutableStateOf(0) }
        var selectedReason by remember { mutableStateOf("") }
        val reasons = listOf("Traffic", "Parking", "Customer not available", "Access issue", "Other")
        var isSending by remember { mutableStateOf(false) }

        ModalBottomSheet(onDismissRequest = { showDelaySheet = false }) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
                Text("Report Delay", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(4.dp))
                Text("How long is your estimated delay?", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))

                // Delay duration chips
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    delayOptions.forEach { mins ->
                        FilterChip(
                            selected = selectedDelay == mins,
                            onClick = { selectedDelay = mins },
                            label = { Text("${mins}m") }
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text("Reason (optional)", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))

                // Reason chips
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    reasons.forEach { r ->
                        FilterChip(
                            selected = selectedReason == r,
                            onClick = { selectedReason = if (selectedReason == r) "" else r },
                            label = { Text(r) }
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))

                Button(
                    onClick = {
                        if (selectedDelay > 0 && !isSending) {
                            isSending = true
                            coroutineScope.launch {
                                deliveryRepo?.reportDelay(
                                    driverId = driverId,
                                    delayMinutes = selectedDelay,
                                    reason = selectedReason
                                )
                                activeDelayMins = selectedDelay
                                delayConfirmed = true
                                isSending = false
                                showDelaySheet = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(12.dp),
                    enabled = selectedDelay > 0 && !isSending,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    if (isSending) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp))
                    } else {
                        Icon(Icons.Default.Warning, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (selectedDelay > 0) "Report ${selectedDelay}min delay" else "Select delay duration",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (activeDelayMins > 0) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            coroutineScope.launch {
                                deliveryRepo?.reportDelay(driverId = driverId, delayMinutes = 0, reason = "Cleared")
                                activeDelayMins = 0
                                showDelaySheet = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Clear current delay (${activeDelayMins}min)", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeliveryDetailScreen(
    navController: NavController,
    delivery: DeliveryTask,
    allDeliveries: List<DeliveryTask>,
    checkedMap: MutableMap<String, Boolean>,
    driverId: String = "",
    deliveryRepo: com.example.data.repository.DeliveryRepository? = null,
    onComplete: (Boolean) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val isBaseLocation = remember(delivery) {
        delivery.companyName.contains("Base", ignoreCase = true) || 
        delivery.address.contains("Base", ignoreCase = true) || 
        delivery.companyName.contains("Pickup", ignoreCase = true) ||
        delivery.companyName.contains("Bella Bona", ignoreCase = true) ||
        delivery.address.contains("Bella Bona", ignoreCase = true) ||
        delivery.companyName.contains("Berlin kitchen", ignoreCase = true) ||
        delivery.address.contains("Berlin kitchen", ignoreCase = true)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Route", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            )
        },
        bottomBar = {
            Button(
                onClick = { onComplete(false) },
                modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Manually Close", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) 
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("${delivery.address}, ${delivery.postalCode}".trim(',', ' '), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(delivery.companyName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) 
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.LocationOn, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(delivery.status, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            Spacer(modifier = Modifier.width(12.dp))
                            Icon(Icons.Default.AccessTime, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (delivery.time.isNotEmpty()) delivery.time else "09:30", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            Spacer(modifier = Modifier.width(12.dp))
                            Icon(Icons.Default.Info, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (delivery.date.isNotEmpty()) delivery.date else "N/A", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                    }
                    Box(modifier = Modifier.width(1.dp).height(48.dp).background(Color.LightGray))
                    Spacer(modifier = Modifier.width(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(delivery.numberOfBoxes.toString(), fontWeight = FontWeight.Bold, fontSize = 24.sp)
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(Icons.Default.LocalShipping, contentDescription = null, tint = Color(0xFFE57373))
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { }, 
                        modifier = if (isBaseLocation) Modifier.weight(1f).height(48.dp) else Modifier.fillMaxWidth().height(48.dp), 
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Stop details")
                    }
                    if (isBaseLocation) {
                        Button(
                            onClick = { navController.navigate(Screen.Scanner.route) }, 
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Scan")
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = Color.LightGray, thickness = 1.dp)
            Spacer(modifier = Modifier.height(16.dp))
            
            Column(modifier = Modifier.padding(horizontal = 16.dp).weight(1f).verticalScroll(rememberScrollState())) {
                val tasksToShow = if (isBaseLocation) {
                    allDeliveries.filter { it.id != delivery.id && !it.companyName.contains("Berlin kitchen", true) && !it.address.contains("Berlin kitchen", true) }
                        .sortedWith(compareBy({ it.routeOrder }, { it.order }, { it.sequence }))
                        .reversed()
                } else {
                    listOf(delivery)
                }

                tasksToShow.forEachIndexed { index, task ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.LocalShipping, contentDescription = null, tint = Color(0xFFE57373))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Pickup ${index + 1}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("0 | ${task.numberOfBoxes}", fontWeight = FontWeight.Bold, color = Color.Gray)
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(Icons.Default.Inbox, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(20.dp))
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Recipient", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    Text("${task.address}, ${task.postalCode}".trim(',', ' '), fontWeight = FontWeight.Bold)
                    Text(task.companyName, fontWeight = FontWeight.Bold, color = Color.Gray)
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    repeat(task.numberOfBoxes) { i ->
                        val boxKey = "${task.id}_box_$i"
                        val isChecked = checkedMap[boxKey] ?: false
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            border = BorderStroke(1.dp, Color.LightGray),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Lunch Boxes", fontWeight = FontWeight.Bold)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    IconButton(onClick = { 
                                        checkedMap[boxKey] = false
                                    }, modifier = Modifier.size(40.dp).border(1.dp, if (!isChecked) Color.LightGray else Color.Transparent, RoundedCornerShape(8.dp))) {
                                        Icon(Icons.Default.Close, contentDescription = "Cancel", tint = if (!isChecked) Color.Gray else Color.LightGray)
                                    }
                                    IconButton(onClick = {
                                        checkedMap[boxKey] = true
                                        coroutineScope.launch {
                                            deliveryRepo?.logDriverAction(
                                                driverId = driverId,
                                                action = "BOX_CHECKED",
                                                taskId = task.id,
                                                details = mapOf(
                                                    "boxKey" to boxKey,
                                                    "boxIndex" to i,
                                                    "companyName" to task.companyName
                                                )
                                            )
                                            deliveryRepo?.updateBoxStatus(
                                                taskId = task.id,
                                                boxIndex = i,
                                                driverId = driverId
                                            )
                                        }
                                        val totalBoxes = tasksToShow.sumOf { it.numberOfBoxes }
                                        val allChecked = totalBoxes > 0 && tasksToShow.all { t ->
                                            (0 until t.numberOfBoxes).all { j ->
                                                checkedMap["${t.id}_box_$j"] == true
                                            }
                                        }
                                        if (allChecked && isBaseLocation) {
                                            onComplete(true)
                                        }
                                    }, modifier = Modifier.size(40.dp).border(1.dp, if (isChecked) Color(0xFF1A73E8) else Color.Transparent, RoundedCornerShape(8.dp))) {
                                        Icon(Icons.Default.Check, contentDescription = "Check", tint = if (isChecked) Color(0xFF1A73E8) else Color.LightGray)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}


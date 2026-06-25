package com.example.ui.screens.scanner

import android.content.Context
import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.example.data.repository.DeliveryRepository
import com.example.domain.models.DeliveryTask
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/** Result of scanning a QR code — shown in the overlay */
sealed class ScanResult {
    object Idle : ScanResult()
    object Scanning : ScanResult()
    data class Matched(
        val task: DeliveryTask,
        val boxIndex: Int,
        val totalBoxes: Int,
        val qrValue: String
    ) : ScanResult()
    data class Error(val message: String, val qrValue: String) : ScanResult()
}

/** Parse our QR format: "{orderId}#{boxN}" */
fun parseBoxQr(qr: String): Pair<String, Int> {
    return if (qr.contains('#')) {
        val parts = qr.split('#')
        Pair(parts[0], parts[1].toIntOrNull() ?: 1)
    } else {
        Pair(qr, 1) // legacy format — treat as box 1
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    navController: NavController,
    deliveryRepo: DeliveryRepository,
    driverId: String,
    routeDeliveries: List<DeliveryTask>,
    onBarcodeScanned: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    var scanResult by remember { mutableStateOf<ScanResult>(ScanResult.Idle) }
    var isProcessing by remember { mutableStateOf(false) }
    // Prevent double-scan: lock after first result
    var scanLocked by remember { mutableStateOf(false) }

    val pendingCount = routeDeliveries.count { it.status.uppercase() == "PENDING" }
    val deliveredCount = routeDeliveries.count { it.status.uppercase() == "DELIVERED" }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // ── Camera Preview ──────────────────────────────────────────────────
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val imageAnalyzer = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also {
                            it.setAnalyzer(
                                Executors.newSingleThreadExecutor(),
                                QrCodeAnalyzer { qrContent ->
                                    if (!scanLocked && !isProcessing) {
                                        scanLocked = true
                                        isProcessing = true
                                        scanResult = ScanResult.Scanning

                                        coroutineScope.launch {
                                            val result = verifyQrCode(
                                                qr = qrContent,
                                                driverId = driverId,
                                                routeDeliveries = routeDeliveries,
                                                deliveryRepo = deliveryRepo
                                            )
                                            scanResult = result
                                            isProcessing = false

                                            // If matched, call the parent callback
                                            if (result is ScanResult.Matched) {
                                                onBarcodeScanned(qrContent)
                                            }

                                            // Auto-dismiss error after 4 seconds, allow re-scan
                                            if (result is ScanResult.Error) {
                                                delay(4000)
                                                scanResult = ScanResult.Idle
                                                scanLocked = false
                                            }
                                        }
                                    }
                                }
                            )
                        }
                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageAnalyzer
                        )
                    } catch (e: Exception) {
                        Log.e("ScannerScreen", "Camera binding failed", e)
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // ── Dark overlay ────────────────────────────────────────────────────
        if (scanResult is ScanResult.Idle || scanResult is ScanResult.Scanning) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
        }

        // ── Top HUD: route progress ─────────────────────────────────────────
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.85f))
                    .padding(top = 48.dp, bottom = 14.dp, start = 20.dp, end = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text("Scan Box QR", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                // Progress pill
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.background(Color(0xFF1D4ED8), RoundedCornerShape(20.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.LocalShipping, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("$deliveredCount / ${routeDeliveries.size}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // ── Scan area cutout ────────────────────────────────────────────
            if (scanResult is ScanResult.Idle) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(width = 280.dp, height = 180.dp)
                        .background(Color.Transparent)
                ) {
                    // Corner guides
                    val cornerLen = 28.dp
                    val stroke = 4.dp
                    val cornerColor = Color(0xFF22D3EE)
                    // Top-left
                    Box(Modifier.size(cornerLen, stroke).align(Alignment.TopStart).background(cornerColor))
                    Box(Modifier.size(stroke, cornerLen).align(Alignment.TopStart).background(cornerColor))
                    // Top-right
                    Box(Modifier.size(cornerLen, stroke).align(Alignment.TopEnd).background(cornerColor))
                    Box(Modifier.size(stroke, cornerLen).align(Alignment.TopEnd).background(cornerColor))
                    // Bottom-left
                    Box(Modifier.size(cornerLen, stroke).align(Alignment.BottomStart).background(cornerColor))
                    Box(Modifier.size(stroke, cornerLen).align(Alignment.BottomStart).background(cornerColor))
                    // Bottom-right
                    Box(Modifier.size(cornerLen, stroke).align(Alignment.BottomEnd).background(cornerColor))
                    Box(Modifier.size(stroke, cornerLen).align(Alignment.BottomEnd).background(cornerColor))
                    // Scan line
                    Box(Modifier.fillMaxWidth().height(2.dp).align(Alignment.Center).background(Color(0xFF22D3EE)))
                }

                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    "Point the camera at a box QR code\nHold ~15 cm away",
                    color = Color.White.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                    fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(horizontal = 40.dp)
                )
            }

            if (scanResult is ScanResult.Scanning) {
                CircularProgressIndicator(
                    color = Color(0xFF22D3EE),
                    modifier = Modifier.align(Alignment.CenterHorizontally).size(48.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text("Verifying…", color = Color.White, modifier = Modifier.align(Alignment.CenterHorizontally))
            }

            Spacer(modifier = Modifier.weight(1f))

            // ── Bottom stop button ──────────────────────────────────────────
            if (scanResult is ScanResult.Idle || scanResult is ScanResult.Scanning) {
                Button(
                    onClick = { navController.popBackStack() },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 28.dp).height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF374151)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Stop Scanning", color = Color.White, fontSize = 16.sp)
                }
            }
        }

        // ── Verification Result Overlay ─────────────────────────────────────
        AnimatedVisibility(
            visible = scanResult is ScanResult.Matched || scanResult is ScanResult.Error,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            when (val r = scanResult) {
                is ScanResult.Matched -> MatchedOverlay(
                    result = r,
                    onConfirm = {
                        navController.popBackStack()
                    },
                    onScanNext = {
                        scanResult = ScanResult.Idle
                        scanLocked = false
                    }
                )
                is ScanResult.Error -> ErrorOverlay(
                    result = r,
                    onRetry = {
                        scanResult = ScanResult.Idle
                        scanLocked = false
                    }
                )
                else -> {}
            }
        }
    }
}

// ── Matched delivery card ────────────────────────────────────────────────────
@Composable
fun MatchedOverlay(result: ScanResult.Matched, onConfirm: () -> Unit, onScanNext: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF052E16)),
        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).background(Color(0xFF16A34A), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text("✅ Box Verified", color = Color(0xFF4ADE80), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("Box ${result.boxIndex} of ${result.totalBoxes}", color = Color(0xFF86EFAC), fontSize = 13.sp)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Divider(color = Color(0xFF166534))
            Spacer(modifier = Modifier.height(16.dp))

            // Company + address
            Text(result.task.companyName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            if (result.task.address.isNotBlank()) {
                Text(result.task.address, color = Color(0xFF86EFAC), fontSize = 13.sp)
            }
            if (result.task.numberOfBoxes > 1) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF14532D), RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Inventory2, contentDescription = null, tint = Color(0xFF4ADE80), modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Total ${result.task.numberOfBoxes} boxes for this stop",
                        color = Color(0xFF86EFAC),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // QR value
            Spacer(modifier = Modifier.height(10.dp))
            Text(result.qrValue, color = Color(0xFF4B5563), fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)

            Spacer(modifier = Modifier.height(20.dp))

            // Action buttons
            if (result.boxIndex < result.totalBoxes) {
                // More boxes to scan for this stop
                Button(
                    onClick = onScanNext,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1D4ED8)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Scan Box ${result.boxIndex + 1} of ${result.totalBoxes}", color = Color.White, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onConfirm,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF86EFAC))
                ) {
                    Text("Done — Go to Delivery Detail", fontSize = 13.sp)
                }
            } else {
                // All boxes scanned (or single box)
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.LocalShipping, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (result.totalBoxes > 1) "All ${result.totalBoxes} Boxes Loaded — Open Stop" else "Go to Delivery Detail",
                        color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp
                    )
                }
            }
        }
    }
}

// ── Error / not found card ───────────────────────────────────────────────────
@Composable
fun ErrorOverlay(result: ScanResult.Error, onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF450A0A)),
        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).background(Color(0xFFDC2626), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text("QR Code Not Matched", color = Color(0xFFFCA5A5), fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text("Contact your dispatcher", color = Color(0xFFF87171), fontSize = 13.sp)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(result.message, color = Color(0xFFFCA5A5), fontSize = 13.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(result.qrValue, color = Color(0xFF6B7280), fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Try Again", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ── Verification logic ───────────────────────────────────────────────────────
suspend fun verifyQrCode(
    qr: String,
    driverId: String,
    routeDeliveries: List<DeliveryTask>,
    deliveryRepo: DeliveryRepository
): ScanResult {
    return try {
        val (taskId, boxIndex) = parseBoxQr(qr)

        // 1. Try to find in the driver's loaded route first (fastest, offline-capable)
        var task = routeDeliveries.find { d ->
            d.id == taskId ||
            d.qrCode == qr ||
            d.qrCode == taskId ||
            (d.id + "#1") == qr
        }

        // 2. Fallback: look up in Firestore — first by doc ID (fast), then by qrCode field
        if (task == null) {
            task = deliveryRepo.getDeliveryById(taskId)
                ?: deliveryRepo.getDeliveryByQrCode(qr)
                ?: deliveryRepo.getDeliveryByQrCode(taskId)
        }

        if (task == null) {
            return ScanResult.Error(
                message = "No delivery found for this QR code.\nMake sure the route is dispatched for today.",
                qrValue = qr
            )
        }

        // 3. Verify driver matches
        val driverPrefix = driverId.substringBefore("@").lowercase()
        val taskDriverPrefix = task.driverId.substringBefore("@").lowercase()
        val driverMatches = task.driverId.equals(driverId, ignoreCase = true) ||
                driverPrefix == taskDriverPrefix

        if (!driverMatches) {
            return ScanResult.Error(
                message = "This box belongs to driver \"${task.driverName.ifBlank { task.driverId }}\", not to you.",
                qrValue = qr
            )
        }

        // 4. Log the box scan
        deliveryRepo.updateBoxStatus(
            taskId = task.id,
            boxIndex = boxIndex,
            driverId = driverId,
            scannedQr = qr
        )
        deliveryRepo.logDriverAction(
            driverId = driverId,
            action = "BOX_SCANNED",
            taskId = task.id,
            details = mapOf(
                "qrCode" to qr,
                "boxIndex" to boxIndex,
                "totalBoxes" to (task.numberOfBoxes),
                "companyName" to task.companyName
            )
        )

        ScanResult.Matched(
            task = task,
            boxIndex = boxIndex,
            totalBoxes = task.numberOfBoxes.coerceAtLeast(1),
            qrValue = qr
        )
    } catch (e: Exception) {
        Log.e("ScannerScreen", "Verification error", e)
        ScanResult.Error(message = "Verification failed: ${e.message}", qrValue = qr)
    }
}

class QrCodeAnalyzer(private val onQrCodeScanned: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val options = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_ALL_FORMATS)
        .build()

    private val scanner = BarcodeScanning.getClient(options)

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    for (barcode in barcodes) {
                        barcode.rawValue?.let { value ->
                            onQrCodeScanned(value)
                            imageProxy.close()
                            return@addOnSuccessListener
                        }
                    }
                    imageProxy.close()
                }
                .addOnFailureListener { imageProxy.close() }
        } else {
            imageProxy.close()
        }
    }
}

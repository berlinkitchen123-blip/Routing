package com.example.ui.screens

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.example.data.repository.DeliveryRepository
import com.example.domain.models.DeliveryTask
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

// ── Map HTML (OpenStreetMap + Leaflet, no API key needed) ────────────────────
private val MAP_HTML = """
<!DOCTYPE html>
<html>
<head>
  <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
  <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
  <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
  <style>
    * { margin:0; padding:0; box-sizing:border-box; }
    body { background:#f5f5f5; }
    #map { width:100vw; height:100vh; }
    #status { position:absolute; top:8px; left:50%; transform:translateX(-50%);
              background:rgba(26,115,232,0.9); color:#fff; padding:4px 14px;
              border-radius:20px; font-size:12px; z-index:1000; font-family:sans-serif; }
  </style>
</head>
<body>
  <div id="map"></div>
  <div id="status">Waiting for GPS...</div>
  <script>
    var map = L.map('map', { zoomControl: true }).setView([52.52, 13.40], 14);
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      attribution: '© OpenStreetMap', maxZoom: 19
    }).addTo(map);

    var driverMarker = null;
    var accuracyCircle = null;
    var stopMarkers = [];
    var routeLine = null;
    var firstFix = true;

    function updateDriver(lat, lng, accuracy, speed, driverName) {
      var pos = [lat, lng];
      var speedKmh = speed > 0 ? (speed * 3.6).toFixed(1) + ' km/h' : 'stopped';
      document.getElementById('status').textContent = driverName + ' · ' + speedKmh;

      if (!driverMarker) {
        var icon = L.divIcon({
          className: '',
          html: '<div style="width:18px;height:18px;background:#1A73E8;border:3px solid #fff;border-radius:50%;box-shadow:0 2px 6px rgba(0,0,0,0.4)"></div>',
          iconSize: [18, 18], iconAnchor: [9, 9]
        });
        driverMarker = L.marker(pos, { icon: icon, zIndexOffset: 1000 })
          .addTo(map).bindPopup('<b>' + driverName + '</b><br>' + speedKmh);
      } else {
        driverMarker.setLatLng(pos);
        driverMarker.getPopup()?.setContent('<b>' + driverName + '</b><br>' + speedKmh);
      }

      if (accuracyCircle) accuracyCircle.setLatLng(pos).setRadius(accuracy);
      else accuracyCircle = L.circle(pos, { radius: accuracy, color: '#1A73E8', fillColor: '#1A73E8', fillOpacity: 0.1, weight: 1 }).addTo(map);

      if (firstFix) { map.setView(pos, 15); firstFix = false; }
      updateRouteLine();
    }

    function setStops(json) {
      stopMarkers.forEach(function(m) { m.remove(); });
      stopMarkers = [];
      try {
        var stops = JSON.parse(json);
        stops.forEach(function(s, i) {
          if (!s.lat || !s.lng) return;
          var color = s.status === 'DELIVERED' ? '#4CAF50' : (i === 0 ? '#FF5722' : '#9E9E9E');
          var icon = L.divIcon({
            className: '',
            html: '<div style="background:' + color + ';color:#fff;width:24px;height:24px;border-radius:50%;display:flex;align-items:center;justify-content:center;font-size:11px;font-weight:bold;font-family:sans-serif;border:2px solid #fff;box-shadow:0 2px 4px rgba(0,0,0,0.3)">' + (i+1) + '</div>',
            iconSize: [24, 24], iconAnchor: [12, 12]
          });
          var m = L.marker([s.lat, s.lng], { icon: icon })
            .addTo(map)
            .bindPopup('<b>' + s.name + '</b><br><small>' + s.address + '</small>');
          stopMarkers.push(m);
        });
        updateRouteLine();
      } catch(e) { console.error('setStops error', e); }
    }

    function updateRouteLine() {
      if (routeLine) { routeLine.remove(); routeLine = null; }
      var points = [];
      if (driverMarker) points.push(driverMarker.getLatLng());
      stopMarkers.forEach(function(m) { points.push(m.getLatLng()); });
      if (points.length >= 2) {
        routeLine = L.polyline(points, { color: '#1A73E8', weight: 3, opacity: 0.6, dashArray: '6,8' }).addTo(map);
      }
    }

    function fitAll() {
      var all = [];
      if (driverMarker) all.push(driverMarker.getLatLng());
      stopMarkers.forEach(function(m) { all.push(m.getLatLng()); });
      if (all.length > 0) map.fitBounds(L.latLngBounds(all).pad(0.15));
    }
  </script>
</body>
</html>
""".trimIndent()

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveTrackingScreen(
    navController: NavController,
    driverId: String,
    deliveries: List<DeliveryTask>,
    deliveryRepo: DeliveryRepository
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var driverLat by remember { mutableStateOf<Double?>(null) }
    var driverLng by remember { mutableStateOf<Double?>(null) }
    var driverSpeed by remember { mutableStateOf(0f) }
    var driverAccuracy by remember { mutableStateOf(0f) }
    var lastUpdated by remember { mutableStateOf("--") }
    var isOnline by remember { mutableStateOf(false) }

    // Real-time Firestore listener for driver location
    DisposableEffect(driverId) {
        val listener = FirebaseFirestore.getInstance()
            .collection("driver_locations")
            .document(driverId)
            .addSnapshotListener { snap, _ ->
                val data = snap?.data ?: return@addSnapshotListener
                val lat = (data["lat"] as? Number)?.toDouble() ?: return@addSnapshotListener
                val lng = (data["lng"] as? Number)?.toDouble() ?: return@addSnapshotListener
                val speed = (data["speed"] as? Number)?.toFloat() ?: 0f
                val accuracy = (data["accuracy"] as? Number)?.toFloat() ?: 10f
                val ts = (data["timestamp"] as? Number)?.toLong() ?: 0L
                val online = data["online"] as? Boolean ?: false

                driverLat = lat
                driverLng = lng
                driverSpeed = speed
                driverAccuracy = accuracy
                isOnline = online

                val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).apply {
                    timeZone = java.util.TimeZone.getTimeZone("Europe/Berlin")
                }
                lastUpdated = sdf.format(java.util.Date(ts))

                // Push update into the WebView map
                val js = "updateDriver($lat, $lng, $accuracy, $speed, '${driverId.substringBefore("@")}');"
                webViewRef?.post { webViewRef?.evaluateJavascript(js, null) }
            }
        onDispose { listener.remove() }
    }

    // Push stops into map once WebView is ready
    LaunchedEffect(deliveries, webViewRef) {
        val wv = webViewRef ?: return@LaunchedEffect
        val pending = deliveries.filter { it.status != "DELIVERED" }
        // Build JSON — we use Berlin lat/lng as approximate fallback if no geocode
        val stopsJson = pending.joinToString(",", "[", "]") { stop ->
            val lat = stop.address.hashCode().let { 52.52 + (it % 100) * 0.001 } // rough fallback
            val lng = stop.address.hashCode().let { 13.40 + (it % 100) * 0.001 }
            """{"name":"${stop.companyName.replace("\"","'")}","address":"${stop.address.replace("\"","'")}","status":"${stop.status}","lat":$lat,"lng":$lng}"""
        }
        wv.post { wv.evaluateJavascript("setStops('${stopsJson.replace("'", "\\'")}');", null) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Live Tracking", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier.size(8.dp).background(
                                    if (isOnline) Color(0xFF4CAF50) else Color(0xFF9E9E9E),
                                    RoundedCornerShape(4.dp)
                                )
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (isOnline) "Online · Updated $lastUpdated" else "Offline",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.primary)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        webViewRef?.evaluateJavascript("fitAll();", null)
                    }) {
                        Icon(Icons.Default.FitScreen, contentDescription = "Fit all", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            // ── GPS info bar ─────────────────────────────────────────────
            if (driverLat != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("GPS", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(
                            "%.5f, %.5f".format(driverLat, driverLng),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Speed", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(
                            if (driverSpeed > 0.5f) "%.1f km/h".format(driverSpeed * 3.6f) else "Stopped",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Accuracy", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(
                            "±%.0fm".format(driverAccuracy),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            // ── Map (70% height) ──────────────────────────────────────────
            Box(modifier = Modifier.fillMaxWidth().weight(0.7f)) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = WebViewClient()
                            loadDataWithBaseURL(
                                "https://map.local",
                                MAP_HTML,
                                "text/html",
                                "UTF-8",
                                null
                            )
                            webViewRef = this
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                if (driverLat == null) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Card(shape = RoundedCornerShape(12.dp)) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator()
                                Spacer(Modifier.height(8.dp))
                                Text("Waiting for GPS signal...", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }

            // ── Upcoming stops list (30% height) ─────────────────────────
            val pending = remember(deliveries) { deliveries.filter { it.status != "DELIVERED" } }
            Column(modifier = Modifier.fillMaxWidth().weight(0.3f).padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    "Upcoming stops (${pending.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                if (pending.isEmpty()) {
                    Text("All stops completed ✓", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                } else {
                    pending.take(3).forEachIndexed { i, stop ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.size(22.dp).background(
                                    if (i == 0) Color(0xFFFF5722) else Color(0xFF9E9E9E),
                                    RoundedCornerShape(11.dp)
                                ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("${i+1}", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stop.companyName, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
                                Text(stop.address, fontSize = 11.sp, color = Color.Gray, maxLines = 1)
                            }
                            if (stop.time.isNotEmpty()) {
                                Text(stop.time, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    if (pending.size > 3) {
                        Text("+${pending.size - 3} more stops", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
    }
}

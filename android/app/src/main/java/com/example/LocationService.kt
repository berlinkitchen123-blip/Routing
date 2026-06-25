package com.example

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import android.content.pm.ServiceInfo
import com.google.android.gms.location.*
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.*

class LocationService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private lateinit var fusedClient: FusedLocationProviderClient
    private var driverId = "unknown_driver"

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            serviceScope.launch {
                try {
                    FirebaseFirestore.getInstance()
                        .collection("driver_locations")
                        .document(driverId)
                        .set(mapOf(
                            "driverId" to driverId,
                            "lat" to location.latitude,
                            "lng" to location.longitude,
                            "accuracy" to location.accuracy,
                            "speed" to location.speed,
                            "timestamp" to System.currentTimeMillis(),
                            "online" to true
                        ))
                    Log.d("LocationService", "GPS updated for $driverId: (${location.latitude}, ${location.longitude})")
                } catch (e: Exception) {
                    Log.e("LocationService", "Failed to write location to Firebase", e)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        driverId = intent?.getStringExtra("DRIVER_ID")?.ifBlank { "unknown_driver" } ?: "unknown_driver"

        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(1, notification)
        }

        startLocationUpdates()
        return START_STICKY
    }

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w("LocationService", "Location permission not granted, skipping GPS updates")
            return
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 30_000L)
            .setMinUpdateIntervalMillis(15_000L)
            .build()

        fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    override fun onDestroy() {
        super.onDestroy()
        fusedClient.removeLocationUpdates(locationCallback)
        serviceScope.cancel()

        // Mark driver offline when service stops
        FirebaseFirestore.getInstance()
            .collection("driver_locations")
            .document(driverId)
            .update("online", false)
            .addOnFailureListener { Log.e("LocationService", "Failed to mark offline", it) }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "routing_location_channel")
            .setContentTitle("Route Active — $driverId")
            .setContentText("Tracking delivery progress...")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "routing_location_channel",
                "Routing Location Tracking",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}

package com.example.data.repository

import android.util.Log
import com.example.domain.models.DeliveryTask
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

class DeliveryRepository {
    private val db: FirebaseFirestore? = try {
        FirebaseFirestore.getInstance()
    } catch (e: Exception) {
        Log.w("DeliveryRepository", "Firebase not initialized", e)
        null
    }

    private fun todayBerlin(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone("Europe/Berlin")
        }.format(Date())
    }

    private fun isTodayBerlin(taskDate: String): Boolean {
        if (taskDate.isBlank()) return true

        val tz = TimeZone.getTimeZone("Europe/Berlin")
        val now = Date()
        val cal = Calendar.getInstance(tz).apply { time = now }

        val day = cal.get(Calendar.DAY_OF_MONTH)
        val month = cal.get(Calendar.MONTH) + 1
        val year = cal.get(Calendar.YEAR)

        val dayStr = day.toString()
        val dayStrZero = if (day < 10) "0$day" else dayStr
        val monthStr = month.toString()
        val monthStrZero = if (month < 10) "0$month" else monthStr
        val yearStr = year.toString()
        val cleanDate = taskDate.trim()

        val formats = listOf(
            "$dayStrZero.$monthStrZero.$yearStr",
            "$yearStr-$monthStrZero-$dayStrZero",
            "$dayStrZero/$monthStrZero/$yearStr",
            "$dayStrZero-$monthStrZero-$dayStrZero",
            "$yearStr/$monthStrZero/$dayStrZero",
            "$dayStr.$monthStr.$yearStr",
            "$yearStr-$monthStr-$dayStr",
            "$dayStr/$monthStr/$yearStr",
            "$dayStr-$monthStr-$dayStr"
        )

        for (f in formats) {
            if (cleanDate.contains(f)) return true
        }

        if (cleanDate.contains(yearStr)) {
            val hasDay = cleanDate.contains(dayStr) || cleanDate.contains(dayStrZero)
            val hasMonth = cleanDate.contains(monthStr) || cleanDate.contains(monthStrZero)
            if (hasDay && hasMonth) return true
        }

        return false
    }

    private fun parseDeliveryTask(doc: com.google.firebase.firestore.DocumentSnapshot): DeliveryTask? {
        return try {
            val data = doc.data ?: return null
            DeliveryTask(
                id = doc.id,
                driverId = data["driverId"]?.toString() ?: "",
                qrCode = data["qrCode"]?.toString() ?: "",
                companyName = data["companyName"]?.toString() ?: "",
                address = data["address"]?.toString() ?: "",
                postalCode = data["postalCode"]?.toString() ?: "",
                deliveryInstructions = data["deliveryInstructions"]?.toString() ?: "",
                numberOfBoxes = (data["numberOfBoxes"] as? Number)?.toInt() ?: 0,
                status = data["status"]?.toString() ?: "PENDING",
                date = data["date"]?.toString() ?: "",
                routeOrder = (data["routeOrder"] as? Number)?.toInt() ?: 0,
                order = (data["order"] as? Number)?.toInt() ?: 0,
                sequence = (data["sequence"] as? Number)?.toInt() ?: 0,
                time = data["time"]?.toString() ?: "",
                timestamp = data["timestamp"]?.toString() ?: ""
            )
        } catch (e: Exception) {
            null
        }
    }

    fun getAssignedDeliveries(driverId: String): Flow<List<DeliveryTask>> = callbackFlow {
        val finalDriverId = if (driverId.equals("Ali1@bellabona.com", ignoreCase = true)) "ALi1@bellabona.com" else driverId
        val subscription = db?.collection("deliveries")
            ?.addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("DeliveryRepository", "Error fetching deliveries", error)
                    trySend(listOf(DeliveryTask(id = "error", address = "Error: ${error.message}", companyName = "Error")))
                    return@addSnapshotListener
                }

                val allTasks = snapshot?.documents?.mapNotNull { parseDeliveryTask(it) } ?: emptyList()
                Log.d("DeliveryRepository", "Total tasks safely parsed: ${allTasks.size}")

                val filteredTasks = allTasks.filter { task ->
                    val taskDriverPrefix = task.driverId.substringBefore("@").trim()
                    val inputDriverPrefix = driverId.substringBefore("@").trim()
                    val matchesDriver = task.driverId.equals(driverId, ignoreCase = true) ||
                            task.driverId.equals(finalDriverId, ignoreCase = true) ||
                            taskDriverPrefix.equals(inputDriverPrefix, ignoreCase = true)
                    val matchesDate = isTodayBerlin(task.date)
                    matchesDriver && matchesDate
                }

                val hasBerlinKitchen = filteredTasks.any {
                    it.companyName.contains("Berlin kitchen", ignoreCase = true) ||
                    it.address.contains("Berlin kitchen", ignoreCase = true)
                }

                val finalTasks = if (hasBerlinKitchen) {
                    filteredTasks
                } else {
                    val berlinKitchenTask = DeliveryTask(
                        id = "berlin_kitchen_${driverId.replace(".", "_")}",
                        driverId = driverId,
                        companyName = "Berlin kitchen",
                        address = "Berlin kitchen Address",
                        status = "PENDING",
                        date = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).apply {
                            timeZone = TimeZone.getTimeZone("Europe/Berlin")
                        }.format(Date()),
                        time = "09:30"
                    )
                    listOf(berlinKitchenTask) + filteredTasks
                }

                trySend(finalTasks)
            }

        awaitClose { subscription?.remove() }
    }

    fun getAllDeliveries(): Flow<List<DeliveryTask>> = callbackFlow {
        val subscription = db?.collection("deliveries")
            ?.addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val allTasks = snapshot?.documents?.mapNotNull { parseDeliveryTask(it) } ?: emptyList()
                trySend(allTasks)
            }
        awaitClose { subscription?.remove() }
    }

    suspend fun getDeliveryByQrCode(qrCode: String): DeliveryTask? {
        return try {
            val snapshot = db?.collection("deliveries")
                ?.whereEqualTo("qrCode", qrCode)
                ?.get()
                ?.await()
            snapshot?.documents?.mapNotNull { parseDeliveryTask(it) }?.firstOrNull()
        } catch (e: Exception) {
            Log.e("DeliveryRepository", "Error fetching delivery by QR", e)
            null
        }
    }

    suspend fun updateDeliveryStatus(taskId: String, status: String, proofType: String? = null, fullTask: DeliveryTask? = null): Boolean {
        return try {
            val updateData = mutableMapOf<String, Any>("status" to status)
            if (proofType != null) {
                updateData["proofType"] = proofType
                updateData["proofTimestamp"] = System.currentTimeMillis()
                updateData["hasProof"] = true
            }
            if (fullTask != null) {
                updateData["driverId"] = fullTask.driverId
                updateData["companyName"] = fullTask.companyName
                updateData["address"] = fullTask.address
                updateData["date"] = fullTask.date
            }
            db?.collection("deliveries")?.document(taskId)
                ?.set(updateData, com.google.firebase.firestore.SetOptions.merge())
                ?.await()
            true
        } catch (e: Exception) {
            Log.e("DeliveryRepository", "Error updating delivery status", e)
            false
        }
    }

    // Logs every driver action to Firestore — backend listens to this in real-time
    suspend fun logDriverAction(
        driverId: String,
        action: String,       // e.g. "BOX_SCANNED", "BOX_CHECKED", "DELIVERED", "NAVIGATE_STARTED"
        taskId: String = "",
        details: Map<String, Any> = emptyMap()
    ) {
        try {
            val today = todayBerlin()
            val entry = mutableMapOf<String, Any>(
                "driverId" to driverId,
                "action" to action,
                "taskId" to taskId,
                "timestamp" to System.currentTimeMillis(),
                "date" to today
            )
            entry.putAll(details)

            db?.collection("driver_events")
                ?.document(today)
                ?.collection(driverId)
                ?.add(entry)
                ?.await()

            Log.d("DeliveryRepository", "Logged action: $action for $driverId on task $taskId")
        } catch (e: Exception) {
            Log.e("DeliveryRepository", "Failed to log driver action", e)
        }
    }

    // Reports a delivery delay — writes to Firestore and updates driver_locations
    suspend fun reportDelay(
        driverId: String,
        delayMinutes: Int,
        reason: String = ""
    ): Boolean {
        return try {
            val today = todayBerlin()
            val ts = System.currentTimeMillis()

            // Write dedicated delay record
            db?.collection("driver_delays")
                ?.document(today)
                ?.collection(driverId)
                ?.add(mapOf(
                    "driverId" to driverId,
                    "delayMinutes" to delayMinutes,
                    "reason" to reason,
                    "timestamp" to ts,
                    "date" to today
                ))
                ?.await()

            // Merge delay into live location doc so backend map shows it
            db?.collection("driver_locations")
                ?.document(driverId)
                ?.update(mapOf(
                    "estimatedDelayMins" to delayMinutes,
                    "delayReason" to reason,
                    "delayReportedAt" to ts
                ))
                ?.await()

            // Also log as a driver event
            logDriverAction(
                driverId = driverId,
                action = "DELAY_REPORTED",
                details = mapOf("delayMinutes" to delayMinutes, "reason" to reason)
            )
            true
        } catch (e: Exception) {
            Log.e("DeliveryRepository", "Failed to report delay", e)
            false
        }
    }

    // Real-time stream of the driver's own GPS location from Firestore
    fun observeDriverLocation(driverId: String): Flow<Map<String, Any>> = callbackFlow {
        val listener = db?.collection("driver_locations")
            ?.document(driverId)
            ?.addSnapshotListener { snap, error ->
                if (error != null) return@addSnapshotListener
                val data = snap?.data ?: return@addSnapshotListener
                trySend(data)
            }
        awaitClose { listener?.remove() }
    }

    suspend fun uploadProofImage(
        taskId: String,
        bitmap: android.graphics.Bitmap,
        type: String // "photo" or "signature"
    ): String? {
        return try {
            val storage = com.google.firebase.storage.FirebaseStorage.getInstance()
            val ref = storage.reference.child("proofs/$taskId/$type.jpg")
            val baos = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, baos)
            val data = baos.toByteArray()
            ref.putBytes(data).await()
            val url = ref.downloadUrl.await().toString()
            // Store URL back in the delivery doc
            val field = if (type == "photo") "proofImageUrl" else "signatureImageUrl"
            db?.collection("deliveries")?.document(taskId)
                ?.update(mapOf(field to url))
                ?.await()
            url
        } catch (e: Exception) {
            Log.e("DeliveryRepository", "Failed to upload $type image", e)
            null
        }
    }

    suspend fun validateDriver(email: String): Boolean {
        return try {
            // Check drivers collection first
            val snap = db?.collection("drivers")
                ?.whereEqualTo("email", email)
                ?.get()?.await()
            if ((snap?.documents?.size ?: 0) > 0) return true
            // Also accept if any delivery has this driverId (they've been dispatched)
            val delivSnap = db?.collection("deliveries")
                ?.whereEqualTo("driverId", email)
                ?.limit(1)?.get()?.await()
            (delivSnap?.documents?.size ?: 0) > 0
        } catch (e: Exception) {
            Log.w("DeliveryRepository", "Driver validation error, allowing login", e)
            true // fail open so app still works if Firestore is unavailable
        }
    }

    // Writes individual box scan/check status so backend can track per-box progress
    suspend fun updateBoxStatus(
        taskId: String,
        boxIndex: Int,
        driverId: String,
        scannedQr: String = ""
    ) {
        try {
            val today = todayBerlin()
            db?.collection("deliveries")
                ?.document(taskId)
                ?.collection("boxes")
                ?.document("box_$boxIndex")
                ?.set(mapOf(
                    "boxIndex" to boxIndex,
                    "scannedAt" to System.currentTimeMillis(),
                    "scannedBy" to driverId,
                    "scannedQr" to scannedQr,
                    "status" to "SCANNED"
                ), com.google.firebase.firestore.SetOptions.merge())
                ?.await()
        } catch (e: Exception) {
            Log.e("DeliveryRepository", "Failed to update box status", e)
        }
    }
}

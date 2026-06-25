package com.example.domain.models

data class DeliveryTask(
    val id: String = "",
    val driverId: String = "",
    val qrCode: String = "",
    val companyName: String = "",
    val address: String = "",
    val postalCode: String = "",
    val deliveryInstructions: String = "",
    val numberOfBoxes: Int = 0,
    val status: String = "PENDING",
    val date: String = "",
    val routeOrder: Int = 0,
    val order: Int = 0,
    val sequence: Int = 0,
    val time: String = "",
    val timestamp: String = ""
)

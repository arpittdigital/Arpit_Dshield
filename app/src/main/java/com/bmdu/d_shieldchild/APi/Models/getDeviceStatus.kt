package com.bmdu.d_shieldchild.APi.Models

data class DeviceStatusRequest(
    val imei1: String
)

data class DeviceStatusResponse(
    val success: Boolean,
    val message: String,
    val status: String? = null,
    val device: Device? = null
)
package com.weekd.miracastreceiver.signaling

enum class MessageType {
    DEVICE_INFO,
    CONNECT_REQUEST,
    CONNECT_RESPONSE,
    OFFER,
    ANSWER,
    ICE_CANDIDATE,
    START_STREAM,
    STOP_STREAM,
    HEARTBEAT,
    ERROR
}

data class SignalingMessage(
    val type: MessageType,
    val sessionId: String? = null,
    val deviceId: String? = null,
    val deviceName: String? = null,
    val payload: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class ConnectResponse(
    val accepted: Boolean,
    val reason: String? = null,
    val sessionId: String? = null
)

data class CastDevice(
    val deviceId: String,
    val deviceName: String,
    val deviceType: DeviceType,
    val ipAddress: String,
    val connectedAt: Long = System.currentTimeMillis()
)

enum class DeviceType {
    ANDROID,
    IOS,
    WINDOWS,
    MACOS,
    WEB,
    UNKNOWN
}

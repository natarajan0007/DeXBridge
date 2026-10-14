package com.weekd.miracastreceiver.security

import com.weekd.miracastreceiver.utils.NetworkUtils

class PinCodeManager {

    private var currentPinCode: String = NetworkUtils.generateConnectionCode()

    fun getCurrentPinCode(): String = currentPinCode

    fun regeneratePinCode(): String {
        currentPinCode = NetworkUtils.generateConnectionCode()
        return currentPinCode
    }

    fun validatePinCode(pinCode: String): Boolean {
        return pinCode.equals(currentPinCode, ignoreCase = true)
    }
}

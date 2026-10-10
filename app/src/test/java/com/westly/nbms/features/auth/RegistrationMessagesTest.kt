package com.westly.nbms.features.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class RegistrationMessagesTest {

    // The create-business Edge Function sends this exact sentence; the app moves back to the right step by comparing it.
    @Test fun verifyExpiredMessage_matchesWhatTheServerSends() {
        val reply = """{"ok":false,"error":"Your email verification has expired. Please verify your email again."}"""
        assertEquals(MSG_VERIFY_EXPIRED, extractServerError(reply))
    }

    @Test fun emailExistsMessage_matchesWhatTheServerSends() {
        val reply = """{"ok":false,"error":"An account with this email already exists."}"""
        assertEquals(MSG_EMAIL_EXISTS, extractServerError(reply))
    }

    @Test fun requestCarriesTheVerificationToken() {
        val request = CreateBusinessRequest(
            ownerName = "Jane", email = "jane@example.com", password = "abcdefg1", businessName = "Hotel",
            enabledRoles = listOf("manager"), verificationToken = "tok"
        )
        assertEquals("tok", request.verificationToken)
    }
}

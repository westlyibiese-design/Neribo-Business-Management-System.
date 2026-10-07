package com.westly.nbms.features.auth

import com.westly.nbms.core.rbac.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinReplyTest {

    @Test fun successReplyIsParsed() {
        val text = """{"ok":true,"access_token":"a.b.c","refresh_token":"r123","user":{"name":"Ada Obi","role":"receptionist"}}"""
        val reply = parsePinReply(text).getOrThrow()
        assertEquals("a.b.c", reply.accessToken)
        assertEquals("r123", reply.refreshToken)
        assertEquals("Ada Obi", reply.name)
        assertEquals(Role.RECEPTIONIST, reply.role)
    }

    @Test fun serverErrorMessageIsKept() {
        val text = """{"ok":false,"error":"Too many attempts. Try again in a few minutes."}"""
        val result = parsePinReply(text)
        assertTrue(result.isFailure)
        assertEquals("Too many attempts. Try again in a few minutes.", result.exceptionOrNull()?.message)
    }

    @Test fun missingTokensFailWithSessionMessage() {
        val text = """{"ok":true,"user":{"name":"Ada","role":"driver"}}"""
        assertEquals(MSG_PIN_SESSION, parsePinReply(text).exceptionOrNull()?.message)
    }

    @Test fun unknownRoleFails() {
        val text = """{"ok":true,"access_token":"a","refresh_token":"b","user":{"name":"X","role":"developer"}}"""
        assertTrue(parsePinReply(text).isFailure)
    }

    @Test fun garbageFailsCleanly() {
        assertEquals(MSG_PIN_SESSION, parsePinReply("not json").exceptionOrNull()?.message)
    }

    @Test fun errorTextIsFoundInRawBodies() {
        assertEquals("Invalid business code or PIN.", pinServerError("""{"ok":false,"error":"Invalid business code or PIN."}"""))
        assertNull(pinServerError(null, "", "nothing here"))
    }
}

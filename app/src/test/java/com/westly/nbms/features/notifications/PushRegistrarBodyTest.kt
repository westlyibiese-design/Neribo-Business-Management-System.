package com.westly.nbms.features.notifications

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushRegistrarBodyTest {

    @Test fun register_body_carries_token_device_and_platform() {
        val body = registerTokenBody("tok", "dev-1", remove = false)
        assertEquals("tok", body["token"]!!.jsonPrimitive.content)
        assertEquals("dev-1", body["deviceId"]!!.jsonPrimitive.content)
        assertEquals("android", body["platform"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("remove"))
    }

    @Test fun remove_body_is_just_token_and_remove() {
        val body = registerTokenBody("tok", "dev-1", remove = true)
        assertEquals("tok", body["token"]!!.jsonPrimitive.content)
        assertTrue(body["remove"]!!.jsonPrimitive.boolean)
        assertNull(body["deviceId"])
    }

    @Test fun blank_device_id_is_left_out() {
        assertFalse(registerTokenBody("tok", " ", remove = false).containsKey("deviceId"))
    }
}

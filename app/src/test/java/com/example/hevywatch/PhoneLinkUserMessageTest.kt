package com.example.hevywatch

import com.example.hevywatch.wear.PhoneLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the user-facing strings for [PhoneLink.userMessage]. The Ready branch
 * must be empty (the UI never renders the message in that state), and the
 * two failure branches must carry a non-blank label so the gating screen
 * doesn't show an unlabelled spinner.
 */
class PhoneLinkUserMessageTest {

    @Test fun `bluetooth off message is non-blank`() {
        val msg = PhoneLink.userMessage(PhoneLink.State.BluetoothOff)
        assertTrue("BluetoothOff message must be non-blank but was '$msg'", msg.isNotBlank())
    }

    @Test fun `phone not connected message is non-blank`() {
        val msg = PhoneLink.userMessage(PhoneLink.State.PhoneNotConnected)
        assertTrue("PhoneNotConnected message must be non-blank but was '$msg'", msg.isNotBlank())
    }

    @Test fun `ready state renders no message`() {
        assertEquals("", PhoneLink.userMessage(PhoneLink.State.Ready))
    }

    @Test fun `failure messages are distinguishable`() {
        val a = PhoneLink.userMessage(PhoneLink.State.BluetoothOff)
        val b = PhoneLink.userMessage(PhoneLink.State.PhoneNotConnected)
        assertTrue(
            "BluetoothOff and PhoneNotConnected must surface different copy " +
                "so the user knows which knob to fix (got '$a' / '$b')",
            a != b,
        )
    }
}

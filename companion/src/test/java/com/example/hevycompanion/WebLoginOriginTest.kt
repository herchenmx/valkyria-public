package com.example.hevycompanion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the WebView-navigation allowlist for [WebLoginActivity]. Only
 * `https://hevy.com` and `https://www.hevy.com` may carry the TokenBridge
 * JS interface — every other destination must be blocked so an off-origin
 * redirect can't invoke `TokenBridge.onTokens(...)` with attacker tokens.
 */
@RunWith(RobolectricTestRunner::class)
class WebLoginOriginTest {

    @Test fun `hevy https allowed`() {
        assertTrue(WebLoginActivity.isHevyOrigin("https://hevy.com"))
        assertTrue(WebLoginActivity.isHevyOrigin("https://hevy.com/login"))
        assertTrue(WebLoginActivity.isHevyOrigin("https://www.hevy.com"))
        assertTrue(WebLoginActivity.isHevyOrigin("https://www.hevy.com/reset?x=1"))
    }

    @Test fun `case-insensitive host and scheme`() {
        assertTrue(WebLoginActivity.isHevyOrigin("HTTPS://Hevy.Com/"))
        assertTrue(WebLoginActivity.isHevyOrigin("https://HEVY.COM/anything"))
    }

    @Test fun `http scheme rejected`() {
        assertFalse(WebLoginActivity.isHevyOrigin("http://hevy.com"))
    }

    @Test fun `subdomain other than www rejected`() {
        assertFalse(WebLoginActivity.isHevyOrigin("https://api.hevy.com"))
        assertFalse(WebLoginActivity.isHevyOrigin("https://blog.hevy.com/"))
    }

    @Test fun `unrelated origin rejected`() {
        assertFalse(WebLoginActivity.isHevyOrigin("https://example.com"))
        assertFalse(WebLoginActivity.isHevyOrigin("https://hevy.evil.com"))
        assertFalse(WebLoginActivity.isHevyOrigin("https://hevy.com.evil.com"))
        assertFalse(WebLoginActivity.isHevyOrigin("https://www.hevy.com.evil.com"))
    }

    @Test fun `empty and malformed rejected`() {
        assertFalse(WebLoginActivity.isHevyOrigin(null))
        assertFalse(WebLoginActivity.isHevyOrigin(""))
        assertFalse(WebLoginActivity.isHevyOrigin("   "))
        assertFalse(WebLoginActivity.isHevyOrigin("not-a-url"))
    }

    @Test fun `file and javascript schemes rejected`() {
        assertFalse(WebLoginActivity.isHevyOrigin("file:///etc/passwd"))
        assertFalse(WebLoginActivity.isHevyOrigin("javascript:TokenBridge.onTokens('a','b','c')"))
        assertFalse(WebLoginActivity.isHevyOrigin("data:text/html,<script>alert(1)</script>"))
    }

    @Test fun `credentials-carrying URL rejected via host mismatch`() {
        assertFalse(WebLoginActivity.isHevyOrigin("https://hevy.com@evil.com/login"))
    }
}

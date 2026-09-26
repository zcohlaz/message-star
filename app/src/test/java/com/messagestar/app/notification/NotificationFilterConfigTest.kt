package com.messagestar.app.notification

import com.messagestar.app.data.NotificationFilterConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationFilterConfigTest {
    @Test fun disabledFilterSkipsEveryPackage() {
        val config = NotificationFilterConfig(false, setOf("com.shop"), emptyList())
        assertFalse(config.shouldEvaluate("com.shop"))
    }

    @Test fun enabledFilterOnlyEvaluatesManagedPackages() {
        val config = NotificationFilterConfig(true, setOf("com.shop"), emptyList())
        assertTrue(config.shouldEvaluate("com.shop"))
        assertFalse(config.shouldEvaluate("com.chat"))
    }
}

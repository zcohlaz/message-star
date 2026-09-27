package com.messagestar.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManifestParserTest {
    private val valid = """{
        "packageName":"com.messagestar.app",
        "versionCode":3,
        "versionName":"1.2.0",
        "notes":"修复问题",
        "apkUrl":"https://example.com/message-star.apk",
        "sizeBytes":1024,
        "sha256":"${"a".repeat(64)}"
    }"""

    @Test fun parsesValidManifest() {
        val result = UpdateManifestParser.parse(valid, "com.messagestar.app")
        assertEquals(3L, result.versionCode)
        assertEquals("1.2.0", result.versionName)
    }

    @Test fun rejectsWrongPackage() {
        assertRejected(valid.replace("com.messagestar.app", "com.attacker.app"))
    }

    @Test fun rejectsInsecureApkUrl() {
        assertRejected(valid.replace("https://example.com", "http://example.com"))
        assertFalse(UpdateManifestParser.isHttpsUrl("https://user:secret@example.com/app.apk"))
        assertTrue(UpdateManifestParser.isHttpsUrl("https://example.com/app.apk"))
    }

    @Test fun rejectsInvalidHashAndSize() {
        assertRejected(valid.replace("a".repeat(64), "deadbeef"))
        assertRejected(valid.replace("\"sizeBytes\":1024", "\"sizeBytes\":0"))
    }

    private fun assertRejected(json: String) {
        try {
            UpdateManifestParser.parse(json, "com.messagestar.app")
            throw AssertionError("Expected invalid manifest to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}

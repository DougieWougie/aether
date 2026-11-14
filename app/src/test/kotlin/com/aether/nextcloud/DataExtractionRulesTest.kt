package com.aether.nextcloud

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `allowBackup="false"` does not stop device-to-device transfer on Android 12+; only `dataExtractionRules` does. These
 * read the checked-in files because a missing reference or domain silently re-enables copying the account database.
 */
class DataExtractionRulesTest {
    private val rules = File("src/main/res/xml/data_extraction_rules.xml").readText()
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    private val domains = listOf("root", "file", "database", "sharedpref", "external", "device_root", "device_file", "device_database", "device_sharedpref")

    private fun section(name: String): String = Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(rules)?.groupValues?.get(1)
        ?: error("no <$name> section")

    @Test
    fun theManifestPointsAtTheRulesAndStillDisablesBackup() {
        assertTrue(manifest.contains("""android:dataExtractionRules="@xml/data_extraction_rules""""))
        assertTrue(manifest.contains("""android:allowBackup="false""""))
    }

    @Test
    fun everyDomainIsExcludedFromCloudBackupAndFromDeviceTransfer() {
        for (name in listOf("cloud-backup", "device-transfer")) {
            val excluded = Regex("""<exclude\s+domain="([a-z_]+)"""").findAll(section(name)).map { it.groupValues[1] }.toSet()
            assertEquals("$name must exclude every domain", domains.toSet(), excluded)
            assertTrue("$name must not include anything", !section(name).contains("<include"))
        }
    }
}

package com.livehub.app

import android.app.Application
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * The credential files must not leave the device through Auto Backup.
 *
 * `files/datastore/` is where `livehub_settings`, `livehub_bilibili` and `livehub_douyin` live —
 * the sync secret, the secret of every paired device, and two sets of session cookies. Cloud
 * backup would copy all of it to the user's Drive account, out of reach of the platform's own
 * "log out everywhere". The rules are declared in the manifest, so this test reads the resources
 * the manifest names rather than the manifest itself (the wiring is checked on a device, in
 * `AppWiringTest`, where the PackageManager is real).
 *
 * It also pins the one thing that would silently make the rules moot: `allowBackup="false"` would
 * be a different answer to the same question (nothing is backed up at all), and neither the rules
 * nor their files would matter any more.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRulesTest {

  private val context get() = ApplicationProvider.getApplicationContext<Application>()

  /**
   * Every `<tag domain=… path=…>` in a rules file, as `domain:path`.
   *
   * Both formats are flat lists of `<include>`/`<exclude>` elements, so walking the tags is the
   * whole structure there is to check.
   */
  private fun rules(resourceId: Int, tag: String): List<String> {
    val parser = context.resources.getXml(resourceId)
    val found = mutableListOf<String>()
    try {
      while (parser.next() != XmlPullParser.END_DOCUMENT) {
        if (parser.eventType != XmlPullParser.START_TAG || parser.name != tag) continue
        val domain = parser.getAttributeValue(null, "domain").orEmpty()
        val path = parser.getAttributeValue(null, "path").orEmpty()
        found += "$domain:$path"
      }
    } finally {
      parser.close()
    }
    return found
  }

  @Test
  fun `cloud backup leaves the datastore behind`() {
    val excluded = rules(R.xml.data_extraction_rules, "exclude")

    assertTrue("cloud backup must exclude the credential files, got $excluded", excluded.any {
      it.startsWith("file:datastore")
    })
  }

  /** A phone upgrade still carries the app data; the rules must not have excluded that too. */
  @Test
  fun `device transfer still carries the datastore`() {
    val included = rules(R.xml.data_extraction_rules, "include")

    assertTrue("device transfer should keep the app data, got $included", included.any {
      it.startsWith("file:datastore")
    })
  }

  /** Android 11 and older read `fullBackupContent` instead; it has to say the same thing. */
  @Test
  fun `the pre-31 rules exclude the datastore too`() {
    val excluded = rules(R.xml.backup_rules, "exclude")

    assertTrue("the old format must exclude it as well, got $excluded", excluded.any {
      it.startsWith("file:datastore")
    })
  }

  @Test
  fun `backup is still enabled, so the rules are the thing doing the work`() {
    val info = context.applicationInfo

    assertTrue(
      "with allowBackup=false the rules would be decoration; this app chose rules over no backup",
      info.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0,
    )
  }

  /**
   * Each file is the schema its attribute expects.
   *
   * The platform ignores a rules file whose root element it does not recognise — silently, with
   * only a log line — so pasting `backup_rules.xml`'s body into `data_extraction_rules.xml` would
   * put the credentials straight back into the cloud backup while both files still looked right.
   * The attribute itself cannot be checked from here (`ApplicationInfo.dataExtractionRulesRes` is
   * `@hide`), but it cannot go missing either: `@xml/…` that does not resolve fails the build.
   */
  @Test
  fun `each file declares the root element its attribute expects`() {
    assertEquals("data-extraction-rules", rootTag(R.xml.data_extraction_rules))
    assertEquals("full-backup-content", rootTag(R.xml.backup_rules))
  }

  private fun rootTag(resourceId: Int): String {
    val parser = context.resources.getXml(resourceId)
    try {
      while (parser.next() != XmlPullParser.END_DOCUMENT) {
        if (parser.eventType == XmlPullParser.START_TAG) return parser.name
      }
    } finally {
      parser.close()
    }
    return ""
  }
}

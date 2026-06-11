package it.allard.multistream.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateParseTest {

    private val payload = """
        {
          "tag_name": "v0.3.0",
          "html_url": "https://github.com/x/releases/v0.3.0",
          "assets": [
            {"name": "notes.txt", "browser_download_url": "https://x/notes.txt"},
            {"name": "multistream.apk", "browser_download_url": "https://x/multistream.apk", "id": 9}
          ]
        }
    """.trimIndent()

    @Test fun returnsApkAssetWhenNewer() {
        val info = parseUpdate(payload, "0.2.9")
        assertEquals("0.3.0", info?.version)
        assertEquals("https://x/multistream.apk", info?.apkUrl)
    }

    @Test fun nullWhenNotNewer() = assertNull(parseUpdate(payload, "0.3.0"))

    @Test fun nullWhenNoApkAsset() {
        val noApk = """{"tag_name":"v9.9.9","assets":[{"name":"notes.txt","browser_download_url":"u"}]}"""
        assertNull(parseUpdate(noApk, "0.2.0"))
    }

    @Test fun nullWhenNewerButNoAssets() =
        assertNull(parseUpdate("""{"tag_name":"v9.9.9","prerelease":false}""", "0.2.0"))
}

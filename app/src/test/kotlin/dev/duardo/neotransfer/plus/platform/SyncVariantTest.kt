package dev.duardo.neotransfer.plus.platform

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import dev.duardo.neotransfer.plus.BuildConfig
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Each variant guards sync with its own signature permission and reads its own NeoTransfer, so `debug` and `preview`
 * install next to the release; the release keeps the published names.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SyncVariantTest {
    private val suffix = BuildConfig.APPLICATION_ID.removePrefix("dev.duardo.neotransfer.plus")
    private val permission = "dev.duardo.neotransfer.permission.SYNC_CONTACTS$suffix"

    @Test fun theReleaseKeepsThePublishedPeer() {
        assertEquals("dev.duardo.neotransfer.sync", peerAuthority(""))
    }

    @Test fun eachVariantReadsTheSameVariantOfNeoTransfer() {
        assertTrue(suffix == ".debug" || suffix == ".preview", suffix)
        assertEquals(suffix, BuildConfig.SYNC_SUFFIX)
        assertEquals("content://dev.duardo.neotransfer$suffix.sync", SyncProvider.PEER)
    }

    @Test fun theMergedManifestDeclaresAndUsesTheVariantsPermission() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.packageManager
        val info = manager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        assertTrue(info.permissions.orEmpty().any { it.name == permission && it.protectionLevel and 0xf == 2 }, "declared as signature")
        assertTrue(permission in info.requestedPermissions.orEmpty())
        val provider = manager.getProviderInfo(ComponentName(context, SyncProvider::class.java), 0)
        assertEquals(permission, provider.readPermission)
        assertEquals("${BuildConfig.APPLICATION_ID}.sync", provider.authority)
    }
}

package com.aether.nextcloud.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aether.nextcloud.R
import com.aether.nextcloud.data.BackupStats
import com.aether.nextcloud.data.local.entity.DocumentEntity
import com.aether.nextcloud.data.local.entity.TransferDirection
import com.aether.nextcloud.data.local.entity.TransferState
import com.aether.nextcloud.data.prefs.BackupAlbum
import com.aether.nextcloud.data.prefs.BackupSettings
import com.aether.nextcloud.dav.UserInfo
import com.aether.nextcloud.ui.dashboard.CameraSection
import com.aether.nextcloud.ui.dashboard.DashboardUiState
import com.aether.nextcloud.ui.dashboard.PinnedSection
import com.aether.nextcloud.ui.dashboard.StorageHero
import com.aether.nextcloud.ui.dashboard.StorageUsage
import com.aether.nextcloud.ui.dashboard.TransfersSection
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DashboardSectionsTest {
    @get:Rule val compose = createComposeRule()

    // region storage

    @Test
    fun storageShowsThePercentageUsedAndTheQuota() {
        val state = DashboardUiState(quota = UserInfo("Alice", usedBytes = 25_000_000, freeBytes = 75_000_000, totalBytes = 100_000_000))
        compose.show { StorageHero(state, onRetry = {}) }

        // The gauge's own text is replaced by one description for screen readers, so that is what is findable.
        compose.onNodeWithContentDescription(str(R.string.storage_percent_used, 25)).assertExists()
    }

    @Test
    fun unlimitedQuotaShowsAnInfinitySignInsteadOfAPercentage() {
        val state = DashboardUiState(quota = UserInfo("Alice", usedBytes = 1_000_000, freeBytes = null, totalBytes = null))
        compose.show { StorageHero(state, onRetry = {}) }

        compose.onNodeWithText(str(R.string.storage_used_unlimited)).assertIsDisplayed()
    }

    @Test
    fun aFailedRefreshOverAKnownQuotaKeepsTheNumbersAndHidesTheGaugeDescription() {
        val state = DashboardUiState(quota = UserInfo("Alice", 10_000_000, 90_000_000, 100_000_000), quotaError = "offline")
        compose.show { StorageHero(state, onRetry = {}) }

        compose.onNodeWithText(str(R.string.storage_title)).assertIsDisplayed()
        compose.onAllNodesWithText(str(R.string.storage_load_failed)).assertCountEquals(0)
    }

    @Test
    fun whileTheQuotaIsLoadingTheCardSaysSo() {
        compose.show { StorageHero(DashboardUiState(), onRetry = {}) }

        compose.onNodeWithText(str(R.string.storage_loading)).assertIsDisplayed()
    }

    @Test
    fun aFailedLookupIsAOneLineNoticeWithRetry() {
        var retried = 0
        compose.show { StorageHero(DashboardUiState(quotaError = "Not found"), onRetry = { retried++ }) }

        compose.onNodeWithText(str(R.string.storage_load_failed)).assertIsDisplayed()
        compose.onNodeWithText("Not found").assertIsDisplayed()
        compose.onNodeWithText(str(R.string.action_retry)).performClick()
        assertEquals(1, retried)
    }

    /** A spinning badge animates forever, so these tests step the clock by hand instead of waiting for idle. */
    private fun showAnimated(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.show(content)
        compose.mainClock.advanceTimeBy(1_000)
    }

    @Test
    fun activeTransfersReplaceThePercentageWithTheirCount() {
        val state = DashboardUiState(
            quota = UserInfo("Alice", 10, 90, 100),
            activeTransfers = listOf(transfer("/a", TransferState.RUNNING), transfer("/b", TransferState.RUNNING)),
        )
        showAnimated { StorageHero(state, onRetry = {}) }

        compose.onNodeWithContentDescription(context.resources.getQuantityString(R.plurals.transfers_active, 2, 2)).assertExists()
    }

    @Test
    fun pinnedAndCacheSizesAppearAsChipsOnlyWhenNotEmpty() {
        val state = DashboardUiState(quota = UserInfo("Alice", 10, 90, 100), storage = StorageUsage(cacheBytes = 0, pinnedBytes = 5_000_000))
        compose.show { StorageHero(state, onRetry = {}) }

        compose.onNodeWithText(str(R.string.storage_pinned, android.text.format.Formatter.formatShortFileSize(context, 5_000_000))).assertIsDisplayed()
        compose.onAllNodesWithText(str(R.string.storage_cache, "0 B")).assertCountEquals(0)
    }

    // endregion

    // region pinned

    @Test
    fun anEmptyPinnedTabExplainsHowToPin() {
        compose.show { PinnedSection(emptyList(), onUnpin = {}) }

        compose.onNodeWithText(str(R.string.pinned_empty_title)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.pinned_empty_body)).assertIsDisplayed()
    }

    @Test
    fun eachPinnedItemSaysWhereItStands() {
        val items = listOf(
            file("/ready.txt", etag = "e", cachedEtag = "e", localPath = "/x"),
            file("/stale.txt", etag = "new", cachedEtag = "old", localPath = "/x"),
            file("/waiting.txt"),
            file("/Synced", etag = "e", cachedEtag = "e", dir = true),
            file("/Syncing", etag = "e", cachedEtag = null, dir = true),
        )
        compose.show { PinnedSection(items, onUnpin = {}) }

        compose.onNodeWithText(str(R.string.pinned_section_title)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.pinned_status_update_waiting)).assertExists()
        compose.onNodeWithText(str(R.string.pinned_status_waiting)).assertExists()
        compose.onNodeWithText(str(R.string.pinned_status_folder_syncing)).assertExists()
        compose.onAllNodesWithText(str(R.string.action_unpin)).assertCountEquals(5)
    }

    @Test
    fun aPinnedAccountRootIsShownAsASlash() {
        compose.show { PinnedSection(listOf(file("/", dir = true).copy(name = "")), onUnpin = {}) }

        compose.onAllNodesWithText("/", useUnmergedTree = true).assertCountEquals(2)
    }

    @Test
    fun unpinReportsWhichItemWasTapped() {
        val unpinned = mutableListOf<DocumentEntity>()
        val items = listOf(file("/one.txt"), file("/two.txt"))
        compose.show { PinnedSection(items, onUnpin = { unpinned += it }) }

        compose.onAllNodesWithText(str(R.string.action_unpin))[1].performClick()

        assertEquals(listOf("/two.txt"), unpinned.map { it.path })
    }

    // endregion

    // region transfers

    @Test
    fun anEmptyTransferListSaysThereAreNone() {
        compose.show { TransfersSection(emptyList(), emptyList()) }

        compose.onNodeWithText(str(R.string.transfers_empty_title)).assertIsDisplayed()
    }

    @Test
    fun activeAndRecentTransfersAreGroupedUnderTheirHeadings() {
        showAnimated {
            Column {
                TransfersSection(
                    active = listOf(transfer("/Docs/big.zip", TransferState.RUNNING, done = 1_000_000, total = 4_000_000)),
                    recent = listOf(
                        transfer("/done.txt", TransferState.COMPLETED, TransferDirection.DOWNLOAD),
                        transfer("/bad.txt", TransferState.FAILED, error = "Your Nextcloud storage is full. Free up space and try again."),
                        transfer("/queued.txt", TransferState.QUEUED),
                    ),
                )
            }
        }

        compose.onNodeWithText(str(R.string.transfers_in_progress)).assertIsDisplayed()
        compose.onNodeWithText("Recent").assertIsDisplayed()
        // Each group starts as a summary; the per-file rows only appear once it is tapped open.
        compose.onNodeWithText("big.zip").assertDoesNotExist()
        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.transfers_active, 1, 1)).performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.transfer_summary_downloaded, 1, 1)).assertIsDisplayed()
        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.transfer_summary_failed, 1, 1)).assertIsDisplayed()
        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.transfer_summary_downloaded, 1, 1)).performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("big.zip").assertIsDisplayed()
        compose.onNodeWithText("Your Nextcloud storage is full. Free up space and try again.").assertIsDisplayed()
        compose.onNodeWithText(str(R.string.transfer_waiting_network)).assertIsDisplayed()
        compose.onAllNodes(hasContentDescription(str(R.string.transfer_download))).assertCountEquals(1)
    }

    @Test
    fun aConflictRowOffersToShowTheFolder() {
        compose.show { TransfersSection(emptyList(), listOf(transfer("/Docs/report (Conflict 1).txt", TransferState.CONFLICT))) }

        compose.onNodeWithText(str(R.string.transfer_summary_nothing_finished)).performClick()
        compose.onNodeWithText(str(R.string.transfer_show_folder)).assertIsDisplayed()
    }

    @Test
    fun showingTheFolderOpensTheSystemFilesAppInsideIt() {
        androidx.test.espresso.intent.Intents.init()
        try {
            androidx.test.espresso.intent.Intents.intending(androidx.test.espresso.intent.matcher.IntentMatchers.hasAction(android.content.Intent.ACTION_VIEW))
                .respondWith(android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null))
            compose.show { TransfersSection(emptyList(), listOf(transfer("/Docs/report (Conflict 1).txt", TransferState.CONFLICT))) }

            compose.onNodeWithText(str(R.string.transfer_summary_nothing_finished)).performClick()
            compose.onNodeWithText(str(R.string.transfer_show_folder)).performClick()

            androidx.test.espresso.intent.Intents.intended(
                org.hamcrest.Matchers.allOf(
                    androidx.test.espresso.intent.matcher.IntentMatchers.hasAction(android.content.Intent.ACTION_VIEW),
                    androidx.test.espresso.intent.matcher.IntentMatchers.hasData(
                        android.provider.DocumentsContract.buildDocumentUri(com.aether.nextcloud.AetherConstants.AUTHORITY, "alice@cloud.example.com:/Docs"),
                    ),
                ),
            )
        } finally {
            androidx.test.espresso.intent.Intents.release()
        }
    }

    // endregion

    // region camera

    @Test
    fun cameraBackupThatIsOffInvitesSetup() {
        compose.show { CameraSection(DashboardUiState()) }

        compose.onNodeWithText(str(R.string.camera_off)).assertIsDisplayed()
        compose.onNodeWithContentDescription(str(R.string.action_settings)).assertIsDisplayed()
        compose.onNode(isToggleable() and hasContentDescription(str(R.string.camera_title))).assertIsOff()
    }

    @Test
    fun cameraBackupThatIsOnShowsProgressAlbumsAndTheSplit() {
        val state = DashboardUiState(
            backup = BackupSettings(enabled = true, accountName = alice.accountName, remoteFolder = "/Photos", albums = listOf(BackupAlbum(1, "Camera"), BackupAlbum(2, "Screenshots"))),
            backupStats = BackupStats(photoCount = 3, photoBytes = 3_000_000, videoCount = 1, videoBytes = 9_000_000, serverBytes = 20_000_000),
            lastBackupAt = System.currentTimeMillis() - 60_000,
        )
        compose.show { CameraSection(state) }

        compose.onNodeWithText(str(R.string.camera_backing_up)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.camera_in_folder, "/Photos")).assertIsDisplayed()
        compose.onNodeWithText("Camera, Screenshots").assertIsDisplayed()
        compose.onNode(isToggleable() and hasContentDescription(str(R.string.camera_title))).assertIsOn()
        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.backup_photos_legend, 3, 3, android.text.format.Formatter.formatShortFileSize(context, 3_000_000))).assertIsDisplayed()
        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.backup_videos_legend, 1, 1, android.text.format.Formatter.formatShortFileSize(context, 9_000_000))).assertIsDisplayed()
        compose.onNodeWithContentDescription(str(R.string.action_settings)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.camera_open_photos)).assertIsDisplayed()
    }

    @Test
    fun beforeAnythingIsUploadedTheTileSaysItIsWaiting() {
        compose.show { CameraSection(DashboardUiState(backup = BackupSettings(enabled = true, accountName = alice.accountName))) }

        compose.onNodeWithText(str(R.string.camera_waiting)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.albums_all)).assertIsDisplayed()
    }

    @Test
    fun moreThanThreeAlbumsAreSummarisedWithAPlusCount() {
        val albums = (1L..5L).map { BackupAlbum(it, "Album$it") }
        compose.show { CameraSection(DashboardUiState(backup = BackupSettings(enabled = true, accountName = alice.accountName, albums = albums))) }

        compose.onNodeWithText("Album1, Album2, Album3 +2").assertIsDisplayed()
    }

    // endregion
}

package com.aether.nextcloud.ui.dashboard

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.SavedStateHandle
import com.aether.nextcloud.AetherConstants
import com.aether.nextcloud.auth.AccountCleaner
import com.aether.nextcloud.auth.AccountRepository
import com.aether.nextcloud.auth.AetherCredentials
import com.aether.nextcloud.auth.AuthIssues
import com.aether.nextcloud.core.AetherError
import com.aether.nextcloud.core.AetherResult
import com.aether.nextcloud.data.AvatarRepository
import com.aether.nextcloud.data.BackupStats
import com.aether.nextcloud.data.CameraBackupStatsRepository
import com.aether.nextcloud.data.DocumentRepository
import com.aether.nextcloud.data.QuotaRepository
import com.aether.nextcloud.data.cache.DocumentDiskCache
import com.aether.nextcloud.data.local.AetherDatabase
import com.aether.nextcloud.data.local.dao.BackedUpMediaDao
import com.aether.nextcloud.data.local.dao.DocumentDao
import com.aether.nextcloud.data.local.dao.TransferDao
import com.aether.nextcloud.data.local.entity.DocumentEntity
import com.aether.nextcloud.data.local.entity.TransferDirection
import com.aether.nextcloud.data.local.entity.TransferEntity
import com.aether.nextcloud.data.prefs.BackupSettings
import com.aether.nextcloud.data.prefs.SettingsRepository
import com.aether.nextcloud.dav.UserInfo
import com.aether.nextcloud.provider.DocumentId
import com.aether.nextcloud.sync.RetryOutcome
import com.aether.nextcloud.sync.SyncScheduler
import com.aether.nextcloud.sync.TransferRetrier
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val alice = AetherCredentials("alice@h", "https://h", "alice", "pw", "Alice")
    private val bob = AetherCredentials("bob@h", "https://h", "bob", "pw", "Bob")

    private val context = mockk<Context>(relaxed = true)
    private val accountRepository = mockk<AccountRepository>(relaxed = true)
    private val cleaner = mockk<AccountCleaner>(relaxed = true)
    private val quotas = mockk<QuotaRepository>()
    private val avatars = mockk<AvatarRepository>()
    private val documents = mockk<DocumentRepository>(relaxed = true)
    private val diskCache = mockk<DocumentDiskCache>(relaxed = true)
    private val settings = mockk<SettingsRepository>()
    private val cameraStats = mockk<CameraBackupStatsRepository>(relaxed = true)
    private val scheduler = mockk<SyncScheduler>(relaxed = true)
    private val retrier = mockk<TransferRetrier>()
    private val authIssues = mockk<AuthIssues>()

    private val active = MutableStateFlow<List<TransferEntity>>(emptyList())
    private val recent = MutableStateFlow<List<TransferEntity>>(emptyList())
    private val pinned = MutableStateFlow<List<DocumentEntity>>(emptyList())
    private val issues = MutableStateFlow<Set<String>>(emptySet())
    private val snapshots = MutableStateFlow<Map<String, QuotaRepository.Snapshot>>(emptyMap())
    private val info = UserInfo("Alice A", usedBytes = 1, freeBytes = 9, totalBytes = 10)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { accountRepository.observeAccounts() } returns flowOf(listOf(alice, bob))
        every { quotas.snapshots } returns snapshots
        coEvery { quotas.refresh(any()) } returns AetherResult.Success(info)
        coEvery { avatars.cached(any()) } returns null
        coEvery { avatars.refresh(any()) } returns null
        every { settings.backup } returns flowOf(BackupSettings())
        every { settings.directShareEnabled } returns flowOf(true)
        every { settings.recentFolders } returns flowOf(emptyList())
        every { cameraStats.observe(any()) } returns flowOf(BackupStats())
        every { scheduler.observeBackupRunning() } returns flowOf(false)
        every { authIssues.accounts } returns issues
        every { diskCache.cacheSizeBytes() } returns 100
        every { diskCache.pinnedSizeBytes() } returns 200
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(DocumentsContract::class)
    }

    private val transferDao = mockk<TransferDao> {
        every { observeActive() } returns active
        every { observeRecent(any()) } returns recent
        every { clearCompleted(any()) } returns Unit
    }

    private fun database(): AetherDatabase {
        val documentDao = mockk<DocumentDao> { every { observePinnedRoots() } returns pinned }
        val backedUp = mockk<BackedUpMediaDao> {
            every { observeCount(any()) } returns flowOf(3)
            every { observeLastBackupAt(any()) } returns flowOf(42L)
        }
        return mockk {
            every { transferDao() } returns transferDao
            every { documentDao() } returns documentDao
            every { backedUpMediaDao() } returns backedUp
        }
    }

    private fun viewModel(saved: SavedStateHandle = SavedStateHandle()) =
        DashboardViewModel(context, saved, accountRepository, cleaner, quotas, avatars, documents, diskCache, settings, cameraStats, scheduler, retrier, authIssues, database())

    private fun TestScope.subscribed(vm: DashboardViewModel) = vm.also { backgroundScope.launch(dispatcher) { it.state.collect {} } }

    private fun transfer(account: String, path: String) = TransferEntity(accountName = account, remotePath = path, direction = TransferDirection.DOWNLOAD, createdAt = 1)

    private fun entity(id: DocumentId) = DocumentEntity(id.toString(), id.accountName, id.path, id.parent?.toString(), id.name, false, "text/plain", 1, 1, "e", "1", "RGDNVW", false)

    @Test
    fun `the first account is selected until the user picks another`() = runTest(dispatcher) {
        val vm = subscribed(viewModel())
        assertEquals("alice@h", vm.state.value.selected?.accountName)

        vm.selectAccount("bob@h")

        assertEquals("bob@h", vm.state.value.selected?.accountName)
        assertEquals(listOf(alice, bob), vm.state.value.accounts)
    }

    @Test
    fun `the chosen account and tab survive recreation`() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        subscribed(viewModel(saved)).also { it.selectAccount("bob@h"); it.selectTab(DashboardTab.Transfers) }

        val restored = subscribed(viewModel(saved))

        assertEquals("bob@h", restored.state.value.selected?.accountName)
        assertEquals(DashboardTab.Transfers, restored.state.value.tab)
    }

    @Test
    fun `an unknown saved tab falls back to pinned`() = runTest(dispatcher) {
        val vm = subscribed(viewModel(SavedStateHandle(mapOf("tab" to "Bogus"))))

        assertEquals(DashboardTab.Pinned, vm.state.value.tab)
    }

    @Test
    fun `transfers and pins are limited to the selected account`() = runTest(dispatcher) {
        active.value = listOf(transfer("alice@h", "/a"), transfer("bob@h", "/b"))
        recent.value = listOf(transfer("bob@h", "/c"))
        pinned.value = listOf(entity(DocumentId("alice@h", "/p")), entity(DocumentId("bob@h", "/q")))
        val vm = subscribed(viewModel())

        assertEquals(listOf("/a"), vm.state.value.activeTransfers.map { it.remotePath })
        assertTrue(vm.state.value.recentTransfers.isEmpty())
        assertEquals(listOf("/p"), vm.state.value.pinned.map { it.path })

        vm.selectAccount("bob@h")

        assertEquals(listOf("/b"), vm.state.value.activeTransfers.map { it.remotePath })
        assertEquals(listOf("/c"), vm.state.value.recentTransfers.map { it.remotePath })
    }

    @Test
    fun `accounts the server rejected are flagged for sign in`() = runTest(dispatcher) {
        val vm = subscribed(viewModel())
        assertTrue(vm.state.value.needsSignIn.isEmpty())

        issues.value = setOf("bob@h")

        assertEquals(listOf(bob), vm.state.value.needsSignIn)
    }

    @Test
    fun `refreshing shows quota, remembers the display name and measures storage`() = runTest(dispatcher) {
        snapshots.value = mapOf("alice@h" to QuotaRepository.Snapshot(info, 1))
        val vm = subscribed(viewModel())

        assertEquals(info, vm.state.value.quota)
        assertNull(vm.state.value.quotaError)
        assertEquals(StorageUsage(100, 200), vm.state.value.storage)
        verify { accountRepository.setDisplayName("alice@h", "Alice A") }
    }

    @Test
    fun `a failed quota lookup becomes a message instead of a crash`() = runTest(dispatcher) {
        coEvery { quotas.refresh(any()) } returns AetherResult.Failure(AetherError.Network(IOException("offline")))

        val vm = subscribed(viewModel())

        assertEquals("offline", vm.state.value.quotaError)
        verify(exactly = 0) { accountRepository.setDisplayName(any(), any()) }
    }

    @Test
    fun `the server size of the backup folder is only fetched while backup is on`() = runTest(dispatcher) {
        subscribed(viewModel())
        coVerify(exactly = 0) { cameraStats.refreshServerSize(any(), any()) }

        every { settings.backup } returns flowOf(BackupSettings(enabled = true, accountName = "alice@h", remoteFolder = "/Photos"))
        subscribed(viewModel())

        coVerify { cameraStats.refreshServerSize(alice, "/Photos") }
    }

    @Test
    fun `unpinning releases the pin and re-measures storage`() = runTest(dispatcher) {
        val doc = entity(DocumentId("alice@h", "/p.txt"))
        val vm = subscribed(viewModel())

        vm.unpin(doc)

        coVerify { documents.setPinned(DocumentId("alice@h", "/p.txt"), false) }
    }

    @Test
    fun `undoing an unpin pins again and starts a sync`() = runTest(dispatcher) {
        val doc = entity(DocumentId("alice@h", "/p.txt"))
        val vm = subscribed(viewModel())

        vm.repin(doc)

        coVerify { documents.setPinned(DocumentId("alice@h", "/p.txt"), true) }
        verify { scheduler.syncPinsNow() }
    }

    @Test
    fun `pinning from the picker ignores other providers and stops on failed lookups`() = runTest(dispatcher) {
        mockkStatic(DocumentsContract::class)
        every { DocumentsContract.isTreeUri(any()) } returns false
        val ids = HashMap<Uri, String>()
        every { DocumentsContract.getDocumentId(any()) } answers { ids.getValue(firstArg()) }
        fun uri(authority: String, id: String): Uri {
            val uri = mockk<Uri>()
            every { uri.authority } returns authority
            ids[uri] = id
            return uri
        }
        every { accountRepository.credentials("alice@h") } returns alice
        every { accountRepository.credentials("ghost@h") } returns null
        val good = DocumentId("alice@h", "/ok.txt")
        val bad = DocumentId("alice@h", "/missing.txt")
        coEvery { documents.refreshDocument(alice, good) } returns AetherResult.Success(entity(good))
        coEvery { documents.refreshDocument(alice, bad) } returns AetherResult.Failure(AetherError.NotFound)
        val vm = subscribed(viewModel())

        vm.pin(
            listOf(
                uri("com.android.externalstorage.documents", "alice@h:/foreign.txt"),
                uri(AetherConstants.AUTHORITY, "not a document id"),
                uri(AetherConstants.AUTHORITY, "ghost@h:/nobody.txt"),
                uri(AetherConstants.AUTHORITY, bad.toString()),
                uri(AetherConstants.AUTHORITY, good.toString()),
            ),
        )

        coVerify(exactly = 1) { documents.setPinned(any(), true) }
        coVerify { documents.setPinned(good, true) }
        verify(exactly = 1) { scheduler.syncPinsNow() }
    }

    @Test
    fun `a pick that pins nothing does not start a sync`() = runTest(dispatcher) {
        val vm = subscribed(viewModel())

        vm.pin(emptyList())

        verify(exactly = 0) { scheduler.syncPinsNow() }
    }

    @Test
    fun `signing out hands the account to the cleaner`() = runTest(dispatcher) {
        subscribed(viewModel()).signOut("alice@h")

        verify { cleaner.remove("alice@h") }
    }

    @Test
    fun `clearing the cache empties it and re-measures`() = runTest(dispatcher) {
        val vm = subscribed(viewModel())

        vm.clearCache()

        verify(timeout = 2_000) { diskCache.clearCache() }
    }

    @Test
    fun `clearing finished transfers only touches the selected account`() = runTest(dispatcher) {
        val vm = subscribed(viewModel())
        vm.selectAccount("bob@h")

        vm.clearFinishedTransfers()

        verify(timeout = 2_000) { transferDao.clearCompleted("bob@h") }
        verify(exactly = 0) { transferDao.clearCompleted("alice@h") }
    }

    @Test
    fun `retrying failed transfers acts on the selected account and reports the outcome`() = runTest(dispatcher) {
        coEvery { retrier.retryFailed("bob@h") } returns RetryOutcome(retried = 2, skipped = 1)
        val vm = subscribed(viewModel())
        vm.selectAccount("bob@h")
        var outcome: RetryOutcome? = null

        vm.retryFailedTransfers { outcome = it }

        assertEquals(RetryOutcome(2, 1), outcome)
        coVerify(exactly = 0) { retrier.retryFailed("alice@h") }
    }

    private fun backUpNowEvents(vm: DashboardViewModel) = mutableListOf<BackUpNowEvent>().also { events -> vm.backUpNow { events += it } }

    @Test
    fun `back up now says so when backup is off and starts nothing`() = runTest(dispatcher) {
        val events = backUpNowEvents(subscribed(viewModel()))

        assertEquals(listOf(BackUpNowEvent.TurnedOff), events)
        coVerify(exactly = 0) { scheduler.backUpNowAndCount() }
    }

    @Test
    fun `back up now says so when the photo permission is missing and starts nothing`() = runTest(dispatcher) {
        every { settings.backup } returns flowOf(BackupSettings(enabled = true, accountName = "alice@h"))
        every { scheduler.canReadMedia() } returns false

        val events = backUpNowEvents(subscribed(viewModel()))

        assertEquals(listOf(BackUpNowEvent.NoPermission), events)
        coVerify(exactly = 0) { scheduler.backUpNowAndCount() }
    }

    @Test
    fun `back up now reports nothing new only when the run found nothing to upload`() = runTest(dispatcher) {
        every { settings.backup } returns flowOf(BackupSettings(enabled = true, accountName = "alice@h"))
        every { scheduler.canReadMedia() } returns true
        coEvery { scheduler.backUpNowAndCount() } returnsMany listOf(0, 3, null)
        val vm = subscribed(viewModel())

        assertEquals(listOf(BackUpNowEvent.Started, BackUpNowEvent.NothingNew), backUpNowEvents(vm))
        assertEquals("uploads were attempted", listOf(BackUpNowEvent.Started), backUpNowEvents(vm))
        assertEquals("a run that did not succeed says nothing more", listOf(BackUpNowEvent.Started), backUpNowEvents(vm))
    }

    private fun pauseSetup(initial: BackupSettings): MutableList<BackupSettings> {
        val saved = mutableListOf<BackupSettings>()
        every { settings.backup } returns flowOf(initial)
        coEvery { settings.updateBackup(any()) } coAnswers { firstArg<(BackupSettings) -> BackupSettings>()(initial).also { saved += it } }
        return saved
    }

    @Test
    fun `pausing backup keeps the settings, applies the schedule and does not scan`() = runTest(dispatcher) {
        val on = BackupSettings(enabled = true, accountName = "alice@h", remoteFolder = "/Photos")
        val saved = pauseSetup(on)

        subscribed(viewModel()).setBackupPaused(true)

        assertEquals(listOf(on.copy(paused = true)), saved)
        verify { scheduler.applyBackup(on.copy(paused = true)) }
        verify(exactly = 0) { scheduler.backUpNow() }
    }

    @Test
    fun `resuming backup applies the schedule and scans straight away`() = runTest(dispatcher) {
        val paused = BackupSettings(enabled = true, accountName = "alice@h", paused = true)
        val saved = pauseSetup(paused)

        subscribed(viewModel()).setBackupPaused(false)

        assertEquals(listOf(paused.copy(paused = false)), saved)
        verify { scheduler.applyBackup(paused.copy(paused = false)) }
        verify(exactly = 1) { scheduler.backUpNow() }
    }

    @Test
    fun `pausing does nothing when backup is off or already in that state`() = runTest(dispatcher) {
        val saved = pauseSetup(BackupSettings())
        subscribed(viewModel()).setBackupPaused(true)

        pauseSetup(BackupSettings(enabled = true, accountName = "alice@h", paused = true)).also { saved += it }
        subscribed(viewModel()).setBackupPaused(true)

        assertEquals(emptyList<BackupSettings>(), saved)
        verify(exactly = 0) { scheduler.applyBackup(any()) }
    }

    @Test
    fun `back up now says backup is paused and starts nothing`() = runTest(dispatcher) {
        every { settings.backup } returns flowOf(BackupSettings(enabled = true, accountName = "alice@h", paused = true))
        every { scheduler.canReadMedia() } returns true

        val events = backUpNowEvents(subscribed(viewModel()))

        assertEquals(listOf(BackUpNowEvent.Paused), events)
        coVerify(exactly = 0) { scheduler.backUpNowAndCount() }
    }

    @Test
    fun `turning direct share off is saved`() = runTest(dispatcher) {
        coEvery { settings.setDirectShareEnabled(any()) } returns Unit
        val vm = subscribed(viewModel())

        vm.setDirectShare(false)

        coVerify { settings.setDirectShareEnabled(false) }
    }
}

package com.aether.nextcloud.ui.backup

import android.net.Uri
import android.provider.DocumentsContract
import com.aether.nextcloud.AetherConstants
import com.aether.nextcloud.auth.AccountRepository
import com.aether.nextcloud.auth.AetherCredentials
import com.aether.nextcloud.data.BackupStats
import com.aether.nextcloud.data.CameraBackupStatsRepository
import com.aether.nextcloud.data.MediaAlbumRepository
import com.aether.nextcloud.data.local.AetherDatabase
import com.aether.nextcloud.data.local.dao.BackedUpMediaDao
import com.aether.nextcloud.data.prefs.BackupAlbum
import com.aether.nextcloud.data.prefs.BackupSettings
import com.aether.nextcloud.data.prefs.SettingsRepository
import com.aether.nextcloud.sync.SyncScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CameraBackupViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val backup = MutableStateFlow(BackupSettings())
    private val settings = mockk<SettingsRepository>()
    private val scheduler = mockk<SyncScheduler>(relaxed = true)
    private val stats = mockk<CameraBackupStatsRepository>(relaxed = true)
    private val albums = mockk<MediaAlbumRepository>()
    private val accountRepository = mockk<AccountRepository>()
    private val dao = mockk<BackedUpMediaDao>()
    private val database = mockk<AetherDatabase> { every { backedUpMediaDao() } returns dao }
    private val alice = AetherCredentials("alice@h", "https://h", "alice", "pw", "Alice")
    private val bob = AetherCredentials("bob@h", "https://h", "bob", "pw", "Bob")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { settings.backup } returns backup
        coEvery { settings.updateBackup(any()) } coAnswers {
            backup.value = firstArg<(BackupSettings) -> BackupSettings>().invoke(backup.value)
            backup.value
        }
        every { scheduler.observeBackupRunning() } returns flowOf(false)
        every { stats.observe(any()) } returns flowOf(BackupStats())
        every { dao.observeCount(any()) } returns flowOf(0)
        every { dao.observeAlbumUploads(any()) } returns flowOf(emptyList())
        every { accountRepository.observeAccounts() } returns flowOf(listOf(alice, bob))
        every { accountRepository.credentials("alice@h") } returns alice
        every { accountRepository.credentials("bob@h") } returns bob
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(DocumentsContract::class)
    }

    private fun viewModel() = CameraBackupViewModel(settings, scheduler, stats, albums, accountRepository, database)

    /** Keeps [CameraBackupViewModel.state] subscribed so its `WhileSubscribed` flow runs. */
    private fun kotlinx.coroutines.test.TestScope.subscribe(vm: CameraBackupViewModel) {
        backgroundScope.launch(dispatcher) { vm.state.collect {} }
    }

    @Test
    fun `turning backup on picks the first signed-in account`() = runTest(dispatcher) {
        val vm = viewModel().also { subscribe(it) }

        vm.setEnabled(true)

        assertTrue(backup.value.enabled)
        assertEquals("alice@h", backup.value.accountName)
        coVerify { scheduler.applyBackup(match { it.enabled && it.accountName == "alice@h" }) }
    }

    @Test
    fun `backup cannot be turned on without an account`() = runTest(dispatcher) {
        every { accountRepository.observeAccounts() } returns flowOf(emptyList())
        val vm = viewModel().also { subscribe(it) }

        vm.setEnabled(true)

        assertFalse(backup.value.enabled)
        assertNull(backup.value.accountName)
    }

    @Test
    fun `turning backup on keeps an account already chosen`() = runTest(dispatcher) {
        backup.value = BackupSettings(accountName = "bob@h")
        val vm = viewModel().also { subscribe(it) }

        vm.setEnabled(true)

        assertEquals("bob@h", backup.value.accountName)
    }

    @Test
    fun `the destination folder is normalized and blank means the default`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.setFolder("Photos//Phone/./")
        assertEquals("/Photos/Phone", backup.value.remoteFolder)

        vm.setFolder("   ")
        assertEquals(BackupSettings.DEFAULT_FOLDER, backup.value.remoteFolder)
    }

    @Test
    fun `the simple toggles update settings and reschedule`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.setUnmeteredOnly(false)
        vm.setChargingOnly(true)
        vm.setIncludeVideos(false)
        vm.setAccount("bob@h")
        vm.setAlbums(listOf(BackupAlbum(1, "Camera")))

        with(backup.value) {
            assertFalse(unmeteredOnly)
            assertTrue(chargingOnly)
            assertFalse(includeVideos)
            assertEquals("bob@h", accountName)
            assertEquals(listOf(BackupAlbum(1, "Camera")), albums)
        }
        coVerify(exactly = 5) { scheduler.applyBackup(any()) }
    }

    @Test
    fun `a folder picked in Aether sets both the account and the folder`() = runTest(dispatcher) {
        mockkStatic(DocumentsContract::class)
        every { DocumentsContract.getTreeDocumentId(any()) } returns "bob@h:/Photos/Camera"
        val tree = mockk<Uri> { every { authority } returns AetherConstants.AUTHORITY }

        viewModel().onFolderPicked(tree)

        assertEquals("bob@h", backup.value.accountName)
        assertEquals("/Photos/Camera", backup.value.remoteFolder)
    }

    @Test
    fun `a folder from another provider or a cancelled picker changes nothing`() = runTest(dispatcher) {
        val before = backup.value
        val foreign = mockk<Uri> { every { authority } returns "com.android.externalstorage.documents" }
        val vm = viewModel()

        vm.onFolderPicked(foreign)
        vm.onFolderPicked(null)

        assertEquals(before, backup.value)
        coVerify(exactly = 0) { settings.updateBackup(any()) }
    }

    @Test
    fun `state reflects the backed up count and per album uploads for the chosen account`() = runTest(dispatcher) {
        backup.value = BackupSettings(accountName = "bob@h")
        every { dao.observeCount("bob@h") } returns flowOf(7)
        every { dao.observeAlbumUploads("bob@h") } returns flowOf(listOf(com.aether.nextcloud.data.local.dao.AlbumUploads(bucketId = 3L, count = 4)))
        val vm = viewModel().also { subscribe(it) }

        assertEquals(7, vm.state.value.backedUpCount)
        assertEquals(mapOf(3L to 4), vm.state.value.albumUploads)
        assertEquals(listOf(alice, bob), vm.state.value.accounts)
    }

    @Test
    fun `albums stay unloaded until requested`() = runTest(dispatcher) {
        coEvery { albums.albums(includeVideos = true) } returns emptyList()
        val vm = viewModel()
        assertNull(vm.albums.value)

        vm.loadAlbums()

        assertEquals(emptyList<Any>(), vm.albums.value)
    }
}

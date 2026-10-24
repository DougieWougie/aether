package com.aether.nextcloud.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aether.nextcloud.R
import com.aether.nextcloud.ui.dashboard.PickAetherDocuments
import com.aether.nextcloud.ui.dashboard.PickAetherFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PinPickersTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val first = Uri.parse("content://com.aether.nextcloud.documents/document/alice%40h%3A%2Fa.txt")
    private val second = Uri.parse("content://com.aether.nextcloud.documents/document/alice%40h%3A%2Fb.txt")

    @Test
    fun theFilePickerAsksForOpenableDocumentsOfAnyTypeAndAllowsSeveral() {
        val intent = PickAetherDocuments().createIntent(context, null)

        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertTrue(Intent.CATEGORY_OPENABLE in intent.categories)
        assertEquals("*/*", intent.type)
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
    }

    @Test
    fun theConfirmButtonReadsPin() {
        val intent = PickAetherDocuments().createIntent(context, null)

        assertEquals(context.getString(R.string.pick_prompt_pin), intent.getStringExtra(DocumentsContract.EXTRA_PROMPT))
    }

    @Test
    fun thePickerStartsAtTheAccountRootWhenGivenOne() {
        val root = Uri.parse("content://com.aether.nextcloud.documents/document/alice%40h%3A%2F")

        assertEquals(root, PickAetherDocuments().createIntent(context, root).getParcelableExtra<Uri>(DocumentsContract.EXTRA_INITIAL_URI))
        assertNull(PickAetherDocuments().createIntent(context, null).getParcelableExtra<Uri>(DocumentsContract.EXTRA_INITIAL_URI))
    }

    @Test
    fun severalPickedDocumentsComeBackFromTheClipData() {
        val result = Intent().apply {
            clipData = android.content.ClipData.newRawUri("docs", first).apply { addItem(android.content.ClipData.Item(second)) }
        }

        assertEquals(listOf(first, second), PickAetherDocuments().parseResult(Activity.RESULT_OK, result))
    }

    @Test
    fun aSinglePickedDocumentComesBackFromTheData() {
        assertEquals(listOf(first), PickAetherDocuments().parseResult(Activity.RESULT_OK, Intent().setData(first)))
    }

    @Test
    fun cancellingOrAnEmptyResultPicksNothing() {
        assertTrue(PickAetherDocuments().parseResult(Activity.RESULT_CANCELED, Intent().setData(first)).isEmpty())
        assertTrue(PickAetherDocuments().parseResult(Activity.RESULT_OK, null).isEmpty())
        assertTrue(PickAetherDocuments().parseResult(Activity.RESULT_OK, Intent()).isEmpty())
    }

    @Test
    fun theFolderPickerKeepsTheStandardTreeIntentAndLabelsTheButtonPin() {
        val intent = PickAetherFolder().createIntent(context, null)

        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, intent.action)
        assertEquals(context.getString(R.string.pick_prompt_pin), intent.getStringExtra(DocumentsContract.EXTRA_PROMPT))
    }

    @Test
    fun theFolderPickerReturnsTheChosenTreeOrNull() {
        val tree = Uri.parse("content://com.aether.nextcloud.documents/tree/alice%40h%3A%2FDocs")

        assertEquals(tree, PickAetherFolder().parseResult(Activity.RESULT_OK, Intent().setData(tree)))
        assertNull(PickAetherFolder().parseResult(Activity.RESULT_CANCELED, null))
    }
}

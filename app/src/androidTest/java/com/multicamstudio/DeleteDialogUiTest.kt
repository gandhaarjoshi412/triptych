package com.multicamstudio

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.multicamstudio.data.repo.DeletionPreview
import com.multicamstudio.features.sessions.DeleteLocalDialog
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeleteDialogUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun eligibleSessionShowsSummaryAndClearWording() {
        var confirmed = false
        compose.setContent {
            DeleteLocalDialog(
                previews = listOf(DeletionPreview("s1", "Business Shoot", fileCount = 8, bytes = 2_500_000_000, eligible = true, problems = emptyList())),
                onConfirm = { confirmed = true },
                onDismiss = {},
            )
        }
        compose.onNodeWithText("Business Shoot: 8 files, 2.50 GB").assertExists()
        compose.onNodeWithText("Google Drive copies are NOT deleted", substring = true).assertExists()
        compose.onNodeWithText("Delete local copies").assertIsEnabled().performClick()
        assertTrue(confirmed)
    }

    @Test
    fun ineligibleSessionCannotBeDeleted() {
        compose.setContent {
            DeleteLocalDialog(
                previews = listOf(DeletionPreview("s1", "Shoot", 3, 100, eligible = false, problems = listOf("rear_wide_001.mp4: PENDING"))),
                onConfirm = {},
                onDismiss = {},
            )
        }
        compose.onNodeWithText("Not eligible", substring = true).assertExists()
        compose.onNodeWithText("Delete local copies").assertIsNotEnabled()
    }
}

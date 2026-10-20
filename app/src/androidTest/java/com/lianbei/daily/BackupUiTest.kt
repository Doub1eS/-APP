package com.lianbei.daily

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class BackupUiTest {
    @get:Rule val compose=createComposeRule()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db:DailyDatabase
    private lateinit var file:File
    private lateinit var contentView:android.view.View
    @Before fun setup() {
        db=Room.inMemoryDatabaseBuilder(context,DailyDatabase::class.java).build()
        file=File.createTempFile("backup-ui-",".json",context.cacheDir)
        file.writeText("""{"version":1,"records":[{"id":"imported","kind":"NOTE","date":"2026-01-01","time":"12:00","title":"恢复的记录"}],"categories":[]}""")
        val owner=object:ActivityResultRegistryOwner {
            override val activityResultRegistry=object:ActivityResultRegistry() {
                override fun <I,O> onLaunch(requestCode:Int,contract:ActivityResultContract<I,O>,input:I,options:ActivityOptionsCompat?) {
                    dispatchResult(requestCode,Activity.RESULT_OK,Intent().setData(Uri.fromFile(file)))
                }
            }
        }
        compose.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) { LianbeiTheme {
            val view=LocalView.current
            SideEffect { contentView=view }
            BackupPanel(db.records())
        } } }
    }
    @After fun cleanup(){compose.waitForIdle();db.close();file.delete()}
    @Test fun importRequiresPreviewConfirmationAndRepeatedImportKeepsLocalEdit() {
        compose.onNodeWithText("导入备份").performClick()
        compose.waitUntil(5000){compose.onAllNodesWithText("确认合并恢复").fetchSemanticsNodes().isNotEmpty()}
        runBlocking { assertTrue(db.records().allRecords().isEmpty()) }
        compose.onNodeWithText("确认合并恢复").performClick()
        compose.waitUntil(5000){compose.onAllNodes(hasText("恢复完成",substring=true)).fetchSemanticsNodes().isNotEmpty()}
        runBlocking { val r=db.records().record("imported")!!;db.records().save(r.copy(title="本机更新")) }
        // Dialog dismissal updates Compose before the Android window returns input focus.
        // Wait for the real window before the second physical tap, not for an arbitrary delay.
        compose.waitUntil(5000){contentView.hasWindowFocus()}
        compose.onNodeWithText("导入备份").assertIsEnabled().performClick()
        compose.waitUntil(5000){compose.onAllNodesWithText("确认合并恢复").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("确认合并恢复").performClick()
        compose.waitUntil(5000){compose.onAllNodes(hasText("新增 0 项",substring=true)).fetchSemanticsNodes().isNotEmpty()}
        runBlocking{assertEquals("本机更新",db.records().record("imported")?.title);assertEquals(1,db.records().allRecords().size)}
    }
    @Test fun cancelledImportChangesNothingAndExportContainsCurrentDatabaseRows() {
        compose.onNodeWithText("导入备份").performClick()
        compose.waitUntil(5000){compose.onAllNodesWithText("取消").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("取消").performClick()
        runBlocking { assertTrue(db.records().allRecords().isEmpty());db.records().save(DailyRecord("now","NOTE","2026-01-01","12:00","新记录")) }
        compose.onNodeWithText("导出记录").performClick()
        compose.waitUntil(5000){compose.onAllNodes(hasText("备份已导出",substring=true)).fetchSemanticsNodes().isNotEmpty()}
        assertEquals("新记录",Backup.parse(file.readText()).records.single().title)
    }
}

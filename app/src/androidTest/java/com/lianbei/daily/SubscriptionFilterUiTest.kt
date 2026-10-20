package com.lianbei.daily

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.assertTrue
import java.time.LocalDate

class SubscriptionFilterUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: DailyDatabase
    private val visible = mutableStateOf(true)
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, DailyDatabase::class.java).build()
        runBlocking {
            listOf(
                Subscription("near", "本周会员", 100, "MONTH", LocalDate.now().plusDays(6).toString()),
                Subscription("far", "下周会员", 100, "MONTH", LocalDate.now().plusDays(7).toString()),
                Subscription("off", "停用会员", 100, "MONTH", LocalDate.now().toString(), active = false)
            ).forEach { db.records().saveSubscription(it) }
        }
        compose.setContent { LianbeiTheme { if (visible.value) SubscriptionScreen(db.records(), emptyList()) } }
    }
    @After fun close() {
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        db.close()
    }
    private fun filter(label: String) {
        val tag = "subscription-filter-${SubscriptionFilter.entries.single { it.label == label }.name}"
        compose.onNodeWithTag("subscriptions-list").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick().assertIsSelected()
    }
    private fun show(id: String) {
        compose.onNodeWithTag("subscriptions-list").performScrollToNode(hasTestTag("subscription-card-$id"))
        compose.onNodeWithTag("subscription-card-$id").assertIsDisplayed()
    }
    private fun absent(id: String) {
        // Searching the entire lazy list avoids a false pass for an off-screen, uncomposed card.
        val search = runCatching {
            compose.onNodeWithTag("subscriptions-list").performScrollToNode(hasTestTag("subscription-card-$id"))
        }
        assertTrue("Filtered card $id must not exist anywhere in the list", search.exceptionOrNull() is AssertionError)
    }
    @Test fun filtersActuallyShowMatchingCardsAndProvideEmptyState() {
        filter("全部")
        show("near"); show("far"); show("off")
        filter("启用")
        show("near"); show("far")
        absent("off")
        filter("停用")
        show("off")
        absent("near"); absent("far")
        filter("7天内到期")
        show("near")
        absent("far"); absent("off")
        runBlocking { db.records().deleteSubscription("near") }
        compose.onNodeWithTag("subscriptions-list").performScrollToNode(hasText("未来7天没有到期订阅"))
        compose.onNodeWithText("未来7天没有到期订阅").assertIsDisplayed()
    }
}

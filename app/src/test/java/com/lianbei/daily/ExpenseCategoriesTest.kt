package com.lianbei.daily

import org.junit.Assert.*
import org.junit.Test

class ExpenseCategoriesTest {
    @Test fun legacyCategoriesRemainVisibleAndCustomNamesArePreserved() {
        assertEquals("食品酒水",ExpenseCategories.main("餐饮"))
        assertEquals("居家物业",ExpenseCategories.main("居家"))
        assertEquals("自定义用途",ExpenseCategories.main("自定义用途"))
    }
    @Test fun selectionEncodesOnlyAChildOfTheSelectedParent() {
        assertEquals("交通 / 地铁",ExpenseCategories.selection("交通","地铁"))
        assertEquals("交通",ExpenseCategories.selection("交通","房租"))
        assertEquals("早餐",ExpenseCategories.foodMeal("早餐"))
        assertEquals("",ExpenseCategories.foodMeal("加餐"))
    }
    @Test fun expenseAnalyticsMergeLegacyFoodIntoNewParent() {
        val record=DailyRecord("old","EXPENSE","2026-10-19","08:00","餐饮",value=1200)
        assertEquals("食品酒水",recordGroup(record,Kind.EXPENSE))
    }
}

package com.lianbei.daily

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.math.BigDecimal
import java.time.LocalTime
import java.time.Duration

enum class Kind(val label: String, val hint: String) {
    FOOD("饮食", "吃了什么"), SLEEP("睡眠", "昨晚睡得怎样"), TRAINING("训练", "记录一次活动"),
    EXPENSE("消费", "花在哪里"), STATE("今天状态", "此刻的心情与精力"), NOTE("随手记", "想到就记下来")
}

@Entity(tableName = "records")
data class DailyRecord(
    @PrimaryKey val id: String,
    val kind: String,
    val date: String,
    val time: String,
    val title: String,
    val detail: String = "",
    val value: Long? = null,
    val extra: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "categories")
data class CustomCategory(@PrimaryKey val name: String)

@Dao
interface RecordDao {
    @Query("SELECT * FROM records ORDER BY date DESC, time DESC, createdAt DESC")
    fun observeAll(): Flow<List<DailyRecord>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(record: DailyRecord)
    @Delete suspend fun delete(record: DailyRecord)
    @Query("SELECT * FROM categories ORDER BY name") fun categories(): Flow<List<CustomCategory>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addCategory(category: CustomCategory)
}

@Database(entities = [DailyRecord::class, CustomCategory::class], version = 1, exportSchema = false)
abstract class DailyDatabase : RoomDatabase() { abstract fun records(): RecordDao }

object RecordRules {
    fun cents(text: String): Long? = try {
        BigDecimal(text.trim()).takeIf { it.signum() > 0 && it.scale() <= 2 }
            ?.movePointRight(2)?.longValueExact()
    } catch (_: Exception) { null }

    fun sleepMinutes(start: String, end: String): Long? = try {
        val minutes = Duration.between(LocalTime.parse(start), LocalTime.parse(end)).toMinutes()
        (if (minutes < 0) minutes + 1440 else minutes).takeIf { it > 0 }
    } catch (_: Exception) { null }

    fun money(cents: Long): String = BigDecimal.valueOf(cents, 2).toPlainString()
}

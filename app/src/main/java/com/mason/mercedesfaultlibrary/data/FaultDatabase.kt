package com.mason.mercedesfaultlibrary.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "fault_records")
data class FaultRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val registration: String = "", val vin: String = "", val model: String = "",
    val engine: String = "", val mileage: String = "", val faultCodes: String = "",
    val symptoms: String = "", val tests: String = "", val cause: String = "",
    val repair: String = "", val confirmed: Boolean = false, val photoPaths: String = ""
)

@Dao
interface FaultDao {
    @Query("SELECT * FROM fault_records ORDER BY createdAt DESC") fun observeAll(): Flow<List<FaultRecord>>
    @Insert suspend fun insert(record: FaultRecord): Long
    @Update suspend fun update(record: FaultRecord)
}

@Database(entities = [FaultRecord::class], version = 1, exportSchema = false)
abstract class FaultDatabase : RoomDatabase() {
    abstract fun faultDao(): FaultDao
    companion object {
        @Volatile private var instance: FaultDatabase? = null
        fun get(context: Context): FaultDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, FaultDatabase::class.java, "mercedes_fault_library.db")
                .build().also { instance = it }
        }
    }
}

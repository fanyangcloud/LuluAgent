package com.lulu.agent.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.lulu.agent.data.local.dao.ChatMessageDao
import com.lulu.agent.data.local.dao.CompanyDao
import com.lulu.agent.data.local.dao.JobDao
import com.lulu.agent.data.local.dao.LLMAuditDao
import com.lulu.agent.data.local.entity.ChatMessageEntity
import com.lulu.agent.data.local.entity.CompanyEntity
import com.lulu.agent.data.local.entity.JobEntity
import com.lulu.agent.data.local.entity.LLMAuditLogEntity

@Database(
    entities = [
        JobEntity::class,
        CompanyEntity::class,
        LLMAuditLogEntity::class,
        ChatMessageEntity::class // 🌟 注册新气泡表
    ],
    version = 2, // 🌟 升级至版本 2
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun jobDao(): JobDao
    abstract fun companyDao(): CompanyDao
    abstract fun llmAuditDao(): LLMAuditDao
    abstract fun chatMessageDao(): ChatMessageDao // 🌟 新增 Dao 访问点

    companion object {
        private const val DB_NAME = "boss_agent.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
package com.televault.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

data class ProjectDb(
    val id: Long,
    val name: String,
    val tgChatId: Long,
    val apiKey: String,
    val encKey: String,
    val createdAt: Long
)

data class VaultFile(
    val id: Long,
    val projectId: Long,
    val fileName: String,
    val tgMessageId: Long,
    val tgFileId: Int,
    val fileSize: Long,
    val createdAt: Long
)

class VaultDbHelper(private val context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        const val DB_NAME = "televault_master.db"
        const val DB_VERSION = 1
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE projects (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                tg_chat_id INTEGER NOT NULL,
                api_key TEXT NOT NULL,
                enc_key TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
        """)
        db.execSQL("""
            CREATE TABLE vault_files (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                project_id INTEGER NOT NULL,
                file_name TEXT NOT NULL,
                tg_message_id INTEGER NOT NULL,
                tg_file_id INTEGER NOT NULL,
                file_size INTEGER NOT NULL,
                created_at INTEGER NOT NULL
            )
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun insertProject(name: String, tgChatId: Long, apiKey: String, encKey: String): Long {
        val cv = ContentValues().apply {
            put("name", name)
            put("tg_chat_id", tgChatId)
            put("api_key", apiKey)
            put("enc_key", encKey)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insert("projects", null, cv)
    }

    fun getAllProjects(): List<ProjectDb> {
        val list = mutableListOf<ProjectDb>()
        readableDatabase.rawQuery(
            "SELECT id, name, tg_chat_id, api_key, enc_key, created_at FROM projects ORDER BY id DESC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    ProjectDb(
                        id = c.getLong(0),
                        name = c.getString(1),
                        tgChatId = c.getLong(2),
                        apiKey = c.getString(3),
                        encKey = c.getString(4),
                        createdAt = c.getLong(5)
                    )
                )
            }
        }
        return list
    }

    fun insertFile(projectId: Long, fileName: String, tgMsgId: Long, tgFileId: Int, size: Long): Long {
        val cv = ContentValues().apply {
            put("project_id", projectId)
            put("file_name", fileName)
            put("tg_message_id", tgMsgId)
            put("tg_file_id", tgFileId)
            put("file_size", size)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insert("vault_files", null, cv)
    }

    fun searchFiles(projectId: Long, query: String): List<VaultFile> {
        val list = mutableListOf<VaultFile>()
        readableDatabase.rawQuery(
            "SELECT id, project_id, file_name, tg_message_id, tg_file_id, file_size, created_at FROM vault_files WHERE project_id = ? AND file_name LIKE ? ORDER BY id DESC",
            arrayOf(projectId.toString(), "%$query%")
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    VaultFile(
                        id = c.getLong(0),
                        projectId = c.getLong(1),
                        fileName = c.getString(2),
                        tgMessageId = c.getLong(3),
                        tgFileId = c.getInt(4),
                        fileSize = c.getLong(5),
                        createdAt = c.getLong(6)
                    )
                )
            }
        }
        return list
    }

    fun getDbFile(): File = context.getDatabasePath(DB_NAME)
}

package dev.pi.mobile.backend

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable

/** One owner, used only on the bridge's serial database thread. */
class NativeStorage(context: Context, name: String = WorkspaceIdentity.LEGACY_DATABASE) : Closeable {
  private val database = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null)
  private var transaction = false
  private val modernSchema: Boolean
  private val jsonFunctions: Boolean

  init {
    database.rawQuery("SELECT sqlite_version()", null).use { cursor ->
      cursor.moveToFirst()
      val version = cursor.getString(0).split('.').map { it.toInt() }
      modernSchema = version[0] > 3 || (version[0] == 3 && version[1] >= 37)
    }
    database.execSQL("PRAGMA foreign_keys = ON")
    jsonFunctions = runCatching { database.rawQuery("SELECT json_valid('{}')", null).use { it.moveToFirst(); it.getInt(0) == 1 } }.getOrDefault(false)
  }

  fun dispatch(operation: String, args: JSONObject): Any? = when (operation) {
    "sql.exec" -> { executeStatements(args.getString("sql")); null }
    "sql.run" -> {
      database.compileStatement(args.getString("sql")).use { statement ->
        val params = args.optJSONArray("params") ?: JSONArray()
        if (!jsonFunctions) validateDurableJson(args.getString("sql"), params)
        for (i in 0 until params.length()) {
          val value = params.opt(i)
          when (value) {
            null, JSONObject.NULL -> statement.bindNull(i + 1)
            is Number -> if (value is Float || value is Double) statement.bindDouble(i + 1, value.toDouble()) else statement.bindLong(i + 1, value.toLong())
            is JSONObject -> statement.bindBlob(i + 1, Base64.decode(value.getString("base64"), Base64.DEFAULT))
            else -> statement.bindString(i + 1, value.toString())
          }
        }
        statement.execute()
      }
      null
    }
    "sql.all", "sql.get" -> {
      val values = args.optJSONArray("params") ?: JSONArray()
      val cursor = database.rawQueryWithFactory({ _, driver, editTable, query ->
        for (i in 0 until values.length()) {
          val value = values.opt(i)
          when (value) {
            null, JSONObject.NULL -> query.bindNull(i + 1)
            is Number -> if (value is Float || value is Double) query.bindDouble(i + 1, value.toDouble()) else query.bindLong(i + 1, value.toLong())
            is JSONObject -> query.bindBlob(i + 1, Base64.decode(value.getString("base64"), Base64.DEFAULT))
            else -> query.bindString(i + 1, value.toString())
          }
        }
        android.database.sqlite.SQLiteCursor(driver, editTable, query)
      }, args.getString("sql"), emptyArray(), "")
      cursor.use {
        val rows = JSONArray()
        while (it.moveToNext()) {
          val row = JSONObject()
          for (i in 0 until it.columnCount) row.put(it.getColumnName(i), when (it.getType(i)) {
            Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
            Cursor.FIELD_TYPE_INTEGER -> it.getLong(i)
            Cursor.FIELD_TYPE_FLOAT -> it.getDouble(i)
            Cursor.FIELD_TYPE_BLOB -> JSONObject().put("base64", Base64.encodeToString(it.getBlob(i), Base64.NO_WRAP))
            else -> it.getString(i)
          })
          rows.put(row)
          if (operation == "sql.get") break
        }
        if (operation == "sql.get") rows.opt(0) else rows
      }
    }
    "sql.begin" -> { check(!transaction); database.beginTransaction(); transaction = true; null }
    "sql.commit" -> { check(transaction); database.setTransactionSuccessful(); database.endTransaction(); transaction = false; null }
    "sql.rollback" -> { check(transaction); database.endTransaction(); transaction = false; null }
    "sql.close" -> { close(); null }
    else -> error("Unknown SQL operation: $operation")
  }

  private fun executeStatements(sql: String) {
    // Pi's migration statements are separate calls. Splitting is for simple PRAGMA/test batches only.
    require(!sql.contains(';') || sql.split(';').count { it.isNotBlank() } <= 1 || !sql.contains('\'')) { "Quoted multi-statement SQL is not supported" }
    for (part in sql.split(';').filter { it.isNotBlank() }) {
      var statement = part.trim()
      if (!modernSchema && statement.startsWith("CREATE TABLE", ignoreCase = true)) {
        // Android 11 SQLite predates STRICT. JSON CHECK remains when JSON1 is present.
        statement = statement.replace(Regex("\\s+STRICT$", RegexOption.IGNORE_CASE), "")
      }
      if (!jsonFunctions && statement.startsWith("CREATE TABLE", ignoreCase = true)) {
        statement = statement.replace(Regex("\\s+CHECK\\s*\\(json_valid\\((record|content)\\)\\)", RegexOption.IGNORE_CASE), "")
      }
      database.execSQL(statement)
    }
  }

  private fun validateDurableJson(sql: String, params: JSONArray) {
    if (!Regex("(?:INSERT|UPDATE).*?(?:conversations|entries|tasks|submissions|documents|document_revisions)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).containsMatchIn(sql)) return
    val index = if (sql.trimStart().startsWith("UPDATE", true)) {
      Regex("(?:record|content)\\s*=\\s*\\?", RegexOption.IGNORE_CASE).find(sql)?.let { sql.substring(0, it.range.last + 1).count { ch -> ch == '?' } - 1 }
    } else {
      Regex("\\(([^)]+)\\)\\s*VALUES", RegexOption.IGNORE_CASE).find(sql)?.groupValues?.get(1)?.split(',')?.map { it.trim() }?.indexOfFirst { it == "record" || it == "content" }?.takeIf { it >= 0 }
    }
    if (index != null) {
      val text = params.getString(index)
      if (text.trimStart().startsWith("{")) JSONObject(text) else JSONArray(text)
    }
  }

  override fun close() {
    if (database.isOpen) {
      if (transaction) { database.endTransaction(); transaction = false }
      database.close()
    }
  }
}

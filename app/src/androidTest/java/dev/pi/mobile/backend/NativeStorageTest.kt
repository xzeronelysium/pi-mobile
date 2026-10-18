package dev.pi.mobile.backend

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeStorageTest {
  @Test fun sqliteRollsBackAndPreservesBoundValues() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val storage = NativeStorage(context, "native-storage-test.sqlite")
    try {
      storage.dispatch("sql.exec", JSONObject().put("sql", "CREATE TABLE IF NOT EXISTS probe (value TEXT); DELETE FROM probe;"))
      storage.dispatch("sql.begin", JSONObject())
      storage.dispatch("sql.run", JSONObject().put("sql", "INSERT INTO probe VALUES (?)").put("params", JSONArray().put("bound ' value")))
      storage.dispatch("sql.rollback", JSONObject())
      assertEquals(0, (storage.dispatch("sql.all", JSONObject().put("sql", "SELECT * FROM probe")) as JSONArray).length())
      storage.dispatch("sql.run", JSONObject().put("sql", "INSERT INTO probe VALUES (?)").put("params", JSONArray().put("bound ' value")))
      val row = storage.dispatch("sql.get", JSONObject().put("sql", "SELECT * FROM probe")) as JSONObject
      assertEquals("bound ' value", row.getString("value"))
    } finally { storage.close() }
  }
}

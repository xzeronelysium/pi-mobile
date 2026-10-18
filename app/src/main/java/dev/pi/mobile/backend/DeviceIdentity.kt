package dev.pi.mobile.backend

import android.content.Context
import java.util.UUID

/**
 * This installation's stable identifier.
 *
 * OpenAI's ChatGPT sign-in sends it as `ext_agent_host_id` (`urn:uuid:<id>`), and pi-ai requires the
 * same value on every later call, so it is generated once and persisted with a synchronous commit
 * before it is ever handed to a flow. It is not a secret and it is never derived from device or
 * account identifiers.
 */
object DeviceIdentity {
  private const val PREFS = "pi-native-identity"
  private const val KEY = "deviceId"

  fun of(context: Context): String {
    val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    preferences.getString(KEY, null)?.takeIf { it.isNotBlank() }?.let { return it }
    val value = UUID.randomUUID().toString()
    preferences.edit().putString(KEY, value).commit()
    return value
  }
}

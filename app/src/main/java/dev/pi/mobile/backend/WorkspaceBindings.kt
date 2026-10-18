package dev.pi.mobile.backend

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Environment
import java.io.File

/** Android-backed [WorkspaceBinding] using SharedPreferences. */
fun workspaceBinding(context: Context): WorkspaceBinding {
  val prefs = context.applicationContext.getSharedPreferences(WorkspaceBinding.PREFS, Context.MODE_PRIVATE)
  return WorkspaceBinding(
    filesDir = context.filesDir,
    externalFilesDir = context.getExternalFilesDir(null),
    sharedStorageRoot = sharedStorageRootOrNull(),
    isAllFilesAccess = { hasAllFilesAccess() },
    load = {
      WorkspaceBinding.storedFromPrefs(
        prefs.getString(WorkspaceBinding.KEY_MODE, WorkspaceBinding.MODE_DEFAULT),
        prefs.getString(WorkspaceBinding.KEY_PATH, null),
        prefs.getString(WorkspaceBinding.KEY_SAF, null),
        prefs.getString(WorkspaceBinding.KEY_LABEL, null)
      )
    },
    save = { stored ->
      WorkspaceBinding.writePrefs(stored) { mode, path, saf, label ->
        prefs.edit().putWorkspace(mode, path, saf, label).apply()
      }
    }
  )
}

fun hasAllFilesAccess(): Boolean =
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
  else true

fun sharedStorageRootOrNull(): File? =
  runCatching { Environment.getExternalStorageDirectory() }.getOrNull()

private fun SharedPreferences.Editor.putWorkspace(
  mode: String,
  path: String?,
  saf: String?,
  label: String?
): SharedPreferences.Editor {
  putString(WorkspaceBinding.KEY_MODE, mode)
  if (path != null) putString(WorkspaceBinding.KEY_PATH, path) else remove(WorkspaceBinding.KEY_PATH)
  if (saf != null) putString(WorkspaceBinding.KEY_SAF, saf) else remove(WorkspaceBinding.KEY_SAF)
  if (label != null) putString(WorkspaceBinding.KEY_LABEL, label) else remove(WorkspaceBinding.KEY_LABEL)
  return this
}

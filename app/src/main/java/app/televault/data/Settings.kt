package app.televault.data

import android.content.Context

/** Small wrapper around app-private SharedPreferences. */
class Settings(context: Context) {
    private val sp = context.getSharedPreferences("televault", Context.MODE_PRIVATE)

    /** From https://my.telegram.org -> API development tools. */
    var apiId: Int
        get() = sp.getInt("apiId", 0)
        set(v) { sp.edit().putInt("apiId", v).apply() }

    var apiHash: String
        get() = sp.getString("apiHash", "") ?: ""
        set(v) { sp.edit().putString("apiHash", v).apply() }

    var lockEnabled: Boolean
        get() = sp.getBoolean("lock", false)
        set(v) { sp.edit().putBoolean("lock", v).apply() }

    var autoBackup: Boolean
        get() = sp.getBoolean("autoBackup", true)
        set(v) { sp.edit().putBoolean("autoBackup", v).apply() }

    /** Manually ticked: also write folder info into every file's caption so the index can be rebuilt by scanning. */
    var captionTags: Boolean
        get() = sp.getBoolean("captionTags", false)
        set(v) { sp.edit().putBoolean("captionTags", v).apply() }

    var lastBackupMsgId: Long
        get() = sp.getLong("lastBackupMsg", 0L)
        set(v) { sp.edit().putLong("lastBackupMsg", v).apply() }

    /** Forgets everything except the API id/hash, so logging in again is quick. */
    fun clearKeepApi() {
        val id = apiId
        val hash = apiHash
        sp.edit().clear().putInt("apiId", id).putString("apiHash", hash).apply()
    }
}

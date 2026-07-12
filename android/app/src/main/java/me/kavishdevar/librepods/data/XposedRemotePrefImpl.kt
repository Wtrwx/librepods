package me.kavishdevar.librepods.data

import androidx.core.content.edit
import me.kavishdevar.librepods.utils.XposedServiceHolder

class XposedRemotePrefImpl: XposedRemotePref {
    override fun isAvailable(): Boolean {
        return XposedServiceHolder.service != null
    }

    override fun getBoolean(key: String, def: Boolean): Boolean {
        val s = XposedServiceHolder.service ?: return def
        return s.getRemotePreferences("me.kavishdevar.librepods").getBoolean(key, def)
    }

    override fun putBoolean(key: String, value: Boolean) {
        val s = XposedServiceHolder.service ?: return
        s.getRemotePreferences("me.kavishdevar.librepods")
            .edit { putBoolean(key, value) }
    }

    override fun getString(key: String, def: String): String {
        val s = XposedServiceHolder.service ?: return def
        return s.getRemotePreferences("me.kavishdevar.librepods").getString(key, def) ?: def
    }

    override fun putString(key: String, value: String) {
        val s = XposedServiceHolder.service ?: return
        s.getRemotePreferences("me.kavishdevar.librepods")
            .edit { putString(key, value) }
    }

    override fun getLong(key: String, def: Long): Long {
        val s = XposedServiceHolder.service ?: return def
        return s.getRemotePreferences("me.kavishdevar.librepods").getLong(key, def)
    }

    override fun putLong(key: String, value: Long) {
        val s = XposedServiceHolder.service ?: return
        s.getRemotePreferences("me.kavishdevar.librepods")
            .edit { putLong(key, value) }
    }
}

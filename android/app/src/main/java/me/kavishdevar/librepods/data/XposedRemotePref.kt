package me.kavishdevar.librepods.data

interface XposedRemotePref {
    fun isAvailable(): Boolean

    fun getBoolean(key: String, def: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)

    fun getString(key: String, def: String): String
    fun putString(key: String, value: String)

    fun getLong(key: String, def: Long): Long
    fun putLong(key: String, value: Long)
}

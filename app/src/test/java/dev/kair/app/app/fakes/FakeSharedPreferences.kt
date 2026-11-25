package dev.kair.app.app.fakes

import android.content.SharedPreferences

/**
 * 内存版 [SharedPreferences]（测试专用，P4-1 / A2）。
 *
 * 为什么能这么写：`SharedPreferences` 是**接口** —— 单测的 android.jar 只有方法桩，
 * 实现接口自造数据在纯 JVM 上完全可跑，不需要 Robolectric（P4-1「不要做」第 1 条）。
 *
 * 语义对齐生产实现的三点：`apply()` / `commit()` 都立即落内存（无持久化）；
 * `edit()` 是暂存式 —— put 先攒着，`commit` / `apply` 才进主表（与 Android 一致）；
 * `getXxx` 在**键存在但类型不符**时抛 `ClassCastException`（与 SharedPreferencesImpl 一致）。
 * 旧版第三点用安全 cast `as?` 静默回退缺省 —— 恰好掩盖 U51 这类缺陷：
 * 生产设置页在组合期崩，假件上的测试却永远绿。
 */
class FakeSharedPreferences : SharedPreferences {

    private val map = linkedMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = map.toMutableMap()

    override fun getString(key: String?, defValue: String?): String? = typed(key, defValue)

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? = typed(key, defValues)

    override fun getInt(key: String?, defValue: Int): Int = typed(key, defValue)

    override fun getLong(key: String?, defValue: Long): Long = typed(key, defValue)

    override fun getFloat(key: String?, defValue: Float): Float = typed(key, defValue)

    override fun getBoolean(key: String?, defValue: Boolean): Boolean = typed(key, defValue)

    /**
     * 键不存在（或存的是 null —— 本假件允许 putString(key, null)，生产没有这一形态）
     * 返回缺省；键存在但类型不符抛 [ClassCastException]，与生产 SharedPreferencesImpl 一致。
     */
    private inline fun <reified T> typed(key: String?, defValue: T): T {
        val value = map[key] ?: return defValue
        return value as? T ?: throw ClassCastException(
            "${value.javaClass.name} cannot be cast to ${T::class.java.name} (key=$key)",
        )
    }

    override fun contains(key: String?): Boolean = map.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private inner class FakeEditor : SharedPreferences.Editor {
        private val staged = linkedMapOf<String, Any?>()
        private var clearRequested = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            staged[key!!] = value
            return this
        }

        override fun putStringSet(key: String?, values: Set<String>?): SharedPreferences.Editor {
            staged[key!!] = values
            return this
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            staged[key!!] = value
            return this
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            staged[key!!] = value
            return this
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            staged[key!!] = value
            return this
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            staged[key!!] = value
            return this
        }

        override fun remove(key: String?): SharedPreferences.Editor {
            staged[key!!] = REMOVE
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearRequested = true
            return this
        }

        override fun commit(): Boolean {
            if (clearRequested) map.clear()
            staged.forEach { (k, v) ->
                if (v === REMOVE) map.remove(k) else map[k] = v
            }
            staged.clear()
            clearRequested = false
            return true
        }

        override fun apply() {
            commit()
        }
    }

    private companion object {
        /** 移除哨兵：null 是合法值（putString(key, null) = 存 null），不能拿来当删除记号。 */
        private val REMOVE = Any()
    }
}

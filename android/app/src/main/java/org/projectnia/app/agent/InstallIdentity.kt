package org.projectnia.app.agent

import android.content.Context
import java.util.UUID

object InstallIdentityPolicy {
    private val pattern = Regex(
        "install-[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
    )

    fun create(): String = "install-${UUID.randomUUID()}"

    fun isValid(value: String): Boolean = pattern.matches(value)
}

class InstallIdentityStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    fun getOrCreate(): String {
        preferences.getString(KEY, null)?.takeIf(InstallIdentityPolicy::isValid)?.let { return it }
        val generated = InstallIdentityPolicy.create()
        preferences.edit().putString(KEY, generated).commit()
        return generated
    }

    private companion object {
        const val PREFERENCES = "nia_install_identity_v1"
        const val KEY = "anonymous_install_id"
    }
}

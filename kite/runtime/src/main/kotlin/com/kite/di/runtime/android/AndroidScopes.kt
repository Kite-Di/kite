package com.kite.di.runtime.android

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.kite.di.runtime.Kite
import com.kite.di.runtime.ScopeId
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID

/**
 * Opens/closes activity and fragment scopes automatically.
 *
 * Activity scopes are keyed by a stable id persisted in `savedInstanceState`, so a
 * scope survives configuration changes (instances are retained across rotation) and
 * closes on real finish. Fragment scopes are per fragment instance in v1 (they do
 * not survive configuration changes).
 */
internal object AndroidScopes {

    private const val STATE_KEY = "com.kite.di.scope-id"

    private val activityIds = Collections.synchronizedMap(IdentityHashMap<Activity, String>())
    private val fragmentSupportAvailable: Boolean by lazy {
        try {
            Class.forName("androidx.fragment.app.FragmentActivity")
            true
        } catch (_: ClassNotFoundException) {
            false
        }
    }

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {

            override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
                openScope(activity, savedInstanceState) // API 29+: scope ready before onCreate body
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                openScope(activity, savedInstanceState) // pre-29 fallback; idempotent
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
                activityIds[activity]?.let { outState.putString(STATE_KEY, it) }
            }

            override fun onActivityDestroyed(activity: Activity) {
                val stableId = activityIds.remove(activity) ?: return
                Kite.unregisterOwner(activity)
                if (!activity.isChangingConfigurations) {
                    Kite.requireContainer().scopeTree.close(scopeId(activity, stableId))
                }
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
        })
    }

    private fun openScope(activity: Activity, savedInstanceState: Bundle?) {
        if (activityIds.containsKey(activity)) return
        val stableId = savedInstanceState?.getString(STATE_KEY)
            ?: UUID.randomUUID().toString().substring(0, 8)
        activityIds[activity] = stableId
        val container = Kite.requireContainer()
        val tree = container.scopeTree
        val id = scopeId(activity, stableId)
        val node = tree.find(id) // still open after a configuration change
            ?: tree.open(id, "ActivityScoped", level = 1, parent = ScopeId.App)
        Kite.registerOwner(activity, node)
        // @Inject fields are filled before the onCreate body runs — no manual
        // Kite.inject(this) call needed in Activities.
        if (container.hasMemberInjector(activity.javaClass)) container.injectMembers(activity, node)
        if (fragmentSupportAvailable) FragmentScopes.installIfFragmentActivity(activity, node)
    }

    private fun scopeId(activity: Activity, stableId: String): ScopeId =
        ScopeId("${activity.javaClass.simpleName}@$stableId")
}

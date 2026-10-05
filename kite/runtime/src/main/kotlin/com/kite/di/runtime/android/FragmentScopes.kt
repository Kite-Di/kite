package com.kite.di.runtime.android

import android.app.Activity
import android.content.Context
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import com.kite.di.runtime.Kite
import com.kite.di.runtime.ScopeId
import com.kite.di.runtime.ScopeNode

/**
 * androidx.fragment integration, isolated in its own class so the runtime never
 * loads androidx types unless the consumer app actually has them ([AndroidScopes]
 * checks availability first — the runtime only `compileOnly`-depends on fragments).
 */
internal object FragmentScopes {

    fun installIfFragmentActivity(activity: Activity, activityScope: ScopeNode) {
        if (activity !is FragmentActivity) return
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {

                override fun onFragmentPreCreated(
                    fm: FragmentManager,
                    fragment: Fragment,
                    savedInstanceState: Bundle?,
                ) {
                    val tree = Kite.containerOrNull()?.scopeTree ?: return
                    val id = scopeId(fragment)
                    // An Activity that outlived a discarded graph (a test restart) has no
                    // scope in this one: its fragments hang off the app scope, like the
                    // Activity itself now resolves against it.
                    val parent = tree.find(activityScope.id)?.id ?: ScopeId.App
                    val node = tree.find(id)
                        ?: tree.open(id, "FragmentScoped", level = 2, parent = parent)
                    Kite.registerOwner(fragment, node)
                }

                override fun onFragmentDestroyed(fm: FragmentManager, fragment: Fragment) {
                    Kite.unregisterOwner(fragment)
                    Kite.containerOrNull()?.scopeTree?.close(scopeId(fragment))
                }
            },
            /* recursive = */ true,
        )
    }

    private fun scopeId(fragment: Fragment): ScopeId =
        ScopeId("${fragment.javaClass.simpleName}@${Integer.toHexString(System.identityHashCode(fragment))}")
}

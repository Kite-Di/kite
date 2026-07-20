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
                    val container = Kite.requireContainer()
                    val tree = container.scopeTree
                    val id = scopeId(fragment)
                    val node = tree.find(id)
                        ?: tree.open(id, "FragmentScoped", level = 2, parent = activityScope.id)
                    Kite.registerOwner(fragment, node)
                    // @Inject fields are filled before the fragment's onCreate runs.
                    if (container.hasMemberInjector(fragment.javaClass)) container.injectMembers(fragment, node)
                }

                override fun onFragmentDestroyed(fm: FragmentManager, fragment: Fragment) {
                    Kite.unregisterOwner(fragment)
                    Kite.requireContainer().scopeTree.close(scopeId(fragment))
                }
            },
            /* recursive = */ true,
        )
    }

    private fun scopeId(fragment: Fragment): ScopeId =
        ScopeId("${fragment.javaClass.simpleName}@${Integer.toHexString(System.identityHashCode(fragment))}")
}

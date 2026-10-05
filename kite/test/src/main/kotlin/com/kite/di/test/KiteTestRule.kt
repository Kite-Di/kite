package com.kite.di.test

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.kite.di.runtime.Key
import com.kite.di.runtime.KiteException
import com.kite.di.runtime.KiteTestApi
import com.kite.di.runtime.KiteTesting
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import kotlin.reflect.KClass

/**
 * Restarts the graph around every test.
 *
 * Before the test it discards whatever graph is running — the one the production
 * `Application` started, or the previous test's — and runs [start], which calls
 * the generated `Graph.start` with the arguments the test wants. After the test it
 * discards that graph too. Give it `order = 0`, so it starts the graph before an
 * Activity rule launches anything and discards it only after that Activity closed.
 *
 * ```
 * @get:Rule(order = 0)
 * val kite = KiteTestRule { app ->
 *     replace<TaskRepository>(FakeTaskRepository())
 *     Graph.start(app, localDataSource = db.taskDao(), …)
 * }
 * ```
 */
@OptIn(KiteTestApi::class)
class KiteTestRule(
    private val start: KiteTestScope.(app: Application) -> Unit,
) : TestRule {

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val app = ApplicationProvider.getApplicationContext<Application>()
            KiteTesting.reset()
            try {
                KiteTestScope().start(app)
                if (!KiteTesting.isStarted) {
                    throw KiteException(
                        "KiteTestRule: the block did not start the graph.\n" +
                            "  hint: call Graph.start(app, …) inside KiteTestRule { app -> … }."
                    )
                }
                base.evaluate()
            } finally {
                KiteTesting.reset()
            }
        }
    }
}

/** The receiver of a [KiteTestRule] block: replacements for the graph about to start. */
@OptIn(KiteTestApi::class)
class KiteTestScope internal constructor() {

    /**
     * Every consumer of [T] gets [instance], for the whole test. Call before
     * `Graph.start`. [mark] targets a marked implementation — the annotation that
     * sits on it and on the parameters that select it.
     */
    inline fun <reified T : Any> replace(instance: T, mark: KClass<out Annotation>? = null) {
        replace(T::class.java, instance, mark)
    }

    fun <T : Any> replace(type: Class<T>, instance: T, mark: KClass<out Annotation>? = null) {
        KiteTesting.replace(Key(type, mark = mark?.java), instance)
    }
}

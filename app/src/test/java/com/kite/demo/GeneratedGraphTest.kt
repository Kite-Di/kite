package com.kite.demo

import com.kite.demo.data.Analytics
import com.kite.demo.di.StartupTask
import com.kite.demo.ui.SecondPresenter
import com.kite.demo.ui.SessionState
import com.kite.di.generated.App_BindingRegistry
import com.kite.di.generated.MergedRegistry
import com.kite.di.runtime.Container
import com.kite.di.runtime.Key
import com.kite.di.runtime.ScopeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Executes the KSP-generated factories/registry on the JVM (no device needed). */
class GeneratedGraphTest {

    @Test
    fun `merged registry loads the app registry`() {
        assertTrue(MergedRegistry.load().any { it is App_BindingRegistry })
    }

    @Test
    fun `singleton from generated factory is cached`() {
        val container = Container(MergedRegistry.load())
        val root = container.scopeTree.root
        val a1: Analytics = container.resolve(Key(Analytics::class.java), root)
        val a2: Analytics = container.resolve(Key(Analytics::class.java), root)
        assertSame(a1, a2)
    }

    @Test
    fun `qualified provides binding resolves`() {
        val container = Container(MergedRegistry.load())
        val key: String = container.resolve(Key(String::class.java, "apiKey"), container.scopeTree.root)
        assertEquals("demo-key-123", key)
    }

    @Test
    fun `set multibinding record is generated with both contributions`() {
        val setRecord = MergedRegistry.load().flatMap { it.bindings() }
            .single { it.key == Key(Set::class.java, element = StartupTask::class.java) }
        assertTrue(setRecord.factory is com.kite.di.runtime.SetFactory)
        assertEquals("Set<StartupTask> (2 contributions)", setRecord.declaration)
    }

    @Test
    fun `fragment scoped presenter shares activity scoped session`() {
        val container = Container(MergedRegistry.load())
        val activity = container.scopeTree.open(ScopeId("MainActivity@x"), "ActivityScoped", 1)
        val fragmentA = container.scopeTree.open(ScopeId("FragA@x"), "FragmentScoped", 2, activity.id)
        val fragmentB = container.scopeTree.open(ScopeId("FragB@x"), "FragmentScoped", 2, activity.id)

        val presenterA: SecondPresenter =
            container.resolve(Key(SecondPresenter::class.java), fragmentA)
        val presenterB: SecondPresenter =
            container.resolve(Key(SecondPresenter::class.java), fragmentB)

        val session: SessionState =
            container.resolve(Key(SessionState::class.java), fragmentA)
        session.visits = 41

        // Different presenters per fragment scope, one session per activity scope.
        assertTrue(presenterA !== presenterB)
        assertEquals(
            41,
            container.resolve<SessionState>(
                Key(SessionState::class.java),
                fragmentB,
            ).visits,
        )
        assertEquals("Session visits so far: 41", presenterB.headline())
    }
}

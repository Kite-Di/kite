package com.kite.di.runtime.observe

import com.kite.di.runtime.Key
import com.kite.di.runtime.ScopeId
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

sealed interface GraphEvent {
    data class InstanceCreated(
        val key: Key,
        /** Scope the instance was cached in (requesting scope for unscoped bindings). */
        val scopeId: ScopeId,
        val scopePath: List<ScopeId>,
        val createdAt: Long,
        val durationMicros: Long,
    ) : GraphEvent

    data class ScopeOpened(val scopeId: ScopeId, val name: String, val parent: ScopeId?) : GraphEvent

    data class ScopeClosed(val scopeId: ScopeId) : GraphEvent

    data class ResolutionFailed(
        val key: Key,
        val scopePath: List<ScopeId>,
        val message: String,
    ) : GraphEvent
}

/**
 * Fire-and-forget event bus the inspector subscribes to. Emission must never block
 * or slow the app: no replay, bounded buffer, oldest events dropped on overflow.
 * In release builds nothing subscribes and [emit] costs one atomic read.
 */
object GraphEvents {
    private val mutableFlow = MutableSharedFlow<GraphEvent>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val flow: SharedFlow<GraphEvent> get() = mutableFlow

    internal fun emit(event: GraphEvent) {
        mutableFlow.tryEmit(event)
    }
}

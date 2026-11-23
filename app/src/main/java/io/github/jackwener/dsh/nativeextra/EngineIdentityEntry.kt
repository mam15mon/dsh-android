package io.github.jackwener.dsh.nativeextra

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jackwener.dsh.core.engine.EngineManager

@EntryPoint
@InstallIn(SingletonComponent::class)
interface EngineIdentityEntry {
    fun engine(): EngineManager
    fun capabilities(): io.github.jackwener.dsh.core.capability.CapabilityHub
    fun sessions(): io.github.jackwener.dsh.core.session.SessionStore
    fun settings(): io.github.jackwener.dsh.core.data.SettingsRepository
}

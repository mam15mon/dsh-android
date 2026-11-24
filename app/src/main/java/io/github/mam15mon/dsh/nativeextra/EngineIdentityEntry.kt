package io.github.mam15mon.dsh.nativeextra

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.mam15mon.dsh.core.engine.EngineManager

@EntryPoint
@InstallIn(SingletonComponent::class)
interface EngineIdentityEntry {
    fun engine(): EngineManager
    fun capabilities(): io.github.mam15mon.dsh.core.capability.CapabilityHub
    fun sessions(): io.github.mam15mon.dsh.core.session.SessionStore
    fun settings(): io.github.mam15mon.dsh.core.data.SettingsRepository
}

package com.netpopup.di

import com.netpopup.dtn.chat.ChatRepository
import com.netpopup.mesh.MeshRuntime
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Fournit le [ChatRepository] de l'app : celui du moteur réel ([MeshRuntime]), adossé à la base chiffrée,
 * à la crypto et au transport Nearby. Le dépôt en mémoire ([com.netpopup.dtn.chat.InMemoryChatRepository])
 * ne sert plus qu'aux tests et aux aperçus.
 */
@Module
@InstallIn(SingletonComponent::class)
object ChatModule {
    @Provides
    @Singleton
    fun provideChatRepository(runtime: MeshRuntime): ChatRepository = runtime.repository
}

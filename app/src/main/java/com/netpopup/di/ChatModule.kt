package com.netpopup.di

import com.netpopup.dtn.chat.ChatRepository
import com.netpopup.dtn.chat.InMemoryChatRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Fournit le [ChatRepository] de l'app.
 *
 * PROVISOIRE : tant que l'implémentation réelle (store, crypto, rooms, ACK, transports) n'est pas
 * assemblée, l'app utilise le dépôt en mémoire — les messages ne quittent pas l'appareil et ne
 * survivent pas à un redémarrage. Remplacer ce `@Provides` suffira : l'UI ne connaît que l'interface.
 */
@Module
@InstallIn(SingletonComponent::class)
object ChatModule {
    @Provides
    @Singleton
    fun provideChatRepository(): ChatRepository = InMemoryChatRepository()
}

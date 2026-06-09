package vn.vietbot.client.data

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import vn.vietbot.client.ApplicationScope
import vn.vietbot.client.Ota
import kotlinx.coroutines.CoroutineScope

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {
    @Provides
    fun provideSettingsRepository(
        context: Context
    ): SettingsRepository {
        return SettingsRepositoryImpl(context)
    }

    @Provides
    fun provideFormRepository(
        settingsRepository: SettingsRepository,
        ota: Ota,
        @ApplicationScope coroutineScope: CoroutineScope
    ): FormRepository {
        return FormRepositoryImpl(ota, settingsRepository, coroutineScope)
    }
}
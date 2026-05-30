package vn.vietbot.client

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.net.ConnectivityManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import vn.vietbot.client.data.SettingsRepository
import vn.vietbot.client.data.SettingsRepositoryImpl
import vn.vietbot.client.data.model.DeviceInfo
import vn.vietbot.client.data.model.DummyDataGenerator
import vn.vietbot.client.mcp.HeyCyanGlassesManager
import javax.inject.Singleton
import dagger.hilt.EntryPoint
import dagger.hilt.android.components.ActivityComponent
import dagger.hilt.android.scopes.ActivityScoped
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Qualifier

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideSharedPreferences(application: Application): SharedPreferences {
        return application.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    }

    @Provides
    @Singleton
    fun provideApplicationContext(application: Application): Context {
        return application.applicationContext
    }

    @Provides
    @Singleton
    fun provideResources(application: Application): Resources {
        return application.resources
    }

    @Provides
    @Singleton
    fun provideConnectivityManager(application: Application): ConnectivityManager {
        return application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    @Provides
    @Singleton
    fun provideDeviceInfo(context: Context): DeviceInfo {
        // Always generate fresh DeviceInfo to get real MAC address
        // Only cache UUID to maintain session consistency
        return DummyDataGenerator.generate(context)
    }

    
    @Provides
    @Singleton
    @ApplicationScope
    fun provideCoroutineScope(
        @DefaultDispatcher defaultDispatcher: CoroutineDispatcher
    ): CoroutineScope = CoroutineScope(SupervisorJob() + defaultDispatcher)

    @DefaultDispatcher
    @Provides
    fun providesDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

}

@Module
@InstallIn(SingletonComponent::class)
object NavigationModule {
    @Provides
    @Singleton
    @NavigationEvents
    fun provideNavigationEvents(): MutableSharedFlow<String> {
        return MutableSharedFlow(extraBufferCapacity = 1)
    }
}

@EntryPoint
@InstallIn(ActivityComponent::class)
interface NavigationEntryPoint {
    @NavigationEvents
    fun getNavigationEvents(): MutableSharedFlow<String>
    fun getSettingsRepository(): SettingsRepository
}

@EntryPoint
@InstallIn(ActivityComponent::class)
interface GlassesManagerEntryPoint {
    fun getHeyCyanGlassesManager(): HeyCyanGlassesManager
}

@Retention(AnnotationRetention.RUNTIME)
@Qualifier
annotation class NavigationEvents


@Retention(AnnotationRetention.RUNTIME)
@Qualifier
annotation class ApplicationScope

@Retention(AnnotationRetention.RUNTIME)
@Qualifier
annotation class DefaultDispatcher

@Module
@InstallIn(SingletonComponent::class)
object GlassesModule {
    @Provides
    @Singleton
    fun provideHeyCyanGlassesManager(
        settingsRepository: SettingsRepository
    ): HeyCyanGlassesManager {
        return HeyCyanGlassesManager(settingsRepository)
    }
}
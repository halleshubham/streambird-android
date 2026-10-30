package app.streambird.android.di

import android.content.Context
import app.streambird.android.data.ApiClient
import app.streambird.android.data.OriginInterceptor
import app.streambird.android.data.PersistentCookieJar
import app.streambird.android.data.Settings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides @Singleton
    fun settings(@ApplicationContext ctx: Context, scope: CoroutineScope) = Settings(ctx, scope)

    @Provides @Singleton
    fun cookieJar(settings: Settings) = PersistentCookieJar(settings.cookieStorage)

    @Provides @Singleton
    fun okHttp(settings: Settings, jar: PersistentCookieJar): OkHttpClient = OkHttpClient.Builder()
        .cookieJar(jar)
        .addInterceptor(OriginInterceptor { settings.baseUrl })
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Provides @Singleton
    fun api(http: OkHttpClient, settings: Settings) = ApiClient(http) { settings.baseUrl }
}

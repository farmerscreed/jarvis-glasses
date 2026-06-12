package com.echo.app.di

import android.content.Context
import com.echo.app.DevConfig
import com.echo.app.GlassesButtonController
import com.echo.device.audio.BtAudioEngine
import com.echo.device.audio.TtsEngine
import com.echo.device.audio.WakeWordEngine
import com.echo.device.ble.GlassesBleManager
import com.echo.device.wifi.GlassesP2pManager
import com.echo.device.wifi.MediaTransferClient
import java.io.File
import com.echo.app.ml.MediaPipeEmbedder
import com.echo.memory.ConnectivityGovernor
import com.echo.memory.EchoBackend
import com.echo.memory.Embedder
import com.echo.memory.MemoryRepository
import com.echo.memory.MemoryStore
import com.echo.memory.MemoryStoreFactory
import com.echo.memory.SupabaseSession
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json { ignoreUnknownKeys = true }

    @Provides
    @Singleton
    fun provideHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS) // chat can take a few seconds
        .build()

    @Provides
    @Singleton
    fun provideSession(@ApplicationContext ctx: Context): SupabaseSession =
        SupabaseSession(
            DevConfig.SUPABASE_URL,
            DevConfig.SUPABASE_ANON_KEY,
            ctx.getSharedPreferences("echo-session", Context.MODE_PRIVATE),
        )

    @Provides
    @Singleton
    fun provideBackend(session: SupabaseSession, http: OkHttpClient, json: Json): EchoBackend =
        EchoBackend(session, http, json)

    @Provides
    @Singleton
    fun provideConnectivityGovernor(@ApplicationContext ctx: Context): ConnectivityGovernor =
        ConnectivityGovernor(ctx)

    @Provides
    @Singleton
    fun provideEmbedder(@ApplicationContext ctx: Context): Embedder = MediaPipeEmbedder(ctx)

    @Provides
    @Singleton
    fun provideMemoryStore(
        @ApplicationContext ctx: Context,
        backend: EchoBackend,
        governor: ConnectivityGovernor,
        embedder: Embedder,
    ): MemoryStore = MemoryStoreFactory.create(ctx, backend, governor, embedder)

    @Provides
    @Singleton
    fun provideMemoryRepository(store: MemoryStore): MemoryRepository = store

    @Provides
    @Singleton
    fun provideAudioEngine(@ApplicationContext ctx: Context): BtAudioEngine = BtAudioEngine(ctx)

    @Provides
    @Singleton
    fun provideBleManager(@ApplicationContext ctx: Context): GlassesBleManager = GlassesBleManager(ctx)

    @Provides
    @Singleton
    fun provideTts(@ApplicationContext ctx: Context): TtsEngine = TtsEngine(ctx)

    @Provides
    @Singleton
    fun provideWakeWord(@ApplicationContext ctx: Context): WakeWordEngine = WakeWordEngine(ctx)

    @Provides
    @Singleton
    fun provideGlassesButtons(@ApplicationContext ctx: Context): GlassesButtonController =
        GlassesButtonController(ctx)

    @Provides
    @Singleton
    fun provideP2p(@ApplicationContext ctx: Context): GlassesP2pManager = GlassesP2pManager(ctx)

    @Provides
    @Singleton
    fun provideTransfer(@ApplicationContext ctx: Context, http: OkHttpClient): MediaTransferClient =
        MediaTransferClient(http, File(ctx.filesDir, "glasses_media"))
}

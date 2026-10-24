package com.aether.nextcloud.di

import android.content.Context
import android.os.Build
import androidx.room.Room
import com.aether.nextcloud.auth.LoginFlowV2Client
import com.aether.nextcloud.core.newAetherHttpClient
import com.aether.nextcloud.data.cache.DocumentDiskCache
import com.aether.nextcloud.data.local.AetherDatabase
import com.aether.nextcloud.dav.NextcloudOcsClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class UserAgent

/** Process-lifetime scope for fire-and-forget background refreshes. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @UserAgent
    fun userAgent(): String = "Aether (Android ${Build.VERSION.SDK_INT})"

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    fun okHttpClient(): OkHttpClient = newAetherHttpClient()

    @Provides
    @Singleton
    fun loginFlowV2Client(client: OkHttpClient, @UserAgent userAgent: String): LoginFlowV2Client =
        LoginFlowV2Client(client, userAgent)

    @Provides
    @Singleton
    fun ocsClient(client: OkHttpClient, @UserAgent userAgent: String): NextcloudOcsClient =
        NextcloudOcsClient(client, userAgent)

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AetherDatabase =
        Room.databaseBuilder(context, AetherDatabase::class.java, "aether.db").addMigrations(AetherDatabase.MIGRATION_1_2).build()

    @Provides
    @Singleton
    fun documentDiskCache(@ApplicationContext context: Context): DocumentDiskCache = DocumentDiskCache(
        cacheDir = File(context.cacheDir, "document_cache"),
        pinnedDir = File(context.filesDir, "pinned"),
        stagingDir = File(context.filesDir, "staging"),
    )
}

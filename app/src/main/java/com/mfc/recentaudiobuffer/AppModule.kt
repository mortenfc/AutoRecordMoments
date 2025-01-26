package com.mfc.recentaudiobuffer

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Singleton
    @Provides
    fun provideFirebaseAuth(): FirebaseAuth {
        return FirebaseAuth.getInstance()
    }

    @Singleton
    @Provides
    fun provideFirebaseFirestore(@ApplicationContext context: Context): FirebaseFirestore {
        val firestore = FirebaseFirestore.getInstance()
        val cacheSizeBytes = 100 * 1024 * 1024L // 100 MB
        val settings = PersistentCacheSettings.newBuilder().setSizeBytes(cacheSizeBytes).build()
        firestore.firestoreSettings =
            FirebaseFirestoreSettings.Builder().setLocalCacheSettings(settings).build()
        return firestore
    }

    @Singleton
    @Provides
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return context.dataStore
    }

    @Singleton
    @Provides
    fun provideSettingsRepository(
        @ApplicationContext context: Context,
        dataStore: DataStore<Preferences>,
        auth: FirebaseAuth,
        firestore: FirebaseFirestore
    ): SettingsRepository {
        return SettingsRepository(context, dataStore, auth, firestore)
    }

    @Singleton
    @Provides
    fun provideAuthenticationManager(
        @ApplicationContext applicationContext: Context,
        auth: FirebaseAuth,
        settingsRepository: SettingsRepository
    ): AuthenticationManager {
        return AuthenticationManager(applicationContext, auth, settingsRepository)
    }
}
package com.mfc.recentaudiobuffer

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import timber.log.Timber
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
        Timber.d("provideFirebaseFirestore started")
        val firestore = FirebaseFirestore.getInstance()
        val cacheSizeBytes = 100 * 1024 * 1024L // 100 MB
        val settings = PersistentCacheSettings.newBuilder().setSizeBytes(cacheSizeBytes).build()
        firestore.firestoreSettings =
            FirebaseFirestoreSettings.Builder().setLocalCacheSettings(settings).build()
        Timber.d("provideFirebaseFirestore finished")
        return firestore
    }
}
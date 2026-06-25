package com.example

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings

class RoutingApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        try {
            val options = FirebaseOptions.Builder()
                .setApiKey(BuildConfig.FIREBASE_API_KEY)
                .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                .setDatabaseUrl(BuildConfig.FIREBASE_DATABASE_URL)
                .setStorageBucket(BuildConfig.FIREBASE_STORAGE_BUCKET)
                .build()
                
            FirebaseApp.initializeApp(this, options)
            FirebaseFirestore.getInstance().firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setPersistenceEnabled(true)
                .build()
            Log.d("RoutingApplication", "Firebase initialized successfully.")
        } catch (e: Exception) {
            Log.e("RoutingApplication", "Failed to initialize Firebase: ${e.message}", e)
        }
    }
}

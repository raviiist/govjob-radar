package com.dhruvaxtechnologies.govjobradar.report

import android.content.Context
import com.dhruvaxtechnologies.govjobradar.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

/** Firebase set up from app-config.properties (no google-services.json needed). */
object FirebaseBridge {
    @Volatile private var app: FirebaseApp? = null

    val configured: Boolean
        get() = BuildConfig.FIREBASE_PROJECT_ID.isNotBlank() && BuildConfig.FIREBASE_APP_ID.isNotBlank() &&
            BuildConfig.FIREBASE_API_KEY.isNotBlank()

    fun init(context: Context): Boolean {
        if (app != null) return true
        if (!configured) return false
        return try {
            val opts = FirebaseOptions.Builder()
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                .setApiKey(BuildConfig.FIREBASE_API_KEY)
                .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                .build()
            app = FirebaseApp.getApps(context).firstOrNull() ?: FirebaseApp.initializeApp(context, opts)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun auth(): FirebaseAuth? = app?.let { FirebaseAuth.getInstance(it) }
    fun db(): FirebaseFirestore? = app?.let { FirebaseFirestore.getInstance(it) }
}

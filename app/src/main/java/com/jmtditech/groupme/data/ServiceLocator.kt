package com.jmtditech.groupme.data

import android.content.Context
import androidx.room.Room
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/** Tiny hand-rolled DI container; initialised once from [com.jmtditech.groupme.GroupMeApp]. */
object ServiceLocator {
    lateinit var appContext: Context
        private set
    lateinit var prefs: Prefs
        private set
    lateinit var db: AppDatabase
        private set
    lateinit var api: GroupMeApi
        private set
    lateinit var repo: Repository
        private set
    lateinit var faye: FayeClient
        private set
    lateinit var updater: Updater
        private set

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
        prefs = Prefs(appContext)
        db = Room.databaseBuilder(appContext, AppDatabase::class.java, "groupme.db")
            .addMigrations(MIGRATION_3_4)
            .fallbackToDestructiveMigration()
            .build()

        val http = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val t = prefs.token
                val req = if (t.isNullOrBlank()) chain.request()
                else chain.request().newBuilder().header("X-Access-Token", t).build()
                chain.proceed(req)
            }
            .build()

        api = Retrofit.Builder()
            .baseUrl("https://api.groupme.com/v3/")
            .client(http)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(GroupMeApi::class.java)

        faye = FayeClient(http.newBuilder().pingInterval(30, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build(), json)
        repo = Repository(api, db, prefs, appContext, json)

        // Deliberately a separate client WITHOUT the interceptor above: the GroupMe access token must never be
        // sent to GitHub.
        updater = Updater(
            appContext,
            OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build(),
            json,
        )
    }
}

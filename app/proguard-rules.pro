##############################################################
# CallerID Phone Lookup — R8 / ProGuard rules
##############################################################

# Keep debug-friendly stack traces (Crashlytics de-obfuscation).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes Signature,Exceptions,InnerClasses,EnclosingMethod
-keepattributes *Annotation*,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations

# -------------------------------------------------------------
# Android essentials
# -------------------------------------------------------------
-keep @androidx.annotation.Keep class * { *; }
-keepclasseswithmembers class * { @androidx.annotation.Keep <methods>; }
-keepclasseswithmembers class * { @androidx.annotation.Keep <fields>; }

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
-keepclassmembers class **.R$* { public static <fields>; }
-keepclassmembers class * { native <methods>; }

# View/Data binding generated classes.
-keep class com.find.my.phone.gps.location.tracker.databinding.** { *; }

# -------------------------------------------------------------
# App models — serialized by Gson (Retrofit) & parsed from
# Firebase Remote Config JSON. Field names must survive.
# -------------------------------------------------------------
-keep class com.find.my.phone.gps.location.tracker.models.** { *; }
-keep class com.find.my.phone.gps.location.tracker.adcast.data.** { *; }
-keepclassmembers class com.find.my.phone.gps.location.tracker.models.** { *; }

# -------------------------------------------------------------
# Kotlin
# -------------------------------------------------------------
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-dontwarn kotlinx.**
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# -------------------------------------------------------------
# Gson
# -------------------------------------------------------------
-keep class com.google.gson.** { *; }
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers,allowobfuscation,allowshrinking class * {
    @com.google.gson.annotations.Expose <fields>;
}
-dontwarn com.google.gson.**

# -------------------------------------------------------------
# Retrofit + OkHttp + Okio
# -------------------------------------------------------------
-keepclasseswithmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>
-dontwarn retrofit2.**
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.annotation.**

-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# -------------------------------------------------------------
# libphonenumber (offline geocoder / carrier / validation)
# Metadata lives in JAR resources — keep the classes that load it.
# -------------------------------------------------------------
-keep class com.google.i18n.phonenumbers.** { *; }
-dontwarn com.google.i18n.phonenumbers.**

# -------------------------------------------------------------
# Ads & monetization SDKs
# -------------------------------------------------------------
# Google Mobile Ads
-keep class com.google.android.gms.ads.** { *; }
-dontwarn com.google.android.gms.**
# Facebook Audience Network + Core SDK
-keep class com.facebook.** { *; }
-dontwarn com.facebook.**
# LightHouse push SDK ships its own consumer ProGuard rules in the AAR; the
# Firebase + Gson keeps below cover its FCM + JSON needs. (Replaced OneSignal.)
# Ad module — AdBeaconActivity is the open base every CanvasActivity extends and
# the class that drives Remote Config init / ad loading. It is declared in the
# manifest, so R8 already keeps the class name; this keeps its members too, so a
# subclass reaching one reflectively can never be stripped.
-keep class com.find.my.phone.gps.location.tracker.adcast.presentation.AdBeaconActivity { *; }

# -------------------------------------------------------------
# Firebase / Crashlytics / Remote Config
# -------------------------------------------------------------
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# -------------------------------------------------------------
# Google Play (in-app update) & install referrer
# -------------------------------------------------------------
-keep class com.google.android.play.core.** { *; }
-dontwarn com.google.android.play.core.**
-keep class com.android.installreferrer.** { *; }

# -------------------------------------------------------------
# Glide
# -------------------------------------------------------------
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep public class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}
-dontwarn com.bumptech.glide.**

# -------------------------------------------------------------
# Lottie / Shimmer (resource-driven, no reflection issues)
# -------------------------------------------------------------
-dontwarn com.airbnb.lottie.**
-dontwarn com.facebook.shimmer.**

# Dexter (runtime permissions)
-dontwarn com.karumi.dexter.**

# -------------------------------------------------------------
# Room (and Room-backed libs like WorkManager)
# Room instantiates its generated *_Impl database classes via
# reflection (Room.getGeneratedImplementation -> getDeclaredConstructor()).
# R8 full-mode strips those no-arg constructors, causing:
#   NoSuchMethodException: androidx.work.impl.WorkDatabase_Impl.<init> []
# Keep every RoomDatabase subclass' no-arg constructor.
# -------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keepclassmembers class * extends androidx.room.RoomDatabase {
    public abstract <methods>;
}
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.**

# -------------------------------------------------------------
# WorkManager — auto-initialized at startup via androidx.startup.
# Keep its Room DB impl and reflectively-constructed Workers.
# -------------------------------------------------------------
-keep class androidx.work.impl.WorkDatabase_Impl { <init>(); }
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-dontwarn androidx.work.**

# -------------------------------------------------------------
# Permission Engine + Full-Screen-Intent (FSI) flow
# (com.find.my.phone.gps.location.tracker.permission.**)
#
# Most of this package needs NO rules:
#  • FsiPortalActivity and FullScreenWatchService are declared in the manifest,
#    so R8 keeps them (and their entry points) automatically.
#  • FullScreenConfig, PermissionModels and the AccessSource / FirebaseAccessParser
#    Remote Config parsers read org.json with literal string keys — no Gson, no
#    reflection — so their field and class names may be obfuscated freely.
#  • AccessEngine, AccessQueue, AccessScheduler, AccessVault and
#    FullScreenReturnWatcher are called or registered directly in code, so they
#    are kept as reachable.
#
# The one reflective surface is the FragmentManager re-instantiating a Fragment
# BY NAME after a configuration change or process death. That applies to every
# Fragment in the app, not just this package's two (AccessLauncher and
# AccessSheetDialog), so the rule is written against the base class: naming
# individual Fragments here has already gone stale once — the Stage 3 rename left
# these pointing at classes that no longer existed, and R8 accepts rules for
# missing classes silently, so nothing surfaced it.
# -------------------------------------------------------------
-keepclassmembers class * extends androidx.fragment.app.Fragment {
    <init>();
}

# ===== GPS Phone Location Tracker: app data models read by Gson / reflection =====
# RecentSearchStore keeps NumberReport as a Gson string; the lookup, premium and vault models
# are (de)serialised by field name, so their names must survive R8.
-keep class com.find.my.phone.gps.location.tracker.data.** { *; }
-keep class com.find.my.phone.gps.location.tracker.ui.home.RecentSearch { *; }
-keep class com.find.my.phone.gps.location.tracker.ui.contacts.ContactNumber { *; }
-keep class com.find.my.phone.gps.location.tracker.ui.lookup.** { *; }
# The launcher module and the push SDK are wired by interface / reflection.
-keep class io.launcher.home.** { *; }
-dontwarn io.launcher.home.**
-keep class io.lighthouse.** { *; }
-dontwarn io.lighthouse.**
-keep class org.fossify.commons.** { *; }
-dontwarn org.fossify.commons.**
# Custom views inflated from XML by name.
-keep class com.find.my.phone.gps.location.tracker.ui.design.** { <init>(...); }
# Activities and fragments started by class name (adb, the debug manifest overlay, the launcher).
-keep class com.find.my.phone.gps.location.tracker.** extends android.app.Activity { *; }
-keep class com.find.my.phone.gps.location.tracker.** extends androidx.fragment.app.Fragment { *; }

# ===== Reminders (com.find.my.phone.gps.location.tracker.reminder) =====
# ReminderStore persists ReminderItem as Gson JSON with no @SerializedName: renamed fields would
# make every reminder saved by one release unreadable after the next update. The feature_config
# models (ReminderConfigDocument / ReminderSettings / ReminderSeedSpec) are Gson-parsed too; their
# @SerializedName fields are kept above, the classes and no-arg constructors here.
# ReminderWorker is created by WorkManager by name (covered by the Worker rule above).
-keep class com.find.my.phone.gps.location.tracker.reminder.ReminderItem { *; }
-keep class com.find.my.phone.gps.location.tracker.reminder.ReminderSettings { *; }
-keep class com.find.my.phone.gps.location.tracker.reminder.ReminderSeedSpec { *; }
-keep class com.find.my.phone.gps.location.tracker.reminder.ReminderConfigDocument { *; }

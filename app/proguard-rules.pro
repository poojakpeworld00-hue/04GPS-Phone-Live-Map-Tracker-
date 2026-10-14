
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes Signature,Exceptions,InnerClasses,EnclosingMethod
-keepattributes *Annotation*,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
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
-keep class com.find.phone.gps.live.location.tracker.databinding.** { *; }
-keep class com.find.phone.gps.live.location.tracker.models.** { *; }
-keep class com.find.phone.gps.live.location.tracker.monetize.model.** { *; }
-keepclassmembers class com.find.phone.gps.live.location.tracker.models.** { *; }
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-dontwarn kotlinx.**
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
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
-keep class com.google.i18n.phonenumbers.** { *; }
-dontwarn com.google.i18n.phonenumbers.**
-keep class com.google.android.gms.ads.** { *; }
-dontwarn com.google.android.gms.**
-keep class com.facebook.** { *; }
-dontwarn com.facebook.**
-keep class com.find.phone.gps.live.location.tracker.monetize.render.SpotBeaconActivity { *; }
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**
-keep class com.google.android.play.core.** { *; }
-dontwarn com.google.android.play.core.**
-keep class com.android.installreferrer.** { *; }
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep public class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}
-dontwarn com.bumptech.glide.**
-dontwarn com.airbnb.lottie.**
-dontwarn com.facebook.shimmer.**
-dontwarn com.karumi.dexter.**
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keepclassmembers class * extends androidx.room.RoomDatabase {
    public abstract <methods>;
}
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.**
-keep class androidx.work.impl.WorkDatabase_Impl { <init>(); }
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-dontwarn androidx.work.**
-keepclassmembers class * extends androidx.fragment.app.Fragment {
    <init>();
}
-keep class com.find.phone.gps.live.location.tracker.store.** { *; }
-keep class com.find.phone.gps.live.location.tracker.screens.dashboard.FreshHunt { *; }
-keep class com.find.phone.gps.live.location.tracker.screens.people.BuddyNum { *; }
-keep class com.find.phone.gps.live.location.tracker.screens.finder.** { *; }
-keep class com.livemap.homedeck.** { *; }
-dontwarn com.livemap.homedeck.**
-keep class io.lighthouse.** { *; }
-dontwarn io.lighthouse.**
-keep class org.fossify.commons.** { *; }
-dontwarn org.fossify.commons.**
-keep class com.find.phone.gps.live.location.tracker.screens.craft.** { <init>(...); }
-keep class com.find.phone.gps.live.location.tracker.** extends android.app.Activity { *; }
-keep class com.find.phone.gps.live.location.tracker.** extends androidx.fragment.app.Fragment { *; }
-keep class com.find.phone.gps.live.location.tracker.memo.MemoElement { *; }
-keep class com.find.phone.gps.live.location.tracker.memo.MemoOptions { *; }
-keep class com.find.phone.gps.live.location.tracker.memo.MemoSproutSchema { *; }
-keep class com.find.phone.gps.live.location.tracker.memo.MemoProfileDocument { *; }

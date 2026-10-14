# Room entities and DAOs are reflected over at runtime.
-keep class com.livemap.homedeck.entities.** { *; }
-keep interface com.livemap.homedeck.ports.**Dao { *; }

# Widget providers and the device-admin receiver are named in the manifest and instantiated by
# the system; R8 cannot see those references.
-keep class com.livemap.homedeck.applets.** { *; }
-keep class com.livemap.homedeck.catchers.** { *; }

# The custom views in the launcher layouts are inflated by name.
-keep class com.livemap.homedeck.widgetry.** { *; }
-keep class com.livemap.homedeck.parts.** { *; }

# The host implements these; keep the shapes it compiles against.
-keep interface com.livemap.homedeck.contract.** { *; }

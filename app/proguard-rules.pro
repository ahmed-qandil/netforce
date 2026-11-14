-optimizationpasses 5
-dontusemixedcaseclassnames
-verbose
-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*

-keepattributes *Annotation*

-ignorewarnings

-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.backup.BackupAgentHelper
-keep public class * extends android.preference.Preference

-keep class * implements android.os.Parcelable {
  public static final android.os.Parcelable$Creator *;
}

-keepnames class * implements java.io.Serializable

-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    !private <fields>;
    !private <methods>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

-keepclassmembers class **.R$* {
    public static <fields>;
}

-dontwarn androidx.window.extensions.**
-dontwarn androidx.window.sidecar.**
# Shizuku UserService: the privileged binder class is instantiated by name by the
# Shizuku server in a standalone process; keep its name and both constructors.
-keep class com.netforce.ims.privileged.PrivilegedService {
    <init>(...);
}
# AIDL bridge between the UI process and the privileged process.
-keep class com.netforce.ims.INetForceService { *; }
-keep class com.netforce.ims.INetForceService$Stub { *; }
-keep class com.netforce.ims.INetForceService$Stub$Proxy { *; }
-keep class com.netforce.ims.INetForceService$Default { *; }

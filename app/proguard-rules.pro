# Reflection into android.os.ServiceManager is used at runtime by PServerClient.
# Keep it visible to the reflection calls (the class is a framework class, but
# keep the rule so R8 does not rewrite/remove the call site).
-keep class android.os.ServiceManager { *; }

# Keep the parcel/binder plumbing used by the PServer protocol.
-keep class android.os.Parcel { *; }
-keep class android.os.IBinder { *; }

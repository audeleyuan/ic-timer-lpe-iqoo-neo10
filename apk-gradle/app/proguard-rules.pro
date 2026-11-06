# Shizuku instantiates the user service by class name from the component, so
# the service and its AIDL stub must survive shrinking.
-keep class dev.ic.timerlpe.IcService { *; }
-keep class dev.ic.timerlpe.IIcService { *; }
-keep interface dev.ic.timerlpe.IIcService { *; }
-keep class dev.ic.timerlpe.IIcService$Stub { *; }

# Shizuku client + provider are looked up through the manifest/binder.
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**

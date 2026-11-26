-adaptresourcefilecontents META-INF/xposed/java_init.list
-keepattributes RuntimeVisibleAnnotations
-keep,allowoptimization public class * extends io.github.libxposed.api.XposedModule {
    public <init>(...);
    public void onModuleLoaded(...);
    public void onPackageLoaded(...);
    public void onPackageReady(...);
}

-keepclasseswithmembernames,includedescriptorclasses class io.github.dszsu.smapix.nativebridge.NativeBridge {
    native <methods>;
}

-keepclassmembers,allowoptimization class ** implements io.github.libxposed.api.XposedInterface$Hooker {
    java.lang.Object intercept(io.github.libxposed.api.XposedInterface$Chain);
}

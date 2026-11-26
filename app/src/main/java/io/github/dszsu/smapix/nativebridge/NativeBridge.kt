package io.github.dszsu.smapix.nativebridge

@Suppress("KotlinJniMissingFunction")
object NativeBridge {
    @JvmStatic
    external fun nativeInstallHooks(bclDir: String, skiaSoPath: String): Boolean

    @JvmStatic
    external fun nativeBootstrapManaged(
        bootstrapDllPath: String,
        configJson: String,
        skiaSoPath: String
    ): Boolean

    @JvmStatic
    external fun nativeGetBclSwapCount(): Int
}

package io.github.dszsu.smapix.xposed

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import io.github.dszsu.smapix.BuildConfig
import io.github.dszsu.smapix.nativebridge.NativeBridge
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

class SmapiXposedModule : XposedModule() {

    companion object {
        const val TAG = "SMAPIX"
        const val TARGET_PACKAGE = "com.chucklefish.stardewvalley"
        const val PREFS_CONFIG = "smapix_config"
        const val KEY_ENABLE_SMAPI = "enable_smapi"
        const val KEY_SMAPI_STAMP = "smapi_zip_stamp"
        const val REMOTE_SMAPI_ZIP = "smapi_android_zip"
    }

    @Volatile
    private var currentProcessName: String = ""
    private val runtimeInitHooked = AtomicBoolean(false)
    private val nativeHooksAttempted = AtomicBoolean(false)
    private val managedBootstrapped = AtomicBoolean(false)

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        currentProcessName = param.processName
        Log.i(TAG, "onModuleLoaded process=$currentProcessName api=$apiVersion")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != TARGET_PACKAGE) return
        if (currentProcessName.isNotEmpty() && currentProcessName != TARGET_PACKAGE) {
            Log.i(TAG, "Detaching non-main process: $currentProcessName")
            detach()
            return
        }

        val cl = param.classLoader
        Log.i(TAG, "onPackageReady for $TARGET_PACKAGE (loader=$cl)")
        hookMonoStartup(cl)
    }

    private fun hookMonoStartup(cl: ClassLoader) {
        try {
            val monoPkgMgrClass = cl.loadClass("mono.MonoPackageManager")
            val runtimeClass = cl.loadClass("mono.android.Runtime")

            // 1. Pre-hook Runtime.initInternal so native ShadowHooks install right after
            // MonoPackageManager loads libmonosgen-2.0.so and BEFORE MonoVM initializes.
            for (method in runtimeClass.declaredMethods) {
                if (method.name == "initInternal" && runtimeInitHooked.compareAndSet(false, true)) {
                    hook(method).intercept { chain ->
                        onBeforeRuntimeInit(currentTargetContext)
                        chain.proceed()
                    }
                    Log.i(TAG, "Hooked mono.android.Runtime.initInternal: ${method.parameterCount} params")
                    break
                }
            }

            // 2. Hook MonoPackageManager.LoadApplication(Context, ApplicationInfo, String[])
            var loadAppHookCount = 0
            for (method in monoPkgMgrClass.declaredMethods) {
                if (method.name == "LoadApplication") {
                    hook(method).intercept { chain ->
                        val args = chain.args
                        val ctx = args.firstOrNull() as? Context
                        val appInfo = args.getOrNull(1) as? ApplicationInfo
                        @Suppress("UNCHECKED_CAST")
                        val apks = args.getOrNull(2) as? Array<String>
                        if (ctx != null) {
                            currentTargetContext = ctx
                            prepareHostWorkspace(ctx)
                        }

                        val result = chain.proceed()

                        if (ctx != null && managedBootstrapped.compareAndSet(false, true)) {
                            onAfterLoadApplication(ctx, appInfo, apks)
                        }
                        result
                    }
                    loadAppHookCount++
                }
            }
            Log.i(TAG, "Hooked $loadAppHookCount MonoPackageManager.LoadApplication overload(s)")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to install Java Mono startup hooks", t)
        }
    }

    @Volatile
    private var currentTargetContext: Context? = null

    @Volatile
    private var preparedBclDir: File? = null

    @Volatile
    private var preparedBootstrapDir: File? = null

    @Volatile
    private var preparedNativeDir: File? = null

    @Volatile
    private var preparedSmapiZip: File? = null

    @Volatile
    private var preparedSmapiStamp: String = ""

    @Volatile
    private var smapiEnabledFlag: Boolean = true

    private fun prepareHostWorkspace(context: Context) {
        val lspDir = File(context.filesDir, "smapix")
        val bclDir = File(lspDir, "bcl")
        val bootstrapDir = File(lspDir, "bootstrap")
        val nativeDir = File(lspDir, "native")
        lspDir.mkdirs()

        extractModuleAssetsIfNeeded(lspDir, bclDir, bootstrapDir, nativeDir)
        syncSmapiArchiveAndPrefs(lspDir)

        preparedBclDir = bclDir
        preparedBootstrapDir = bootstrapDir
        preparedNativeDir = nativeDir

        loadModuleNativeLibraries()
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    private fun loadModuleNativeLibraries() {
        val nativeDir = moduleApplicationInfo.nativeLibraryDir
        val extractedFile = File(nativeDir, "libsmapix.so")
        val apkEntryPath = "${moduleApplicationInfo.sourceDir}!/lib/arm64-v8a/libsmapix.so"
        try {
            when {
                extractedFile.exists() -> {
                    System.load(extractedFile.absolutePath)
                    Log.i(TAG, "Loaded libsmapix.so from $nativeDir")
                }
                runCatching { System.load(apkEntryPath) }.isSuccess -> {
                    Log.i(TAG, "Loaded libsmapix.so from $apkEntryPath")
                }
                else -> {
                    System.loadLibrary("smapix")
                    Log.i(TAG, "Loaded libsmapix via System.loadLibrary")
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load libsmapix.so (nativeDir=$nativeDir, apkEntry=$apkEntryPath)", t)
            throw t
        }
    }

    private fun extractModuleAssetsIfNeeded(
        lspDir: File,
        bclDir: File,
        bootstrapDir: File,
        nativeDir: File
    ) {
        val moduleApkPath = moduleApplicationInfo.sourceDir
        val moduleApk = File(moduleApkPath)
        val expectedStamp = "${BuildConfig.VERSION_CODE}:${moduleApk.length()}:${moduleApk.lastModified()}"
        val stampFile = File(lspDir, ".module_stamp")

        if (stampFile.exists() && stampFile.readText().trim() == expectedStamp &&
            File(bclDir, "System.Private.CoreLib.dll").exists() &&
            File(bootstrapDir, "SMAPI.LspBootstrap.dll").exists() &&
            File(nativeDir, "libSkiaSharp.so").exists()
        ) {
            return
        }

        Log.i(TAG, "Extracting BCL, Bootstrap & Native assets from $moduleApkPath -> ${lspDir.absolutePath}")
        bclDir.mkdirs()
        bootstrapDir.mkdirs()
        nativeDir.mkdirs()

        ZipFile(moduleApkPath).use { apkZip ->
            // 1. Extract assets/bcl.zip -> bclDir
            val bclEntry = apkZip.getEntry("assets/bcl.zip")
                ?: throw IllegalStateException("assets/bcl.zip missing in module APK")
            val tmpBclZip = File(lspDir, "bcl.tmp.zip")
            apkZip.getInputStream(bclEntry).use { input ->
                FileOutputStream(tmpBclZip).use { output -> input.copyTo(output) }
            }
            ZipFile(tmpBclZip).use { bclZip ->
                val entries = bclZip.entries()
                var count = 0
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory || entry.name.contains("..")) continue
                    val out = File(bclDir, File(entry.name).name)
                    bclZip.getInputStream(entry).use { input ->
                        FileOutputStream(out).use { output -> input.copyTo(output) }
                    }
                    count++
                }
                Log.i(TAG, "Extracted $count untrimmed .NET 9.0.8 BCL assemblies")
            }
            tmpBclZip.delete()

            // 2. Extract assets/bootstrap/* -> bootstrapDir & assets/native/arm64-v8a/* -> nativeDir
            var bsCount = 0
            var nativeCount = 0
            val allEntries = apkZip.entries()
            while (allEntries.hasMoreElements()) {
                val entry = allEntries.nextElement()
                if (entry.isDirectory || entry.name.contains("..")) continue
                when {
                    entry.name.startsWith("assets/bootstrap/") -> {
                        val fileName = entry.name.removePrefix("assets/bootstrap/")
                        if (fileName.isNotEmpty() && !fileName.contains('/')) {
                            val out = File(bootstrapDir, fileName)
                            apkZip.getInputStream(entry).use { input ->
                                FileOutputStream(out).use { output -> input.copyTo(output) }
                            }
                            bsCount++
                        }
                    }
                    entry.name.startsWith("assets/native/arm64-v8a/") -> {
                        val fileName = entry.name.removePrefix("assets/native/arm64-v8a/")
                        if (fileName.isNotEmpty() && !fileName.contains('/')) {
                            val out = File(nativeDir, fileName)
                            apkZip.getInputStream(entry).use { input ->
                                FileOutputStream(out).use { output -> input.copyTo(output) }
                            }
                            nativeCount++
                        }
                    }
                }
            }
            Log.i(TAG, "Extracted $bsCount bootstrap files and $nativeCount native files")
        }

        stampFile.writeText(expectedStamp)
    }

    private fun syncSmapiArchiveAndPrefs(lspDir: File) {
        var enableSmapi = true
        var remoteStamp = ""
        try {
            val prefs = getRemotePreferences(PREFS_CONFIG)
            enableSmapi = prefs.getBoolean(KEY_ENABLE_SMAPI, true)
            remoteStamp = prefs.getString(KEY_SMAPI_STAMP, "") ?: ""
        } catch (t: Throwable) {
            Log.w(TAG, "getRemotePreferences unavailable: ${t.message}")
        }
        smapiEnabledFlag = enableSmapi

        val localZip = File(lspDir, "smapi_android.zip")
        val localStampFile = File(lspDir, ".smapi_remote_stamp")
        val localStamp = if (localStampFile.exists()) localStampFile.readText().trim() else ""

        if (remoteStamp.isNotEmpty() && remoteStamp == localStamp && localZip.exists()) {
            preparedSmapiZip = localZip
            preparedSmapiStamp = remoteStamp
            return
        }

        try {
            val pfd = openRemoteFile(REMOTE_SMAPI_ZIP)
            pfd.use {
                val tmp = File(lspDir, "smapi_android.tmp.zip")
                FileInputStream(it.fileDescriptor).use { input ->
                    FileOutputStream(tmp).use { output -> input.copyTo(output) }
                }
                if (tmp.length() > 0) {
                    if (localZip.exists()) localZip.delete()
                    tmp.renameTo(localZip)
                    val effectiveStamp = remoteStamp.ifEmpty { "${localZip.length()}:${localZip.lastModified()}" }
                    localStampFile.writeText(effectiveStamp)
                    preparedSmapiZip = localZip
                    preparedSmapiStamp = effectiveStamp
                    Log.i(TAG, "Synced SMAPI archive from remote file (${localZip.length()} bytes)")
                    return
                } else {
                    tmp.delete()
                }
            }
        } catch (t: Throwable) {
            Log.i(TAG, "Remote SMAPI archive not active (${t.javaClass.simpleName}: ${t.message})")
        }

        if (localZip.exists()) {
            preparedSmapiZip = localZip
            preparedSmapiStamp = localStamp.ifEmpty { "${localZip.length()}" }
        } else {
            preparedSmapiZip = null
            preparedSmapiStamp = ""
        }
    }

    private fun onBeforeRuntimeInit(context: Context?) {
        if (!nativeHooksAttempted.compareAndSet(false, true)) return
        val bclPath = preparedBclDir?.absolutePath
            ?: context?.let { File(it.filesDir, "smapix/bcl").absolutePath }
            ?: ""
        val nativeDir = preparedNativeDir
            ?: context?.let { File(it.filesDir, "smapix/native") }
        val skiaPath = nativeDir?.let { File(it, "libSkiaSharp.so").absolutePath } ?: ""
        val ok = NativeBridge.nativeInstallHooks(bclPath, skiaPath)
        Log.i(TAG, "nativeInstallHooks($bclPath, $skiaPath) -> $ok")
    }

    private fun onAfterLoadApplication(
        context: Context,
        appInfo: ApplicationInfo?,
        apks: Array<String>?
    ) {
        val nativeDir = preparedNativeDir ?: File(context.filesDir, "smapix/native")
        val skiaPath = File(nativeDir, "libSkiaSharp.so").absolutePath
        val bclDir = preparedBclDir ?: File(context.filesDir, "smapix/bcl")
        val bootstrapDir = preparedBootstrapDir ?: File(context.filesDir, "smapix/bootstrap")
        val assembliesDir = File(context.filesDir, "Stardew Assemblies")
        val bootstrapDll = File(bootstrapDir, "SMAPI.LspBootstrap.dll")

        val resolvedInfo = appInfo ?: context.applicationInfo
        val baseApk = apks?.firstOrNull { it.endsWith("/base.apk") }
            ?: resolvedInfo.sourceDir
            ?: ""
        val splitArm64 = apks?.firstOrNull { it.contains("arm64") && it.endsWith(".apk") }
            ?: resolvedInfo.splitSourceDirs?.firstOrNull { it.contains("arm64") }
            ?: ""

        val smapiZip = preparedSmapiZip
        val hasSmapiZip = smapiZip != null && smapiZip.exists()

        val gameStamp = listOf(baseApk, splitArm64).joinToString("|") { p ->
            val f = File(p)
            if (p.isNotEmpty() && f.exists()) "${f.length()}:${f.lastModified()}" else "0"
        }

        val gameVersionName = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.6.15.3"

        val configText = buildString {
            append("bclDir=").append(bclDir.absolutePath).append('\n')
            append("bootstrapDir=").append(bootstrapDir.absolutePath).append('\n')
            append("assembliesDir=").append(assembliesDir.absolutePath).append('\n')
            append("smapiZipPath=").append(smapiZip?.absolutePath ?: "").append('\n')
            append("nativeLibDir=").append(nativeDir.absolutePath).append('\n')
            append("baseApkPath=").append(baseApk).append('\n')
            append("splitArm64ApkPath=").append(splitArm64).append('\n')
            append("smapiZipStamp=").append(preparedSmapiStamp).append('\n')
            append("gameStamp=").append(gameStamp).append('\n')
            append("gameVersionName=").append(gameVersionName).append('\n')
            append("enableSmapi=").append(smapiEnabledFlag && hasSmapiZip).append('\n')
        }

        val swaps = NativeBridge.nativeGetBclSwapCount()
        Log.i(
            TAG,
            "Launching managed bootstrap: bclSwaps=$swaps enableSmapi=${smapiEnabledFlag && hasSmapiZip} splitArm64=$splitArm64"
        )
        val ok = NativeBridge.nativeBootstrapManaged(
            bootstrapDll.absolutePath,
            configText,
            skiaPath
        )
        Log.i(TAG, "nativeBootstrapManaged -> $ok")
    }
}

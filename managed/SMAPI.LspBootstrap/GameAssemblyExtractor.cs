using System.Text.RegularExpressions;
using Xamarin.Android.AssemblyStore;

namespace SMAPI.LspBootstrap;

internal static class GameAssemblyExtractor
{
    public static void EnsureGameAssembliesExtracted(BootstrapConfig config)
    {
        var assembliesDir = config.AssembliesDir;
        Directory.CreateDirectory(assembliesDir);

        var stampPath = Path.Combine(assembliesDir, ".game_stamp");
        var stardewDllPath = Path.Combine(assembliesDir, "StardewValley.dll");
        var monoAndroidPath = Path.Combine(assembliesDir, "Mono.Android.dll");

        var desiredStamp = !string.IsNullOrEmpty(config.GameStamp)
            ? config.GameStamp
            : ComputeApkStamp(config.BaseApkPath, config.SplitArm64ApkPath);

        if (File.Exists(stampPath) && File.Exists(stardewDllPath) && File.Exists(monoAndroidPath))
        {
            if (File.ReadAllText(stampPath).Trim() == desiredStamp)
            {
                LinkOrCopySupportAssemblies(config.BclDir, config.BootstrapDir, assembliesDir);
                BootstrapLog.Info("Game assemblies up-to-date in Stardew Assemblies");
                return;
            }
        }

        // Build set of pure .NET 9.0.8 BCL filenames so we NEVER overwrite them with trimmed APK copies,
        // and conversely NEVER overwrite official Mono.Android / game DLLs with anything from bclDir.
        var bclNames = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        if (Directory.Exists(config.BclDir))
        {
            foreach (var file in Directory.GetFiles(config.BclDir, "*.dll"))
            {
                bclNames.Add(Path.GetFileName(file));
            }
        }

        int extractedCount = 0;
        if (!string.IsNullOrEmpty(config.SplitArm64ApkPath) && File.Exists(config.SplitArm64ApkPath))
        {
            extractedCount += ExtractFromStore(config.SplitArm64ApkPath, assembliesDir, bclNames);
        }
        if (extractedCount == 0 && !string.IsNullOrEmpty(config.BaseApkPath) && File.Exists(config.BaseApkPath))
        {
            extractedCount += ExtractFromStore(config.BaseApkPath, assembliesDir, bclNames);
        }

        BootstrapLog.Info($"Extracted {extractedCount} non-BCL game/Android assemblies into {assembliesDir}");
        LinkOrCopySupportAssemblies(config.BclDir, config.BootstrapDir, assembliesDir);
        File.WriteAllText(stampPath, desiredStamp);
    }

    private static int ExtractFromStore(string storeApkPath, string assembliesDir, HashSet<string> bclNames)
    {
        int count = 0;
        var explorer = new AssemblyStoreExplorer(
            storeApkPath,
            (level, msg) =>
            {
                if (level == AssemblyStoreExplorerLogLevel.Error)
                {
                    BootstrapLog.Warn($"AssemblyStore: {msg}");
                }
            },
            keepStoreInMemory: true);

        foreach (var asm in explorer.Assemblies)
        {
            var dllName = asm.DllName;
            if (string.IsNullOrEmpty(dllName)) continue;

            // Keep pure .NET 9.0.8 untrimmed BCLs instead of official trimmed BCLs
            if (bclNames.Contains(dllName)) continue;

            asm.ExtractImage(assembliesDir, dllName, decompress: true);
            count++;
        }

        return count;
    }

    /// <summary>
    /// Ensures untrimmed 9.0.8 BCLs and SharedLibs (0Harmony, MonoMod, SkiaSharp, Mono.Cecil)
    /// are visible inside EarlyConstants.GamePath so Mono.Cecil & SMAPI's AssemblyLoader resolve
    /// every metadata reference cleanly on disk.
    /// </summary>
    private static void LinkOrCopySupportAssemblies(string bclDir, string bootstrapDir, string assembliesDir)
    {
        LinkOrCopyDir(bclDir, assembliesDir, skipIfExists: false);
        LinkOrCopyDir(bootstrapDir, assembliesDir, skipIfExists: false);
    }

    private static void LinkOrCopyDir(string srcDir, string dstDir, bool skipIfExists)
    {
        if (string.IsNullOrEmpty(srcDir) || !Directory.Exists(srcDir)) return;
        foreach (var srcFile in Directory.GetFiles(srcDir, "*.dll"))
        {
            var fileName = Path.GetFileName(srcFile);
            if (fileName.Equals("SMAPI.LspBootstrap.dll", StringComparison.OrdinalIgnoreCase)) continue;
            var dstFile = Path.Combine(dstDir, fileName);
            if (skipIfExists && File.Exists(dstFile)) continue;

            try
            {
                var fi = new FileInfo(dstFile);
                if (fi.LinkTarget != null || File.Exists(dstFile))
                {
                    if (fi.LinkTarget == srcFile && File.Exists(srcFile)) continue;
                    File.Delete(dstFile);
                }
                File.CreateSymbolicLink(dstFile, srcFile);
            }
            catch
            {
                File.Copy(srcFile, dstFile, overwrite: true);
            }
        }
    }

    private static string ComputeApkStamp(string baseApk, string splitApk)
    {
        static string Part(string p) => File.Exists(p)
            ? $"{new FileInfo(p).Length}:{new FileInfo(p).LastWriteTimeUtc.Ticks}"
            : "0";
        return $"{Part(baseApk)}|{Part(splitApk)}";
    }
}

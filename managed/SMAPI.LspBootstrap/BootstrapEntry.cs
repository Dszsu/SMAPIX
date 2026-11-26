using System.Reflection;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;

namespace SMAPI.LspBootstrap;

public sealed class BootstrapConfig
{
    public string BclDir { get; set; } = string.Empty;
    public string BootstrapDir { get; set; } = string.Empty;
    public string AssembliesDir { get; set; } = string.Empty;
    public string SmapiZipPath { get; set; } = string.Empty;
    public string NativeLibDir { get; set; } = string.Empty;
    public string BaseApkPath { get; set; } = string.Empty;
    public string SplitArm64ApkPath { get; set; } = string.Empty;
    public string SmapiZipStamp { get; set; } = string.Empty;
    public string GameStamp { get; set; } = string.Empty;
    public string GameVersionName { get; set; } = "1.6.15.3";
    public bool EnableSmapi { get; set; } = true;

    public static BootstrapConfig ParseKeyValues(string raw)
    {
        var cfg = new BootstrapConfig();
        if (string.IsNullOrEmpty(raw)) return cfg;
        var lines = raw.Split('\n');
        foreach (var line in lines)
        {
            var idx = line.IndexOf('=');
            if (idx <= 0) continue;
            var key = line.Substring(0, idx).Trim();
            var val = line.Substring(idx + 1).Trim();
            switch (key)
            {
                case "bclDir": cfg.BclDir = val; break;
                case "bootstrapDir": cfg.BootstrapDir = val; break;
                case "assembliesDir": cfg.AssembliesDir = val; break;
                case "smapiZipPath": cfg.SmapiZipPath = val; break;
                case "nativeLibDir": cfg.NativeLibDir = val; break;
                case "baseApkPath": cfg.BaseApkPath = val; break;
                case "splitArm64ApkPath": cfg.SplitArm64ApkPath = val; break;
                case "smapiZipStamp": cfg.SmapiZipStamp = val; break;
                case "gameStamp": cfg.GameStamp = val; break;
                case "gameVersionName": cfg.GameVersionName = val; break;
                case "enableSmapi": cfg.EnableSmapi = string.Equals(val, "true", StringComparison.OrdinalIgnoreCase); break;
            }
        }
        return cfg;
    }
}

internal static class BootstrapLog
{
    [DllImport("log", EntryPoint = "__android_log_print")]
    private static extern int AndroidLogPrint(int prio, string tag, string fmt, string msg);

    private static readonly object _fileLock = new();
    private static string? _logFilePath;

    public static void InitFile(string path)
    {
        try
        {
            var dir = Path.GetDirectoryName(path);
            if (!string.IsNullOrEmpty(dir)) Directory.CreateDirectory(dir);
            _logFilePath = path;
            File.WriteAllText(path, $"=== SMAPIX_CS session start {DateTime.UtcNow:O} ===\n");
        }
        catch { }
    }

    public static void Info(string msg) => Write(4, "INF", msg);
    public static void Warn(string msg) => Write(5, "WRN", msg);
    public static void Error(string msg) => Write(6, "ERR", msg);

    private static void Write(int prio, string lvl, string msg)
    {
        try { AndroidLogPrint(prio, "SMAPIX_CS", "%s", msg); }
        catch { Console.WriteLine($"[SMAPIX_CS/{lvl}] {msg}"); }

        var path = _logFilePath;
        if (!string.IsNullOrEmpty(path))
        {
            try
            {
                lock (_fileLock)
                {
                    File.AppendAllText(path, $"[{DateTime.Now:HH:mm:ss.fff} {lvl}] {msg}\n");
                }
            }
            catch { }
        }
    }
}

public static class BootstrapEntry
{
    private static BootstrapConfig? _config;
    private static int _initialized;

    // IMPORTANT: Keep this method free of any types from assemblies absent in official XABA
    // (such as System.Text.Json or System.Runtime.Loader) so JITting Initialize() succeeds
    // and installs AppDomain.CurrentDomain.AssemblyResolve BEFORE any extra BCL loads.
    public static void Initialize(string configKeyValues)
    {
        if (Interlocked.Exchange(ref _initialized, 1) == 1)
        {
            BootstrapLog.Warn("BootstrapEntry.Initialize already executed");
            return;
        }

        _config = BootstrapConfig.ParseKeyValues(configKeyValues);
        AppDomain.CurrentDomain.AssemblyResolve += ResolveManagedAssembly;

        BootstrapLog.Info($"BootstrapEntry initialized. EnableSmapi={_config.EnableSmapi}, BclDir={_config.BclDir}");
        BootstrapRunner.Run(_config);
    }

    private static Assembly? ResolveManagedAssembly(object? sender, ResolveEventArgs args)
    {
        if (_config == null) return null;
        var reqName = new AssemblyName(args.Name).Name;
        if (string.IsNullOrEmpty(reqName)) return null;

        // 1. Return already-loaded assembly first (prevents duplicate type identity for StardewValley / Mono.Android)
        foreach (var loaded in AppDomain.CurrentDomain.GetAssemblies())
        {
            if (string.Equals(loaded.GetName().Name, reqName, StringComparison.OrdinalIgnoreCase))
            {
                return loaded;
            }
        }

        // 2. Search bootstrapDir, assembliesDir, bclDir using Assembly.LoadFrom (in System.Runtime)
        var fileName = reqName + ".dll";
        string[] searchDirs = { _config.BootstrapDir, _config.AssembliesDir, _config.BclDir };
        foreach (var dir in searchDirs)
        {
            if (string.IsNullOrEmpty(dir)) continue;
            var candidate = Path.Combine(dir, fileName);
            if (File.Exists(candidate))
            {
                try
                {
                    return Assembly.LoadFrom(candidate);
                }
                catch (Exception ex)
                {
                    BootstrapLog.Warn($"Assembly.LoadFrom({candidate}) failed: {ex.Message}");
                }
            }
        }

        return null;
    }
}

internal static class BootstrapRunner
{
    [MethodImpl(MethodImplOptions.NoInlining)]
    public static void Run(BootstrapConfig config)
    {
        try
        {
            var lspBaseDir = !string.IsNullOrEmpty(config.BclDir)
                ? Path.GetDirectoryName(config.BclDir)
                : null;
            if (!string.IsNullOrEmpty(lspBaseDir))
            {
                BootstrapLog.InitFile(Path.Combine(lspBaseDir, "bootstrap.log"));
                BootstrapLog.Info($"BootstrapRunner.Run started (EnableSmapi={config.EnableSmapi})");
            }

            NativeResolver.Install(config.NativeLibDir);

            if (!config.EnableSmapi)
            {
                BootstrapLog.Info("EnableSmapi=false; running vanilla Stardew Valley under Pure JIT + 9.0.8 BCL");
                return;
            }

            GameAssemblyExtractor.EnsureGameAssembliesExtracted(config);

            if (!SmapiZipExtractor.EnsureSmapiExtracted(config.SmapiZipPath, config.AssembliesDir, config.SmapiZipStamp, config.GameVersionName))
            {
                BootstrapLog.Warn("SMAPI zip unavailable or invalid; falling back to vanilla Pure JIT launch.");
                return;
            }

            MainActivityHooks.Install(config.AssembliesDir);
        }
        catch (Exception ex)
        {
            BootstrapLog.Error($"BootstrapRunner.Run fatal error: {ex}");
        }
    }
}

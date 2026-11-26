using System.Reflection;
using System.Runtime.Loader;
using HarmonyLib;

namespace SMAPI.LspBootstrap;

internal static class MainActivityHooks
{
    private static string _assembliesDir = string.Empty;
    private static int _smapiStarted;
    private static Assembly? _smapiAssembly;
    private static Type? _cecilModuleDefType;
    private static Type? _cecilReaderParamsType;
    private static MethodInfo? _cecilReadModuleMethod;

    public static void Install(string assembliesDir)
    {
        _assembliesDir = assembliesDir;
        var smapiDllPath = Path.Combine(assembliesDir, "StardewModdingAPI.dll");
        if (!File.Exists(smapiDllPath))
        {
            BootstrapLog.Error($"Cannot install MainActivity hooks: missing {smapiDllPath}");
            return;
        }

        // Load StardewModdingAPI.dll from Stardew Assemblies/ so EarlyConstants.GamePath
        // resolves to assembliesDir via Assembly.GetExecutingAssembly().Location.
        _smapiAssembly = AssemblyLoadContext.Default.LoadFromAssemblyPath(smapiDllPath);
        BootstrapLog.Info($"Loaded SMAPI assembly from {smapiDllPath}");

        var svAsm = AppDomain.CurrentDomain.GetAssemblies()
            .FirstOrDefault(a => string.Equals(a.GetName().Name, "StardewValley", StringComparison.OrdinalIgnoreCase))
            ?? Assembly.Load("StardewValley");
        var mgAsm = AppDomain.CurrentDomain.GetAssemblies()
            .FirstOrDefault(a => string.Equals(a.GetName().Name, "MonoGame.Framework", StringComparison.OrdinalIgnoreCase));

        LogAsmLocation("StardewValley", svAsm);
        LogAsmLocation("MonoGame.Framework", mgAsm);
        LogAsmLocation("StardewModdingAPI", _smapiAssembly);

        var mainActivityType = svAsm.GetType("StardewValley.MainActivity", throwOnError: true)!;
        var onCreatePartTwo = AccessTools.Method(mainActivityType, "OnCreatePartTwo");
        if (onCreatePartTwo == null)
        {
            BootstrapLog.Error("StardewValley.MainActivity.OnCreatePartTwo method not found");
            return;
        }

        var harmony = new Harmony("io.github.dszsu.smapix.bootstrap");
        harmony.Patch(
            onCreatePartTwo,
            prefix: new HarmonyMethod(typeof(MainActivityHooks), nameof(Prefix_OnCreatePartTwo)));

        var checkStorageMigration = AccessTools.Method(mainActivityType, "CheckStorageMigration");
        if (checkStorageMigration != null)
        {
            harmony.Patch(
                checkStorageMigration,
                prefix: new HarmonyMethod(typeof(MainActivityHooks), nameof(Prefix_CheckStorageMigration)));
        }

        InstallSmapiDiagnosticsAndShims(harmony);
        ModMenuInputAdapter.Install(harmony, svAsm, mgAsm, _smapiAssembly);

        BootstrapLog.Info("Installed Harmony hook on StardewValley.MainActivity.OnCreatePartTwo");
    }

    private static void LogAsmLocation(string label, Assembly? asm)
    {
        if (asm == null)
        {
            BootstrapLog.Warn($"AsmLocation[{label}]: <not loaded>");
            return;
        }
        string loc = "<err>";
        string fqn = "<err>";
        try { loc = asm.Location ?? "<null>"; } catch (Exception ex) { loc = $"<ex:{ex.Message}>"; }
        try { fqn = asm.Modules.FirstOrDefault()?.FullyQualifiedName ?? "<null>"; } catch (Exception ex) { fqn = $"<ex:{ex.Message}>"; }
        BootstrapLog.Info($"AsmLocation[{label}]: Location='{loc}', ManifestFQN='{fqn}'");
    }

    private static void InstallSmapiDiagnosticsAndShims(Harmony harmony)
    {
        if (_smapiAssembly == null) return;

        TaskScheduler.UnobservedTaskException += (_, e) =>
        {
            BootstrapLog.Error($"UnobservedTaskException: {e.Exception}");
        };

        try
        {
            // Fix PlatformAssemblyMap module loading when StardewValley.dll / MonoGame.Framework.dll
            // were loaded from Xamarin AssemblyStore instead of Stardew Assemblies/ directory
            var pamType = _smapiAssembly.GetType("StardewModdingAPI.Framework.ModLoading.PlatformAssemblyMap");
            var pamNested = pamType?.GetNestedType("<>c", BindingFlags.NonPublic);
            var modLoaderSelector = pamNested?.GetMethods(BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance | BindingFlags.Static)
                .FirstOrDefault(m =>
                    m.ReturnType.FullName == "Mono.Cecil.ModuleDefinition" &&
                    m.GetParameters().Length == 1 &&
                    m.GetParameters()[0].ParameterType == typeof(Assembly));
            if (modLoaderSelector != null)
            {
                var cecilAsm = modLoaderSelector.ReturnType.Assembly;
                _cecilModuleDefType = modLoaderSelector.ReturnType;
                _cecilReaderParamsType = cecilAsm.GetType("Mono.Cecil.ReaderParameters");
                if (_cecilModuleDefType != null && _cecilReaderParamsType != null)
                {
                    _cecilReadModuleMethod = _cecilModuleDefType.GetMethod(
                        "ReadModule",
                        BindingFlags.Public | BindingFlags.Static,
                        null,
                        new[] { typeof(string), _cecilReaderParamsType },
                        null);
                }

                harmony.Patch(
                    modLoaderSelector,
                    prefix: new HarmonyMethod(typeof(MainActivityHooks), nameof(Prefix_PlatformAssemblyMapReadModule)));
                BootstrapLog.Info($"Patched PlatformAssemblyMap selector: {modLoaderSelector.Name} (Cecil asm={cecilAsm.GetName().Version})");
            }
            else
            {
                BootstrapLog.Warn("PlatformAssemblyMap<>c ModuleDefinition selector not found");
            }

            // Capture silent background exceptions in SCore.LoadMods / InitializeBeforeFirstAssetLoaded
            var sCoreType = _smapiAssembly.GetType("StardewModdingAPI.Framework.SCore");
            if (sCoreType != null)
            {
                var finMethod = AccessTools.Method(typeof(MainActivityHooks), nameof(Finalizer_LogException));
                foreach (var name in new[] { "LoadMods", "InitializeBeforeFirstAssetLoaded", "OnGameUpdateForModLoader" })
                {
                    var m = AccessTools.Method(sCoreType, name);
                    if (m != null)
                    {
                        harmony.Patch(m, finalizer: new HarmonyMethod(finMethod));
                    }
                }
                BootstrapLog.Info("Installed SCore diagnostic finalizers");
            }
        }
        catch (Exception ex)
        {
            BootstrapLog.Error($"InstallSmapiDiagnosticsAndShims failed: {ex}");
        }
    }

    private static object? ReadCecilModuleInMemory(string path)
    {
        if (_cecilReadModuleMethod == null || _cecilReaderParamsType == null) return null;
        var rp = Activator.CreateInstance(_cecilReaderParamsType)!;
        _cecilReaderParamsType.GetProperty("InMemory")?.SetValue(rp, true);
        return _cecilReadModuleMethod.Invoke(null, new[] { path, rp });
    }

    // Handles Assembly.Modules.Single().FullyQualifiedName returning <Unknown> or APK-internal path
    // when game/MonoGame assemblies are loaded from Xamarin AssemblyStore instead of disk.
    private static bool Prefix_PlatformAssemblyMapReadModule(Assembly assembly, ref object __result)
    {
        try
        {
            var fqn = assembly.Modules.FirstOrDefault()?.FullyQualifiedName ?? string.Empty;
            var candidate = Path.Combine(_assembliesDir, fqn);
            BootstrapLog.Info($"PlatformAssemblyMap resolving '{assembly.GetName().Name}': fqn='{fqn}', candidate='{candidate}', exists={File.Exists(candidate)}");
            if (File.Exists(candidate))
            {
                var loaded = ReadCecilModuleInMemory(candidate);
                if (loaded != null) { __result = loaded; return false; }
            }

            var shortName = assembly.GetName().Name + ".dll";
            var fallback = Path.Combine(_assembliesDir, shortName);
            BootstrapLog.Warn($"PlatformAssemblyMap fallback for '{assembly.GetName().Name}': '{fallback}', exists={File.Exists(fallback)}");
            if (File.Exists(fallback))
            {
                var loaded = ReadCecilModuleInMemory(fallback);
                if (loaded != null) { __result = loaded; return false; }
            }
        }
        catch (Exception ex)
        {
            BootstrapLog.Error($"Prefix_PlatformAssemblyMapReadModule error: {ex}");
        }
        return true;
    }

    private static Exception? Finalizer_LogException(MethodBase __originalMethod, Exception? __exception)
    {
        if (__exception != null)
        {
            BootstrapLog.Error($"Exception inside {__originalMethod.DeclaringType?.Name}.{__originalMethod.Name}: {__exception}");
        }
        return __exception;
    }

    // Bypass legacy /sdcard/StardewValley migration prompt blocking SMAPI boot if desired,
    // returning false lets OnCreate run OnCreatePartTwo immediately unless user has unmigrated legacy data.
    private static bool Prefix_CheckStorageMigration(ref bool __result)
    {
        return true; // Run official check; OnCreatePartTwo hook captures after migration too
    }

    private static bool Prefix_OnCreatePartTwo(object __instance)
    {
        if (Interlocked.Exchange(ref _smapiStarted, 1) == 1)
        {
            BootstrapLog.Warn("Prefix_OnCreatePartTwo called more than once");
            return false;
        }

        try
        {
            BootstrapLog.Info("Entering Prefix_OnCreatePartTwo -> launching SMAPI");
            var svAsm = __instance.GetType().Assembly;

            // 1. MobileDisplay.SetupDisplaySettings()
            var mobileDisplay = svAsm.GetType("StardewValley.Mobile.MobileDisplay");
            AccessTools.Method(mobileDisplay, "SetupDisplaySettings")?.Invoke(null, null);

            // 2. MainActivity.SetPaddingForMenus()
            AccessTools.Method(__instance.GetType(), "SetPaddingForMenus")?.Invoke(__instance, null);

            // 3. StardewModdingAPI.Program.Main(Array.Empty<string>())
            var programType = _smapiAssembly?.GetType("StardewModdingAPI.Program", throwOnError: true)
                ?? throw new InvalidOperationException("StardewModdingAPI.Program not found");
            var mainMethod = AccessTools.Method(programType, "Main", new[] { typeof(string[]) })
                ?? throw new InvalidOperationException("StardewModdingAPI.Program.Main(string[]) not found");

            mainMethod.Invoke(null, new object[] { Array.Empty<string>() });

            // 4. Populate MainActivity._game1 from GameRunner.instance.gamePtr cleanly
            var gameRunnerType = svAsm.GetType("StardewValley.GameRunner");
            var grInstance = AccessTools.Field(gameRunnerType, "instance")?.GetValue(null);
            var gamePtr = grInstance != null
                ? AccessTools.Field(gameRunnerType, "gamePtr")?.GetValue(grInstance)
                : null;
            if (gamePtr != null)
            {
                AccessTools.Field(__instance.GetType(), "_game1")?.SetValue(__instance, gamePtr);
            }

            // 5. Run MainActivity.DoLicenseCheck() just like official OnCreatePartTwo
            AccessTools.Method(__instance.GetType(), "DoLicenseCheck")?.Invoke(__instance, null);

            BootstrapLog.Info("SMAPI Program.Main launched inside MainActivity.OnCreatePartTwo!");
            return false;
        }
        catch (Exception ex)
        {
            BootstrapLog.Error($"Failed inside Prefix_OnCreatePartTwo: {ex}");
            // Fallback to vanilla OnCreatePartTwo if SMAPI threw before setting up GameRunner
            return true;
        }
    }
}

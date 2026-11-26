using System.IO.Compression;
using Mono.Cecil;
using Mono.Cecil.Cil;

namespace SMAPI.LspBootstrap;

internal static class SmapiZipExtractor
{
    public static bool EnsureSmapiExtracted(
        string smapiZipPath,
        string assembliesDir,
        string smapiZipStamp,
        string gameVersionName = "1.6.15.3")
    {
        if (string.IsNullOrEmpty(smapiZipPath) || !File.Exists(smapiZipPath))
        {
            BootstrapLog.Warn($"SMAPI zip not found at {smapiZipPath}");
            return false;
        }

        Directory.CreateDirectory(assembliesDir);
        var smapiDllPath = Path.Combine(assembliesDir, "StardewModdingAPI.dll");
        var stampFile = Path.Combine(assembliesDir, ".smapi_stamp");
        var effectiveVersion = NormalizeVersionString(gameVersionName);
        var actualStamp = (string.IsNullOrEmpty(smapiZipStamp)
            ? BuildFileStamp(smapiZipPath)
            : smapiZipStamp) + "|" + effectiveVersion;

        if (File.Exists(stampFile) && File.Exists(smapiDllPath))
        {
            var existing = File.ReadAllText(stampFile).Trim();
            if (existing == actualStamp)
            {
                BootstrapLog.Info("SMAPI assemblies up-to-date in Stardew Assemblies");
                PatchSmapiCompatibilityIfNeeded(smapiDllPath, assembliesDir, effectiveVersion);
                return true;
            }
        }

        BootstrapLog.Info($"Extracting SMAPI archive from {smapiZipPath} -> {assembliesDir}");
        using var zip = ZipFile.OpenRead(smapiZipPath);

        // Locate root folder inside zip containing StardewModdingAPI.dll (handles both root & SMAPI-Android-x.x.x.x/ prefix)
        var smapiEntry = zip.Entries.FirstOrDefault(e =>
            e.Name.Equals("StardewModdingAPI.dll", StringComparison.OrdinalIgnoreCase));
        if (smapiEntry == null)
        {
            BootstrapLog.Error("Invalid SMAPI zip: StardewModdingAPI.dll not found inside archive");
            return false;
        }

        var fullEntry = smapiEntry.FullName.Replace('\\', '/');
        var slashIdx = fullEntry.LastIndexOf('/');
        var prefix = slashIdx >= 0 ? fullEntry[..(slashIdx + 1)] : string.Empty;

        int extracted = 0;
        foreach (var entry in zip.Entries)
        {
            var norm = entry.FullName.Replace('\\', '/');
            if (string.IsNullOrEmpty(entry.Name) || norm.EndsWith('/')) continue;
            if (!string.IsNullOrEmpty(prefix) && !norm.StartsWith(prefix, StringComparison.OrdinalIgnoreCase)) continue;

            var relPath = string.IsNullOrEmpty(prefix) ? norm : norm[prefix.Length..];
            if (string.IsNullOrEmpty(relPath) || relPath.Contains("..")) continue;

            // Preserve user's existing smapi-internal/config.json if already configured
            var outPath = Path.Combine(assembliesDir, relPath.Replace('/', Path.DirectorySeparatorChar));
            if (relPath.Equals("smapi-internal/config.json", StringComparison.OrdinalIgnoreCase) && File.Exists(outPath))
            {
                continue;
            }

            Directory.CreateDirectory(Path.GetDirectoryName(outPath)!);
            entry.ExtractToFile(outPath, overwrite: true);
            extracted++;
        }

        BootstrapLog.Info($"Extracted {extracted} SMAPI files into {assembliesDir}");
        PatchSmapiCompatibilityIfNeeded(smapiDllPath, assembliesDir, effectiveVersion);
        File.WriteAllText(stampFile, actualStamp);
        return File.Exists(smapiDllPath);
    }

    private static string NormalizeVersionString(string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw)) return "1.6.15.3";
        var cleaned = new string(raw.Trim().TakeWhile(c => char.IsDigit(c) || c == '.').ToArray()).Trim('.');
        return Version.TryParse(cleaned, out _) ? cleaned : "1.6.15.3";
    }

    private static string BuildFileStamp(string path)
    {
        var fi = new FileInfo(path);
        return $"{fi.Length}:{fi.LastWriteTimeUtc.Ticks}";
    }

    /// <summary>
    /// Patches upstream SMAPI-Android-1.6 (built against SDK Mono.Android)
    /// so it binds cleanly against the official trimmed Mono.Android.dll from Stardew Valley 1.6.15.3:
    ///   1. AndroidLogger..cctor -> removes missing Android.Provider.Settings.Global.GetInt/AdbEnabled
    ///   2. LauncherAppInfo.GetPackageInfo -> replaces missing API 33 PackageManager.PackageInfoFlags.Of
    ///      with GetPackageInfo(string, (PackageInfoFlags)0)
    ///   3. LauncherAppInfo.get_CurrentVersion -> avoids missing PackageInfo.get_VersionName()
    ///   4. AndroidGameLoopManager.PrintMemory -> stubs out missing ActivityManager.MemoryInfo
    /// </summary>
    private static void PatchSmapiCompatibilityIfNeeded(string smapiDllPath, string assembliesDir, string gameVersion)
    {
        var monoAndroidPath = Path.Combine(assembliesDir, "Mono.Android.dll");
        if (!File.Exists(smapiDllPath) || !File.Exists(monoAndroidPath)) return;

        using var monoAndroid = AssemblyDefinition.ReadAssembly(monoAndroidPath);
        bool hasSettingsGlobal = monoAndroid.MainModule.GetTypes().Any(t => t.FullName == "Android.Provider.Settings/Global");
        bool hasPkgInfoFlagsClass = monoAndroid.MainModule.GetTypes().Any(t => t.FullName == "Android.Content.PM.PackageManager/PackageInfoFlags");
        bool hasActivityManager = monoAndroid.MainModule.GetTypes().Any(t => t.FullName == "Android.App.ActivityManager");
        var pkgInfoType = monoAndroid.MainModule.GetType("Android.Content.PM.PackageInfo");
        bool hasVersionName = pkgInfoType?.Methods.Any(m => m.Name == "get_VersionName") == true;

        if (hasSettingsGlobal && hasPkgInfoFlagsClass && hasActivityManager && hasVersionName)
        {
            BootstrapLog.Info("Official Mono.Android.dll contains all APIs; no Cecil patch needed.");
            return;
        }

        var resolver = new DefaultAssemblyResolver();
        resolver.AddSearchDirectory(assembliesDir);
        var readerParams = new ReaderParameters { AssemblyResolver = resolver, ReadSymbols = false };

        byte[] smapiBytes = File.ReadAllBytes(smapiDllPath);
        using var ms = new MemoryStream(smapiBytes);
        using var smapiAsm = AssemblyDefinition.ReadAssembly(ms, readerParams);
        var module = smapiAsm.MainModule;
        bool modified = false;

        if (!hasSettingsGlobal)
        {
            var androidLogger = module.GetType("StardewModdingAPI.Mobile.AndroidLogger");
            var cctor = androidLogger?.Methods.FirstOrDefault(m => m.Name == ".cctor" && m.HasBody);
            if (cctor != null)
            {
                modified |= PatchAndroidLoggerCctor(cctor);
            }
        }

        var launcherAppInfo = module.GetType("StardewModdingAPI.Mobile.LauncherAppInfo");
        if (launcherAppInfo != null)
        {
            if (!hasPkgInfoFlagsClass)
            {
                var getPkgInfo = launcherAppInfo.Methods.FirstOrDefault(m => m.Name == "GetPackageInfo" && m.HasBody);
                if (getPkgInfo != null)
                {
                    modified |= PatchLauncherGetPackageInfo(module, monoAndroid.MainModule, getPkgInfo);
                }
            }
            if (!hasVersionName)
            {
                var getCurrentVersion = launcherAppInfo.Methods.FirstOrDefault(m => m.Name == "get_CurrentVersion" && m.HasBody);
                if (getCurrentVersion != null)
                {
                    modified |= PatchLauncherCurrentVersion(module, getCurrentVersion, gameVersion);
                }
            }
        }

        if (!hasActivityManager)
        {
            var loopMgr = module.GetType("StardewModdingAPI.Mobile.AndroidGameLoopManager");
            var printMem = loopMgr?.Methods.FirstOrDefault(m => m.Name == "PrintMemory" && m.HasBody);
            if (printMem != null)
            {
                modified |= PatchPrintMemory(printMem);
            }
        }

        if (modified)
        {
            using var outMs = new MemoryStream();
            smapiAsm.Write(outMs);
            File.WriteAllBytes(smapiDllPath, outMs.ToArray());
            BootstrapLog.Info("Patched StardewModdingAPI.dll compatibility shims via Mono.Cecil");
        }
    }

    private static bool PatchPrintMemory(MethodDefinition method)
    {
        bool usesActivityManager = method.Body.Variables.Any(v => v.VariableType.FullName.Contains("ActivityManager", StringComparison.Ordinal))
            || method.Body.Instructions.Any(i => i.Operand is MemberReference mr && mr.DeclaringType.FullName.Contains("ActivityManager", StringComparison.Ordinal));
        if (!usesActivityManager) return false;

        method.Body.ExceptionHandlers.Clear();
        method.Body.Variables.Clear();
        method.Body.Instructions.Clear();
        method.Body.InitLocals = false;
        var il = method.Body.GetILProcessor();
        il.Append(il.Create(OpCodes.Ret));
        BootstrapLog.Info("Cecil patched AndroidGameLoopManager.PrintMemory (removed missing ActivityManager)");
        return true;
    }

    private static bool PatchLauncherCurrentVersion(ModuleDefinition module, MethodDefinition method, string gameVersion)
    {
        bool usesVersionName = method.Body.Instructions.Any(i =>
            i.Operand is MemberReference mr && mr.Name == "get_VersionName");
        if (!usesVersionName) return false;

        var versionCtor = typeof(Version).GetConstructor(new[] { typeof(string) })
            ?? throw new InvalidOperationException("Version(string) ctor missing");
        var versionCtorRef = module.ImportReference(versionCtor);

        method.Body.ExceptionHandlers.Clear();
        method.Body.Variables.Clear();
        method.Body.Instructions.Clear();
        method.Body.InitLocals = false;
        var il = method.Body.GetILProcessor();
        il.Append(il.Create(OpCodes.Ldstr, gameVersion));
        il.Append(il.Create(OpCodes.Newobj, versionCtorRef));
        il.Append(il.Create(OpCodes.Ret));
        BootstrapLog.Info($"Cecil patched LauncherAppInfo.get_CurrentVersion -> {gameVersion}");
        return true;
    }

    private static bool PatchAndroidLoggerCctor(MethodDefinition cctor)
    {
        var il = cctor.Body.GetILProcessor();
        var instrs = cctor.Body.Instructions;
        bool changed = false;

        // Pass 1: replace any field access to Settings/Global (e.g., AdbEnabled) with empty/literal string
        for (int i = 0; i < instrs.Count; i++)
        {
            if (instrs[i].Operand is FieldReference fr &&
                fr.DeclaringType.FullName.Contains("Settings/Global", StringComparison.Ordinal))
            {
                instrs[i] = il.Create(OpCodes.Ldstr, "adb_enabled");
                changed = true;
            }
        }

        // Pass 2: replace calls to Settings/Global::GetInt(...) while maintaining exact IL stack balance
        for (int i = 0; i < instrs.Count; i++)
        {
            if ((instrs[i].OpCode == OpCodes.Call || instrs[i].OpCode == OpCodes.Callvirt) &&
                instrs[i].Operand is MethodReference mr &&
                mr.DeclaringType.FullName.Contains("Settings/Global", StringComparison.Ordinal))
            {
                int argCount = mr.Parameters.Count + (mr.HasThis ? 1 : 0);
                var target = instrs[i];
                for (int p = 0; p < argCount; p++)
                {
                    il.InsertBefore(target, il.Create(OpCodes.Pop));
                }
                if (mr.ReturnType.MetadataType != MetadataType.Void)
                {
                    il.Replace(target, il.Create(OpCodes.Ldc_I4_0));
                }
                else
                {
                    il.Replace(target, il.Create(OpCodes.Nop));
                }
                changed = true;
                i += argCount;
            }
        }

        if (changed)
        {
            BootstrapLog.Info("Cecil patched AndroidLogger..cctor (removed Settings.Global calls)");
        }
        return changed;
    }

    private static bool PatchLauncherGetPackageInfo(
        ModuleDefinition smapiModule,
        ModuleDefinition monoAndroidModule,
        MethodDefinition method)
    {
        bool usesMissingFlags = method.Body.Instructions.Any(ins =>
            ins.Operand is MemberReference mr &&
            (mr.DeclaringType.FullName.Contains("PackageManager/PackageInfoFlags", StringComparison.Ordinal) ||
             mr.DeclaringType.FullName.Contains("PackageInfoFlagsLong", StringComparison.Ordinal)));
        if (!usesMissingFlags) return false;

        var appType = monoAndroidModule.GetType("Android.App.Application");
        var ctxType = monoAndroidModule.GetType("Android.Content.Context");
        var pmType = monoAndroidModule.GetType("Android.Content.PM.PackageManager");
        var enumType = monoAndroidModule.GetType("Android.Content.PM.PackageInfoFlags");

        var getContextDef = appType?.Methods.FirstOrDefault(m => m.Name == "get_Context" && m.Parameters.Count == 0);
        var getPmDef = ctxType?.Methods.FirstOrDefault(m => m.Name == "get_PackageManager" && m.Parameters.Count == 0);
        var getPkgDef = pmType?.Methods.FirstOrDefault(m =>
            m.Name == "GetPackageInfo" &&
            m.Parameters.Count == 2 &&
            m.Parameters[0].ParameterType.FullName == "System.String" &&
            m.Parameters[1].ParameterType.FullName == "Android.Content.PM.PackageInfoFlags");

        if (getContextDef == null || getPmDef == null || getPkgDef == null || enumType == null)
        {
            BootstrapLog.Error("Failed to find Mono.Android PackageManager.GetPackageInfo(string, PackageInfoFlags) for Cecil patch");
            return false;
        }

        var getContextRef = smapiModule.ImportReference(getContextDef);
        var getPmRef = smapiModule.ImportReference(getPmDef);
        var getPkgRef = smapiModule.ImportReference(getPkgDef);

        method.Body.ExceptionHandlers.Clear();
        method.Body.Variables.Clear();
        method.Body.Instructions.Clear();
        method.Body.InitLocals = false;

        var il = method.Body.GetILProcessor();
        il.Append(il.Create(OpCodes.Call, getContextRef));
        il.Append(il.Create(OpCodes.Callvirt, getPmRef));
        il.Append(il.Create(OpCodes.Ldarg_0));
        il.Append(il.Create(OpCodes.Ldc_I4_0));
        il.Append(il.Create(OpCodes.Callvirt, getPkgRef));
        il.Append(il.Create(OpCodes.Ret));

        BootstrapLog.Info("Cecil patched LauncherAppInfo.GetPackageInfo -> GetPackageInfo(string, (PackageInfoFlags)0)");
        return true;
    }
}

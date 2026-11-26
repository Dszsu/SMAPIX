using System.Reflection;
using System.Runtime.InteropServices;

namespace SMAPI.LspBootstrap;

internal static class NativeResolver
{
    private static string _nativeLibDir = string.Empty;
    private static IntPtr _skiaHandle = IntPtr.Zero;

    public static void Install(string nativeLibDir)
    {
        _nativeLibDir = nativeLibDir ?? string.Empty;
        PreloadSkia();

        foreach (var asm in AppDomain.CurrentDomain.GetAssemblies())
        {
            AttachResolver(asm);
        }

        AppDomain.CurrentDomain.AssemblyLoad += (_, args) =>
        {
            if (args.LoadedAssembly != null)
            {
                AttachResolver(args.LoadedAssembly);
            }
        };
    }

    private static void PreloadSkia()
    {
        if (string.IsNullOrEmpty(_nativeLibDir)) return;
        var skiaPath = Path.Combine(_nativeLibDir, "libSkiaSharp.so");
        if (File.Exists(skiaPath))
        {
            if (NativeLibrary.TryLoad(skiaPath, out var handle))
            {
                _skiaHandle = handle;
                BootstrapLog.Info($"Loaded libSkiaSharp.so from {skiaPath}");
            }
            else
            {
                BootstrapLog.Warn($"Failed to NativeLibrary.TryLoad({skiaPath})");
            }
        }
    }

    private static void AttachResolver(Assembly asm)
    {
        try
        {
            var name = asm.GetName().Name;
            if (string.Equals(name, "SkiaSharp", StringComparison.OrdinalIgnoreCase) ||
                string.Equals(name, "StardewModdingAPI", StringComparison.OrdinalIgnoreCase))
            {
                NativeLibrary.SetDllImportResolver(asm, ResolveDllImport);
                BootstrapLog.Info($"Attached DllImportResolver to {name}");
            }
        }
        catch (InvalidOperationException)
        {
            // Resolver already attached to this assembly
        }
        catch (Exception ex)
        {
            BootstrapLog.Warn($"AttachResolver failed for {asm.FullName}: {ex.Message}");
        }
    }

    private static IntPtr ResolveDllImport(string libraryName, Assembly assembly, DllImportSearchPath? searchPath)
    {
        if (libraryName.Contains("SkiaSharp", StringComparison.OrdinalIgnoreCase))
        {
            if (_skiaHandle != IntPtr.Zero) return _skiaHandle;
            if (!string.IsNullOrEmpty(_nativeLibDir))
            {
                var candidate = Path.Combine(_nativeLibDir, "libSkiaSharp.so");
                if (File.Exists(candidate) && NativeLibrary.TryLoad(candidate, out _skiaHandle))
                {
                    return _skiaHandle;
                }
            }
            if (NativeLibrary.TryLoad("libSkiaSharp.so", assembly, searchPath, out _skiaHandle))
            {
                return _skiaHandle;
            }
        }

        if (!string.IsNullOrEmpty(_nativeLibDir))
        {
            var normalized = libraryName.EndsWith(".so", StringComparison.OrdinalIgnoreCase)
                ? libraryName
                : $"lib{libraryName}.so";
            var candidate = Path.Combine(_nativeLibDir, normalized);
            if (File.Exists(candidate) && NativeLibrary.TryLoad(candidate, out var handle))
            {
                return handle;
            }
        }

        return IntPtr.Zero;
    }
}

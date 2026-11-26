$ErrorActionPreference = "Stop"

$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$managed = Join-Path $root "managed"
$bootstrapOut = Join-Path $root "app\src\main\assets\bootstrap"
$nativeAssetsOut = Join-Path $root "app\src\main\assets\native\arm64-v8a"
$nuget = Join-Path $env:USERPROFILE ".nuget\packages"

New-Item -ItemType Directory -Force $bootstrapOut | Out-Null
New-Item -ItemType Directory -Force $nativeAssetsOut | Out-Null
Get-ChildItem $bootstrapOut -Filter "*.dll" | Remove-Item -Force

Write-Host "Building AssemblyStore & SMAPI.LspBootstrap..."
dotnet build (Join-Path $managed "SMAPI.LspBootstrap\SMAPI.LspBootstrap.csproj") -c Release --verbosity minimal
if ($LASTEXITCODE -ne 0) { throw "dotnet build failed" }

$binDir = Join-Path $managed "SMAPI.LspBootstrap\bin\Release\net9.0"

# 1. Core bootstrap + AssemblyStore + Cecil + K4os.Compression.LZ4 from build output
Get-ChildItem $binDir -Filter "*.dll" | ForEach-Object {
    Copy-Item $_.FullName (Join-Path $bootstrapOut $_.Name) -Force
}

# 2. Exact SMAPILoader/SharedLibs (0Harmony 2.4.2 + MonoMod 1.3.1 / 25.0.9 for .NET 9 Mono ARM64)
Get-ChildItem (Join-Path $managed "SharedLibs") -Filter "*.dll" | ForEach-Object {
    Copy-Item $_.FullName (Join-Path $bootstrapOut $_.Name) -Force
}

# 3. Managed SkiaSharp 2.88.9 + Newtonsoft.Json 13.0.3 + Mono.Cecil satellites (Mdb/Pdb/Rocks)
Copy-Item (Join-Path $nuget "skiasharp\2.88.9\lib\net6.0\SkiaSharp.dll") $bootstrapOut -Force
Copy-Item (Join-Path $nuget "newtonsoft.json\13.0.3\lib\net6.0\Newtonsoft.Json.dll") $bootstrapOut -Force
foreach ($cecilSat in @("Mono.Cecil.Mdb.dll", "Mono.Cecil.Pdb.dll", "Mono.Cecil.Rocks.dll")) {
    $src = Join-Path $nuget "mono.cecil\0.11.6\lib\netstandard2.0\$cecilSat"
    if (Test-Path $src) {
        Copy-Item $src $bootstrapOut -Force
    }
}

# 4. Stage libSkiaSharp.so (4KB LOAD align) inside assets/native/arm64-v8a instead of jniLibs/
# so module APK keeps pure 16KB ZIP + ELF alignment while Stardew dlopens it after extraction.
$skiaSo = Join-Path $nuget "skiasharp.nativeassets.android\2.88.9\runtimes\android-arm64\native\libSkiaSharp.so"
if (Test-Path $skiaSo) {
    Copy-Item $skiaSo (Join-Path $nativeAssetsOut "libSkiaSharp.so") -Force
}

Write-Host "Staged bootstrap assemblies in ${bootstrapOut}:"
(Get-ChildItem $bootstrapOut -Filter "*.dll").Name | Sort-Object

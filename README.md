# SMAPIX

An LSPosed module to load SMAPI for Stardew Valley on Android without repacking.  
基于 LSPosed 的安卓《星露谷物语》免改包 SMAPI 加载器。

---

## 特性亮点

- **免改包注入**：直接针对原版《星露谷物语》（`com.chucklefish.stardewvalley`）运行，无需修改或重打包游戏 APK，保留正版环境与存档路径。
- **一键切换原版 / SMAPI**：在模块主页开关「启用 SMAPI」，无需卸载重装即可切换模组模式与原版模式。
- **内置模组管理**：支持直接导入 `.zip` 格式模组包，自动识别 `manifest.json`（兼容单模组包、嵌套目录与多模组整合包），更新时保留原有 `config.json`；支持一键启用/禁用与查看/删除模组。
- **模组菜单触控与游标适配**：在第三方 SMAPI 模组菜单（如 Generic Mod Config Menu、CJB Cheats Menu 等）下自动接管触控并显示星露谷官方箭头光标，支持悬停点击、下拉框选择、滑条横拖、垂直滑动与惯性滚动以及长按右键。
- **日志查看与复制**：内置读取并一键复制 `SMAPI 日志`（`SMAPI-latest.txt`）与`引导日志`（`bootstrap.log`），方便排查模组兼容问题。

---

## 环境要求

- **Android 9+**，仅支持 **arm64-v8a** 架构设备
- **正版 / 原版《星露谷物语》Android 版**（包名 `com.chucklefish.stardewvalley`）
- **Root 权限**（KernelSU / Magisk 等，用于读写游戏 `Mods` 目录与日志）
- **LSPosed 框架**（需支持 `libxposed API 102`）
- **SMAPI 核心压缩包**（`SMAPI-Android-*.zip`，可在应用内跳转到 Nexus Mods 下载）

---

## 使用步骤

### 1. 激活模块与权限准备
1. 安装 **SMAPIX**。
2. 打开 **LSPosed 管理器**，启用 **SMAPIX** 模块，并确保作用域勾选 **《星露谷物语》**（`com.chucklefish.stardewvalley`）；也可进入 SMAPIX 主页点击「**添加游戏作用域**」自动申请。
3. 在 SMAPIX 主页（或 KernelSU / Magisk 中）点击「**授予 Root 权限**」，用于管理模组目录与读取日志。

### 2. 导入 SMAPI 核心包
1. 前往 [SMAPI for Android (Nexus Mods)](https://www.nexusmods.com/stardewvalley/mods/44436) 的 **Files** 页面下载 `SMAPI-Android-*.zip`（也可在 SMAPIX 主页点击「**获取 SMAPI for Android**」直接跳转）。
2. 在 SMAPIX **主页 → 运行设置 → SMAPI 核心包** 中选择刚才下载的 `.zip` 压缩包（内部应包含 `StardewModdingAPI.dll`）。
3. 确认顶部状态卡片显示「**就绪**」后，点击「**启动星露谷 (SMAPI)**」即可加载启动。

---

## 功能说明

### 主页
- 查看当前环境检测状态（游戏版本、LSPosed 框架与作用域、Root 授权状态、SMAPI 核心包状态）。
- 「**启用 SMAPI**」开关：开启时注入加载 SMAPI 与模组；关闭时以原版游戏直接启动。
- 「**SMAPI 核心包**」：随时导入或更换新版本的 `SMAPI-Android-*.zip`。

### 模组
- 点击「**安装模组 (.zip)**」选取模组压缩包，自动解压到 `Android/data/com.chucklefish.stardewvalley/files/Mods`。
- 通过列表右侧开关启用/禁用模组（禁用会按 SMAPI 原生规则将目录重命名为 `.目录名`）。
- 点击单个模组条目可查看版本、作者、`UniqueID`、目录路径与简介，或执行删除操作。

### 日志
- 切换查看 **SMAPI 日志** 与 **引导日志**，支持一键复制全部日志内容反馈排查。

---

## 常见问题

<details>
<summary>模组界面不能滑动、没有鼠标光标或无法点击非按钮项？</summary>

SMAPIX 已内置官方风格游标与滚动物理适配，仅在当前最顶层的第三方模组菜单激活（原版游戏菜单与实体手柄控制保持原生逻辑）：
- **点击**：点按（移动不超过拖拽阈值）会先更新悬停坐标再触发左键点击与释放；
- **滑动滚动**：上下滑动转为滚轮事件并带阻尼惯性；
- **拖条**：左右滑动自动转为按住左键拖拽（适配 GMCM / CJB 滑条）；
- **右键**：单点按住不放达到 650ms 触发右键。

</details>

<details>
<summary>需要配合虚拟键盘模组触发快捷键？</summary>

许多 PC 移植模组（如 CJB 作弊菜单等）需要按键打开菜单，建议配合安装 **Virtual Keyboard** 等移动端虚拟按键模组使用。

</details>

<details>
<summary>关闭 SMAPI 后会删除我的模组或存档吗？</summary>

不会。关闭主页「启用 SMAPI」开关只会跳过 SMAPI 引导以原版方式启动游戏，`Mods` 目录、模组配置和存档均完整保留。

</details>

---

## 仓库结构

```text
SMAPIX/
├── app/                                      # Android 应用与 LSPosed 模块主工程
│   ├── src/main/java/io/github/dszsu/smapix/
│   │   ├── App.kt                            # libxposed 服务监听与 HiddenApiBypass
│   │   ├── MainActivity.kt                   # Miuix UI（主页 / 模组 / 日志）
│   │   ├── SmapiPackageHelper.kt             # SMAPI 核心包导入、Root 模组安装/管理与日志读取
│   │   ├── nativebridge/NativeBridge.kt      # JNI 原生钩子与托管引导入口
│   │   └── xposed/SmapiXposedModule.kt       # LSPosed API 102 入口（Hook Mono 启动与资源同步）
│   ├── src/main/cpp/                         # Native 注入层（ARM64 内联 Hook、Mono BCL 替换）
│   ├── src/main/assets/                      # 运行时资产（bcl.zip、bootstrap/*.dll、libSkiaSharp.so）
│   └── src/main/resources/META-INF/xposed/   # Xposed 模块声明与作用域配置
├── managed/                                  # .NET 9 托管引导层
│   ├── SMAPI.LspBootstrap/                   # SMAPI 注入引导、程序集解包与模组菜单触控/光标适配
│   ├── AssemblyStore/                        # Xamarin/Android 程序集商店（blobs）读取工具
│   └── SharedLibs/                           # 0Harmony 与 MonoMod 运行库
├── tools/                                    # 资产构建与打包脚本
│   ├── StageAssets/                          # .NET 9 BCL / SkiaSharp 等 NuGet 依赖清单
│   └── build_assets.ps1                      # 构建托管引导并同步至 app/src/main/assets/
├── gradle/                                   # Gradle Wrapper 与版本目录（libs.versions.toml）
├── build.gradle.kts / settings.gradle.kts    # 根工程构建配置
└── LICENSE                                   # GPL-3.0 开源协议
```

---

## 构建说明（开发者）

1. 使用 `tools/build_assets.ps1` 构建并整理托管引导程序集与运行库资源：
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\tools\build_assets.ps1
   ```
2. 使用 Gradle 构建 Release APK：
   ```powershell
   ./gradlew :app:assembleRelease
   ```

---

## 致谢与协议

- 作者：[Dszsu](https://github.com/Dszsu)
- 基于 [NRTnarathip 的 SMAPILoader](https://github.com/NRTnarathip/SMAPILoader) 方案演进构建
- 使用了 `libxposed`、`0Harmony` / `MonoMod`、`Mono.Cecil`、`Miuix` 等开源项目
- 本项目遵循 [GNU General Public License v3.0](LICENSE) 开源

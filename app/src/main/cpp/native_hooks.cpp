#include <android/log.h>
#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <jni.h>
#include <mutex>
#include <string>
#include <type_traits>
#include <vector>

#include "arm64_hook.h"
#include "bcl_swap.h"
#include "mono_api.h"

#define LOG_TAG "SMAPIX_NATIVE"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr const char *kMonoLib = "libmonosgen-2.0.so";

std::mutex g_init_mutex;
bool g_hooks_installed = false;
smapix::BclSwapIndex g_bcl_index;
std::atomic<int> g_bcl_swaps{0};

void (*orig_mono_jit_set_aot_mode)(int mode) = nullptr;
void (*orig_mono_set_use_llvm)(int use_llvm) = nullptr;
void (*orig_mono_jit_parse_options)(int argc, char *argv[]) = nullptr;
void *(*orig_mono_image_open_from_data_internal)(
    void *alc, char *data, uint32_t data_len, int need_copy,
    int *status, int refonly, const char *name_a, const char *name_b) = nullptr;

void hook_mono_jit_set_aot_mode(int mode) {
    LOGI("mono_jit_set_aot_mode(%d) -> forced 0 (MONO_AOT_MODE_NONE)", mode);
    if (orig_mono_jit_set_aot_mode) {
        orig_mono_jit_set_aot_mode(0);
    }
}

void hook_mono_set_use_llvm(int use_llvm) {
    LOGI("mono_set_use_llvm(%d) -> forced 0", use_llvm);
    if (orig_mono_set_use_llvm) {
        orig_mono_set_use_llvm(0);
    }
}

void hook_mono_jit_parse_options(int argc, char *argv[]) {
    if (!orig_mono_jit_parse_options) return;
    if (argc <= 0 || !argv) {
        orig_mono_jit_parse_options(argc, argv);
        return;
    }
    std::vector<char *> filtered;
    filtered.reserve(static_cast<size_t>(argc));
    for (int i = 0; i < argc; ++i) {
        const char *opt = argv[i];
        if (!opt) continue;
        if (std::strcmp(opt, "--llvm") == 0 || std::strncmp(opt, "--aot", 5) == 0) {
            LOGI("Stripped Mono JIT option: %s", opt);
            continue;
        }
        filtered.push_back(argv[i]);
    }
    orig_mono_jit_parse_options(static_cast<int>(filtered.size()),
                                filtered.empty() ? nullptr : filtered.data());
}

void *hook_mono_image_open_from_data_internal(
    void *alc, char *data, uint32_t data_len, int need_copy,
    int *status, int refonly, const char *name_a, const char *name_b) {

    const char *effective_name = (name_b && name_b[0]) ? name_b : name_a;
    uint8_t *repl_buf = nullptr;
    uint32_t repl_len = 0;

    if (effective_name && g_bcl_index.TryLoadReplacement(effective_name, &repl_buf, &repl_len)) {
        int swap_num = g_bcl_swaps.fetch_add(1, std::memory_order_relaxed) + 1;
        if (swap_num <= 12 || std::strstr(effective_name, "CoreLib") != nullptr) {
            LOGI("BCL swap #%d: %s (%u -> %u bytes)", swap_num, effective_name, data_len, repl_len);
        }
        void *img = orig_mono_image_open_from_data_internal(
            alc, reinterpret_cast<char *>(repl_buf), repl_len, 1 /*need_copy*/,
            status, refonly, name_a, name_b);
        std::free(repl_buf);
        return img;
    }

    return orig_mono_image_open_from_data_internal(
        alc, data, data_len, need_copy, status, refonly, name_a, name_b);
}

void *ResolveMonoSym(const char *sym_name, uintptr_t *out_base = nullptr) {
    void *addr = smapix::FindLibrarySymbol(kMonoLib, sym_name, out_base);
    if (!addr) {
        addr = dlsym(RTLD_DEFAULT, sym_name);
    }
    return addr;
}

template <typename Fn>
bool HookMonoSym(const char *sym_name, Fn replacement, Fn *orig_out) {
    uintptr_t base = 0;
    void *sym_addr = ResolveMonoSym(sym_name, &base);
    if (!sym_addr) {
        LOGE("Symbol not found in %s: %s", kMonoLib, sym_name);
        return false;
    }
    if (!smapix::InstallInlineHook(sym_addr, reinterpret_cast<void *>(replacement), reinterpret_cast<void **>(orig_out))) {
        LOGE("InstallInlineHook failed on %s @ %p", sym_name, sym_addr);
        return false;
    }
    LOGI("Hooked %s @ %p (+0x%lx)", sym_name, sym_addr, static_cast<unsigned long>(reinterpret_cast<uintptr_t>(sym_addr) - base));
    return true;
}

bool PatchMonoReturnOne(const char *sym_name) {
    uintptr_t base = 0;
    void *sym_addr = ResolveMonoSym(sym_name, &base);
    if (!sym_addr) {
        LOGE("Symbol not found for mov1 patch: %s", sym_name);
        return false;
    }
    if (!smapix::PatchReturnOne(sym_addr)) return false;
    LOGI("Patched %s @ %p (+0x%lx) -> return 1", sym_name, sym_addr, static_cast<unsigned long>(reinterpret_cast<uintptr_t>(sym_addr) - base));
    return true;
}

bool LoadMonoApi(MonoApi *api) {
    auto load = [](auto &fn, const char *name) -> bool {
        using FnType = std::remove_reference_t<decltype(fn)>;
        fn = reinterpret_cast<FnType>(ResolveMonoSym(name));
        if (!fn) {
            LOGE("Missing Mono API symbol: %s", name);
            return false;
        }
        return true;
    };

    bool ok = true;
    api->mono_get_root_domain = reinterpret_cast<MonoDomain *(*)(void)>(ResolveMonoSym("mono_get_root_domain"));
    ok &= load(api->mono_domain_get, "mono_domain_get");
    api->mono_thread_current = reinterpret_cast<MonoThread *(*)(void)>(ResolveMonoSym("mono_thread_current"));
    ok &= load(api->mono_jit_thread_attach, "mono_jit_thread_attach");
    ok &= load(api->mono_domain_assembly_open, "mono_domain_assembly_open");
    api->mono_assembly_open = reinterpret_cast<MonoAssembly *(*)(const char *, int *)>(ResolveMonoSym("mono_assembly_open"));
    ok &= load(api->mono_assembly_get_image, "mono_assembly_get_image");
    ok &= load(api->mono_class_from_name, "mono_class_from_name");
    ok &= load(api->mono_class_get_method_from_name, "mono_class_get_method_from_name");
    ok &= load(api->mono_string_new, "mono_string_new");
    ok &= load(api->mono_runtime_invoke, "mono_runtime_invoke");
    api->mono_object_to_string = reinterpret_cast<MonoString *(*)(MonoObject *, MonoObject **)>(ResolveMonoSym("mono_object_to_string"));
    api->mono_string_to_utf8 = reinterpret_cast<char *(*)(MonoString *)>(ResolveMonoSym("mono_string_to_utf8"));
    return ok;
}

std::string JStringToStd(JNIEnv *env, jstring jstr) {
    if (!jstr) return {};
    const char *chars = env->GetStringUTFChars(jstr, nullptr);
    if (!chars) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return out;
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_dszsu_smapix_nativebridge_NativeBridge_nativeInstallHooks(
    JNIEnv *env, jclass /*clazz*/, jstring bclDir, jstring skiaSoPath) {

    std::lock_guard<std::mutex> lock(g_init_mutex);
    if (g_hooks_installed) {
        return JNI_TRUE;
    }

    std::string bcl_path = JStringToStd(env, bclDir);
    if (!bcl_path.empty()) {
        size_t count = g_bcl_index.Init(bcl_path);
        if (count == 0) {
            LOGW("BCL directory yielded 0 replacement DLLs: %s", bcl_path.c_str());
        }
    }

    std::string skia_path = JStringToStd(env, skiaSoPath);
    if (!skia_path.empty()) {
        void *skia = dlopen(skia_path.c_str(), RTLD_NOW | RTLD_GLOBAL);
        if (skia) {
            LOGI("Preloaded libSkiaSharp.so from %s", skia_path.c_str());
        } else {
            LOGW("Early dlopen libSkiaSharp.so failed (%s): %s", skia_path.c_str(), dlerror());
        }
    }

    bool ok = true;
    ok &= HookMonoSym("mono_jit_set_aot_mode", hook_mono_jit_set_aot_mode, &orig_mono_jit_set_aot_mode);
    ok &= HookMonoSym("mono_set_use_llvm", hook_mono_set_use_llvm, &orig_mono_set_use_llvm);
    HookMonoSym("mono_jit_parse_options", hook_mono_jit_parse_options, &orig_mono_jit_parse_options);
    ok &= PatchMonoReturnOne("mono_method_can_access_method");
    ok &= PatchMonoReturnOne("mono_method_can_access_field");
    ok &= HookMonoSym("mono_image_open_from_data_internal", hook_mono_image_open_from_data_internal, &orig_mono_image_open_from_data_internal);

    g_hooks_installed = ok;
    LOGI("Mono ARM64 native hook installation complete: ok=%d", ok ? 1 : 0);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_dszsu_smapix_nativebridge_NativeBridge_nativeBootstrapManaged(
    JNIEnv *env, jclass /*clazz*/, jstring bootstrapDllPath, jstring configJson, jstring skiaSoPath) {

    std::string dll_path = JStringToStd(env, bootstrapDllPath);
    std::string cfg_json = JStringToStd(env, configJson);
    std::string skia_path = JStringToStd(env, skiaSoPath);

    LOGI("nativeBootstrapManaged starting, bcl_swaps_so_far=%d", g_bcl_swaps.load());

    if (!skia_path.empty()) {
        if (!dlopen(skia_path.c_str(), RTLD_NOW | RTLD_GLOBAL)) {
            LOGW("dlopen(%s) warning: %s", skia_path.c_str(), dlerror());
        }
    }

    MonoApi api{};
    if (!LoadMonoApi(&api)) {
        return JNI_FALSE;
    }

    MonoDomain *domain = api.mono_get_root_domain ? api.mono_get_root_domain() : api.mono_domain_get();
    if (!domain) {
        LOGE("Mono root domain is null");
        return JNI_FALSE;
    }

    if (!api.mono_thread_current || !api.mono_thread_current()) {
        api.mono_jit_thread_attach(domain);
    }

    MonoAssembly *assembly = api.mono_domain_assembly_open(domain, dll_path.c_str());
    if (!assembly && api.mono_assembly_open) {
        int status = 0;
        assembly = api.mono_assembly_open(dll_path.c_str(), &status);
        LOGW("Fallback mono_assembly_open(%s) -> %p status=%d", dll_path.c_str(), assembly, status);
    }
    if (!assembly) {
        LOGE("Failed to open SMAPI.LspBootstrap.dll at %s", dll_path.c_str());
        return JNI_FALSE;
    }

    MonoImage *image = api.mono_assembly_get_image(assembly);
    MonoClass *klass = api.mono_class_from_name(image, "SMAPI.LspBootstrap", "BootstrapEntry");
    if (!klass) {
        LOGE("Class SMAPI.LspBootstrap.BootstrapEntry not found");
        return JNI_FALSE;
    }

    MonoMethod *method = api.mono_class_get_method_from_name(klass, "Initialize", 1);
    if (!method) {
        LOGE("Method BootstrapEntry.Initialize(string) not found");
        return JNI_FALSE;
    }

    MonoString *arg = api.mono_string_new(domain, cfg_json.c_str());
    void *params[1] = { arg };
    MonoObject *exc = nullptr;
    api.mono_runtime_invoke(method, nullptr, params, &exc);

    if (exc) {
        if (api.mono_object_to_string && api.mono_string_to_utf8) {
            MonoObject *to_str_exc = nullptr;
            MonoString *exc_str = api.mono_object_to_string(exc, &to_str_exc);
            const char *utf8 = exc_str ? api.mono_string_to_utf8(exc_str) : nullptr;
            LOGE("Managed BootstrapEntry.Initialize threw exception:\n%s", utf8 ? utf8 : "<null>");
        } else {
            LOGE("Managed BootstrapEntry.Initialize threw an exception (%p)", exc);
        }
        return JNI_FALSE;
    }

    LOGI("BootstrapEntry.Initialize completed cleanly");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_dszsu_smapix_nativebridge_NativeBridge_nativeGetBclSwapCount(
    JNIEnv * /*env*/, jclass /*clazz*/) {
    return static_cast<jint>(g_bcl_swaps.load(std::memory_order_relaxed));
}

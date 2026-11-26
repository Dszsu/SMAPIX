#pragma once

#include <cstdint>

namespace smapix {

// Resolves a dynamic (.dynsym) symbol inside an already-loaded library matching
// lib_substr (e.g. "libmonosgen-2.0.so") across all Android linker namespaces
// using dl_iterate_phdr, and optionally returns the module load bias.
void *FindLibrarySymbol(const char *lib_substr, const char *sym_name, uintptr_t *out_base = nullptr);

// Installs a 16-byte ARM64 inline hook with PC-relative instruction relocation
// in a dedicated RWX trampoline slab. Returns true on success.
bool InstallInlineHook(void *target, void *replacement, void **orig_out);

// Patches a small side-effect-free predicate function directly with `mov w0, #1; ret`.
bool PatchReturnOne(void *target);

} // namespace smapix

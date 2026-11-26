#include "arm64_hook.h"

#include <android/log.h>
#include <cstdint>
#include <cstring>
#include <elf.h>
#include <link.h>
#include <mutex>
#include <sys/mman.h>
#include <unistd.h>

#define LOG_TAG "SMAPIX_HOOK"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace smapix {

namespace {

uintptr_t FixDynPtr(Elf64_Addr load_bias, Elf64_Addr raw) {
    if (raw == 0) return 0;
    return (raw < load_bias) ? static_cast<uintptr_t>(load_bias + raw) : static_cast<uintptr_t>(raw);
}

uint32_t GnuHash(const char *name) {
    uint32_t h = 5381;
    for (const auto *p = reinterpret_cast<const unsigned char *>(name); *p; ++p) {
        h = (h << 5) + h + *p;
    }
    return h;
}

uint32_t SysvHash(const char *name) {
    uint32_t h = 0, g = 0;
    for (const auto *p = reinterpret_cast<const unsigned char *>(name); *p; ++p) {
        h = (h << 4) + *p;
        g = h & 0xf0000000U;
        if (g) h ^= (g >> 24);
        h &= ~g;
    }
    return h;
}

const Elf64_Sym *LookupGnuHash(
    const uint32_t *gh, const Elf64_Sym *symtab, const char *strtab, const char *name) {
    uint32_t nbuckets = gh[0];
    uint32_t symoffset = gh[1];
    uint32_t bloom_size = gh[2];
    if (nbuckets == 0) return nullptr;

    const auto *bloom = reinterpret_cast<const uint64_t *>(gh + 4);
    const auto *buckets = reinterpret_cast<const uint32_t *>(bloom + bloom_size);
    const uint32_t *chains = buckets + nbuckets;

    uint32_t hash = GnuHash(name);
    uint32_t idx = buckets[hash % nbuckets];
    if (idx < symoffset) return nullptr;

    for (;;) {
        uint32_t c = chains[idx - symoffset];
        if (((c ^ hash) >> 1) == 0) {
            const Elf64_Sym *sym = &symtab[idx];
            if (sym->st_name && std::strcmp(strtab + sym->st_name, name) == 0) {
                return sym;
            }
        }
        if (c & 1U) break;
        ++idx;
    }
    return nullptr;
}

const Elf64_Sym *LookupSysvHash(
    const uint32_t *ht, const Elf64_Sym *symtab, const char *strtab, const char *name) {
    uint32_t nbuckets = ht[0];
    uint32_t nchain = ht[1];
    if (nbuckets == 0 || nchain == 0) return nullptr;

    const uint32_t *buckets = ht + 2;
    const uint32_t *chains = buckets + nbuckets;
    uint32_t hash = SysvHash(name);

    for (uint32_t idx = buckets[hash % nbuckets]; idx != 0 && idx < nchain; idx = chains[idx]) {
        const Elf64_Sym *sym = &symtab[idx];
        if (sym->st_name && std::strcmp(strtab + sym->st_name, name) == 0) {
            return sym;
        }
    }
    return nullptr;
}

struct SearchCtx {
    const char *lib_substr;
    const char *sym_name;
    void *result;
    uintptr_t base;
};

int PhdrCallback(struct dl_phdr_info *info, size_t /*size*/, void *data) {
    auto *ctx = static_cast<SearchCtx *>(data);
    if (!info->dlpi_name || !std::strstr(info->dlpi_name, ctx->lib_substr)) {
        return 0;
    }

    const Elf64_Dyn *dyn = nullptr;
    for (Elf64_Half i = 0; i < info->dlpi_phnum; ++i) {
        if (info->dlpi_phdr[i].p_type == PT_DYNAMIC) {
            dyn = reinterpret_cast<const Elf64_Dyn *>(info->dlpi_addr + info->dlpi_phdr[i].p_vaddr);
            break;
        }
    }
    if (!dyn) return 0;

    Elf64_Addr raw_strtab = 0, raw_symtab = 0, raw_gnu_hash = 0, raw_hash = 0;
    for (; dyn->d_tag != DT_NULL; ++dyn) {
        switch (dyn->d_tag) {
            case DT_STRTAB:   raw_strtab = dyn->d_un.d_ptr; break;
            case DT_SYMTAB:   raw_symtab = dyn->d_un.d_ptr; break;
            case DT_GNU_HASH: raw_gnu_hash = dyn->d_un.d_ptr; break;
            case DT_HASH:     raw_hash = dyn->d_un.d_ptr; break;
            default: break;
        }
    }

    auto *strtab = reinterpret_cast<const char *>(FixDynPtr(info->dlpi_addr, raw_strtab));
    auto *symtab = reinterpret_cast<const Elf64_Sym *>(FixDynPtr(info->dlpi_addr, raw_symtab));
    auto *gnu_hash = reinterpret_cast<const uint32_t *>(FixDynPtr(info->dlpi_addr, raw_gnu_hash));
    auto *sysv_hash = reinterpret_cast<const uint32_t *>(FixDynPtr(info->dlpi_addr, raw_hash));

    if (!strtab || !symtab) return 0;

    const Elf64_Sym *found = nullptr;
    if (gnu_hash) {
        found = LookupGnuHash(gnu_hash, symtab, strtab, ctx->sym_name);
    }
    if (!found && sysv_hash) {
        found = LookupSysvHash(sysv_hash, symtab, strtab, ctx->sym_name);
    }
    if (found && found->st_value != 0) {
        ctx->base = static_cast<uintptr_t>(info->dlpi_addr);
        ctx->result = reinterpret_cast<void *>(info->dlpi_addr + found->st_value);
        return 1;
    }
    return 0;
}

// RWX trampoline allocator (one 16KB/64KB page easily fits all trampolines)
std::mutex g_tramp_mutex;
uint8_t *g_tramp_page = nullptr;
size_t g_tramp_used = 0;
constexpr size_t kTrampPoolSize = 16384;

uint32_t *AllocTrampoline(size_t max_bytes) {
    std::lock_guard<std::mutex> lock(g_tramp_mutex);
    if (!g_tramp_page) {
        void *mem = mmap(nullptr, kTrampPoolSize, PROT_READ | PROT_WRITE | PROT_EXEC,
                         MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        if (mem == MAP_FAILED) {
            LOGE("mmap failed for trampoline pool");
            return nullptr;
        }
        g_tramp_page = static_cast<uint8_t *>(mem);
        g_tramp_used = 0;
    }
    size_t aligned = (max_bytes + 15) & ~size_t(15);
    if (g_tramp_used + aligned > kTrampPoolSize) {
        LOGE("Trampoline pool exhausted");
        return nullptr;
    }
    auto *ptr = reinterpret_cast<uint32_t *>(g_tramp_page + g_tramp_used);
    g_tramp_used += aligned;
    return ptr;
}

inline int64_t SignExtend(uint64_t val, int bits) {
    uint64_t shift = 64 - bits;
    return static_cast<int64_t>(val << shift) >> shift;
}

void EmitLoadImm64(uint32_t *&out, uint32_t reg, uint64_t imm) {
    if (reg == 31) return; // xzr ignores writes
    // movz reg, #imm[15:0]
    *out++ = 0xD2800000U | ((imm & 0xFFFFU) << 5) | reg;
    uint16_t hw1 = (imm >> 16) & 0xFFFFU;
    uint16_t hw2 = (imm >> 32) & 0xFFFFU;
    uint16_t hw3 = (imm >> 48) & 0xFFFFU;
    if (hw1) *out++ = 0xF2A00000U | (hw1 << 5) | reg;
    if (hw2) *out++ = 0xF2C00000U | (hw2 << 5) | reg;
    if (hw3) *out++ = 0xF2E00000U | (hw3 << 5) | reg;
}

void EmitAbsBranchX16(uint32_t *&out, uint64_t target) {
    // ldr x16, #8  (0x58000050)
    // br  x16      (0xD61F0200)
    // .quad target
    *out++ = 0x58000050U;
    *out++ = 0xD61F0200U;
    std::memcpy(out, &target, sizeof(target));
    out += 2;
}

void RelocateOneInsn(uint32_t insn, uintptr_t orig_pc, uint32_t *&out) {
    uint32_t rd = insn & 0x1FU;

    // 1. ADRP Xd, imm
    if ((insn & 0x9F000000U) == 0x90000000U) {
        uint64_t immlo = (insn >> 29) & 0x3U;
        uint64_t immhi = (insn >> 5) & 0x7FFFFU;
        int64_t off = SignExtend((immhi << 2) | immlo, 21) << 12;
        uint64_t target = (orig_pc & ~0xFFFULL) + off;
        EmitLoadImm64(out, rd, target);
        return;
    }

    // 2. ADR Xd, imm
    if ((insn & 0x9F000000U) == 0x10000000U) {
        uint64_t immlo = (insn >> 29) & 0x3U;
        uint64_t immhi = (insn >> 5) & 0x7FFFFU;
        int64_t off = SignExtend((immhi << 2) | immlo, 21);
        EmitLoadImm64(out, rd, orig_pc + off);
        return;
    }

    // 3. B imm / BL imm
    if ((insn & 0x7C000000U) == 0x14000000U) {
        int64_t off = SignExtend(insn & 0x03FFFFFFU, 26) << 2;
        uint64_t dest = orig_pc + off;
        if (insn & 0x80000000U) {
            // BL -> set LR (x30) = PC + 20 (past 5 u32 words: adr, ldr, br, .quad)
            *out++ = 0x100000BEU; // adr x30, #20
        }
        EmitAbsBranchX16(out, dest);
        return;
    }

    // 4. B.cond imm
    if ((insn & 0xFF000010U) == 0x54000000U) {
        int64_t off = SignExtend((insn >> 5) & 0x7FFFFU, 19) << 2;
        uint64_t dest = orig_pc + off;
        uint32_t inv_cond = (insn & 0xFU) ^ 1U;
        // b.!cond +20 (skip ldr + br + .quad)
        *out++ = 0x54000000U | (5U << 5) | inv_cond;
        EmitAbsBranchX16(out, dest);
        return;
    }

    // 5. CBZ / CBNZ (32 or 64 bit)
    if ((insn & 0x7E000000U) == 0x34000000U) {
        int64_t off = SignExtend((insn >> 5) & 0x7FFFFU, 19) << 2;
        uint64_t dest = orig_pc + off;
        uint32_t inv = (insn ^ (1U << 24)) & ~((0x7FFFFU) << 5);
        *out++ = inv | (5U << 5); // skip +20
        EmitAbsBranchX16(out, dest);
        return;
    }

    // 6. TBZ / TBNZ
    if ((insn & 0x7E000000U) == 0x36000000U) {
        int64_t off = SignExtend((insn >> 5) & 0x3FFFU, 14) << 2;
        uint64_t dest = orig_pc + off;
        uint32_t inv = (insn ^ (1U << 24)) & ~((0x3FFFU) << 5);
        *out++ = inv | (5U << 5); // skip +20
        EmitAbsBranchX16(out, dest);
        return;
    }

    // 7. LDR (literal) Wt/Xt/Dt/Qt (uncommon at entry, handle 64-bit Xt safely)
    if ((insn & 0xFF000000U) == 0x58000000U) {
        int64_t off = SignExtend((insn >> 5) & 0x7FFFFU, 19) << 2;
        uint64_t addr = orig_pc + off;
        EmitLoadImm64(out, 16, addr);
        *out++ = 0xF9400200U | rd; // ldr Xd, [x16]
        return;
    }

    // Default: copy instruction unchanged
    *out++ = insn;
}

bool SetPageWritable(void *addr, size_t len, int prot) {
    long page_size = sysconf(_SC_PAGESIZE);
    uintptr_t start = reinterpret_cast<uintptr_t>(addr) & ~(static_cast<uintptr_t>(page_size) - 1U);
    uintptr_t end = (reinterpret_cast<uintptr_t>(addr) + len + page_size - 1U) & ~(static_cast<uintptr_t>(page_size) - 1U);
    return mprotect(reinterpret_cast<void *>(start), end - start, prot) == 0;
}

} // namespace

void *FindLibrarySymbol(const char *lib_substr, const char *sym_name, uintptr_t *out_base) {
    SearchCtx ctx{lib_substr, sym_name, nullptr, 0};
    dl_iterate_phdr(PhdrCallback, &ctx);
    if (out_base) *out_base = ctx.base;
    return ctx.result;
}

bool InstallInlineHook(void *target, void *replacement, void **orig_out) {
    if (!target || !replacement || !orig_out) return false;

    // 4 prologue instructions (16 bytes) relocated up to ~96 bytes + 16-byte tail jump
    uint32_t *tramp_start = AllocTrampoline(128);
    if (!tramp_start) return false;

    uint32_t *out = tramp_start;
    auto *src = reinterpret_cast<const uint32_t *>(target);
    for (int i = 0; i < 4; ++i) {
        RelocateOneInsn(src[i], reinterpret_cast<uintptr_t>(&src[i]), out);
    }
    EmitAbsBranchX16(out, reinterpret_cast<uintptr_t>(target) + 16U);

    __builtin___clear_cache(reinterpret_cast<char *>(tramp_start), reinterpret_cast<char *>(out));
    *orig_out = tramp_start;

    if (!SetPageWritable(target, 16, PROT_READ | PROT_WRITE | PROT_EXEC)) {
        LOGE("mprotect RWX failed for %p", target);
        return false;
    }

    auto *patch = reinterpret_cast<uint32_t *>(target);
    EmitAbsBranchX16(patch, reinterpret_cast<uintptr_t>(replacement));

    __builtin___clear_cache(reinterpret_cast<char *>(target), reinterpret_cast<char *>(target) + 16);
    SetPageWritable(target, 16, PROT_READ | PROT_EXEC);
    return true;
}

bool PatchReturnOne(void *target) {
    if (!target) return false;
    if (!SetPageWritable(target, 8, PROT_READ | PROT_WRITE | PROT_EXEC)) {
        LOGE("mprotect RWX failed for %p", target);
        return false;
    }
    auto *patch = reinterpret_cast<uint32_t *>(target);
    patch[0] = 0x52800020U; // mov w0, #1
    patch[1] = 0xD65F03C0U; // ret
    __builtin___clear_cache(reinterpret_cast<char *>(target), reinterpret_cast<char *>(target) + 8);
    SetPageWritable(target, 8, PROT_READ | PROT_EXEC);
    return true;
}

} // namespace smapix

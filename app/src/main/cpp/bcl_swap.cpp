#include "bcl_swap.h"

#include <android/log.h>
#include <cctype>
#include <cstdio>
#include <cstdlib>
#include <dirent.h>
#include <sys/stat.h>

#define LOG_TAG "SMAPIX_BCL"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace smapix {

namespace {

std::string ToLowerAscii(const std::string &input) {
    std::string out;
    out.reserve(input.size());
    for (unsigned char c : input) {
        out.push_back(static_cast<char>(std::tolower(c)));
    }
    return out;
}

std::string ExtractNormalizedDllName(const char *name) {
    if (!name || !name[0]) return {};
    std::string raw(name);
    size_t slash = raw.find_last_of("/\\");
    std::string base = (slash == std::string::npos) ? raw : raw.substr(slash + 1);
    // Strip trailing metadata suffixes if Mono appends any (e.g., ", Version=..." or ".aotdata")
    size_t comma = base.find(',');
    if (comma != std::string::npos) {
        base.resize(comma);
    }
    std::string lower = ToLowerAscii(base);
    if (lower.size() < 4 || lower.compare(lower.size() - 4, 4, ".dll") != 0) {
        lower += ".dll";
    }
    return lower;
}

} // namespace

size_t BclSwapIndex::Init(const std::string &bcl_dir) {
    entries_.clear();
    DIR *dir = opendir(bcl_dir.c_str());
    if (!dir) {
        LOGW("Failed to open bcl_dir: %s", bcl_dir.c_str());
        return 0;
    }

    struct dirent *ent = nullptr;
    while ((ent = readdir(dir)) != nullptr) {
        if (ent->d_name[0] == '.') continue;
        std::string name(ent->d_name);
        std::string lower = ToLowerAscii(name);
        if (lower.size() <= 4 || lower.compare(lower.size() - 4, 4, ".dll") != 0) {
            continue;
        }
        std::string full_path = bcl_dir;
        if (!full_path.empty() && full_path.back() != '/') {
            full_path.push_back('/');
        }
        full_path += name;

        struct stat st{};
        if (stat(full_path.c_str(), &st) == 0 && S_ISREG(st.st_mode) && st.st_size > 0) {
            entries_.emplace(std::move(lower), std::move(full_path));
        }
    }
    closedir(dir);

    LOGI("Indexed %zu untrimmed .NET 9.0.8 BCL assemblies from %s", entries_.size(), bcl_dir.c_str());
    return entries_.size();
}

bool BclSwapIndex::TryLoadReplacement(const char *image_name, uint8_t **out_buf, uint32_t *out_len) const {
    if (!out_buf || !out_len || entries_.empty()) return false;
    std::string key = ExtractNormalizedDllName(image_name);
    if (key.empty()) return false;

    auto it = entries_.find(key);
    if (it == entries_.end()) return false;

    FILE *fp = std::fopen(it->second.c_str(), "rb");
    if (!fp) {
        LOGW("Failed to open replacement BCL file: %s", it->second.c_str());
        return false;
    }

    if (std::fseek(fp, 0, SEEK_END) != 0) {
        std::fclose(fp);
        return false;
    }
    long size = std::ftell(fp);
    if (size <= 0 || size > 64 * 1024 * 1024 || std::fseek(fp, 0, SEEK_SET) != 0) {
        std::fclose(fp);
        return false;
    }

    auto *buf = static_cast<uint8_t *>(std::malloc(static_cast<size_t>(size)));
    if (!buf) {
        std::fclose(fp);
        return false;
    }

    size_t read_bytes = std::fread(buf, 1, static_cast<size_t>(size), fp);
    std::fclose(fp);
    if (read_bytes != static_cast<size_t>(size)) {
        std::free(buf);
        return false;
    }

    *out_buf = buf;
    *out_len = static_cast<uint32_t>(size);
    return true;
}

} // namespace smapix

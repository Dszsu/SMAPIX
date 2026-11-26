#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <unordered_map>

namespace smapix {

// Builds an in-memory map of lowercase assembly filename (e.g. "system.private.corelib.dll")
// -> absolute file path inside bcl_dir. Thread-safe once initialized prior to MonoVM startup.
class BclSwapIndex {
public:
    size_t Init(const std::string &bcl_dir);

    // Returns true and allocates a malloc'd buffer + size if the assembly image name matches a
    // pure .NET 9.0.8 BCL assembly in our replacement index. Caller must free(*out_buf).
    bool TryLoadReplacement(const char *image_name, uint8_t **out_buf, uint32_t *out_len) const;

private:
    std::unordered_map<std::string, std::string> entries_;
};

} // namespace smapix

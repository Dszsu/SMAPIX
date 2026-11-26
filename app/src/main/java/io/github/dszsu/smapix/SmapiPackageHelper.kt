package io.github.dszsu.smapix

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.edit
import io.github.dszsu.smapix.xposed.SmapiXposedModule
import io.github.libxposed.service.XposedService
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

data class ImportedSmapiInfo(
    val versionLabel: String,
    val fileCount: Int,
    val sizeBytes: Long,
    val stamp: String
)

data class InstalledModInfo(
    val folderName: String,
    val displayName: String,
    val version: String,
    val author: String,
    val uniqueId: String,
    val description: String,
    val enabled: Boolean,
    val hasManifest: Boolean
)

data class ModInstallResult(
    val installedNames: List<String>,
    val targetFolders: List<String>,
    val fileCount: Int
)

data class RootStardewState(
    val hasRoot: Boolean,
    val mods: List<InstalledModInfo>,
    val latestLog: String?,
    val bootstrapLog: String?
)

private data class ZipEntryData(
    val normPath: String,
    val entry: ZipEntry
)

private data class ModManifestFields(
    val name: String = "",
    val version: String = "",
    val author: String = "",
    val uniqueId: String = "",
    val description: String = ""
)

object SmapiPackageHelper {

    private const val TARGET_FILES_EXTERNAL =
        "/storage/emulated/0/Android/data/${SmapiXposedModule.TARGET_PACKAGE}/files"
    private const val TARGET_MODS_DIR = "$TARGET_FILES_EXTERNAL/Mods"
    private const val REAL_MODS_DIR =
        "/data/media/0/Android/data/${SmapiXposedModule.TARGET_PACKAGE}/files/Mods"
    private const val TARGET_SMAPI_LOG = "$TARGET_FILES_EXTERNAL/ErrorLogs/SMAPI-latest.txt"
    @SuppressLint("SdCardPath")
    private const val TARGET_BOOTSTRAP_LOG =
        "/data/user/0/${SmapiXposedModule.TARGET_PACKAGE}/files/smapix/bootstrap.log"

    fun importSmapiZip(
        context: Context,
        uri: Uri,
        service: XposedService?
    ): Result<ImportedSmapiInfo> = runCatching {
        val cacheTmp = File(context.cacheDir, "smapi_import.tmp.zip")
        try {
            copyUriToFile(context, uri, cacheTmp)

            var hasSmapiDll = false
            var fileCount = 0
            var detectedVersion = ""
            ZipInputStream(cacheTmp.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        fileCount++
                        val name = entry.name.replace('\\', '/')
                        if (name.endsWith("/StardewModdingAPI.dll", ignoreCase = true) ||
                            name.equals("StardewModdingAPI.dll", ignoreCase = true)
                        ) {
                            hasSmapiDll = true
                            val folderPart = name.substringBeforeLast('/', "")
                            if (folderPart.isNotEmpty() && detectedVersion.isEmpty()) {
                                detectedVersion = folderPart.substringAfterLast('/')
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            require(hasSmapiDll) { context.getString(R.string.err_zip_missing_smapi_dll) }

            val sizeBytes = cacheTmp.length()
            val stamp = "${sizeBytes}:${System.currentTimeMillis()}"

            val activeService = service ?: error(context.getString(R.string.err_lsposed_service_missing))
            runCatching { activeService.deleteRemoteFile(SmapiXposedModule.REMOTE_SMAPI_ZIP) }

            val pfd = activeService.openRemoteFile(SmapiXposedModule.REMOTE_SMAPI_ZIP)
            pfd.use { fd ->
                FileOutputStream(fd.fileDescriptor).use { out ->
                    out.channel.truncate(0)
                    cacheTmp.inputStream().use { inp -> inp.copyTo(out) }
                    out.fd.sync()
                }
            }

            val prefs = activeService.getRemotePreferences(SmapiXposedModule.PREFS_CONFIG)
            prefs.edit {
                putString(SmapiXposedModule.KEY_SMAPI_STAMP, stamp)
                putString("smapi_version_label", detectedVersion.ifEmpty { "SMAPI-Android" })
            }

            ImportedSmapiInfo(
                versionLabel = detectedVersion.ifEmpty {
                    context.resources.getQuantityString(R.plurals.smapi_version_files_fallback, fileCount, fileCount)
                },
                fileCount = fileCount,
                sizeBytes = sizeBytes,
                stamp = stamp
            )
        } finally {
            cacheTmp.delete()
        }
    }

    /**
     * Installs one or more SMAPI mods from a user-selected `.zip` archive into Android/data/.../Mods via Root.
     * Supports flat root manifests, standard single-folder archives, wrapped archives, and multi-mod packs.
     */
    fun installModZip(context: Context, uri: Uri): Result<ModInstallResult> = runCatching {
        val tmpZip = File(context.cacheDir, "mod_install_src.tmp.zip")
        val stageRoot = File(context.cacheDir, "mod_stage_${System.currentTimeMillis()}")
        try {
            copyUriToFile(context, uri, tmpZip)
            requireZipSignature(context, tmpZip)

            val fallbackName = queryDisplayName(context, uri)
                ?.substringBeforeLast('.')
                ?.let(::sanitizeFolderName)
                .orEmpty()
                .ifEmpty { "ImportedMod" }

            stageRoot.mkdirs()

            val (installedNames, targetFolders, fileCount) = openZipWithFallbackCharset(tmpZip) { zip ->
                val validEntries = collectValidZipEntries(zip)
                val manifestEntries = validEntries.filter {
                    it.normPath.substringAfterLast('/').equals("manifest.json", ignoreCase = true)
                }
                require(manifestEntries.isNotEmpty()) {
                    context.getString(R.string.err_zip_missing_manifest)
                }

                // Keep only top-most mod roots so internal assets/manifest.json files aren't split into separate mods.
                val sortedRoots = manifestEntries
                    .map { it.normPath.substringBeforeLast('/', "") }
                    .distinct()
                    .sortedBy { if (it.isEmpty()) 0 else it.count { ch -> ch == '/' } + 1 }

                val modRoots = mutableListOf<String>()
                for (candidate in sortedRoots) {
                    val covered = modRoots.any { root ->
                        root.isEmpty() || candidate.startsWith("$root/")
                    }
                    if (!covered) modRoots.add(candidate)
                }

                val usedFolders = mutableSetOf<String>()
                val names = mutableListOf<String>()
                val folders = mutableListOf<String>()
                var extractedFiles = 0

                for (modRoot in modRoots) {
                    val manifestEntry = manifestEntries.first {
                        it.normPath.substringBeforeLast('/', "") == modRoot
                    }
                    val manifestRaw = zip.getInputStream(manifestEntry.entry).bufferedReader().use { it.readText() }
                    val manifest = parseManifestLenient(manifestRaw)

                    val baseFolder = if (modRoot.isEmpty()) {
                        sanitizeFolderName(manifest.name).ifEmpty { fallbackName }
                    } else {
                        sanitizeFolderName(modRoot.substringAfterLast('/'))
                            .ifEmpty { sanitizeFolderName(manifest.name) }
                            .ifEmpty { fallbackName }
                    }

                    var targetFolder = baseFolder
                    var suffix = 2
                    while (!usedFolders.add(targetFolder.lowercase())) {
                        targetFolder = "${baseFolder}_$suffix"
                        suffix++
                    }

                    val targetStageDir = File(stageRoot, targetFolder).apply { mkdirs() }
                    val stageCanonical = targetStageDir.canonicalPath

                    val rootPrefix = if (modRoot.isEmpty()) "" else "$modRoot/"
                    for ((normPath, entry) in validEntries) {
                        if (rootPrefix.isNotEmpty() && !normPath.startsWith(rootPrefix)) continue
                        val rel = if (rootPrefix.isEmpty()) normPath else normPath.removePrefix(rootPrefix)
                        if (rel.isEmpty()) continue

                        val outFile = File(targetStageDir, rel)
                        if (!outFile.canonicalPath.startsWith(stageCanonical + File.separator)) continue
                        outFile.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { inp ->
                            FileOutputStream(outFile).use { out -> inp.copyTo(out) }
                        }
                        extractedFiles++
                    }

                    names.add(manifest.name.ifEmpty { targetFolder })
                    folders.add(targetFolder)
                }

                Triple(names.toList(), folders.toList(), extractedFiles)
            }

            require(requestRootPermission()) { context.getString(R.string.err_root_required_mods) }

            val modsQ = shellQuote(TARGET_MODS_DIR)
            val realModsQ = shellQuote(REAL_MODS_DIR)
            val stageQ = shellQuote(stageRoot.absolutePath)
            val installScript = buildString {
                append("set -e; ")
                append("mkdir -p $modsQ; ")
                append($$"ug=$(stat -c '%u:%g' $$realModsQ 2>/dev/null || stat -c '%u:%g' $$modsQ 2>/dev/null || true); ")
                append("for src in $stageQ/*; do ")
                append($$"[ -d \"$src\" ] || continue; ")
                append($$"name=\"${src##*/}\"; ")
                append($$"dst=$$modsQ/\"$name\"; ")
                append($$"disabled_dst=$$modsQ/.\"$name\"; ")
                append($$"target_name=\"$name\"; ")
                // Preserve user config.json when updating an existing enabled or disabled mod
                append($$"if [ -f \"$dst/config.json\" ] && [ ! -f \"$src/config.json\" ]; then ")
                append($$"cp \"$dst/config.json\" \"$src/config.json\"; ")
                append($$"elif [ -f \"$disabled_dst/config.json\" ] && [ ! -f \"$src/config.json\" ]; then ")
                append($$"cp \"$disabled_dst/config.json\" \"$src/config.json\"; ")
                append("fi; ")
                append($$"if [ -d \"$disabled_dst\" ] && [ ! -d \"$dst\" ]; then dst=\"$disabled_dst\"; target_name=.\"$name\"; fi; ")
                append($$"rm -rf \"$dst\"; ")
                append($$"cp -r \"$src\" \"$dst\"; ")
                append($$"chmod -R ug+rwX,o-rwx \"$dst\" 2>/dev/null || chmod -R 0770 \"$dst\" 2>/dev/null || true; ")
                append($$"real_dst=$$realModsQ/\"$target_name\"; ")
                append($$"if [ -n \"$ug\" ] && [ -e \"$real_dst\" ]; then ")
                append($$"chown -R \"$ug\" \"$real_dst\" 2>/dev/null || true; ")
                append($$"chmod -R ug+rwX,o-rwx \"$real_dst\" 2>/dev/null || true; ")
                append("fi; ")
                append("done")
            }

            val result = runSuCommandDetailed(installScript, timeoutMs = 20_000, context = context)
            check(result.exitCode == 0) {
                result.stderr.ifBlank { context.getString(R.string.err_root_copy_mods_failed, result.exitCode) }
            }

            ModInstallResult(
                installedNames = installedNames,
                targetFolders = targetFolders,
                fileCount = fileCount
            )
        } finally {
            tmpZip.delete()
            stageRoot.deleteRecursively()
        }
    }

    /**
     * Toggles a mod using SMAPI's native dot-prefix ignore convention (`Foo` <-> `.Foo`).
     */
    fun setModEnabled(context: Context, folderName: String, enable: Boolean): Result<String> = runCatching {
        validateSingleFolderName(context, folderName)
        val cleanName = folderName.removePrefix(".")
        require(cleanName.isNotEmpty()) { context.getString(R.string.err_invalid_mod_folder) }

        val targetName = if (enable) cleanName else ".$cleanName"
        if (folderName == targetName) return@runCatching targetName

        val srcQ = shellQuote("$TARGET_MODS_DIR/$folderName")
        val dstQ = shellQuote("$TARGET_MODS_DIR/$targetName")
        val script = buildString {
            append("if [ ! -d $srcQ ]; then exit 2; fi; ")
            append("if [ -e $dstQ ]; then exit 3; fi; ")
            append("mv $srcQ $dstQ")
        }
        val res = runSuCommandDetailed(script, timeoutMs = 5000, context = context)
        check(res.exitCode == 0) {
            when (res.exitCode) {
                2 -> context.getString(R.string.err_mod_folder_not_found)
                3 -> context.getString(R.string.err_mod_conflict_exists, targetName)
                else -> res.stderr.ifBlank { context.getString(R.string.err_toggle_failed) }
            }
        }
        targetName
    }

    fun deleteModFolder(context: Context, folderName: String): Result<Unit> = runCatching {
        validateSingleFolderName(context, folderName)
        val targetQ = shellQuote("$TARGET_MODS_DIR/$folderName")
        val res = runSuCommandDetailed("rm -rf $targetQ", timeoutMs = 8000, context = context)
        check(res.exitCode == 0) {
            res.stderr.ifBlank { context.getString(R.string.err_delete_mod_failed) }
        }
    }

    fun readStardewVersion(context: Context): String? = runCatching {
        val pm = context.packageManager
        val info = pm.getPackageInfo(SmapiXposedModule.TARGET_PACKAGE, 0)
        "${info.versionName} (${info.longVersionCode})"
    }.getOrNull()

    fun requestRootPermission(): Boolean =
        runSuCommand("id -u", timeoutMs = 4000)?.trim() == "0"

    fun readRootStardewState(maxChars: Int = 20_000): RootStardewState {
        val modsQ = shellQuote(TARGET_MODS_DIR)
        val smapiLogQ = shellQuote(TARGET_SMAPI_LOG)
        val bootLogQ = shellQuote(TARGET_BOOTSTRAP_LOG)

        val script = buildString {
            append("if [ \"$(id -u)\" = \"0\" ]; then echo '__ROOT_OK__'; ")
            append("if [ -d $modsQ ]; then ")
            append("for d in $modsQ/* $modsQ/.*; do ")
            append($$"[ -d \"$d\" ] || continue; ")
            append($$"b=\"${d##*/}\"; ")
            append($$"[ \"$b\" = '.' ] || [ \"$b\" = '..' ] && continue; ")
            append($$"case \"$b\" in .DS_Store|.nomedia|.git|.trash|.Trash|.thumbnails|._*|__MACOSX) continue ;; esac; ")
            append("echo '__MOD_ITEM__'; ")
            append($$"printf '%s\\n' \"$b\"; ")
            append("m=''; ")
            append($$"for c in \"$d/manifest.json\" \"$d/Manifest.json\"; do ")
            append($$"if [ -f \"$c\" ]; then m=\"$c\"; break; fi; ")
            append("done; ")
            append($$"if [ -n \"$m\" ]; then ")
            append("echo '__HAS_MANIFEST__'; ")
            append($$"head -c 4096 \"$m\" 2>/dev/null; ")
            append("echo ''; ")
            append("fi; ")
            append("echo '__MOD_END__'; ")
            append("done; ")
            append("fi; ")
            append("echo '__SMAPI_LOG__'; ")
            append("if [ -f $smapiLogQ ]; then tail -c $maxChars $smapiLogQ 2>/dev/null; fi; ")
            append("echo ''; ")
            append("echo '__BOOT_LOG__'; ")
            append("if [ -f $bootLogQ ]; then tail -c $maxChars $bootLogQ 2>/dev/null; fi; ")
            append("fi")
        }

        val out = runSuCommand(script, timeoutMs = 3500)
            ?: return RootStardewState(hasRoot = false, mods = emptyList(), latestLog = null, bootstrapLog = null)
        if (!out.contains("__ROOT_OK__")) {
            return RootStardewState(hasRoot = false, mods = emptyList(), latestLog = null, bootstrapLog = null)
        }

        val afterOk = out.substringAfter("__ROOT_OK__\n", "")
        val modsBlock = afterOk.substringBefore("__SMAPI_LOG__")
        val logsBlock = afterOk.substringAfter("__SMAPI_LOG__\n", "")
        val rawSmapiLog = logsBlock.substringBefore("__BOOT_LOG__")
        val rawBootLog = logsBlock.substringAfter("__BOOT_LOG__\n", "")

        val mods = parseModBlocks(modsBlock)

        return RootStardewState(
            hasRoot = true,
            mods = mods,
            latestLog = formatTailLog(rawSmapiLog, maxChars),
            bootstrapLog = formatTailLog(rawBootLog, maxChars)
        )
    }

    private fun parseModBlocks(modsBlock: String): List<InstalledModInfo> {
        val result = mutableListOf<InstalledModInfo>()
        var cursor = 0
        while (true) {
            val startIdx = modsBlock.indexOf("__MOD_ITEM__\n", cursor)
            if (startIdx < 0) break
            val bodyStart = startIdx + "__MOD_ITEM__\n".length
            val endIdx = modsBlock.indexOf("__MOD_END__", bodyStart)
            if (endIdx < 0) break
            cursor = endIdx + "__MOD_END__".length

            val chunk = modsBlock.substring(bodyStart, endIdx)
            val nl = chunk.indexOf('\n')
            val folderName = (if (nl >= 0) chunk.substring(0, nl) else chunk).trimEnd('\r')
            if (folderName.isEmpty()) continue

            val rest = if (nl >= 0) chunk.substring(nl + 1) else ""
            val hasManifest = rest.startsWith("__HAS_MANIFEST__\n")
            val manifestRaw = if (hasManifest) rest.removePrefix("__HAS_MANIFEST__\n") else ""
            val fields = if (hasManifest) parseManifestLenient(manifestRaw) else ModManifestFields()
            val enabled = !folderName.startsWith(".")
            val cleanName = folderName.removePrefix(".")

            result.add(
                InstalledModInfo(
                    folderName = folderName,
                    displayName = fields.name.ifEmpty { cleanName },
                    version = fields.version,
                    author = fields.author,
                    uniqueId = fields.uniqueId,
                    description = fields.description,
                    enabled = enabled,
                    hasManifest = hasManifest
                )
            )
        }
        return result.sortedWith(
            compareByDescending<InstalledModInfo> { it.enabled }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }
        )
    }

    private fun formatTailLog(raw: String, maxChars: Int): String? {
        val trimmed = raw.trimEnd('\r', '\n')
        if (trimmed.isBlank()) return null
        return if (trimmed.length >= maxChars) "...\n$trimmed" else trimmed
    }

    private fun copyUriToFile(context: Context, uri: Uri, dst: File) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(dst).use { output -> input.copyTo(output) }
        } ?: error(context.getString(R.string.err_read_archive_failed))
    }

    private fun requireZipSignature(context: Context, file: File) {
        require(file.length() >= 4) { context.getString(R.string.err_file_empty_invalid) }
        val header = ByteArray(2)
        file.inputStream().use { it.read(header) }
        require(header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()) {
            context.getString(R.string.err_not_zip_archive)
        }
    }

    private fun <T> openZipWithFallbackCharset(file: File, block: (ZipFile) -> T): T {
        return try {
            ZipFile(file, Charsets.UTF_8).use { zip ->
                // Trigger entry enumeration inside UTF-8 so malformed non-UTF8 names fall back immediately
                zip.entries().toList()
                block(zip)
            }
        } catch (_: IllegalArgumentException) {
            val fallback = runCatching { Charset.forName("GBK") }.getOrDefault(Charsets.ISO_8859_1)
            ZipFile(file, fallback).use(block)
        }
    }

    private fun collectValidZipEntries(zip: ZipFile): List<ZipEntryData> {
        val list = mutableListOf<ZipEntryData>()
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory) continue
            val norm = entry.name.replace('\\', '/').trimStart('/')
            if (norm.isEmpty()) continue
            val segments = norm.split('/')
            if (segments.any { it.isEmpty() || it == "." || it == ".." }) continue
            val top = segments.first()
            val leaf = segments.last()
            if (top.equals("__MACOSX", ignoreCase = true) ||
                leaf.equals(".DS_Store", ignoreCase = true) ||
                leaf.equals("Thumbs.db", ignoreCase = true) ||
                leaf.startsWith("._")
            ) {
                continue
            }
            list.add(ZipEntryData(norm, entry))
        }
        return list
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()

    private fun sanitizeFolderName(raw: String): String =
        raw.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]+"), "_")
            .trim()
            .trimStart('.')
            .trim()

    private fun validateSingleFolderName(context: Context, folderName: String) {
        require(
            folderName.isNotEmpty() &&
                folderName != "." &&
                folderName != ".." &&
                !folderName.contains('/') &&
                !folderName.contains('\\') &&
                !folderName.contains('\u0000')
        ) { context.getString(R.string.err_invalid_folder_name) }
    }

    private fun parseManifestLenient(raw: String): ModManifestFields {
        if (raw.isBlank()) return ModManifestFields()
        return runCatching {
            val cleaned = stripJsonCommentsAndTrailingCommas(raw.removePrefix("\uFEFF"))
            val obj = JSONObject(cleaned)
            ModManifestFields(
                name = obj.optString("Name", "").trim(),
                version = parseVersionField(obj),
                author = obj.optString("Author", "").trim(),
                uniqueId = obj.optString("UniqueID", "").trim(),
                description = obj.optString("Description", "").trim()
            )
        }.getOrDefault(ModManifestFields())
    }

    private fun parseVersionField(obj: JSONObject): String {
        val raw = obj.opt("Version") ?: return ""
        return when (raw) {
            is String -> raw.trim()
            is JSONObject -> {
                val major = raw.optInt("MajorVersion", -1)
                val minor = raw.optInt("MinorVersion", 0)
                val patch = raw.optInt("PatchVersion", 0)
                if (major >= 0) "$major.$minor.$patch" else ""
            }
            else -> ""
        }
    }

    // SMAPI manifest.json often uses JSON5 line/block comments and trailing commas.
    private fun stripJsonCommentsAndTrailingCommas(input: String): String {
        val noComments = StringBuilder(input.length)
        var i = 0
        var inString = false
        var escaped = false
        while (i < input.length) {
            val c = input[i]
            val next = if (i + 1 < input.length) input[i + 1] else '\u0000'
            if (inString) {
                noComments.append(c)
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inString = false
                }
                i++
            } else {
                when (c) {
                    '"' -> {
                        inString = true
                        noComments.append(c)
                        i++
                    }
                    '/' if next == '/' -> {
                        i += 2
                        while (i < input.length && input[i] != '\n') i++
                    }
                    '/' if next == '*' -> {
                        i += 2
                        while (i + 1 < input.length && !(input[i] == '*' && input[i + 1] == '/')) i++
                        i = (i + 2).coerceAtMost(input.length)
                    }
                    else -> {
                        noComments.append(c)
                        i++
                    }
                }
            }
        }

        // Remove trailing commas right before '}' or ']' outside string literals
        val out = StringBuilder(noComments.length)
        inString = false
        escaped = false
        val s = noComments.toString()
        i = 0
        while (i < s.length) {
            val c = s[i]
            if (inString) {
                out.append(c)
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inString = false
                i++
            } else if (c == '"') {
                inString = true
                out.append(c)
                i++
            } else if (c == ',') {
                var j = i + 1
                while (j < s.length && s[j].isWhitespace()) j++
                if (j < s.length && (s[j] == '}' || s[j] == ']')) {
                    i++
                    continue
                }
                out.append(c)
                i++
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private fun shellQuote(value: String): String =
        "'${value.replace("'", "'\"'\"'")}'"

    private data class SuResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    )

    private fun runSuCommand(cmd: String, timeoutMs: Long): String? =
        runCatching {
            val res = runSuCommandDetailed(cmd, timeoutMs)
            if (res.exitCode == 0) res.stdout else null
        }.getOrNull()

    private fun runSuCommandDetailed(cmd: String, timeoutMs: Long, context: Context? = null): SuResult {
        val proc = ProcessBuilder("su", "-c", cmd).start()
        var stdout = ""
        var stderr = ""
        val outThread = Thread { stdout = proc.inputStream.bufferedReader().readText() }.apply { start() }
        val errThread = Thread { stderr = proc.errorStream.bufferedReader().readText() }.apply { start() }
        val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            proc.destroyForcibly()
            return SuResult(-1, stdout, context?.getString(R.string.err_root_cmd_timeout) ?: "Root command timed out")
        }
        outThread.join(1000)
        errThread.join(1000)
        return SuResult(proc.exitValue(), stdout, stderr.trim())
    }
}

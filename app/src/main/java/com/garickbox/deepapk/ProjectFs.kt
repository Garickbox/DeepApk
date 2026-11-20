package com.garickbox.deepapk

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import kotlin.coroutines.cancellation.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

sealed class FsReadOp {
    data class Tree(val path: String) : FsReadOp()
    data class Grep(val pattern: String, val path: String) : FsReadOp()
    data class Read(val path: String, val start: Int, val end: Int) : FsReadOp()
}

sealed class FsWriteOp {
    abstract val path: String
    data class Patch(override val path: String, val replacements: List<Pair<String, String>>) : FsWriteOp()
    data class Write(override val path: String, val content: String) : FsWriteOp()
}

data class FsApplyResult(
    val ok: Boolean,
    val message: String,
    val modifiedFiles: List<String> = emptyList()
)

private data class DocEntry(
    val docId: String,
    val name: String,
    val isDir: Boolean,
    val size: Long
)

object ProjectFs {
    private const val PREF = "deepapk_project_fs"
    private const val KEY_URI = "tree_uri"
    private const val KEY_LABEL = "tree_label"
    private const val KEY_ENABLED = "auto_enabled"

    private val IGNORED_DIRS = setOf(".git", ".gradle", "build", ".idea", "apk", "node_modules", "__pycache__", ".cxx", ".ocr")
    private val BINARY_EXTS = setOf(
        "png", "jpg", "jpeg", "webp", "gif", "ico", "jar", "apk", "aab",
        "so", "dex", "class", "zip", "ttf", "otf", "pdf", "docx", "xlsx", "pptx"
    )

    fun saveTree(ctx: Context, uri: Uri): String {
        val rootId = DocumentsContract.getTreeDocumentId(uri)
        val label = rootId.substringAfterLast(':').substringAfterLast('/').ifBlank { "Проект" }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY_URI, uri.toString())
            .putString(KEY_LABEL, label)
            .putBoolean(KEY_ENABLED, true)
            .apply()
        return label
    }

    fun clearTree(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .remove(KEY_URI)
            .remove(KEY_LABEL)
            .putBoolean(KEY_ENABLED, false)
            .apply()
    }

    fun setEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun getLabel(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_LABEL, "").orEmpty()

    fun getTreeUri(ctx: Context): Uri? {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_URI, null) ?: return null
        val uri = Uri.parse(raw)
        val hasPerm = ctx.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        return if (hasPerm) uri else null
    }

    fun isReady(ctx: Context): Boolean = getTreeUri(ctx) != null

    fun systemPromptAddendum(ctx: Context): String {
        val label = getLabel(ctx).ifBlank { "Проект" }
        val bak = activeBackupCount(ctx)
        val bakNote = if (bak > 0) " Внимание: сейчас активен бэкап прошлой правки ($bak файл.) — не предлагай новые правки, пока пользователь не проверит текущую." else ""
        return "ДОСТУП К ПРОЕКТУ (SAF, папка «$label»): подключены реальные исходники.\n" +
            "Не гадай и не проси юзера гонять grep — сразу выводи до 4 тегов (пути только реальные из fs_tree/fs_grep):\n" +
            "- <fs_tree/> или <fs_tree path=\"РЕАЛЬНАЯ_ПАПКА\"/>\n" +
            "- <fs_grep pattern=\"ТЕКСТ\" path=\"РЕАЛЬНАЯ_ПАПКА\"/>\n" +
            "- <fs_read path=\"РЕАЛЬНЫЙ_ФАЙЛ\" start=\"1\" end=\"180\"/>\n" +
            "Правка в SAF: <fs_patch path=\"РЕАЛЬНЫЙ_ФАЙЛ\"><old>ТОЧНЫЙ_ФРАГМЕНТ</old><new>НОВЫЙ_КОД</new></fs_patch> или <fs_write path=\"РЕАЛЬНЫЙ_ФАЙЛ\">КОД</fs_write>.\n" +
            "📚 КНИГИ/OCR: при вопросе про книгу/PDF сначала проверь \".ocr\" через <fs_tree path=\".ocr\"/> и <fs_grep pattern=\"СЛОВО\" path=\".ocr\"/> (внутри _name.txt и page_NNNNN.md)." +
            (if (bakNote.isNotEmpty()) "\n" + bakNote.trim() else "")
    }

    internal fun sanitizeRelPath(raw: String): String? {
        val clean = raw.trim().replace('\\', '/').trimStart('/')
        if (clean.isBlank()) return ""
        val segs = clean.split('/').filter { it.isNotBlank() && it != "." }
        if (segs.any { it == ".." || it.contains('\u0000') }) return null
        return segs.joinToString("/")
    }

    private fun listChildren(ctx: Context, treeUri: Uri, parentDocId: String): List<DocEntry> {
        val out = mutableListOf<DocEntry>()
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val proj = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        try {
            ctx.contentResolver.query(childUri, proj, null, null, null)?.use { c ->
                val iId = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val iName = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val iMime = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val iSize = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                while (c.moveToNext()) {
                    val id = if (iId >= 0) c.getString(iId).orEmpty() else continue
                    val name = if (iName >= 0) c.getString(iName).orEmpty() else continue
                    val mime = if (iMime >= 0) c.getString(iMime).orEmpty() else ""
                    val size = if (iSize >= 0 && !c.isNull(iSize)) c.getLong(iSize) else 0L
                    out.add(DocEntry(id, name, mime == DocumentsContract.Document.MIME_TYPE_DIR, size))
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (se: SecurityException) {
            Log.e("DeepApk", "listChildren($parentDocId) SecurityException", se)
        } catch (te: Throwable) {
            Log.e("DeepApk", "listChildren($parentDocId) failed: ${te.javaClass.simpleName}: ${te.message}", te)
        }
        return out.sortedWith(compareBy<DocEntry> { !it.isDir }.thenBy { it.name.lowercase() })
    }

    private fun findDoc(ctx: Context, treeUri: Uri, relPath: String): DocEntry? {
        val clean = sanitizeRelPath(relPath) ?: return null
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        if (clean.isEmpty()) return DocEntry(rootId, getLabel(ctx), true, 0L)
        var curId = rootId
        var found: DocEntry? = null
        for (seg in clean.split('/')) {
            val children = listChildren(ctx, treeUri, curId)
            val match = children.firstOrNull { it.name == seg }
                ?: children.firstOrNull { it.name.equals(seg, ignoreCase = true) }
                ?: return null
            found = match
            curId = match.docId
        }
        return found
    }

    private fun ensureFileDoc(ctx: Context, treeUri: Uri, relPath: String): Pair<DocEntry, Boolean>? {
        val existing = findDoc(ctx, treeUri, relPath)
        if (existing != null) return if (existing.isDir) null else (existing to true)
        val clean = sanitizeRelPath(relPath)?.takeIf { it.isNotEmpty() } ?: return null
        val segs = clean.split('/')
        var curId = DocumentsContract.getTreeDocumentId(treeUri)
        for (idx in 0 until segs.lastIndex) {
            val dirName = segs[idx]
            val children = listChildren(ctx, treeUri, curId)
            val next = children.firstOrNull { it.isDir && it.name == dirName }
            curId = if (next != null) {
                next.docId
            } else {
                val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, curId)
                val created = DocumentsContract.createDocument(
                    ctx.contentResolver,
                    parentUri,
                    DocumentsContract.Document.MIME_TYPE_DIR,
                    dirName
                ) ?: return null
                DocumentsContract.getDocumentId(created)
            }
        }
        val fileName = segs.last()
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, curId)
        val createdFile = DocumentsContract.createDocument(ctx.contentResolver, parentUri, "text/plain", fileName)
            ?: return null
        return DocEntry(DocumentsContract.getDocumentId(createdFile), fileName, false, 0L) to false
    }

    private fun readBytesDoc(ctx: Context, treeUri: Uri, docId: String): ByteArray? {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        return try {
            ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (ce: CancellationException) {
            throw ce
        } catch (se: SecurityException) {
            Log.e("DeepApk", "readBytesDoc($docId) SecurityException", se)
            null
        } catch (te: Throwable) {
            Log.e("DeepApk", "readBytesDoc($docId) failed: ${te.javaClass.simpleName}: ${te.message}", te)
            null
        }
    }

    private fun writeBytesDoc(ctx: Context, treeUri: Uri, docId: String, bytes: ByteArray): Boolean {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        val os = try {
            ctx.contentResolver.openOutputStream(uri, "wt")
        } catch (ce1: CancellationException) {
            throw ce1
        } catch (e1: Exception) {
            try {
                ctx.contentResolver.openOutputStream(uri, "w")
            } catch (ce2: CancellationException) {
                throw ce2
            } catch (e2: Exception) {
                Log.e("DeepApk", "writeBytesDoc($docId) both modes failed: wt=${e1.message}, w=${e2.message}")
                null
            }
        } ?: return false
        os.use { it.write(bytes); it.flush() }
        return true
    }

    internal fun isTextFile(name: String, size: Long): Boolean {
        if (size > 350_000L) return false
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext !in BINARY_EXTS
    }

    fun listTree(ctx: Context, relPath: String = "", maxEntries: Int = 180): String {
        val treeUri = getTreeUri(ctx) ?: return "[SAF: папка проекта не подключена]"
        val start = findDoc(ctx, treeUri, relPath) ?: return "[SAF: путь не найден: $relPath]"
        if (!start.isDir) return "${sanitizeRelPath(relPath)} (${start.size} B)"
        val out = mutableListOf<String>()
        val basePrefix = sanitizeRelPath(relPath).orEmpty()

        fun walk(docId: String, prefix: String) {
            if (out.size >= maxEntries) return
            for (c in listChildren(ctx, treeUri, docId)) {
                if (out.size >= maxEntries) return
                if (c.isDir && c.name in IGNORED_DIRS) continue
                val full = if (prefix.isEmpty()) c.name else "$prefix/${c.name}"
                if (c.isDir) {
                    out.add("$full/")
                    walk(c.docId, full)
                } else {
                    out.add("$full (${c.size} B)")
                }
            }
        }
        walk(start.docId, basePrefix)
        if (out.isEmpty()) return "[Пусто]"
        val suffix = if (out.size >= maxEntries) "\n... [показано первых $maxEntries элементов]" else ""
        return out.joinToString("\n") + suffix
    }

    fun grepProject(ctx: Context, pattern: String, relPath: String = "", maxHits: Int = 45): String {
        val treeUri = getTreeUri(ctx) ?: return "[SAF: папка проекта не подключена]"
        if (pattern.isBlank()) return "[SAF: пустой шаблон поиска]"
        val start = findDoc(ctx, treeUri, relPath) ?: return "[SAF: путь не найден: $relPath]"
        val rx = try { Regex(pattern) } catch (_: Exception) { null }
        val hits = mutableListOf<String>()
        val basePrefix = sanitizeRelPath(relPath).orEmpty()

        fun scanFile(docId: String, fullPath: String, name: String, size: Long) {
            if (hits.size >= maxHits || !isTextFile(name, size)) return
            val bytes = try { readBytesDoc(ctx, treeUri, docId) } catch (ce: CancellationException) {
                throw ce
            } catch (se: SecurityException) {
                Log.e("DeepApk", "grep read $fullPath SecurityException", se); null
            } catch (te: Throwable) {
                Log.e("DeepApk", "grep read $fullPath failed: ${te.javaClass.simpleName}: ${te.message}", te); null
            } ?: return
            val lines = String(bytes, Charsets.UTF_8).replace("\r\n", "\n").split('\n')
            for ((idx, line) in lines.withIndex()) {
                if (hits.size >= maxHits) break
                val matched = rx?.containsMatchIn(line) ?: line.contains(pattern, ignoreCase = true)
                if (matched) {
                    hits.add("$fullPath:${idx + 1}: ${line.trimEnd().take(180)}")
                }
            }
        }

        if (!start.isDir) {
            scanFile(start.docId, basePrefix, start.name, start.size)
        } else {
            fun walk(docId: String, prefix: String) {
                if (hits.size >= maxHits) return
                for (c in listChildren(ctx, treeUri, docId)) {
                    if (hits.size >= maxHits) break
                    if (c.isDir && c.name in IGNORED_DIRS) continue
                    val full = if (prefix.isEmpty()) c.name else "$prefix/${c.name}"
                    if (c.isDir) walk(c.docId, full) else scanFile(c.docId, full, c.name, c.size)
                }
            }
            walk(start.docId, basePrefix)
        }
        if (hits.isEmpty()) return "[Совпадений не найдено для: $pattern]"
        val suffix = if (hits.size >= maxHits) "\n... [показано первых $maxHits совпадений]" else ""
        return hits.joinToString("\n") + suffix
    }

    fun readRawContent(ctx: Context, relPath: String): String? {
        val treeUri = getTreeUri(ctx) ?: return null
        val entry = findDoc(ctx, treeUri, relPath) ?: return null
        if (entry.isDir) return null
        if (!isTextFile(entry.name, entry.size)) return null
        val bytes = readBytesDoc(ctx, treeUri, entry.docId) ?: return null
        return String(bytes, Charsets.UTF_8).replace("\r\n", "\n")
    }

    fun writeRelFile(ctx: Context, relPath: String, text: String): Boolean {
        val treeUri = getTreeUri(ctx) ?: return false
        return try {
            val (doc, _) = ensureFileDoc(ctx, treeUri, relPath) ?: return false
            writeBytesDoc(ctx, treeUri, doc.docId, text.toByteArray(Charsets.UTF_8))
        } catch (ce: CancellationException) { throw ce }
        catch (te: Throwable) {
            Log.e("DeepApk", "writeRelFile($relPath): ${te.javaClass.simpleName}: ${te.message}", te)
            false
        }
    }

    fun readRelFile(ctx: Context, relPath: String): String? {
        val treeUri = getTreeUri(ctx) ?: return null
        return try {
            val doc = findDoc(ctx, treeUri, relPath) ?: return null
            if (doc.isDir) return null
            val bytes = readBytesDoc(ctx, treeUri, doc.docId) ?: return null
            String(bytes, Charsets.UTF_8)
        } catch (ce: CancellationException) { throw ce }
        catch (_: Throwable) { null }
    }

    fun deleteRelPath(ctx: Context, relPath: String): Boolean {
        val treeUri = getTreeUri(ctx) ?: return false
        return try {
            val doc = findDoc(ctx, treeUri, relPath) ?: return false
            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, doc.docId)
            DocumentsContract.deleteDocument(ctx.contentResolver, uri)
        } catch (ce: CancellationException) { throw ce }
        catch (te: Throwable) {
            Log.e("DeepApk", "deleteRelPath($relPath): ${te.javaClass.simpleName}", te)
            false
        }
    }

    fun listRelChildren(ctx: Context, relPath: String): List<Triple<String, Boolean, Long>> {
        val treeUri = getTreeUri(ctx) ?: return emptyList()
        return try {
            val doc = findDoc(ctx, treeUri, relPath) ?: return emptyList()
            if (!doc.isDir) return emptyList()
            listChildren(ctx, treeUri, doc.docId).map { Triple(it.name, it.isDir, it.size) }
        } catch (ce: CancellationException) { throw ce }
        catch (_: Throwable) { emptyList() }
    }

    fun readFileLines(ctx: Context, relPath: String, start: Int = 1, end: Int = 150): String {
        val treeUri = getTreeUri(ctx) ?: return "[SAF: папка проекта не подключена]"
        val entry = findDoc(ctx, treeUri, relPath) ?: return "[SAF: файл не найден: $relPath]"
        if (entry.isDir) return listTree(ctx, relPath)
        if (!isTextFile(entry.name, entry.size)) return "[SAF: бинарный или слишком большой файл: $relPath (${entry.size} B)]"
        val bytes = try { readBytesDoc(ctx, treeUri, entry.docId) } catch (ce: CancellationException) {
            throw ce
        } catch (se: SecurityException) {
            Log.e("DeepApk", "readFileLines($relPath) SecurityException", se); null
        } catch (te: Throwable) {
            Log.e("DeepApk", "readFileLines($relPath) failed: ${te.javaClass.simpleName}: ${te.message}", te); null
        } ?: return "[SAF: ошибка чтения $relPath]"
        val lines = String(bytes, Charsets.UTF_8).replace("\r\n", "\n").split('\n')
        if (lines.isEmpty()) return "[Пустой файл: $relPath]"
        val s = start.coerceIn(1, lines.size)
        val e = end.coerceIn(s, (s + 179).coerceAtMost(lines.size))
        val sb = StringBuilder("=== $relPath ($s..$e из ${lines.size}) ===\n")
        for (i in s..e) {
            sb.append(i).append(": ").append(lines[i - 1]).append('\n')
        }
        return sb.toString().trimEnd()
    }

    internal fun parseAttrs(attrStr: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val rx = Regex("""(\w+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
        rx.findAll(attrStr).forEach { m ->
            val k = m.groupValues[1].lowercase()
            val v = m.groupValues[2].ifEmpty { m.groupValues[3] }
            map[k] = v
        }
        return map
    }

    fun extractReadOps(rawText: String): List<FsReadOp> {
        val text = normalizeDsml(rawText)
        val ops = mutableListOf<Pair<Int, FsReadOp>>()
        Regex("""<fs_tree([^>]*?)(?:/>|>[\s\S]*?</fs_tree>)""", RegexOption.IGNORE_CASE).findAll(text).forEach { m ->
            val attrs = parseAttrs(m.groupValues[1])
            val _p = attrs["path"].orEmpty()
            if (!isPlaceholderPath(_p)) ops.add(m.range.first to FsReadOp.Tree(_p))
        }
        Regex("""<fs_grep([^>]*?)(?:/>|>[\s\S]*?</fs_grep>)""", RegexOption.IGNORE_CASE).findAll(text).forEach { m ->
            val attrs = parseAttrs(m.groupValues[1])
            val pat = attrs["pattern"].orEmpty()
            val _gp = attrs["path"].orEmpty()
            if (pat.isNotBlank() && !isPlaceholderPath(_gp)) ops.add(m.range.first to FsReadOp.Grep(pat, _gp))
        }
        Regex("""<fs_read([^>]*?)(?:/>|>[\s\S]*?</fs_read>)""", RegexOption.IGNORE_CASE).findAll(text).forEach { m ->
            val attrs = parseAttrs(m.groupValues[1])
            val p = attrs["path"].orEmpty()
            val s = attrs["start"]?.toIntOrNull() ?: 1
            val e = attrs["end"]?.toIntOrNull() ?: (s + 149)
            if (p.isNotBlank() && !isPlaceholderPath(p)) ops.add(m.range.first to FsReadOp.Read(p, s, e))
        }
        return ops.sortedBy { it.first }.map { it.second }.take(4)
    }

    fun stripReadTags(rawText: String, summary: String): String {
        var cleaned = normalizeDsml(rawText)
            .replace(Regex("""<fs_tree[^>]*?(?:/>|>[\s\S]*?</fs_tree>)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""<fs_grep[^>]*?(?:/>|>[\s\S]*?</fs_grep>)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""<fs_read[^>]*?(?:/>|>[\s\S]*?</fs_read>)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
        // Авто-заметка только в chipLabel payload'а (executeReadOps),
        // чтобы модель не копировала "> 🔍 Запрос к проекту:" из своего же контекста.
        if (cleaned.isBlank() && summary.isNotBlank()) cleaned = "— прочитано: $summary"
        return cleaned
    }

    fun executeReadOps(ctx: Context, ops: List<FsReadOp>): Pair<String, String> {
        val labels = mutableListOf<String>()
        val body = StringBuilder()
        ops.forEach { op ->
            when (op) {
                is FsReadOp.Tree -> {
                    val p = op.path.ifBlank { "/" }
                    labels.add("дерево $p")
                    body.append("=== TREE ").append(p).append(" ===\n")
                    body.append(listTree(ctx, op.path)).append("\n\n")
                }
                is FsReadOp.Grep -> {
                    labels.add("поиск «${op.pattern.take(24)}»")
                    body.append("=== GREP ").append(op.pattern).append(" ===\n")
                    body.append(grepProject(ctx, op.pattern, op.path)).append("\n\n")
                }
                is FsReadOp.Read -> {
                    val fName = op.path.substringAfterLast('/')
                    labels.add("$fName (${op.start}..${op.end})")
                    body.append(readFileLines(ctx, op.path, op.start, op.end)).append("\n\n")
                }
            }
        }
        val chipLabel = "Прочитан: " + labels.joinToString(" · ").take(64)
        val rawText = body.toString().trim()
        val fence = if (rawText.contains("```")) "````" else "```"
        val payload = "[Файл: $chipLabel]\n${fence}text\n$rawText\n$fence"
        return chipLabel to payload
    }

    fun extractWriteOps(rawText: String): List<FsWriteOp> {
        val text = normalizeDsml(rawText)
        val out = mutableListOf<Pair<Int, FsWriteOp>>()
        Regex("""<fs_patch([^>]*)>([\s\S]*?)</fs_patch>""", RegexOption.IGNORE_CASE).findAll(text).forEach { m ->
            val path = parseAttrs(m.groupValues[1])["path"].orEmpty().trim()
            if (path.isNotBlank() && !isPlaceholderPath(path)) {
                val inner = m.groupValues[2]
                val pairs = Regex("""<old>([\s\S]*?)</old>\s*<new>([\s\S]*?)</new>""").findAll(inner)
                    .map { pm -> pm.groupValues[1].trim('\r', '\n') to pm.groupValues[2].trim('\r', '\n') }
                    .toList()
                if (pairs.isNotEmpty()) out.add(m.range.first to FsWriteOp.Patch(path, pairs))
            }
        }
        Regex("""<fs_write([^>]*)>([\s\S]*?)</fs_write>""", RegexOption.IGNORE_CASE).findAll(text).forEach { m ->
            val path = parseAttrs(m.groupValues[1])["path"].orEmpty().trim()
            if (path.isNotBlank() && !isPlaceholderPath(path)) {
                val body = m.groupValues[2].trim('\r', '\n')
                out.add(m.range.first to FsWriteOp.Write(path, body))
            }
        }
        return out.sortedBy { it.first }.map { it.second }
    }

    private fun backupDir(ctx: Context): File = File(ctx.filesDir, "fs_backups")
    private fun backupIndexFile(ctx: Context): File = File(backupDir(ctx), "active.json")

    fun activeBackupCount(ctx: Context): Int {
        val f = backupIndexFile(ctx)
        if (!f.exists()) return 0
        return try { JSONArray(f.readText()).length() } catch (_: Exception) { 0 }
    }

    fun activeBackupFiles(ctx: Context): List<String> {
        val f = backupIndexFile(ctx)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { arr.getJSONObject(it).optString("path").takeIf { p -> p.isNotBlank() } }
        } catch (_: Exception) { emptyList() }
    }

    internal fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var idx = 0
        while (true) {
            val found = haystack.indexOf(needle, idx)
            if (found < 0) break
            count++
            idx = found + needle.length
        }
        return count
    }

    fun applyWithBackup(ctx: Context, ops: List<FsWriteOp>): FsApplyResult = try {
        applyWithBackupInternal(ctx, ops)
    } catch (e: Throwable) {
        Log.e("DeepApk", "applyWithBackup crashed: " + e.javaClass.simpleName + ": " + e.message, e)
        FsApplyResult(false, "Ошибка применения: " + (e.message ?: e.javaClass.simpleName))
    }

    private fun applyWithBackupInternal(ctx: Context, ops: List<FsWriteOp>): FsApplyResult {
        val treeUri = getTreeUri(ctx) ?: return FsApplyResult(false, "Папка проекта не подключена")
        if (ops.isEmpty()) return FsApplyResult(false, "Нет операций для применения")

        val staged = mutableListOf<Triple<String, DocEntry?, ByteArray>>()
        val newContents = mutableMapOf<String, String>()

        // 1. Dry-run & validate all anchors before touching a single file
        for (op in ops) {
            val cleanPath = sanitizeRelPath(op.path)?.takeIf { it.isNotEmpty() }
                ?: return FsApplyResult(false, "Небезопасный путь: ${op.path}")
            when (op) {
                is FsWriteOp.Patch -> {
                    val doc = findDoc(ctx, treeUri, cleanPath)
                        ?: return FsApplyResult(false, "Файл не найден: $cleanPath")
                    if (doc.isDir) return FsApplyResult(false, "Это папка: $cleanPath")
                    val origBytes = readBytesDoc(ctx, treeUri, doc.docId)
                        ?: return FsApplyResult(false, "Не удалось прочитать: $cleanPath")
                    var curText = newContents[cleanPath] ?: String(origBytes, Charsets.UTF_8)
                    val norm = curText.replace("\r\n", "\n")
                    var working = norm
                    for ((idx, pair) in op.replacements.withIndex()) {
                        val oldNorm = pair.first.replace("\r\n", "\n")
                        val newNorm = pair.second.replace("\r\n", "\n")
                        val cnt = countOccurrences(working, oldNorm)
                        if (cnt != 1) {
                            return FsApplyResult(
                                false,
                                "Промах якоря #${idx + 1} в $cleanPath (совпадений: $cnt, нужно 1). Файлы НЕ тронуты."
                            )
                        }
                        working = working.replaceFirst(oldNorm, newNorm)
                    }
                    newContents[cleanPath] = working
                    if (staged.none { it.first == cleanPath }) staged.add(Triple(cleanPath, doc, origBytes))
                }
                is FsWriteOp.Write -> {
                    val doc = findDoc(ctx, treeUri, cleanPath)
                    val origBytes = if (doc != null && !doc.isDir) {
                        readBytesDoc(ctx, treeUri, doc.docId) ?: ByteArray(0)
                    } else ByteArray(0)
                    newContents[cleanPath] = op.content.replace("\r\n", "\n")
                    if (staged.none { it.first == cleanPath }) staged.add(Triple(cleanPath, doc, origBytes))
                }
            }
        }

        // 2. Save backups first (preserve earliest pre-session snapshot if active.json already exists)
        val bDir = backupDir(ctx)
        val bFilesDir = File(bDir, "files").apply { mkdirs() }
        val arr = try {
            val idxF = backupIndexFile(ctx)
            if (idxF.exists()) JSONArray(idxF.readText()) else JSONArray()
        } catch (_: Exception) { JSONArray() }
        val alreadyBacked = (0 until arr.length()).mapNotNull { arr.getJSONObject(it).optString("path") }.toMutableSet()

        staged.forEachIndexed { idx, (relPath, doc, origBytes) ->
            if (relPath !in alreadyBacked) {
                val bakFileName = "bak_${System.currentTimeMillis()}_$idx.bin"
                val existed = doc != null
                if (existed) File(bFilesDir, bakFileName).writeBytes(origBytes)
                arr.put(
                    JSONObject()
                        .put("path", relPath)
                        .put("existed", existed)
                        .put("bak", bakFileName)
                )
                alreadyBacked.add(relPath)
            }
        }
        backupIndexFile(ctx).writeText(arr.toString())

        // 3. Apply changes
        val applied = mutableListOf<String>()
        for ((relPath, content) in newContents) {
            val ensured = ensureFileDoc(ctx, treeUri, relPath)
                ?: return FsApplyResult(false, "Не удалось создать/открыть: $relPath")
            val ok = writeBytesDoc(ctx, treeUri, ensured.first.docId, content.toByteArray(Charsets.UTF_8))
            if (!ok) return FsApplyResult(false, "Ошибка записи в SAF: $relPath")
            applied.add(relPath)
        }
        return FsApplyResult(true, "Применено (${applied.size} файл.), бэкап сохранён", applied)
    }

    fun commitBackupOk(ctx: Context) {
        try { backupDir(ctx).deleteRecursively() } catch (e: Exception) {
            Log.w("DeepApk", "commitBackupOk delete failed: ${e.message}")
        }
    }

    fun rollbackBackup(ctx: Context): FsApplyResult {
        val treeUri = getTreeUri(ctx) ?: return FsApplyResult(false, "Папка проекта не подключена")
        val f = backupIndexFile(ctx)
        if (!f.exists()) return FsApplyResult(false, "Активный бэкап не найден")
        return try {
            val arr = JSONArray(f.readText())
            val bFilesDir = File(backupDir(ctx), "files")
            val restored = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val path = o.optString("path")
                val existed = o.optBoolean("existed", true)
                val bak = o.optString("bak")
                val doc = findDoc(ctx, treeUri, path)
                if (existed) {
                    val bytes = File(bFilesDir, bak).takeIf { it.exists() }?.readBytes() ?: continue
                    val target = doc ?: ensureFileDoc(ctx, treeUri, path)?.first ?: continue
                    writeBytesDoc(ctx, treeUri, target.docId, bytes)
                    restored.add(path)
                } else if (doc != null) {
                    val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, doc.docId)
                    try { DocumentsContract.deleteDocument(ctx.contentResolver, uri) } catch (e: Exception) {
                        Log.w("DeepApk", "deleteDocument($path) failed: ${e.message}")
                    }
                    restored.add("$path (удалён)")
                }
            }
            backupDir(ctx).deleteRecursively()
            FsApplyResult(true, "Откат выполнен (${restored.size} файл.)", restored)
        } catch (ce: CancellationException) {
            throw ce
        } catch (se: SecurityException) {
            Log.e("DeepApk", "rollbackBackup SecurityException", se)
            FsApplyResult(false, "Ошибка отката (SecurityException): ${se.message}")
        } catch (te: Throwable) {
            Log.e("DeepApk", "rollbackBackup failed: ${te.javaClass.simpleName}: ${te.message}", te)
            FsApplyResult(false, "Ошибка отката: ${te.message}")
        }
    }
}

// ===== DSML tolerance (DeepSeek tool-call tokens) =====
internal fun normalizeDsml(input: String): String {
    if (!input.contains("DSML")) return input
    val bar = "[\uFF5C|]{2,3}"
    val invokeRe = Regex(
        "<\\s*" + bar + "\\s*DSML\\s*" + bar + "\\s*invoke\\s+name=\"(\\w+)\">([\\s\\S]*?)</\\s*" + bar + "\\s*DSML\\s*" + bar + "\\s*invoke>",
        RegexOption.IGNORE_CASE
    )
    val paramRe = Regex(
        "<\\s*" + bar + "\\s*DSML\\s*" + bar + "\\s*parameter\\s+name=\"(\\w+)\"[^>]*>([\\s\\S]*?)</\\s*" + bar + "\\s*DSML\\s*" + bar + "\\s*parameter>",
        RegexOption.IGNORE_CASE
    )
    return invokeRe.replace(input) { m ->
        val name = m.groupValues[1].lowercase()
        val ps = paramRe.findAll(m.groupValues[2]).associate { it.groupValues[1] to it.groupValues[2].trim() }
        when (name) {
            "fs_read" -> {
                val pa = ps["path"] ?: return@replace m.value
                val st = ps["start"] ?: "1"
                val en = ps["end"] ?: "150"
                "<fs_read path=\"$pa\" start=\"$st\" end=\"$en\"/>"
            }
            "fs_tree" -> {
                val pa = ps["path"] ?: ""
                if (pa.isBlank()) "<fs_tree/>" else "<fs_tree path=\"$pa\"/>"
            }
            "fs_grep" -> {
                val pat = ps["pattern"] ?: return@replace m.value
                val pa = ps["path"] ?: ""
                if (pa.isBlank()) "<fs_grep pattern=\"$pat\"/>" else "<fs_grep pattern=\"$pat\" path=\"$pa\"/>"
            }
            "fs_patch", "fs_write" -> {
                val pa = ps["path"] ?: return@replace m.value
                val body = ps["content"] ?: ps["text"] ?: ps["body"] ?: ""
                "<" + name + " path=\"$pa\">" + body + "</" + name + ">"
            }
            else -> m.value
        }
    }
}

// Blank = валидная (root для tree, весь проект для grep).
// true — только очевидный мусор: "X", "...", ".", "??".
internal fun isPlaceholderPath(input: String): Boolean {
    val s = input.trim()
    if (s.isEmpty()) return false
    // Заглушка = ТОЛЬКО из точек/звёздочек/знаков вопроса ('..', '...', '???')
    // Короткие имена ('a', 'b', 'X') — валидные имена файлов без расширения.
    if (s.all { it == '.' || it == '\u2026' || it == '*' || it == '?' || it == '_' }) return true
    return false
}


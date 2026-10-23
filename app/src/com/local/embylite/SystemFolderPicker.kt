package com.local.embylite

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract

/**
 * 系统「选择文件夹」入口。
 *
 * 调用顺序（都是系统 UI，不再自绘）：
 *   1. 小米文件管理（com.android.fileexplorer / com.mi.android.globalFileexplorer）
 *      —— 只有当它确实注册了 OPEN_DOCUMENT_TREE 时才会用它，否则跳过；
 *   2. 系统默认目录选择器（DocumentsUI / HyperOS 换皮版）。
 *
 * 说明：Android 11+ 的 SAF 不允许选「存储根目录 / Download / Android/data」，
 * 这类目录仍需走「所有文件访问权限」+ 内置浏览（见 FolderPicker.kt），
 * 本类只负责系统选择器这条路。
 */
internal object SystemFolderPicker {

    /** 小米系文件管理包名：国行 / 国际版。 */
    private val XIAOMI_PACKAGES = listOf(
        "com.android.fileexplorer",
        "com.mi.android.globalFileexplorer",
    )

    /** HyperOS 会把 ACTION_OPEN_DOCUMENT 改写到这个私有 action（仅用于日志诊断）。 */
    private const val ACTION_HYPER_OPEN_DOCUMENT = "hyper.intent.action.OPEN_DOCUMENT"

    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    /**
     * 构造目录选择 Intent：优先指定小米文件管理，定位到当前目录。
     * @param initialPath 当前已选目录（绝对路径），用于让选择器直接落在该目录；可为 null。
     */
    fun buildIntent(ctx: Context, initialPath: String?): Intent {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            initialPath?.let { p ->
                initialDocumentUri(p)?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
            }
        }

        val xiaomi = xiaomiHandler(ctx)
        if (xiaomi != null) {
            intent.setPackage(xiaomi)
            EmbyLog.info("目录选择：使用小米文件管理 $xiaomi")
        } else {
            EmbyLog.info("目录选择：使用系统选择器（未发现可处理选目录的小米文件管理）")
            logHyperHandler(ctx)
        }
        return intent
    }

    /** 当前会由哪个 App 承接「选文件夹」，用于设置页展示。 */
    fun targetLabel(ctx: Context): String =
        if (xiaomiHandler(ctx) != null) "小米文件管理" else "系统文件选择器"

    /** 小米文件管理若注册了 OPEN_DOCUMENT_TREE，返回其包名。 */
    private fun xiaomiHandler(ctx: Context): String? {
        val handlers = runCatching {
            // 注意：这里不能用 MATCH_DEFAULT_ONLY —— DocumentsUI 的 OPEN_DOCUMENT_TREE
            // 过滤器没有 CATEGORY_DEFAULT，带这个 flag 会查不到任何处理者。
            ctx.packageManager.queryIntentActivities(
                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),
                0
            ).map { it.activityInfo.packageName }
        }.getOrDefault(emptyList())
        EmbyLog.info("OPEN_DOCUMENT_TREE 可用处理者: $handlers")
        return XIAOMI_PACKAGES.firstOrNull { pkg -> handlers.any { it == pkg } }
    }

    /** HyperOS 的私有 picker 是否存在，只写日志，便于真机排查。 */
    private fun logHyperHandler(ctx: Context) {
        val hit = runCatching {
            ctx.packageManager.queryIntentActivities(
                Intent(ACTION_HYPER_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                },
                PackageManager.MATCH_DEFAULT_ONLY
            ).map { it.activityInfo.packageName }
        }.getOrDefault(emptyList())
        if (hit.isNotEmpty()) EmbyLog.info("HyperOS 私有 picker: $hit")
    }

    /** 把绝对路径转成 externalstorage 的 document URI，供 EXTRA_INITIAL_URI 定位。 */
    private fun initialDocumentUri(path: String): Uri? = runCatching {
        val primaryRoot = Environment.getExternalStorageDirectory()?.absolutePath
            ?: "/storage/emulated/0"
        val clean = path.trimEnd('/')
        val docId = when {
            clean.isEmpty() || clean == primaryRoot -> "primary:"
            clean.startsWith("$primaryRoot/") -> "primary:" + clean.removePrefix("$primaryRoot/")
            clean.startsWith("/storage/") -> {
                // 形如 /storage/1A2B-3C4D/Movies → 卷 ID 是路径第一段
                val rest = clean.removePrefix("/storage/")
                val vol = rest.substringBefore('/')
                val sub = rest.substringAfter('/', "")
                "$vol:" + sub
            }
            else -> return null
        }
        DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, docId)
    }.getOrNull()

    /** tree URI → 绝对路径（primary:Movies/x → /storage/emulated/0/Movies/x）。 */
    fun treeUriToPath(uri: Uri): String? = runCatching {
        val docId = java.net.URLDecoder.decode(
            DocumentsContract.getTreeDocumentId(uri), "UTF-8"
        )
        val vol = docId.substringBefore(':', "primary")
        val sub = docId.substringAfter(':', "").trim('/')
        val base = if (vol.equals("primary", true)) {
            Environment.getExternalStorageDirectory()?.absolutePath ?: "/storage/emulated/0"
        } else {
            "/storage/$vol"
        }
        if (sub.isEmpty()) base else "$base/$sub"
    }.getOrNull()
}

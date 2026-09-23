package com.example.analysis

/**
 * One project file handed to the analyzer. [path] is relative to the project root and always uses '/'.
 * The analyzer never touches IDE APIs, so it can be unit tested with plain strings.
 */
data class SourceFile(val path: String, val text: String) {
    val name: String get() = path.substringAfterLast('/')
    val dir: String get() = path.substringBeforeLast('/', "")
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
}

/** Everything the web view renders. Serialized to JSON with Gson, so keep it to plain data. */
data class ProjectGraph(
    val projectName: String,
    val projectPath: String,
    val status: String,
    val message: String,
    val modules: List<ModuleInfo>,
    val endpoints: List<EndpointInfo>,
    val calls: List<CallInfo>,
    val externals: List<ExternalService>,
    val datastores: List<Datastore>,
    val links: List<ModuleLink>,
    val warnings: List<String>,
    val stats: GraphStats,
    val generatedAt: Long = System.currentTimeMillis()
)

data class ModuleInfo(
    val id: String,
    val name: String,
    /** Directory relative to the project root; "" for the root. */
    val path: String,
    /** frontend | backend | fullstack | library */
    val kind: String,
    val frameworks: List<String>,
    val languages: List<String>,
    val port: Int?,
    val basePath: String?,
    val fileCount: Int,
    val callCount: Int,
    val endpointCount: Int,
    val datastoreIds: List<String>
)

data class EndpointInfo(
    val id: String,
    val moduleId: String,
    val framework: String,
    val method: String,
    val path: String,
    val handler: String,
    val controller: String?,
    val filePath: String,
    val line: Int,
    val callerIds: List<String>
)

data class CallInfo(
    val id: String,
    val moduleId: String,
    /** fetch, axios, HttpClient, wrapper name ... */
    val client: String,
    /** null when the method could not be determined statically. */
    val method: String?,
    /** Source expression of the URL argument, as written. */
    val rawUrl: String,
    /** Normalized path, placeholders written as {name}. */
    val path: String,
    val host: String?,
    val filePath: String,
    val line: Int,
    val function: String?,
    /** matched | external | unresolved | dynamic */
    val status: String,
    val endpointId: String?,
    val externalId: String?,
    /** exact | likely | inferred, only for matched calls */
    val confidence: String?,
    val note: String?
)

data class ExternalService(val id: String, val host: String, val callIds: List<String>)

data class Datastore(
    val id: String,
    val name: String,
    /** database | cache | queue | search | platform */
    val kind: String,
    val moduleIds: List<String>,
    val evidence: List<String>
)

data class ModuleLink(
    val id: String,
    val source: String,
    val target: String,
    /** http | external | datastore | unresolved */
    val kind: String,
    val count: Int
)

data class GraphStats(
    val scannedFiles: Int,
    val modules: Int,
    val calls: Int,
    val endpoints: Int,
    val matchedCalls: Int,
    val externalCalls: Int,
    val unresolvedCalls: Int,
    val dynamicCalls: Int,
    val truncated: Boolean
)

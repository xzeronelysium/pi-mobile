package dev.pi.mobile.backend

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * The pure half of the in-process capability probe: it turns what the seam-driven runner observed
 * into the report the agent reads at `<workspace>/.pi-capabilities.json` (plus a short human-readable
 * `.md`).
 *
 * Nothing here touches Android, the filesystem or a process. The runner supplies every fact, so the
 * distinctions that matter — a denied direct exec carrying its real reason, the interpreter route
 * judged on its own, a simply-absent binary not being a failure, and an unwritable report being
 * surfaced instead of vanishing — are all decided, and asserted, off-device.
 */
object CapabilityReport {
    /** Bumped only if the report's shape changes incompatibly. */
    const val SCHEMA_VERSION = 1

    const val JSON_FILE = ".pi-capabilities.json"
    const val MARKDOWN_FILE = ".pi-capabilities.md"

    /** The two locations the runner stages an exec case in; also the keys the reason codes speak in. */
    const val LOCATION_APP_FILES = "app_files"
    const val LOCATION_WORKSPACE = "workspace"

    /**
     * Why a direct exec of an app-written file was refused. Resolved per location, not from the OS
     * error text: both locations fail with the same `error=13, Permission denied` while the causes —
     * SELinux W^X in app storage, a `noexec` mount in shared storage — are different.
     */
    const val REASON_SELINUX_WX = "selinux_wx_app_storage"
    const val REASON_NOEXEC = "noexec_shared_storage"
    const val REASON_DIRECT_EXEC_BLOCKED = "direct_exec_blocked"

    /** Facts about the process the probe ran in. Every one is observed, never inferred. */
    data class Environment(
        val androidRelease: String,
        val sdkInt: Int,
        val primaryAbi: String?,
        val filesDir: String,
        val cacheDir: String,
        val workspaceRoot: String?,
        val nativeLibraryDir: String?,
        val allFilesAccess: Boolean,
        val internetGranted: Boolean,
        val versionName: String?,
        val versionCode: Long,
        val generatedAt: String,
    )

    /**
     * One way of running a script the app wrote.
     *
     * A route that was never tried is [attempted]=false — neither an ok nor a failure, and with no
     * invented [detail]. A route that was tried and did not run is [attempted]=true, [ok]=false with
     * [detail] holding the exact reason, because "permission denied by SELinux/exec policy",
     * "noexec mount" and "not found" are the whole point of the probe and must stay distinguishable.
     */
    data class Route(val attempted: Boolean, val ok: Boolean, val detail: String?) {
        companion object {
            val skipped = Route(attempted = false, ok = false, detail = null)
            fun ok(detail: String? = null) = Route(attempted = true, ok = true, detail = detail)
            fun failed(detail: String) = Route(attempted = true, ok = false, detail = detail)
        }
    }

    /** The two routes for one location. [interpreter] is judged on its own, never inferred from [direct]. */
    data class ExecCase(val location: String, val direct: Route, val interpreter: Route)

    /**
     * One curated external command. [present] is what `command -v` found on the app's PATH;
     * [executable] is whether the app's uid could actually start it. A binary that is simply not
     * there is absent — not a failure — so [failure] stays null while [present] is false.
     */
    data class Command(
        val name: String,
        val present: Boolean,
        val executable: Boolean,
        val version: String? = null,
        val failure: String? = null,
    )

    /** A binary that exists on PATH but an app must not rely on, with the one-line reason why. */
    data class ShellOnly(val name: String, val reason: String)

    /**
     * The reach the app itself grants its own file tools: the roots a file path may live in, plus
     * the roots a shell's *starting* cwd may live in, read from the live configuration on every run.
     *
     * This is the half of "what can I do here" that a permission cannot answer. All-files access can be
     * granted while the app's own router still refuses every path outside [shellRoots]/[fileRoots], so
     * the refusal an agent meets is this app's confinement and not an Android denial. An unread router
     * is [observed]=false — the roots are unknown, not empty.
     *
     * [shellRoots] are cwd roots, not a jail. The shell runs as the app's own UID; [shellConfined] is
     * therefore false today and exists so the report can say so explicitly instead of implying the
     * shell is limited to these paths.
     */
    data class Reach(
        val observed: Boolean,
        val shellRoots: List<String>,
        val fileRoots: List<String>,
        val defaultRoot: String?,
        val fallbackRoot: String?,
        val tempRoot: String?,
        val source: String?,
        val failure: String?,
        val shellConfined: Boolean = false,
    ) {
        companion object {
            val unobserved = Reach(false, emptyList(), emptyList(), null, null, null, null, null)
            fun blocked(failure: String) = Reach(false, emptyList(), emptyList(), null, null, null, null, failure)
        }
    }

    /** A permission the app holds but its own configuration never lets the agent use. */
    data class UnusedGrant(val capability: String, val detail: String)

    /** Whether the report itself landed, and where — the workspace can be missing or unwritable. */
    data class WriteOutcome(
        val ok: Boolean,
        val jsonPath: String?,
        val markdownPath: String?,
        val markdownWritten: Boolean,
        val failure: String?,
    )

    data class Report(
        val environment: Environment,
        val exec: List<ExecCase>,
        val commands: List<Command>,
        val shellOnly: List<ShellOnly>,
        val write: WriteOutcome,
        /** The app-configured reach, read per run. Additive with a default, so existing readers hold. */
        val reach: Reach = Reach.unobserved,
    )

    /**
     * One rule the platform imposes on what an agent running in this app can do.
     *
     * Guidance, not observation: it holds on every device the app supports, so it is emitted even
     * when a route could not be attempted — the point is that the agent reads the *rule*, not a row
     * it has to interpret. The observed half of the same question lives in [ExecEffective].
     */
    data class Guidance(val code: String, val text: String)

    /** The observed half of the exec answer; the *rule* is [GUIDANCE]. See [execEffective]. */
    data class ExecEffective(
        /** False when no route was tried at all: the two verdicts below are then unknown, not failures. */
        val attempted: Boolean,
        val scriptsViaInterpreter: Boolean,
        val directExec: Boolean,
        val reasonCodes: List<String>,
    )

    /**
     * The effective rule for running a file this app wrote — stated so an agent does not have to
     * infer it from a `direct=FAILED` row (which it reasonably reads as "I cannot run scripts").
     *
     * Every clause here was observed on a real device in the app's own process, and each is a
     * platform fact rather than a permission the app could request.
     */
    val GUIDANCE: List<Guidance> = listOf(
        Guidance(
            "exec_via_interpreter",
            "A script this app writes CAN be run — but only through the interpreter: `/system/bin/sh <path>`. " +
                "That works in BOTH the app files dir and the workspace, so staging a script and running it " +
                "through sh is a supported strategy here.",
        ),
        Guidance(
            "direct_exec_blocked",
            "Running an app-written file directly (the file as argv[0], or ProcessBuilder on its path) is " +
                "blocked by the platform in both locations, and NO permission, manifest entry or app-side flag " +
                "changes that: app storage is SELinux W^X (an app process may not execute app_data_file) and " +
                "shared storage is mounted noexec.",
        ),
        Guidance(
            "binaries_ship_in_apk",
            "So a BINARY this app writes can never be executed. To run native code, ship it inside the APK " +
                "instead: `lib*.so` in the jniLibs / native library dir is the only location this process is " +
                "allowed to execute from.",
        ),
        Guidance(
            "never_lower_target_sdk",
            "Never lower targetSdk to try to change any of this: it would evade a security boundary rather than " +
                "lift one, and it cannot lift the noexec mount either.",
        ),
    )

    /**
     * The effective exec answer, derived only from what the routes observed: whether a script actually
     * ran through the interpreter, whether a direct exec actually worked, and why it was refused.
     * [ExecEffective.attempted] keeps "nothing was tried" from reading as "nothing works".
     */
    fun execEffective(exec: List<ExecCase>): ExecEffective = ExecEffective(
        attempted = exec.any { it.direct.attempted || it.interpreter.attempted },
        scriptsViaInterpreter = exec.any { it.interpreter.attempted && it.interpreter.ok },
        directExec = exec.any { it.direct.attempted && it.direct.ok },
        reasonCodes = exec.mapNotNull(::directReasonCode).distinct(),
    )

    /**
     * Why this location refused a direct exec. The OS error text is the same for both locations
     * (`error=13, Permission denied`), so the location decides which platform reason it is; explicit
     * `noexec` text wins because it names the reason outright.
     */
    private fun directReasonCode(case: ExecCase): String? = when {
        !case.direct.attempted || case.direct.ok -> null
        NOEXEC.containsMatchIn(case.direct.detail.orEmpty()) -> REASON_NOEXEC
        case.location == LOCATION_APP_FILES -> REASON_SELINUX_WX
        case.location == LOCATION_WORKSPACE -> REASON_NOEXEC
        else -> REASON_DIRECT_EXEC_BLOCKED
    }

    private val NOEXEC = Regex("(?i)noexec")

    /**
     * Permissions the app holds but never uses, because its own router is narrower than the permission
     * would allow. All-files access is the case that misleads: the report says `granted`, yet every root
     * the app allows is app-private, so the grant buys the agent nothing outside them.
     */
    fun unusedGrants(reach: Reach, environment: Environment): List<UnusedGrant> {
        if (!reach.observed || !environment.allFilesAccess) return emptyList()
        val roots = (reach.shellRoots + reach.fileRoots).distinct()
        if (roots.isEmpty() || roots.any { !isAppPrivate(it, environment) }) return emptyList()
        return listOf(
            UnusedGrant(
                "all_files_access",
                "granted but unused: every root this app allows (" + roots.joinToString(", ") +
                    ") is app-private, so the router refuses /sdcard and everything else the grant covers",
            ),
        )
    }

    /**
     * What the agent can read and write, in one plain sentence — and whose refusal it meets.
     *
     * A path outside the roots comes back as `permission_denied` from the app's own router, not from
     * Android, which is the distinction an agent gets wrong when it sees a granted permission next to a
     * refusal. An unobserved reach says unknown, never "no roots".
     */
    fun reachLine(reach: Reach, environment: Environment): String {
        if (!reach.observed) {
            return "Reach was not observed (${reach.failure ?: "the router could not be read"}): treat the " +
                "allowed roots as unknown, not as none. The shell is app-privileged either way; these " +
                "roots describe its starting cwd and the file tools' paths, not a sandbox."
        }
        val roots = (reach.shellRoots + reach.fileRoots).distinct()
        val sharedReachable = roots.any { !isAppPrivate(it, environment) }
        return buildString {
            append("The file tools can read and write under ")
            append(if (roots.isEmpty()) "nothing" else roots.joinToString(", "))
            append(" and nowhere else: every other path — ")
            append(
                if (sharedReachable) "app-private paths outside those roots, and the rest of the device"
                else "/sdcard, /storage/emulated/0/…, and everything else outside those roots",
            )
            append(" — comes back as permission_denied from this app's own NativeIoRouter confinement, ")
            append("not from Android. ")
            if (reach.shellConfined) {
                append("The shell is confined to those roots as well.")
            } else {
                append("The shell is different: shell.exec starts a command in one of those roots, but the ")
                append("command runs with this app's own UID and is NOT confined to them. It can reach the ")
                append("app's own data (for example its databases and shared_prefs) and any file the app ")
                append("can read, and it can write wherever the app can write. Treat shell output as ")
                append("app-privileged, not as file-tool-confined.")
            }
            val unused = unusedGrants(reach, environment)
            when {
                unused.isNotEmpty() -> append(" All-files access is granted but unused, so the permission buys the agent nothing today.")
                environment.allFilesAccess && sharedReachable ->
                    append(" All-files access is granted, and it is what makes the shared-storage roots above reachable.")
            }
        }
    }

    /** A root the app owns itself: only its own storage is reachable without a grant of some kind. */
    private fun isAppPrivate(root: String, environment: Environment): Boolean =
        root == environment.filesDir || root.startsWith(environment.filesDir + "/") ||
            root == environment.cacheDir || root.startsWith(environment.cacheDir + "/")

    // ------------------------------------------------------------------ machine-readable

    /** The contract the agent reads. Stable keys, stable nulls; never a bare boolean where a reason belongs. */
    fun json(report: Report): String = JSONObject()
        .put("schema", SCHEMA_VERSION)
        .put("environment", environmentJson(report.environment))
        .put("exec", JSONArray().apply { report.exec.forEach { put(execJson(it)) } })
        .put("exec_effective", execEffectiveJson(execEffective(report.exec)))
        .put("guidance", JSONArray().apply {
            GUIDANCE.forEach { put(JSONObject().put("code", it.code).put("text", it.text)) }
        })
        .put("reach", reachJson(report.reach, report.environment))
        .put("unused_grants", unusedGrantsJson(report.reach, report.environment))
        .put("commands", JSONArray().apply { report.commands.forEach { put(commandJson(it)) } })
        .put("shell_only", JSONArray().apply {
            report.shellOnly.forEach { put(JSONObject().put("name", it.name).put("reason", it.reason)) }
        })
        .put("write", writeJson(report.write))
        .toString(2)

    private fun environmentJson(environment: Environment): JSONObject = JSONObject()
        .put("android_release", environment.androidRelease)
        .put("sdk_int", environment.sdkInt)
        .put("primary_abi", environment.primaryAbi ?: JSONObject.NULL)
        .put("files_dir", environment.filesDir)
        .put("cache_dir", environment.cacheDir)
        .put("workspace_root", environment.workspaceRoot ?: JSONObject.NULL)
        .put("native_library_dir", environment.nativeLibraryDir ?: JSONObject.NULL)
        .put("all_files_access", environment.allFilesAccess)
        .put("internet", environment.internetGranted)
        .put("version_name", environment.versionName ?: JSONObject.NULL)
        .put("version_code", environment.versionCode)
        .put("generated_at", environment.generatedAt)

    private fun execJson(case: ExecCase): JSONObject = JSONObject()
        .put("location", case.location)
        .put("direct", routeJson(case.direct))
        .put("interpreter", routeJson(case.interpreter))

    private fun routeJson(route: Route): JSONObject = JSONObject()
        .put("attempted", route.attempted)
        .put("ok", route.ok)
        .put("detail", route.detail ?: JSONObject.NULL)

    private fun execEffectiveJson(effective: ExecEffective): JSONObject = JSONObject()
        .put("attempted", effective.attempted)
        .put("scripts_via_interpreter", effective.scriptsViaInterpreter)
        .put("direct_exec", effective.directExec)
        .put("reason_codes", JSONArray(effective.reasonCodes))

    private fun reachJson(reach: Reach, environment: Environment): JSONObject = JSONObject()
        .put("observed", reach.observed)
        .put("shell_roots", JSONArray(reach.shellRoots))
        .put("shell_confined", reach.shellConfined)
        .put("file_roots", JSONArray(reach.fileRoots))
        .put("default_root", reach.defaultRoot ?: JSONObject.NULL)
        .put("fallback_root", reach.fallbackRoot ?: JSONObject.NULL)
        .put("temp_root", reach.tempRoot ?: JSONObject.NULL)
        .put("source", reach.source ?: JSONObject.NULL)
        .put("failure", reach.failure ?: JSONObject.NULL)
        .put("line", reachLine(reach, environment))

    private fun unusedGrantsJson(reach: Reach, environment: Environment): JSONArray =
        JSONArray().apply {
            unusedGrants(reach, environment).forEach {
                put(JSONObject().put("capability", it.capability).put("detail", it.detail))
            }
        }

    private fun commandJson(command: Command): JSONObject = JSONObject()
        .put("name", command.name)
        .put("present", command.present)
        .put("executable", command.executable)
        .put("version", command.version ?: JSONObject.NULL)
        .put("failure", command.failure ?: JSONObject.NULL)

    private fun writeJson(write: WriteOutcome): JSONObject = JSONObject()
        .put("ok", write.ok)
        .put("json_path", write.jsonPath ?: JSONObject.NULL)
        .put("markdown_path", write.markdownPath ?: JSONObject.NULL)
        .put("markdown_written", write.markdownWritten)
        .put("failure", write.failure ?: JSONObject.NULL)

    // ------------------------------------------------------------------ human-readable

    fun markdown(report: Report): String {
        val e = report.environment
        val effective = execEffective(report.exec)
        return buildString {
            appendLine("# Pi mobile — in-app capability probe")
            appendLine()
            appendLine("Written inside the app's own process (SELinux domain `untrusted_app`), so these are the")
            appendLine("answers that matter for what an agent running here can actually do — not what a host shell")
            appendLine("or an `adb run-as` session sees.")
            appendLine()
            appendLine("- generated: ${e.generatedAt}")
            appendLine("- app: ${e.versionName ?: "?"} (versionCode ${e.versionCode})")
            appendLine("- Android: ${e.androidRelease} (SDK ${e.sdkInt}), primary ABI ${e.primaryAbi ?: "?"}")
            appendLine("- filesDir: ${e.filesDir}")
            appendLine("- cacheDir: ${e.cacheDir}")
            appendLine("- workspace root: ${e.workspaceRoot ?: "(none bound)"}")
            appendLine("- nativeLibraryDir: ${e.nativeLibraryDir ?: "(none)"}")
            appendLine("- All-files access: ${if (e.allFilesAccess) "granted" else "NOT granted"}")
            appendLine("- INTERNET: ${if (e.internetGranted) "granted" else "NOT granted"}")
            appendLine()
            appendLine("## Executing a file this app wrote")
            appendLine()
            appendLine("| location | direct exec | via /system/bin/sh |")
            appendLine("| --- | --- | --- |")
            report.exec.forEach { appendLine("| ${it.location} | ${describeRoute(it.direct)} | ${describeRoute(it.interpreter)} |") }
            appendLine()
            appendLine("## The effective rule for executing a file this app wrote")
            appendLine()
            appendLine("The rows above say *how* each route behaved. This is what they mean, so a `direct` failure is not")
            appendLine("read as \"I cannot run scripts\":")
            appendLine()
            GUIDANCE.forEach { appendLine("- `${it.code}` — ${it.text}") }
            appendLine()
            appendLine("observed here: attempted=${effective.attempted} " +
                "scripts_via_interpreter=${effective.scriptsViaInterpreter} " +
                "direct_exec=${effective.directExec} reasons=${orNone(effective.reasonCodes)}")
            appendLine()
            appendLine("## What the file tools can reach, and where the shell starts")
            appendLine()
            appendLine(reachLine(report.reach, report.environment))
            appendLine()
            if (report.reach.observed) {
                appendLine("- shell cwd roots (starting directory only; not a sandbox): ${orNone(report.reach.shellRoots)}")
                appendLine("- shell confined to those roots: ${report.reach.shellConfined}")
                appendLine("- file path roots: ${orNone(report.reach.fileRoots)}")
                appendLine("- default cwd: ${report.reach.defaultRoot ?: "-"}")
                appendLine("- read from: ${report.reach.source ?: "-"}")
                unusedGrants(report.reach, report.environment).forEach {
                    appendLine("- granted but unused: `${it.capability}` — ${it.detail}")
                }
            }
            appendLine()
            appendLine("## External commands reachable from the app's PATH (/system/bin)")
            appendLine()
            appendLine("| command | present | executable by this app | note |")
            appendLine("| --- | --- | --- | --- |")
            report.commands.forEach { appendLine("| ${it.name} | ${it.present} | ${it.executable} | ${oneLine(commandNote(it))} |") }
            appendLine()
            appendLine("## Shell-only binaries — do NOT use from the app")
            appendLine()
            report.shellOnly.forEach { appendLine("- `${it.name}` — ${it.reason}") }
            appendLine()
            appendLine("## Report files")
            appendLine()
            if (report.write.ok) {
                appendLine("- json: ${report.write.jsonPath ?: "-"}")
                appendLine("- markdown: ${report.write.markdownPath ?: "-"}${if (report.write.markdownWritten) "" else " (NOT written)"}")
            } else {
                appendLine("- NOT WRITTEN: ${report.write.failure ?: "unknown failure"}")
            }
        }
    }

    /** One line, greppable, no per-route prose — what `adb logcat -s PiRuntimeConsole` shows. */
    fun summary(report: Report): String {
        val routes = report.exec.joinToString(" ") { "${it.location}=${routeMark(it.direct)}/${routeMark(it.interpreter)}" }
        val effective = execEffective(report.exec)
        val unused = unusedGrants(report.reach, report.environment)
        val present = report.commands.count { it.present }
        val executable = report.commands.count { it.executable }
        val reason = report.write.failure?.let { " reason=${oneLine(it)}" } ?: ""
        val reachFailure = report.reach.failure?.let { " reach_failure=${oneLine(it)}" } ?: ""
        return "capability.probe sdk=${report.environment.sdkInt} exec[$routes] " +
            "exec_effective exec_attempted=${effective.attempted} " +
            "scripts_via_interpreter=${effective.scriptsViaInterpreter} " +
            "direct_exec=${effective.directExec} exec_reasons=${orNone(effective.reasonCodes)} " +
            "reach observed=${report.reach.observed} shell_roots=${report.reach.shellRoots.size} " +
            "shell_confined=${report.reach.shellConfined} " +
            "file_roots=${report.reach.fileRoots.size} default_root=${oneLine(report.reach.defaultRoot ?: "-")} " +
            "all_files_unused=${unused.isNotEmpty()}$reachFailure commands " +
            "present=$present executable=$executable total=${report.commands.size} " +
            "shell_only=${report.shellOnly.size} wrote=${report.write.ok} json=${oneLine(report.write.jsonPath ?: "-")}$reason"
    }

    /** A list rendered for a one-line summary or a table cell: never an empty string that reads as none. */
    private fun orNone(names: List<String>): String = names.joinToString(",").ifEmpty { "-" }

    private fun commandNote(command: Command): String = when {
        command.failure != null -> command.failure
        !command.present -> "absent — do not rely on it"
        command.version != null -> command.version
        else -> ""
    }

    private fun describeRoute(route: Route): String = when {
        !route.attempted -> "not attempted"
        route.ok -> "ok"
        else -> "FAILED — ${oneLine(route.detail ?: "no reason recorded")}"
    }

    private fun routeMark(route: Route): String = when {
        !route.attempted -> "skipped"
        route.ok -> "ok"
        else -> "failed"
    }

    /** Anything that will be embedded in a one-line or table cell must be flattened first. */
    private fun oneLine(text: String): String = text.replace(Regex("\\s+"), " ").trim()
}

/**
 * Log channel for the probe's single summary line, mirroring [NativeShellTrace]: emission is a no-op
 * off the device, so unit tests neither log nor touch Android's `Log`.
 */
object CapabilityLog {
    /** The app's existing single console tag, so discovery is still one `logcat -s`. */
    const val TAG = ConsoleRedaction.TAG

    @Volatile var sink: ((String) -> Unit)? = null

    fun emit(line: String) { sink?.invoke(line) }
}

/**
 * The reach the capability report states: [source] when one is supplied, else [mirror] — the
 * precedence in one place, so "the router the app really built wins over a rebuilt mirror" is a
 * property a test can pin rather than a detail buried in the device half.
 *
 * It lives outside [AndroidProbe] because that half only runs on a device while the rule itself is
 * pure: a live source must win outright, and a mirror is only ever the answer for a caller that has
 * no router to hand in. Inverting the two would silently report a root set the app is not using —
 * exactly the drift this file exists to prevent. A mirror that cannot be read is an unobserved
 * reach, never an empty one that reads as "the agent can reach nothing".
 */
internal fun resolveReach(
    source: (() -> CapabilityReport.Reach)?,
    mirror: () -> CapabilityReport.Reach,
): CapabilityReport.Reach {
    if (source != null) return source()
    return try {
        mirror()
    } catch (error: Exception) {
        CapabilityReport.Reach.blocked(describeFailure(error))
    }
}

/** `Class: message`, or the class name alone when the message is blank. */
internal fun describeFailure(error: Throwable): String {
    val message = error.message?.takeIf { it.isNotBlank() }
    return if (message != null) "${error.javaClass.simpleName}: $message" else error.javaClass.simpleName
}

/**
 * The in-process capability probe.
 *
 * An agent that runs shell commands here has twice drawn wrong conclusions about its own
 * environment — reporting `curl not found` when `/system/bin/curl` exists and fetches HTTPS, and
 * probing shell-only binaries like `pm`/`am`/`cmd` that an app can never use. Ground truth gathered
 * outside the app is not authoritative either: `adb run-as` runs in the SELinux domain `runas_app`,
 * while the app's own code runs in `untrusted_app`, and the two differ on exactly the questions that
 * matter — executing a file the app wrote, and which system binaries are reachable.
 *
 * So this probe runs in the app's own process and writes its findings where the agent can read them.
 * [Probe] is the seam over every OS/exec call; the runner only decides how the observations become a
 * [CapabilityReport.Report].
 */
class CapabilityProbe(private val probe: Probe) {

    /** Everything the probe needs from the device. A device run is the only remaining unknown. */
    interface Probe {
        fun environment(): CapabilityReport.Environment

        /** Run both exec routes for a script staged in [directory], then delete it. */
        fun execCase(location: String, directory: File): CapabilityReport.ExecCase

        /** Every curated command, located and started through the app's own shell. */
        fun commands(): List<CapabilityReport.Command>

        /**
         * The reach the app configures for agent calls — the roots a shell cwd and a file path may use.
         * Read from the live configuration on every run, never restated as a constant, because the roots
         * follow the bound workspace and the grants. Defaulted so a probe that has no router to read
         * reports an unobserved reach instead of an invented empty one.
         */
        fun reach(): CapabilityReport.Reach = CapabilityReport.Reach.unobserved

        /** Write [content] to [target]; null on success, else the exact failure reason. */
        fun write(target: File, content: String): String?
    }

    /**
     * Produce the report and write both files into the bound workspace.
     *
     * The human-readable markdown is written first, so the JSON — the machine contract — can state
     * truthfully whether both landed, with no second pass. A write failure is recorded in the
     * returned report (and its summary line) rather than thrown, because "the workspace is
     * unwritable" is itself one of the answers the agent needs.
     */
    fun run(): CapabilityReport.Report {
        val environment = probe.environment()
        val appFiles = File(environment.filesDir)
        val workspaceDir = environment.workspaceRoot?.let(::File) ?: appFiles

        val exec = listOf(
            probe.execCase(CapabilityReport.LOCATION_APP_FILES, appFiles),
            probe.execCase(CapabilityReport.LOCATION_WORKSPACE, workspaceDir),
        )
        val commands = probe.commands()
        val reach = probe.reach()

        val jsonTarget = File(workspaceDir, CapabilityReport.JSON_FILE)
        val markdownTarget = File(workspaceDir, CapabilityReport.MARKDOWN_FILE)
        // Assume success for the provisional copy: this is only a placeholder the markdown (written
        // first) renders, and the JSON is written last with the true outcome baked in.
        val provisional = CapabilityReport.Report(
            environment = environment,
            exec = exec,
            commands = commands,
            shellOnly = SHELL_ONLY,
            write = CapabilityReport.WriteOutcome(true, jsonTarget.path, markdownTarget.path, true, null),
            reach = reach,
        )
        val markdownFailure = probe.write(markdownTarget, CapabilityReport.markdown(provisional))
        val beforeJson = provisional.copy(
            write = CapabilityReport.WriteOutcome(
                ok = true,
                jsonPath = jsonTarget.path,
                markdownPath = markdownTarget.path,
                markdownWritten = markdownFailure == null,
                failure = markdownFailure,
            ),
        )
        val jsonFailure = probe.write(jsonTarget, CapabilityReport.json(beforeJson))
        val written = if (jsonFailure == null) beforeJson else beforeJson.copy(
            write = CapabilityReport.WriteOutcome(
                ok = false,
                jsonPath = jsonTarget.path,
                markdownPath = markdownTarget.path,
                markdownWritten = markdownFailure == null,
                failure = jsonFailure,
            ),
        )
        CapabilityLog.emit(CapabilityReport.summary(written))
        return written
    }

    /** Run off the main thread: the probe must never block the UI, and it logs one summary line. */
    fun runDetached() {
        Thread({
            runCatching { run() }.onFailure { error ->
                CapabilityLog.emit("capability.probe FAILED ${error.javaClass.simpleName}: ${error.message ?: ""}")
            }
        }, "pi-capability-probe").apply { isDaemon = true; start() }
    }

    companion object {
        /**
         * The commands the agent is told it may rely on, and whether a version line is worth
         * capturing. Presence is not enough: each is also actually started, because `command -v`
         * only proves PATH resolution, not that this uid (SELinux `untrusted_app`) may execute it.
         */
        val COMMANDS: List<Pair<String, Boolean>> = listOf(
            "curl" to true,
            "toybox" to true,
            "getprop" to false,
            "tar" to false,
            "unzip" to false,
            "gzip" to false,
            "base64" to false,
            "sha256sum" to false,
            "md5sum" to false,
            "nc" to false,
            "ping" to false,
            "sed" to false,
            "awk" to false,
            "grep" to false,
            "find" to false,
            "xargs" to false,
            "timeout" to false,
            "setsid" to false,
            "nohup" to false,
            "dd" to false,
            "xxd" to false,
            "uuencode" to false,
            "lsof" to false,
            "python3" to false,
            "sqlite3" to false,
            "jq" to false,
        )

        /**
         * Binaries that exist on PATH but an app must never rely on: they are gated to the shell UID
         * or to signature/privileged permissions, so an app invoking them gets a SecurityException
         * (or a silently empty result) even though `command -v` finds them.
         */
        val SHELL_ONLY: List<CapabilityReport.ShellOnly> = listOf(
            CapabilityReport.ShellOnly("pm", "package manager: runs as the shell UID; an app call raises SecurityException"),
            CapabilityReport.ShellOnly("am", "activity manager: runs as the shell UID; an app call raises SecurityException"),
            CapabilityReport.ShellOnly("cmd", "generic system-service shell: shell UID only"),
            CapabilityReport.ShellOnly("content", "content-provider client: shell UID only"),
            CapabilityReport.ShellOnly("settings", "settings-provider client: shell UID only"),
            CapabilityReport.ShellOnly("dumpsys", "system diagnostics: shell UID only"),
            CapabilityReport.ShellOnly("svc", "power/data/wifi control: signature|shell permissions"),
            CapabilityReport.ShellOnly("input", "input injection: signature|shell permissions"),
            CapabilityReport.ShellOnly("screencap", "screen capture: signature|shell permissions"),
            CapabilityReport.ShellOnly("logcat", "log read: READ_LOGS is signature|privileged; an app sees only its own"),
        )

        /**
         * The real, device-backed probe. Unit tests never reach this; they inject a fake [Probe].
         *
         * [reachSource] is how the runtime host hands the probe the very router it built, so the
         * report states the app's real reach — widened by a granted All-files access, and the
         * workspace pair while it is absent — instead of the mirror below re-deriving it. `MainActivity`
         * always supplies it ([DurableRuntime.reach]); only a caller that has no router to give falls
         * back to the mirror.
         */
        fun forApp(
            context: Context,
            workspace: File?,
            reachSource: (() -> CapabilityReport.Reach)? = null,
        ): CapabilityProbe {
            CapabilityLog.sink = { line -> android.util.Log.println(android.util.Log.INFO, CapabilityLog.TAG, line) }
            return CapabilityProbe(AndroidProbe(context.applicationContext, workspace, reachSource = reachSource))
        }

        /**
         * Parse the one-shot command probe's output into [CapabilityReport.Command]s.
         *
         * Each marker line is `PI_CMD|name|absent|||` or `PI_CMD|name|present|rc|want|first-line`.
         * `rc` is the started binary's own exit status, which the shell sets to 126 when it found the
         * file but could not execute it — the SELinux/exec-policy refusal we are here to name — and
         * 127 when it is not there. Any other line is ignored.
         */
        fun parseCommandOutput(output: String): List<CapabilityReport.Command> {
            val commands = mutableListOf<CapabilityReport.Command>()
            for (raw in output.lineSequence()) {
                val line = raw.trimEnd('\r')
                if (!line.startsWith(MARKER)) continue
                val fields = line.split("|", limit = 6)
                if (fields.size < 3) continue
                val name = fields[1]
                if (name.isBlank()) continue
                if (fields[2] == "absent") {
                    commands += CapabilityReport.Command(name, present = false, executable = false)
                    continue
                }
                val exitCode = fields.getOrNull(3)?.trim()?.toIntOrNull()
                val wantsVersion = fields.getOrNull(4) == "1"
                val detail = fields.getOrNull(5)?.trim().orEmpty()
                val executable = !isExecRefusal(exitCode, detail)
                commands += CapabilityReport.Command(
                    name = name,
                    present = true,
                    executable = executable,
                    version = if (executable && wantsVersion && detail.isNotEmpty()) detail else null,
                    failure = if (executable) null else detail.ifEmpty { "exit=${exitCode ?: "unknown"}" },
                )
            }
            return commands
        }

        private const val MARKER = "PI_CMD|"

        /** 126/127 are the shell's own "found but not executable" / "not found"; the text names the rest. */
        private fun isExecRefusal(exitCode: Int?, detail: String): Boolean =
            exitCode == 126 || exitCode == 127 || EXEC_REFUSAL.containsMatchIn(detail)

        private val EXEC_REFUSAL = Regex("(?i)(permission denied|cannot execute|not executable|exec format error)")

        /**
         * Build the one-shot command probe: `command -v` proves presence, then the binary is actually
         * started to prove this uid may execute it. The shell sets `$?` to the started binary's own
         * status — and to 126 (found, not executable) / 127 (not found) when it could not start it — so
         * presence and executability both come back in a single shell invocation.
         *
         * Pure, so the exact marker format [parseCommandOutput] depends on is covered by a test and not
         * only by a device run.
         */
        fun commandProbeScript(commands: List<Pair<String, Boolean>>): String = buildString {
            append("probe() {\n")
            append("  c=\"\$1\"\n")
            append("  want=\"\$2\"\n")
            append("  shift 2\n")
            append("  p=\$(command -v \"\$c\" 2>/dev/null)\n")
            append("  if [ -z \"\$p\" ]; then echo \"PI_CMD|\$c|absent|||\"; return; fi\n")
            append("  out=\$(\"\$p\" \"\$@\" 2>&1)\n")
            append("  rc=\$?\n")
            append("  first=\$(printf '%s' \"\$out\" | head -n 1 | tr -d '\\r')\n")
            append("  echo \"PI_CMD|\$c|present|\$rc|\$want|\$first\"\n")
            append("}\n")
            commands.forEach { (name, wantsVersion) ->
                val argv = when (name) {
                    // A version line is the point for these two; `toybox` with no args prints its banner.
                    "curl" -> "--version"
                    "toybox" -> null
                    // A benign invocation that starts the binary and returns; never a command that blocks.
                    else -> "--help"
                }
                append("probe ").append(name).append(' ').append(if (wantsVersion) "1" else "0")
                if (argv != null) append(' ').append(argv)
                append('\n')
            }
        }
    }
}

/**
 * The device-backed [CapabilityProbe.Probe]. Every call here can only answer truthfully inside the
 * app's own process, which is the entire point: `untrusted_app` is not what `adb run-as` sees.
 */
internal class AndroidProbe(
    private val context: Context,
    private val workspace: File?,
    private val shellExecutable: String = "/system/bin/sh",
    private val reachSource: (() -> CapabilityReport.Reach)? = null,
) : CapabilityProbe.Probe {

    override fun environment(): CapabilityReport.Environment {
        val info = packageInfo()
        val versionCode = when {
            info == null -> 0L
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> info.longVersionCode
            else -> @Suppress("DEPRECATION") info.versionCode.toLong()
        }
        return CapabilityReport.Environment(
            androidRelease = Build.VERSION.RELEASE ?: "unknown",
            sdkInt = Build.VERSION.SDK_INT,
            primaryAbi = Build.SUPPORTED_ABIS?.firstOrNull(),
            filesDir = context.filesDir.path,
            cacheDir = context.cacheDir.path,
            workspaceRoot = workspace?.path,
            nativeLibraryDir = runCatching { context.applicationInfo.nativeLibraryDir }.getOrNull(),
            allFilesAccess = hasAllFilesAccess(),
            internetGranted = context.checkPermission(android.Manifest.permission.INTERNET, Process.myPid(), Process.myUid()) ==
                PackageManager.PERMISSION_GRANTED,
            versionName = info?.versionName,
            versionCode = versionCode,
            generatedAt = timestamp(),
        )
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(): android.content.pm.PackageInfo? =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date())

    /**
     * Stage a script in [directory], chmod it 0700, and run it two ways: directly, and through
     * `/system/bin/sh`. The two are recorded independently — one working says nothing about the
     * other — and every failure keeps its exact text rather than collapsing to a boolean.
     */
    override fun execCase(location: String, directory: File): CapabilityReport.ExecCase {
        val script = File(directory, ".pi-probe-${UUID.randomUUID().toString().take(8)}.sh")
        return try {
            directory.mkdirs()
            script.writeText("#!/system/bin/sh\necho PI_PROBE_OK\n")
            script.setReadable(true, true)
            // Maps to chmod 0700 for the owner: exactly the permission a script the app itself wrote has.
            script.setExecutable(true, true)
            val shell = NativeShell(directory, shellExecutable)
            val direct = execRoute(shell, NativeShell.Command.Argv(listOf(script.path)))
            val interpreter = execRoute(shell, NativeShell.Command.Argv(listOf(shellExecutable, script.path)))
            CapabilityReport.ExecCase(location, direct, interpreter)
        } catch (error: Exception) {
            val failure = "could not stage the probe script: ${describeFailure(error)}"
            CapabilityReport.ExecCase(location, CapabilityReport.Route.failed(failure), CapabilityReport.Route.failed(failure))
        } finally {
            runCatching { script.delete() }
        }
    }

    /** Run one route and keep the real reason on failure. A launch failure is the SELinux refusal. */
    private fun execRoute(shell: NativeShell, command: NativeShell.Command): CapabilityReport.Route {
        val result = try {
            shell.execute(
                command,
                environment = mapOf("PATH" to PATH),
                maxOutputBytes = 8 * 1024,
                timeoutMillis = EXEC_TIMEOUT_MILLIS,
            )
        } catch (error: Exception) {
            return CapabilityReport.Route.failed("launch failed: ${describeFailure(error)}")
        }
        return when {
            result.cancelled -> CapabilityReport.Route.failed("cancelled before it ran")
            result.timedOut -> CapabilityReport.Route.failed("timed out after $EXEC_TIMEOUT_MILLIS ms")
            result.exitCode == 0 && result.stdout.contains(SCRIPT_MARKER) -> CapabilityReport.Route.ok(result.stdout.trim())
            else -> CapabilityReport.Route.failed(
                buildString {
                    append("exit=").append(result.exitCode ?: "null")
                    val detail = listOf(result.stderr, result.stdout).firstOrNull { it.isNotBlank() }
                    if (detail != null) append(": ").append(detail.trim().take(300))
                },
            )
        }
    }

    /**
     * One shell invocation locates and starts every curated command, so the probe stays well under a
     * second even though it proves executable-ness rather than mere presence.
     */
    override fun commands(): List<CapabilityReport.Command> {
        val shell = NativeShell(workspace ?: context.filesDir, shellExecutable)
        val result = try {
            shell.execute(
                NativeShell.Command.Script(CapabilityProbe.commandProbeScript(CapabilityProbe.COMMANDS)),
                environment = mapOf("PATH" to PATH),
                maxOutputBytes = 64 * 1024,
                timeoutMillis = COMMANDS_TIMEOUT_MILLIS,
            )
        } catch (error: Exception) {
            return unprobed("the command probe could not start: ${describeFailure(error)}")
        }
        val parsed = CapabilityProbe.parseCommandOutput(result.stdout)
        if (parsed.isNotEmpty()) return parsed
        // The shell ran but produced nothing usable: say so, rather than report a clean "all absent".
        val reason = if (result.timedOut) "the command probe timed out after $COMMANDS_TIMEOUT_MILLIS ms"
        else "the command probe produced no output (exit=${result.exitCode ?: "null"})"
        return unprobed(reason)
    }

    private fun unprobed(reason: String): List<CapabilityReport.Command> =
        CapabilityProbe.COMMANDS.map { (name, _) ->
            CapabilityReport.Command(name, present = false, executable = false, failure = reason)
        }

    /**
     * The roots this app confines agent calls to, read on every run.
     *
     * When the app hands in the router it is really routing through ([CapabilityProbe.forApp]'s
     * `reachSource`, which `MainActivity` always supplies) that router's own answer wins outright:
     * the report then cannot drift from the reach the agent actually meets. Only a caller with no
     * router to hand in — a test, or a future entry point that has none — falls back to the mirror
     * below, which rebuilds a [NativeIoRouter] with the same inputs the runtime host builds its own
     * with: the app-private `workspace` fallback, the bound workspace path, `cacheDir/native-tmp`,
     * the grant read fresh, and the candidates a granted All-files access unlocks (the same
     * [sharedStorageRootOrNull] the workspace binding uses). Either way the roots come from the
     * router's own [NativeIoRouter.reach], so the two halves cannot state a different shape; only
     * the router instance differs.
     */
    override fun reach(): CapabilityReport.Reach = resolveReach(reachSource) { mirroredReach() }

    /** The fallback for a caller that supplied no live router. */
    private fun mirroredReach(): CapabilityReport.Reach =
        NativeIoRouter(
            fallbackRoot = File(context.filesDir, WORKSPACE_DIR),
            workspacePath = workspace?.path,
            tempRoot = File(context.cacheDir, TEMP_DIR),
            isAllFilesAccess = { hasAllFilesAccess() },
            broadRoots = listOfNotNull(
                sharedStorageRootOrNull(),
                context.getExternalFilesDir(null),
                context.filesDir,
                context.cacheDir,
                context.noBackupFilesDir,
            ),
        ).reach().copy(source = REACH_SOURCE)

    /**
     * The workspace can be missing, gone, or simply not writable — every one of those is an answer the
     * agent needs, so the failure reason is returned rather than thrown and swallowed.
     */
    override fun write(target: File, content: String): String? = try {
        target.parentFile?.mkdirs()
        target.writeText(content)
        null
    } catch (error: Exception) {
        describeFailure(error)
    }

    private companion object {
        const val PATH = "/system/bin"
        const val SCRIPT_MARKER = "PI_PROBE_OK"
        const val EXEC_TIMEOUT_MILLIS = 2_500L
        const val COMMANDS_TIMEOUT_MILLIS = 5_000L

        /** The dirs `NativeRuntimeHost` wires the router with — the fallback root and the file temp dir. */
        const val WORKSPACE_DIR = "workspace"
        const val TEMP_DIR = "native-tmp"

        /**
         * Stated in the report only when the mirror answered, so a reader can tell the fallback from
         * the live router (`NativeIoRouter.REACH_SOURCE`) and knows how the roots were re-derived.
         */
        const val REACH_SOURCE = "NativeIoRouter.allowedRoots, rebuilt per run as the runtime host " +
            "builds it (workspace fallback + bound workspace + cacheDir/native-tmp; shared storage joins " +
            "only while the All-files grant is held) — no live router was handed in"
    }
}

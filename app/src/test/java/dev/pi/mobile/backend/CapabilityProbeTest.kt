package dev.pi.mobile.backend

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The in-process capability probe, exercised off-device.
 *
 * The probe's whole reason to exist is the SELinux/exec-policy distinction that only a run inside
 * the app's own process can settle: a file the app wrote may or may not be executable, directly or
 * through the interpreter, and a PATH binary may be present yet unusable by the app's uid. The
 * device calls are seams; this pins down what the probe *decides* from what they report, so the
 * findings the agent reads are shaped correctly before a single device run:
 *
 *  - a denied direct exec is reported as denied, with its real reason, never a bare boolean;
 *  - the interpreter route is judged on its own, never inferred from the direct route;
 *  - a binary that is simply not there is absent, not a failure;
 *  - a report that could not be written says so instead of vanishing.
 */
class CapabilityProbeTest {

  // ------------------------------------------------------------------ pure rendering: exec routes

  @Test fun aDeniedDirectExecIsReportedAsDeniedWithItsReason() {
    val reason = "/data/user/0/dev.pi.mobile/files/.pi-probe-a1b2.sh: cannot execute: Permission denied"
    val exec = JSONObject(CapabilityReport.json(report(
      exec = listOf(CapabilityReport.ExecCase(
        location = "app_files",
        direct = CapabilityReport.Route.failed(reason),
        interpreter = CapabilityReport.Route.ok("/system/bin/sh ran it: PI_PROBE_OK"),
      )),
    ))).getJSONArray("exec").getJSONObject(0)

    val direct = exec.getJSONObject("direct")
    assertTrue("the route was tried", direct.getBoolean("attempted"))
    assertFalse("and it did not run", direct.getBoolean("ok"))
    assertTrue("the exact reason survives", direct.getString("detail").contains("Permission denied"))
  }

  @Test fun theInterpreterRouteIsReportedIndependentlyOfTheDirectRoute() {
    val exec = JSONObject(CapabilityReport.json(report(
      exec = listOf(
        CapabilityReport.ExecCase(
          "app_files",
          direct = CapabilityReport.Route.failed("cannot execute: Permission denied"),
          interpreter = CapabilityReport.Route.ok("PI_PROBE_OK"),
        ),
        CapabilityReport.ExecCase(
          "workspace",
          direct = CapabilityReport.Route.ok("PI_PROBE_OK"),
          interpreter = CapabilityReport.Route.failed("/mnt/media_rw/...: noexec mount"),
        ),
      ),
    ))).getJSONArray("exec")

    // Direct failed but the interpreter route succeeded — the two are not collapsed to one verdict.
    val appFiles = exec.getJSONObject(0)
    assertFalse(appFiles.getJSONObject("direct").getBoolean("ok"))
    assertTrue(appFiles.getJSONObject("interpreter").getBoolean("ok"))

    // And the other way round: direct works, the interpreter route still fails on its own terms.
    val workspace = exec.getJSONObject(1)
    assertTrue(workspace.getJSONObject("direct").getBoolean("ok"))
    assertFalse(workspace.getJSONObject("interpreter").getBoolean("ok"))
    assertTrue(workspace.getJSONObject("interpreter").getString("detail").contains("noexec"))
  }

  @Test fun aRouteThatWasNeverTriedIsNeitherOkNorAFailure() {
    val direct = JSONObject(CapabilityReport.json(report(
      exec = listOf(CapabilityReport.ExecCase("workspace", CapabilityReport.Route.skipped, CapabilityReport.Route.skipped)),
    ))).getJSONArray("exec").getJSONObject(0).getJSONObject("direct")

    assertFalse(direct.getBoolean("attempted"))
    assertFalse(direct.getBoolean("ok"))
    assertTrue("a skipped route carries no fabricated reason", direct.isNull("detail"))
  }

  @Test fun theHumanReportSpellsOutWhichRouteFailedAndWhy() {
    val markdown = CapabilityReport.markdown(report(
      exec = listOf(CapabilityReport.ExecCase(
        "workspace",
        direct = CapabilityReport.Route.failed("Permission denied"),
        interpreter = CapabilityReport.Route.failed("noexec mount"),
      )),
    ))
    assertTrue(markdown.contains("Permission denied"))
    assertTrue(markdown.contains("noexec mount"))
    assertTrue(markdown.contains("workspace"))
  }

  // ------------------------------------------------------------------ pure rendering: commands

  @Test fun aMissingBinaryIsReportedAsAbsentNotAsAFailure() {
    val command = JSONObject(CapabilityReport.json(report(
      commands = listOf(CapabilityReport.Command("jq", present = false, executable = false)),
    ))).getJSONArray("commands").getJSONObject(0)

    assertFalse(command.getBoolean("present"))
    assertFalse(command.getBoolean("executable"))
    assertTrue("absent is not a failure", command.isNull("failure"))
    assertTrue(command.isNull("version"))
  }

  @Test fun aPresentButUnexecutableBinaryCarriesTheReason() {
    val command = JSONObject(CapabilityReport.json(report(
      commands = listOf(CapabilityReport.Command(
        "python3",
        present = true,
        executable = false,
        failure = "sh: /system/bin/python3: cannot execute: Permission denied",
      )),
    ))).getJSONArray("commands").getJSONObject(0)

    assertTrue(command.getBoolean("present"))
    assertFalse(command.getBoolean("executable"))
    assertTrue(command.getString("failure").contains("Permission denied"))
  }

  // ------------------------------------------------------------------ pure rendering: environment

  @Test fun environmentFactsAreRecordedVerbatimIncludingGrantedPermissions() {
    val environment = JSONObject(CapabilityReport.json(report(
      environment = environment(allFilesAccess = true, internet = false),
    ))).getJSONObject("environment")

    assertEquals(33, environment.getInt("sdk_int"))
    assertEquals("13", environment.getString("android_release"))
    assertEquals("arm64-v8a", environment.getString("primary_abi"))
    assertEquals("/data/user/0/dev.pi.mobile/files", environment.getString("files_dir"))
    assertEquals("/data/user/0/dev.pi.mobile/cache", environment.getString("cache_dir"))
    assertEquals("/data/user/0/dev.pi.mobile/files/workspace", environment.getString("workspace_root"))
    assertTrue(environment.getString("native_library_dir").contains("arm64"))
    assertTrue("All-files access is reported as observed", environment.getBoolean("all_files_access"))
    assertFalse("a missing INTERNET grant is reported honestly", environment.getBoolean("internet"))
    assertEquals("1.0.0", environment.getString("version_name"))
    assertEquals(1L, environment.getLong("version_code"))
    assertTrue(environment.getString("generated_at").isNotBlank())
  }

  @Test fun aMissingWorkspaceIsRecordedAsNullNotAsTheStringNull() {
    val environment = JSONObject(CapabilityReport.json(report(
      environment = environment(workspace = null),
    ))).getJSONObject("environment")
    assertTrue(environment.isNull("workspace_root"))
  }

  // ------------------------------------------------------------------ pure rendering: write outcome

  @Test fun aFailedWriteOutcomeIsSurfacedInEveryRenderedForm() {
    val failure = "ENOENT: the workspace directory is gone: /w"
    val written = report(write = CapabilityReport.WriteOutcome(
      ok = false, jsonPath = "/w/.pi-capabilities.json", markdownPath = "/w/.pi-capabilities.md",
      markdownWritten = false, failure = failure,
    ))

    val json = JSONObject(CapabilityReport.json(written)).getJSONObject("write")
    assertFalse(json.getBoolean("ok"))
    assertEquals(failure, json.getString("failure"))

    assertTrue(CapabilityReport.markdown(written).contains(failure))

    val summary = CapabilityReport.summary(written)
    assertFalse("the summary must stay one line", summary.contains('\n'))
    assertTrue(summary.contains("wrote=false"))
    assertTrue(summary.contains("ENOENT"))
  }

  @Test fun theSummaryIsOneGrepableLineWithTheHeadlineCounts() {
    val line = CapabilityReport.summary(report(
      exec = listOf(
        CapabilityReport.ExecCase("app_files", CapabilityReport.Route.ok(), CapabilityReport.Route.ok()),
        CapabilityReport.ExecCase("workspace", CapabilityReport.Route.failed("noexec"), CapabilityReport.Route.ok()),
      ),
      commands = listOf(
        CapabilityReport.Command("curl", true, true, "curl 7.67.0"),
        CapabilityReport.Command("jq", false, false),
      ),
    ))
    assertFalse(line.contains('\n'))
    assertTrue(line.startsWith("capability.probe "))
    assertTrue(line.contains("wrote=true"))
  }

  // ------------------------------------------------------------------ the shell-output parser

  @Test fun theShellOutputParserDistinguishesAbsentFromPresentButUnexecutable() {
    val output = listOf(
      "PI_CMD|curl|present|0|1|curl 7.67.0 (aarch64) libcurl/7.67.0",
      "PI_CMD|jq|absent|||",
      "PI_CMD|python3|present|126|0|sh: /system/bin/python3: cannot execute: Permission denied",
      "unrelated noise the shell may have printed",
    ).joinToString("\n")

    val commands = CapabilityProbe.parseCommandOutput(output).associateBy { it.name }
    assertEquals(3, commands.size)

    assertEquals("curl 7.67.0 (aarch64) libcurl/7.67.0", commands.getValue("curl").version)
    assertTrue(commands.getValue("curl").present)
    assertTrue(commands.getValue("curl").executable)

    assertFalse(commands.getValue("jq").present)
    assertNull("an absent binary is not a failure", commands.getValue("jq").failure)

    assertTrue("python3 is on PATH…", commands.getValue("python3").present)
    assertFalse("…but the app's uid cannot run it", commands.getValue("python3").executable)
    assertTrue(commands.getValue("python3").failure!!.contains("Permission denied"))
  }

  @Test fun aNonVersionProbeDoesNotMisfileItsUsageLineAsAVersion() {
    val commands = CapabilityProbe.parseCommandOutput("PI_CMD|getprop|present|0|0|Usage: getprop [name]")
      .associateBy { it.name }
    assertTrue(commands.getValue("getprop").executable)
    assertNull(commands.getValue("getprop").version)
  }

  // ------------------------------------------------------------------ the runner

  @Test fun theRunnerWritesTheHumanReportFirstAndTheJsonLast() {
    val fake = FakeProbe()
    val result = CapabilityProbe(fake).run()

    assertTrue(result.write.ok)
    assertTrue(result.write.markdownWritten)
    val names = fake.writes.keys.map { it.substringAfterLast('/') }
    assertEquals(listOf(CapabilityReport.MARKDOWN_FILE, CapabilityReport.JSON_FILE), names)
    assertTrue("the JSON is the machine contract", fake.writes.values.last().contains("\"schema\": 1"))
  }

  @Test fun theRunnerSurfacesAReportItCouldNotWriteInsteadOfFailingSilently() {
    val failure = "EACCES: the workspace is not writable: /w"
    val fake = FakeProbe(writeFailure = failure)
    val result = CapabilityProbe(fake).run()

    assertFalse(result.write.ok)
    assertEquals(failure, result.write.failure)
    assertTrue("nothing landed on disk", fake.writes.isEmpty())

    val json = JSONObject(CapabilityReport.json(result)).getJSONObject("write")
    assertFalse(json.getBoolean("ok"))
    assertEquals(failure, json.getString("failure"))
    assertTrue(CapabilityReport.summary(result).contains("wrote=false"))
  }

  @Test fun theReportCarriesEveryShellOnlyBinaryWithAReason() {
    val result = CapabilityProbe(FakeProbe()).run()

    val names = result.shellOnly.map { it.name }
    assertTrue(names.containsAll(listOf(
      "pm", "am", "cmd", "content", "settings", "dumpsys", "svc", "input", "screencap", "logcat",
    )))
    assertTrue("each shell-only binary explains itself", result.shellOnly.all { it.reason.isNotBlank() })
    assertEquals(result.shellOnly.size, JSONObject(CapabilityReport.json(result)).getJSONArray("shell_only").length())
  }

  @Test fun theCuratedCommandListCoversTheProbeTheAgentIsToldToTrust() {
    val names = CapabilityProbe.COMMANDS.map { it.first }
    assertTrue(names.containsAll(listOf(
      "curl", "toybox", "getprop", "tar", "unzip", "gzip", "base64", "sha256sum", "md5sum", "nc",
      "ping", "sed", "awk", "grep", "find", "xargs", "timeout", "setsid", "nohup", "dd", "xxd",
      "uuencode", "lsof", "python3", "sqlite3", "jq",
    )))
    assertTrue("curl's version line is captured", CapabilityProbe.COMMANDS.first { it.first == "curl" }.second)
    assertTrue("toybox's version line is captured", CapabilityProbe.COMMANDS.first { it.first == "toybox" }.second)
  }

  @Test fun theCommandProbeScriptLocatesAndStartsEachCommand() {
    val script = CapabilityProbe.commandProbeScript(listOf("curl" to true, "toybox" to true, "jq" to false))
    // Presence is looked up, and the started binary's own status is captured — the two facts that
    // separate "not there" from "there but this uid cannot run it".
    assertTrue(script.contains("command -v"))
    assertTrue(script.contains("PI_CMD|"))
    assertTrue(script.contains("present|"))
    // curl and toybox are asked for a version line; a plain command gets a benign, non-blocking start.
    assertTrue(script.contains("probe curl 1 --version"))
    assertTrue(script.contains("probe toybox 1"))
    assertTrue(script.contains("probe jq 0 --help"))
  }

  @Test fun theCommandProbeScriptCoversEveryCuratedCommand() {
    val script = CapabilityProbe.commandProbeScript(CapabilityProbe.COMMANDS)
    CapabilityProbe.COMMANDS.forEach { (name, _) ->
      assertTrue("the script must probe $name", script.contains("probe $name "))
    }
  }

  // ------------------------------------------------------------------ the effective exec rule

  @Test fun theGuidanceStatesTheEffectiveRuleInStructuredJson() {
    val guidance = JSONObject(CapabilityReport.json(report())).getJSONArray("guidance")
    val codes = (0 until guidance.length()).map { guidance.getJSONObject(it).getString("code") }
    val texts = (0 until guidance.length()).joinToString("\n") { guidance.getJSONObject(it).getString("text") }

    assertTrue("every rule is stated, under a stable code", codes.containsAll(listOf(
      "exec_via_interpreter", "direct_exec_blocked", "binaries_ship_in_apk", "never_lower_target_sdk",
    )))
    assertEquals("and stated exactly once", codes.size, codes.distinct().size)
    assertTrue("the interpreter route is named", texts.contains("/system/bin/sh"))
    assertTrue("the app-storage refusal is named", texts.contains("SELinux"))
    assertTrue("the shared-storage refusal is named", texts.contains("noexec"))
    assertTrue("the only exec-allowed location is named", texts.contains("lib*.so"))
    assertTrue("lowering targetSdk is ruled out", texts.contains("targetSdk"))
  }

  @Test fun theHumanReportCarriesTheSameEffectiveRuleAsTheJson() {
    val markdown = CapabilityReport.markdown(report())
    assertTrue("the .md has its own section", markdown.contains("## The effective rule"))
    listOf("exec_via_interpreter", "direct_exec_blocked", "binaries_ship_in_apk", "never_lower_target_sdk")
      .forEach { assertTrue("$it must be named in the .md too", markdown.contains(it)) }
    assertTrue(markdown.contains("/system/bin/sh"))
    assertTrue(markdown.contains("noexec"))
    assertTrue(markdown.contains("lib*.so"))
  }

  @Test fun theEffectiveMarkerReflectsInterpreterOkAndDirectFailed() {
    val result = report(exec = listOf(
      CapabilityReport.ExecCase(
        "app_files",
        direct = CapabilityReport.Route.failed("Cannot run program \"…/.pi-probe-a1b2.sh\": error=13, Permission denied"),
        interpreter = CapabilityReport.Route.ok("PI_PROBE_OK"),
      ),
      CapabilityReport.ExecCase(
        "workspace",
        direct = CapabilityReport.Route.failed("Cannot run program \"…/.pi-probe-c3d4.sh\": error=13, Permission denied"),
        interpreter = CapabilityReport.Route.ok("PI_PROBE_OK"),
      ),
    ))

    val effective = JSONObject(CapabilityReport.json(result)).getJSONObject("exec_effective")
    assertTrue("scripts do run — through the interpreter", effective.getBoolean("scripts_via_interpreter"))
    assertFalse("…but never directly", effective.getBoolean("direct_exec"))

    val codes = effective.getJSONArray("reason_codes").let { array -> (0 until array.length()).map { array.getString(it) } }
    assertTrue("app storage refuses for SELinux W^X, not for a missing permission", codes.contains("selinux_wx_app_storage"))
    assertTrue("shared storage refuses because it is a noexec mount", codes.contains("noexec_shared_storage"))

    val markdown = CapabilityReport.markdown(result)
    assertTrue(markdown.contains("scripts_via_interpreter=true"))
    assertTrue(markdown.contains("direct_exec=false"))
  }

  @Test fun theEffectiveMarkerIsDerivedFromWhatWasObservedNotAssumed() {
    val untouched = report(exec = listOf(
      CapabilityReport.ExecCase("workspace", CapabilityReport.Route.skipped, CapabilityReport.Route.skipped),
    ))
    val skipped = JSONObject(CapabilityReport.json(untouched)).getJSONObject("exec_effective")
    assertFalse("a skipped route is not a working interpreter", skipped.getBoolean("scripts_via_interpreter"))
    assertFalse(skipped.getBoolean("direct_exec"))

    val directWorks = report(exec = listOf(
      CapabilityReport.ExecCase("workspace", CapabilityReport.Route.ok("PI_PROBE_OK"), CapabilityReport.Route.ok("PI_PROBE_OK")),
    ))
    val observed = JSONObject(CapabilityReport.json(directWorks)).getJSONObject("exec_effective")
    assertTrue("where direct exec did work, the marker says so", observed.getBoolean("direct_exec"))
  }

  @Test fun theRawFailureRowsAreStillFailuresAlongsideTheGuidance() {
    val reason = "Cannot run program \"/data/user/0/dev.pi.mobile/files/.pi-probe-a1b2.sh\": error=13, Permission denied"
    val result = report(exec = listOf(CapabilityReport.ExecCase(
      "app_files",
      direct = CapabilityReport.Route.failed(reason),
      interpreter = CapabilityReport.Route.ok("PI_PROBE_OK"),
    )))

    val appFiles = JSONObject(CapabilityReport.json(result)).getJSONArray("exec").getJSONObject(0)
    assertTrue("the route is still reported as tried", appFiles.getJSONObject("direct").getBoolean("attempted"))
    assertFalse("and still as a failure — guidance does not soften it", appFiles.getJSONObject("direct").getBoolean("ok"))
    assertEquals("with its exact reason", reason, appFiles.getJSONObject("direct").getString("detail"))

    val markdown = CapabilityReport.markdown(result)
    assertTrue("the .md still shows the raw failure", markdown.contains("FAILED"))
    assertTrue(markdown.contains("Permission denied"))
  }

  @Test fun theSummaryAloneAnswersWhetherScriptsCanRun() {
    val line = CapabilityReport.summary(report(exec = listOf(
      CapabilityReport.ExecCase("app_files", CapabilityReport.Route.failed("Permission denied"), CapabilityReport.Route.ok("PI_PROBE_OK")),
      CapabilityReport.ExecCase("workspace", CapabilityReport.Route.failed("Permission denied"), CapabilityReport.Route.ok("PI_PROBE_OK")),
    )))

    assertFalse("the summary stays one grep line", line.contains('\n'))
    assertTrue(line.contains("exec_effective"))
    assertTrue(line.contains("scripts_via_interpreter=true"))
    assertTrue(line.contains("direct_exec=false"))
    assertTrue(line.contains("selinux_wx_app_storage"))
    assertTrue(line.contains("noexec_shared_storage"))
  }

  @Test fun theEffectiveMarkerSaysWhetherAnythingWasAttemptedAtAll() {
    val nothingTried = report(exec = listOf(
      CapabilityReport.ExecCase("workspace", CapabilityReport.Route.skipped, CapabilityReport.Route.skipped),
    ))
    val untouched = JSONObject(CapabilityReport.json(nothingTried)).getJSONObject("exec_effective")
    assertFalse("nothing was tried, so the marker knows nothing", untouched.getBoolean("attempted"))
    assertTrue("and the summary says so rather than reading as a failure", CapabilityReport.summary(nothingTried).contains("exec_attempted=false"))

    val tried = report(exec = listOf(
      CapabilityReport.ExecCase("app_files", CapabilityReport.Route.failed("Permission denied"), CapabilityReport.Route.ok("PI_PROBE_OK")),
    ))
    assertTrue(JSONObject(CapabilityReport.json(tried)).getJSONObject("exec_effective").getBoolean("attempted"))
    assertTrue(CapabilityReport.summary(tried).contains("exec_attempted=true"))
  }

  // ------------------------------------------------------------------ reach: what the app confines

  @Test fun theReachSectionNamesTheRootsTheAppActuallyAllows() {
    val result = report(reach = reachOf(
      shellRoots = listOf("/data/user/0/dev.pi.mobile/files/workspace", "/storage/emulated/0/Documents/proj"),
      fileRoots = listOf(
        "/data/user/0/dev.pi.mobile/files/workspace",
        "/storage/emulated/0/Documents/proj",
        "/data/user/0/dev.pi.mobile/cache/native-tmp",
      ),
    ))

    val reach = JSONObject(CapabilityReport.json(result)).getJSONObject("reach")
    assertTrue("the reach is stated as observed", reach.getBoolean("observed"))
    assertEquals("every cwd root", 2, reach.getJSONArray("shell_roots").length())
    assertEquals("the shell is not a sandbox", false, reach.getBoolean("shell_confined"))
    assertEquals("…plus the file-only temp dir", 3, reach.getJSONArray("file_roots").length())
    assertEquals("/data/user/0/dev.pi.mobile/files/workspace", reach.getString("default_root"))
    assertTrue("and where they were read from", reach.getString("source").contains("NativeIoRouter"))

    val markdown = CapabilityReport.markdown(result)
    assertTrue(markdown.contains("## What the file tools can reach, and where the shell starts"))
    assertTrue("the .md lists the roots too", markdown.contains("/storage/emulated/0/Documents/proj"))
    assertTrue(markdown.contains("native-tmp"))
    assertTrue("and says the shell is not confined", markdown.contains("NOT confined"))
  }

  @Test fun thePlainLanguageReachLineBlamesTheAppsConfinementNotAndroid() {
    val result = report(environment = environment(allFilesAccess = true), reach = reachOf())
    val line = JSONObject(CapabilityReport.json(result)).getJSONObject("reach").getString("line")

    assertTrue("it names the roots", line.contains("/data/user/0/dev.pi.mobile/files/workspace"))
    assertTrue("it names the code a refusal carries", line.contains("permission_denied"))
    assertTrue("it names whose refusal it is", line.contains("NativeIoRouter"))
    assertTrue("and rules Android out", line.contains("from Android"))
    assertTrue("it does not imply the shell is confined", line.contains("NOT confined"))
    assertTrue("the .md carries the same line", CapabilityReport.markdown(result).contains(line))
  }

  @Test fun aGrantedButUnusedAllFilesAccessIsReportedAsUnused() {
    val narrow = report(environment = environment(allFilesAccess = true), reach = reachOf())
    val grants = JSONObject(CapabilityReport.json(narrow)).getJSONArray("unused_grants")
    assertEquals("the grant is called out once", 1, grants.length())
    assertEquals("all_files_access", grants.getJSONObject(0).getString("capability"))
    assertTrue("with the reason it is unused", grants.getJSONObject(0).getString("detail").contains("granted"))
    assertTrue("and the summary says so", CapabilityReport.summary(narrow).contains("all_files_unused=true"))

    // A root outside app storage is the grant being used, so nothing is called unused.
    val shared = report(
      environment = environment(allFilesAccess = true),
      reach = reachOf(
        shellRoots = listOf("/storage/emulated/0/Documents/proj"),
        fileRoots = listOf("/storage/emulated/0/Documents/proj"),
      ),
    )
    assertEquals(0, JSONObject(CapabilityReport.json(shared)).getJSONArray("unused_grants").length())
    assertTrue(CapabilityReport.summary(shared).contains("all_files_unused=false"))
  }

  @Test fun anUnobservedReachIsReportedAsUnknownNotAsNoRoots() {
    val result = report(environment = environment(allFilesAccess = true), reach = CapabilityReport.Reach.blocked("IOException: the cache dir is gone"))
    val reach = JSONObject(CapabilityReport.json(result)).getJSONObject("reach")

    assertFalse(reach.getBoolean("observed"))
    assertTrue(reach.getString("failure").contains("cache dir"))
    assertTrue("the line says unknown, never empty", reach.getString("line").contains("unknown"))
    assertEquals("nothing is inferred from an unread router", 0, JSONObject(CapabilityReport.json(result)).getJSONArray("unused_grants").length())
    assertTrue(CapabilityReport.markdown(result).contains("Reach was not observed"))
    assertTrue(CapabilityReport.summary(result).contains("reach observed=false"))
    assertTrue(CapabilityReport.summary(result).contains("reach_failure="))
  }

  @Test fun theReachIsReadFromTheProbeOnEveryRunNotRestatedAsAConstant() {
    val first = CapabilityProbe(FakeProbe(reach = reachOf(shellRoots = listOf("/a"), fileRoots = listOf("/a")))).run()
    val second = CapabilityProbe(FakeProbe(reach = reachOf(shellRoots = listOf("/b"), fileRoots = listOf("/b", "/c")))).run()

    val firstRoots = JSONObject(CapabilityReport.json(first)).getJSONObject("reach").getJSONArray("shell_roots")
    assertEquals(1, firstRoots.length())
    assertEquals("/a", firstRoots.getString(0))
    assertEquals(2, JSONObject(CapabilityReport.json(second)).getJSONObject("reach").getJSONArray("file_roots").length())

    val summary = CapabilityReport.summary(second)
    assertTrue(summary.contains("shell_roots=1"))
    assertTrue(summary.contains("file_roots=2"))
    assertTrue(summary.contains("reach observed=true"))
  }

  // ------------------------------------------------------------------ reach: the live source wins

  /**
   * The report must state the app's real reach. A live source — the router the runtime host actually
   * built, handed in as `reachSource` — wins over the mirror unconditionally, so the precedence can
   * never silently invert into "report a root set the app does not have"; and the mirror stays the
   * documented answer for a caller that has no router to hand in.
   */
  @Test fun theSuppliedReachSourceWinsOverTheMirrorAndIsWhatTheReportStates() {
    val live = reachOf(
      shellRoots = listOf("/storage/emulated/0/Documents/live"),
      fileRoots = listOf("/storage/emulated/0/Documents/live"),
    )
    val mirror = reachOf(shellRoots = listOf("/data/user/0/dev.pi.mobile/files/workspace"))

    // The precedence itself, pinned: the source is returned verbatim, the mirror untouched.
    assertEquals("the live source wins outright", live, resolveReach({ live }) { mirror })
    assertEquals("with no source the mirror is the fallback", mirror, resolveReach(null) { mirror })

    // And it is the source's roots, not the mirror's, that the report ends up stating.
    val reach = JSONObject(CapabilityReport.json(
      CapabilityProbe(FakeProbe(reach = resolveReach({ live }) { mirror })).run(),
    )).getJSONObject("reach")
    assertEquals(1, reach.getJSONArray("shell_roots").length())
    assertEquals("/storage/emulated/0/Documents/live", reach.getJSONArray("shell_roots").getString(0))
    assertTrue(
      "the report's own line names the live root",
      reach.getString("line").contains("/storage/emulated/0/Documents/live"),
    )
    assertFalse(
      "and never the mirror's",
      reach.getString("line").contains("dev.pi.mobile/files/workspace"),
    )
  }

  /**
   * A mirror that cannot be read is an unobserved reach — never an empty one, which would read as
   * "the agent can reach nothing" rather than "the roots are unknown".
   */
  @Test fun aMirrorThatCannotBeReadIsReportedAsUnobservedRatherThanEmpty() {
    val blocked = resolveReach(null) { throw IllegalStateException("the cache dir is gone") }

    assertFalse(blocked.observed)
    assertTrue(blocked.shellRoots.isEmpty() && blocked.fileRoots.isEmpty())
    assertTrue(blocked.failure!!.contains("cache dir"))
  }

  // ------------------------------------------------------------------ helpers

  private fun environment(
    allFilesAccess: Boolean = false,
    internet: Boolean = true,
    workspace: String? = "/data/user/0/dev.pi.mobile/files/workspace",
  ) = CapabilityReport.Environment(
    androidRelease = "13",
    sdkInt = 33,
    primaryAbi = "arm64-v8a",
    filesDir = "/data/user/0/dev.pi.mobile/files",
    cacheDir = "/data/user/0/dev.pi.mobile/cache",
    workspaceRoot = workspace,
    nativeLibraryDir = "/data/app/~~abc/dev.pi.mobile-xyz/lib/arm64",
    allFilesAccess = allFilesAccess,
    internetGranted = internet,
    versionName = "1.0.0",
    versionCode = 1L,
    generatedAt = "2026-10-17T18:00:00.000+03:00",
  )

  private fun report(
    environment: CapabilityReport.Environment = environment(),
    exec: List<CapabilityReport.ExecCase> = emptyList(),
    commands: List<CapabilityReport.Command> = emptyList(),
    shellOnly: List<CapabilityReport.ShellOnly> = emptyList(),
    write: CapabilityReport.WriteOutcome = CapabilityReport.WriteOutcome(
      ok = true,
      jsonPath = "/w/.pi-capabilities.json",
      markdownPath = "/w/.pi-capabilities.md",
      markdownWritten = true,
      failure = null,
    ),
    reach: CapabilityReport.Reach = CapabilityReport.Reach.unobserved,
  ) = CapabilityReport.Report(environment, exec, commands, shellOnly, write, reach)

  /** The reach the router reports: both the workspace and the app-private fallback are app storage. */
  private fun reachOf(
    shellRoots: List<String> = listOf("/data/user/0/dev.pi.mobile/files/workspace"),
    fileRoots: List<String> = shellRoots + "/data/user/0/dev.pi.mobile/cache/native-tmp",
    defaultRoot: String? = shellRoots.firstOrNull(),
  ) = CapabilityReport.Reach(
    observed = true,
    shellRoots = shellRoots,
    fileRoots = fileRoots,
    defaultRoot = defaultRoot,
    fallbackRoot = "/data/user/0/dev.pi.mobile/files/workspace",
    tempRoot = "/data/user/0/dev.pi.mobile/cache/native-tmp",
    source = "NativeIoRouter.allowedRoots, built as the runtime host builds it",
    failure = null,
  )

  /** A [CapabilityProbe.Probe] that never touches the device: it records what the runner asked for. */
  private inner class FakeProbe(
    private val writeFailure: String? = null,
    private val execCase: (String) -> CapabilityReport.ExecCase = { location ->
      CapabilityReport.ExecCase(location, CapabilityReport.Route.ok(), CapabilityReport.Route.ok())
    },
    private val commands: List<CapabilityReport.Command> = listOf(
      CapabilityReport.Command("curl", present = true, executable = true, version = "curl 7.67.0"),
    ),
    private val reach: CapabilityReport.Reach = CapabilityReport.Reach.unobserved,
  ) : CapabilityProbe.Probe {
    val writes = LinkedHashMap<String, String>()

    override fun environment() = this@CapabilityProbeTest.environment()

    override fun execCase(location: String, directory: File) = execCase(location)

    override fun commands() = commands

    override fun reach() = reach

    override fun write(target: File, content: String): String? {
      writeFailure?.let { return it }
      writes[target.path] = content
      return null
    }
  }
}

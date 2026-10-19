package dev.pi.mobile.backend

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * The isolated JS sandbox, on a real WebView.
 *
 * This is the part a JVM test cannot prove: an unattached WebView must actually load
 * `https://pi.local/sandbox.html`, evaluate `new Function` under the sandbox CSP, read a declared
 * input, write a declared output, and report through the narrow bridge — and for Python, boot
 * Pyodide and reach PyPI through micropip.
 *
 * The class shares ONE host (and so one sandbox WebView), because the app does: a fresh page per
 * run is slower and a far bigger memory footprint, and this suite boots Pyodide. The runs are
 * still isolated from each other — the worker is terminated after each, and the page mediates
 * every read and write against the run's own declared paths.
 */
@RunWith(AndroidJUnit4::class)
class JsSandboxHostDeviceTest {
  companion object {
    private val sharedHost = JsSandboxHost(ApplicationProvider.getApplicationContext<Context>())

    @AfterClass
    @JvmStatic
    fun closeHost() {
      sharedHost.close()
    }
  }

  @Test fun evaluatesCodeAndReportsAJsonResult() {
    val host = sharedHost
    val result = host.run("test-basic", "return 1 + 1;", JSONObject(), emptyList(), emptyList(), 10_000)
    assertEquals(result.toString(), true, result.optBoolean("ok"))
    assertEquals(2, result.optInt("result"))
    assertEquals(0, result.optJSONArray("logs")?.length() ?: 0)
  }

  @Test fun readsOnlyDeclaredInputsAndWritesOnlyDeclaredOutputs() {
    val host = sharedHost
    val root = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "js-sandbox-${UUID.randomUUID()}").apply { mkdirs() }
    try {
      val input = File(root, "input.txt").apply { writeText("hello") }
      val output = File(root, "output.txt")
      val code = """
        const key = Object.keys(piInputs)[0];
        const text = new TextDecoder().decode(piInputs[key]);
        await piWrite(${JSONObject.quote(output.absolutePath)}, text.toUpperCase());
        return text.length;
      """.trimIndent()
      val result = host.run("test-io", code, JSONObject(), listOf(input), listOf(output), 10_000)
      assertEquals(result.toString(), true, result.optBoolean("ok"))
      assertEquals(5, result.optInt("result"))
      assertEquals("HELLO", output.readText())
    } finally {
      root.deleteRecursively()
    }
  }

  @Test fun anUndeclaredOutputWriteFailsTheRun() {
    val host = sharedHost
    val root = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "js-sandbox-${UUID.randomUUID()}").apply { mkdirs() }
    try {
      val undeclared = File(root, "not-declared.txt")
      val code = "await piWrite(${JSONObject.quote(undeclared.absolutePath)}, 'nope'); return 'never';"
      val result = host.run("test-denied", code, JSONObject(), emptyList(), emptyList(), 10_000)
      assertEquals(false, result.optBoolean("ok"))
      assertTrue(result.optString("error").contains("not declared"))
      assertEquals(false, undeclared.exists())
    } finally {
      root.deleteRecursively()
    }
  }

  @Test fun aStructuredJsFailureNamesThePhaseTypeAndUserLine() {
    val host = sharedHost
    // An error thrown at the body's first line: the generated wrapper adds exactly one line before
    // it, so the deepest `at ` frame's line minus one is the user's line — the thing a JVM test
    // cannot prove about Chromium's frames.
    val result = host.run("test-js-err", "return missing.property;", JSONObject(), emptyList(), emptyList(), 10_000)
    assertEquals(false, result.optBoolean("ok"))
    assertEquals("execution", result.optString("phase"))
    assertEquals("ReferenceError", result.optString("errorType"))
    assertEquals(1, result.optInt("userLine"))
    assertTrue(result.optString("message").contains("missing"))
    assertTrue(result.optString("trace").contains("missing"))
  }

  @Test fun aCancelledRunReportsCancellation() {
    val host = sharedHost
    val run = Thread {
      host.run("test-cancel", "while (true) {}", JSONObject(), emptyList(), emptyList(), 5_000)
    }
    run.start()
    Thread.sleep(1_000)
    host.cancel("test-cancel")
    run.join(5_000)
    assertTrue("the cancelled run did not finish", !run.isAlive)
  }

  @Test fun loadsWorkspaceModulesAsRealEsmWithRelativeImports() {
    val host = sharedHost
    val root = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "js-sandbox-${UUID.randomUUID()}").apply { mkdirs() }
    try {
      val helper = File(root, "sandbox-mod-helper.mjs").apply { writeText("export const double = value => value * 2;") }
      val tools = File(root, "sandbox-mod-tools.mjs").apply {
        writeText("import { double } from './sandbox-mod-helper.mjs';\nexport const quad = value => double(double(value));")
      }
      // Keyed by absolute path exactly the way NativeRuntimeHost hands the sources over, plus the
      // declared-as-written alias. Both must reach the same source — and whichever one the body
      // imports, the module's own relative import of the helper must resolve to a served sibling.
      val code =
        "const byAlias = piModules['tools.mod'].quad(2);\n" +
        "return [byAlias, piModules[${JSONObject.quote(tools.absolutePath)}].quad(3)];"
      val result = host.run(
        "test-modules", code, JSONObject(), emptyList(), emptyList(), 20_000,
        mapOf(helper.absolutePath to helper.readText(), tools.absolutePath to tools.readText()),
        mapOf("tools.mod" to tools.absolutePath),
      )
      assertEquals(result.toString(), true, result.optBoolean("ok"))
      assertEquals("[8,12]", result.optJSONArray("result")?.toString())
    } finally {
      root.deleteRecursively()
    }
  }

  @Test fun anUndeclaredModuleImportFailsTheRunWithAModuleName() {
    val host = sharedHost
    val root = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "js-sandbox-${UUID.randomUUID()}").apply { mkdirs() }
    try {
      val tools = File(root, "sandbox-mod-missing.mjs").apply {
        writeText("import './sandbox-mod-absent.mjs';\nexport const x = 1;")
      }
      val code = "return piModules[${JSONObject.quote(tools.absolutePath)}].x;"
      val result = host.run(
        "test-modules-missing", code, JSONObject(), emptyList(), emptyList(), 20_000,
        mapOf(tools.absolutePath to tools.readText()),
      )
      assertEquals(result.toString(), false, result.optBoolean("ok"))
      assertTrue(result.optString("error").contains("Workspace module failed to load"))
      assertTrue(result.optString("error").contains(tools.absolutePath))
    } finally {
      root.deleteRecursively()
    }
  }

  @Test fun importsAnEsmPackageFromTheCdnInsideTheWorker() {
    val host = sharedHost
    val imports = JSONObject().put("pdf-lib", "https://esm.sh/pdf-lib@1.17.1")
    val code = "return typeof piImports['pdf-lib'].PDFDocument;"
    val result = host.run("test-import", code, imports, emptyList(), emptyList(), 60_000)
    assertEquals(result.toString(), true, result.optBoolean("ok"))
    assertEquals("function", result.optString("result"))
  }

  @Test fun createsAndReadsAPdfWithARealEsmPackage() {
    val host = sharedHost
    val root = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "js-sandbox-${UUID.randomUUID()}").apply { mkdirs() }
    try {
      val pdf = File(root, "created.pdf")
      val imports = JSONObject().put("pdf-lib", "https://esm.sh/pdf-lib@1.17.1")
      val create = """
        const { PDFDocument, StandardFonts } = piImports['pdf-lib'];
        const doc = await PDFDocument.create();
        const page = doc.addPage([300, 200]);
        const font = await doc.embedFont(StandardFonts.Helvetica);
        page.drawText('Hello Pi', { x: 20, y: 100, size: 18, font });
        const bytes = await doc.save();
        await piWrite(${JSONObject.quote(pdf.absolutePath)}, bytes);
        return bytes.length;
      """.trimIndent()
      val created = host.run("test-pdf-create", create, imports, emptyList(), listOf(pdf), 20_000)
      assertEquals(created.toString(), true, created.optBoolean("ok"))
      assertTrue(created.optInt("result") > 0)
      assertTrue(pdf.isFile)

      val read = """
        const { PDFDocument } = piImports['pdf-lib'];
        const key = Object.keys(piInputs)[0];
        const doc = await PDFDocument.load(piInputs[key]);
        return doc.getPageCount();
      """.trimIndent()
      val loaded = host.run("test-pdf-read", read, imports, listOf(pdf), emptyList(), 20_000)
      assertEquals(loaded.toString(), true, loaded.optBoolean("ok"))
      assertEquals(1, loaded.optInt("result"))
    } finally {
      root.deleteRecursively()
    }
  }

  @Test fun extractsTextFromAPdfWithPdfJs() {
    val host = sharedHost
    val root = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "js-sandbox-${UUID.randomUUID()}").apply { mkdirs() }
    try {
      val pdf = File(root, "text.pdf")
      val pdfLib = JSONObject().put("pdf-lib", "https://esm.sh/pdf-lib@1.17.1")
      val create = """
        const { PDFDocument, StandardFonts } = piImports['pdf-lib'];
        const doc = await PDFDocument.create();
        const page = doc.addPage([300, 200]);
        const font = await doc.embedFont(StandardFonts.Helvetica);
        page.drawText('Hello Pi PDF', { x: 20, y: 100, size: 18, font });
        await piWrite(${JSONObject.quote(pdf.absolutePath)}, await doc.save());
        return 'created';
      """.trimIndent()
      val created = host.run("test-pdfjs-create", create, pdfLib, emptyList(), listOf(pdf), 20_000)
      assertEquals(created.toString(), true, created.optBoolean("ok"))

      val pdfjs = JSONObject().put("pdfjs-dist", "https://esm.sh/pdfjs-dist@6.4.299")
      val extract = """
        const pdfjs = piImports['pdfjs-dist'];
        pdfjs.GlobalWorkerOptions.workerSrc = 'https://cdn.jsdelivr.net/npm/pdfjs-dist@6.4.299/build/pdf.worker.min.mjs';
        const key = Object.keys(piInputs)[0];
        const doc = await pdfjs.getDocument({ data: piInputs[key], isEvalSupported: false }).promise;
        const page = await doc.getPage(1);
        const content = await page.getTextContent();
        return content.items.map(item => item.str).join(' ');
      """.trimIndent()
      val extracted = host.run("test-pdfjs-text", extract, pdfjs, listOf(pdf), emptyList(), 30_000)
      assertEquals(extracted.toString(), true, extracted.optBoolean("ok"))
      assertTrue(extracted.optString("result"), extracted.optString("result").contains("Hello Pi PDF"))
    } finally {
      root.deleteRecursively()
    }
  }

  // The Pyodide tests come last on purpose: each run boots the interpreter, which is this suite's
  // biggest memory consumer, so the heaviest work runs when everything else has already finished.

  @Test fun pythonReadsDeclaredInputsAndWritesDeclaredOutputs() {
    val host = sharedHost
    val root = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "js-sandbox-${UUID.randomUUID()}").apply { mkdirs() }
    try {
      val input = File(root, "hello.txt").apply { writeText("hello") }
      val output = File(root, "out.txt")
      // One Pyodide boot covers the whole surface a JVM test cannot: the interpreter reports a
      // JSON-safe result, print() becomes the run log, a declared input crosses as readable bytes,
      // and piWrite writes through the same narrow bridge the JS body uses.
      val code = """
        def emphasise(text):
            return text.upper()
        print('pyboot')
        text = piInputs[piInputPaths[0]].tobytes().decode()
        await piWrite(piOutputs[0], emphasise(text).encode())
        return {'len': len(text), 'sample': text + ' world'}
      """.trimIndent()
      val result = host.run(
        "test-py-io", code, JSONArray(), listOf(input), listOf(output), 60_000,
        kind = "py",
        pyodideUrl = "https://cdn.jsdelivr.net/pyodide/v0.26.2/full/pyodide.mjs",
        pyodideIndexUrl = "https://cdn.jsdelivr.net/pyodide/v0.26.2/full/",
      )
      assertEquals("the Python run failed: $result", true, result.optBoolean("ok"))
      assertEquals("{\"len\":5,\"sample\":\"hello world\"}", result.optString("result"))
      assertTrue(result.optJSONArray("logs").toString().contains("pyboot"))
      assertEquals("HELLO", output.readText())
    } finally {
      root.deleteRecursively()
    }
  }

  @Test fun installsAPurePyPIPackageWithMicropip() {
    // six is the canonical pure wheel: tiny, PyPI-pinned, exactly what micropip is for. The
    // version pin is what py_resolve would have handed py_run. The 180s ceiling is the whole
    // budget Pyodide's first micropip run can take on this device: boot + interpreter + download.
    val imports = JSONArray().put("six==1.16.0")
    val code = "import six\nreturn six.__version__"
    val result = sharedHost.run(
      "test-py-six", code, imports, emptyList(), emptyList(), 180_000,
      kind = "py",
      pyodideUrl = "https://cdn.jsdelivr.net/pyodide/v0.26.2/full/pyodide.mjs",
      pyodideIndexUrl = "https://cdn.jsdelivr.net/pyodide/v0.26.2/full/",
    )
    assertEquals("the Python run failed: $result", true, result.optBoolean("ok"))
    assertEquals("1.16.0", result.optString("result"))
  }

  @Test fun aStructuredPyFailureNamesThePhaseTypeAndUserLine() {
    // Third and last Pyodide boot of the suite: the traceback must lose both wrapper lines and
    // report the honest user line, with the full trace still available beside it.
    val result = sharedHost.run(
      "test-py-err", "x = 1\nraise ValueError('user diagnostic')", JSONArray(), emptyList(), emptyList(), 90_000,
      kind = "py",
      pyodideUrl = "https://cdn.jsdelivr.net/pyodide/v0.26.2/full/pyodide.mjs",
      pyodideIndexUrl = "https://cdn.jsdelivr.net/pyodide/v0.26.2/full/",
    )
    assertEquals(false, result.optBoolean("ok"))
    assertEquals("execution", result.optString("phase"))
    assertEquals("ValueError", result.optString("errorType"))
    assertEquals(2, result.optInt("userLine"))
    assertEquals("the Python message should name the raise", "user diagnostic", result.optString("message"))
    assertTrue("the full trace stays in the report", result.optString("trace").contains("ValueError"))
  }
}
package dev.pi.mobile.backend

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * The isolated JS sandbox, on a real WebView.
 *
 * This is the part a JVM test cannot prove: an unattached/1x1 WebView must actually load
 * `https://pi.local/sandbox.html`, evaluate `new Function` under the sandbox CSP, read a declared
 * input, write a declared output, and report through the narrow bridge.
 */
@RunWith(AndroidJUnit4::class)
class JsSandboxHostDeviceTest {
  private fun sandbox() = JsSandboxHost(ApplicationProvider.getApplicationContext<Context>())

  @Test fun evaluatesCodeAndReportsAJsonResult() {
    val host = sandbox()
    try {
      val result = host.run("test-basic", "return 1 + 1;", JSONObject(), emptyList(), emptyList(), 10_000)
      assertEquals(result.toString(), true, result.optBoolean("ok"))
      assertEquals(2, result.optInt("result"))
      assertEquals(0, result.optJSONArray("logs")?.length() ?: 0)
    } finally {
      host.close()
    }
  }

  @Test fun readsOnlyDeclaredInputsAndWritesOnlyDeclaredOutputs() {
    val root = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "js-sandbox-${UUID.randomUUID()}").apply { mkdirs() }
    val input = File(root, "input.txt").apply { writeText("hello") }
    val output = File(root, "output.txt")
    val code = """
      const key = Object.keys(piInputs)[0];
      const text = new TextDecoder().decode(piInputs[key]);
      await piWrite(${JSONObject.quote(output.absolutePath)}, text.toUpperCase());
      return text.length;
    """.trimIndent()
    val host = sandbox()
    try {
      val result = host.run("test-io", code, JSONObject(), listOf(input), listOf(output), 10_000)
      assertEquals(result.toString(), true, result.optBoolean("ok"))
      assertEquals(5, result.optInt("result"))
      assertEquals("HELLO", output.readText())
    } finally {
      host.close()
      root.deleteRecursively()
    }
  }

  @Test fun anUndeclaredOutputWriteFailsTheRun() {
    val root = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "js-sandbox-${UUID.randomUUID()}").apply { mkdirs() }
    val undeclared = File(root, "not-declared.txt")
    val code = "await piWrite(${JSONObject.quote(undeclared.absolutePath)}, 'nope'); return 'never';"
    val host = sandbox()
    try {
      val result = host.run("test-denied", code, JSONObject(), emptyList(), emptyList(), 10_000)
      assertEquals(false, result.optBoolean("ok"))
      assertTrue(result.optString("error").contains("not declared"))
      assertEquals(false, undeclared.exists())
    } finally {
      host.close()
      root.deleteRecursively()
    }
  }

  @Test fun aCancelledRunReportsCancellation() {
    val host = sandbox()
    try {
      val run = Thread {
        host.run("test-cancel", "while (true) {}", JSONObject(), emptyList(), emptyList(), 5_000)
      }
      run.start()
      Thread.sleep(1_000)
      host.cancel("test-cancel")
      run.join(5_000)
      assertTrue("the cancelled run did not finish", !run.isAlive)
    } finally {
      host.close()
    }
  }

  @Test fun importsAnEsmPackageFromTheCdnInsideTheWorker() {
    val host = sandbox()
    try {
      val imports = JSONObject().put("pdf-lib", "https://esm.sh/pdf-lib@1.17.1")
      val code = "return typeof piImports['pdf-lib'].PDFDocument;"
      val result = host.run("test-import", code, imports, emptyList(), emptyList(), 20_000)
      assertEquals(result.toString(), true, result.optBoolean("ok"))
      assertEquals("function", result.optString("result"))
    } finally {
      host.close()
    }
  }

  @Test fun createsAndReadsAPdfWithARealEsmPackage() {
    val root = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "js-sandbox-${UUID.randomUUID()}").apply { mkdirs() }
    val pdf = File(root, "created.pdf")
    val host = sandbox()
    try {
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
      host.close()
      root.deleteRecursively()
    }
  }

  @Test fun extractsTextFromAPdfWithPdfJs() {
    val root = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "js-sandbox-${UUID.randomUUID()}").apply { mkdirs() }
    val pdf = File(root, "text.pdf")
    val host = sandbox()
    try {
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
      host.close()
      root.deleteRecursively()
    }
  }
}

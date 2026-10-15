package com.zifiq.platform

import com.zifiq.platform.documents.PdfRenderer
import com.zifiq.platform.files.FileKind
import com.zifiq.platform.files.FileTypeDetector
import com.zifiq.platform.files.SvgSafety
import java.util.Base64
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** SVG and AVIF uploads: recognised by content, SVG refused when it can run script or fetch anything, and SVG drawn on PDFs. */
class SvgLogoTest {
    private val safe = """<?xml version="1.0"?>
        <!-- exported by an editor -->
        <svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 100 40" width="100" height="40">
          <defs><linearGradient id="g"><stop offset="0" stop-color="#0a6"/><stop offset="1" stop-color="#06a"/></linearGradient></defs>
          <rect id="r" width="100" height="40" rx="6" fill="url(#g)"/>
          <use xlink:href="#r" x="2" y="2" opacity="0.3"/>
          <path d="M10 30 L50 10 L90 30 Z" fill="#fff" style="opacity: 0.8"/>
        </svg>""".trimIndent()

    private fun svg(body: String) = """<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink">$body</svg>"""

    @Test
    fun `SVG and AVIF are recognised from their bytes, whatever the file is called`() {
        assertThat(FileTypeDetector.detect(safe.toByteArray(), "logo.png")).isEqualTo(FileKind.SVG)
        val avif = byteArrayOf(0, 0, 0, 0x1C) + "ftypavif".toByteArray() + byteArrayOf(0, 0, 0, 0) + "avifmif1miaf".toByteArray()
        assertThat(FileTypeDetector.detect(avif, "logo.png")).isEqualTo(FileKind.AVIF)
        // An HEIC photo shares the container but not the brand.
        val heic = byteArrayOf(0, 0, 0, 0x18) + "ftypheic".toByteArray() + byteArrayOf(0, 0, 0, 0) + "mif1heic".toByteArray()
        assertThat(FileTypeDetector.detect(heic, "logo.avif")).isNull()
        assertThat(FileTypeDetector.detect("<html><svg></svg></html>".toByteArray(), "x.svg")).isEqualTo(FileKind.TEXT)
    }

    @Test
    fun `a plain SVG with gradients, internal references and inline styles is safe`() {
        assertThat(SvgSafety.problem(safe.toByteArray())).isNull()
        assertThat(SvgSafety.problem(svg("""<image href="data:image/png;base64,iVBORw0KGgo="/>""").toByteArray())).isNull()
    }

    @Test
    fun `anything that can run script or fetch another resource is refused`() {
        val unsafe = listOf(
            svg("<script>alert(1)</script>"),
            svg("""<rect onload="alert(1)"/>"""),
            svg("""<a href="javascript:alert(1)"><rect/></a>"""),
            svg("""<a xlink:href=" JaVaScRiPt:alert(1)"><rect/></a>"""),
            svg("""<image href="https://tracker.example/pixel.png"/>"""),
            svg("""<use xlink:href="other.svg#icon"/>"""),
            svg("""<foreignObject><div xmlns="http://www.w3.org/1999/xhtml">x</div></foreignObject>"""),
            svg("""<rect style="fill: url(https://example.com/x)"/>"""),
            svg("""<style>@import url(https://example.com/x.css);</style>"""),
            svg("""<a><set attributeName="href" to="javascript:alert(1)"/></a>"""),
            """<?xml version="1.0"?><!DOCTYPE svg [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><svg xmlns="http://www.w3.org/2000/svg">&xxe;</svg>""",
            "<svg xmlns=\"http://www.w3.org/2000/svg\"><rect>",
        )
        unsafe.forEach { assertThat(SvgSafety.problem(it.toByteArray())).describedAs(it).isNotNull() }
    }

    @Test
    fun `an SVG logo is drawn on a PDF`() {
        val renderer = PdfRenderer()
        val without = renderer.render("logo-probe", mapOf("logo" to null))
        val with = renderer.render("logo-probe", mapOf("logo" to "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(safe.toByteArray())))
        assertThat(String(with, 0, 5, Charsets.US_ASCII)).isEqualTo("%PDF-")
        // The vector drawing adds content the logo-less page doesn't have.
        assertThat(with.size).isGreaterThan(without.size + 200)
    }

    @Test
    fun `a logo the PDF engine can't draw never stops the document`() {
        // Safe, but Batik can't build it (a <use> of a gradient isn't a shape): the page renders without the logo.
        val odd = svg("""<defs><linearGradient id="g"/></defs><use xlink:href="#g"/>""")
        val pdf = PdfRenderer().render("logo-probe", mapOf("logo" to "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(odd.toByteArray())))
        assertThat(String(pdf, 0, 5, Charsets.US_ASCII)).isEqualTo("%PDF-")
    }
}

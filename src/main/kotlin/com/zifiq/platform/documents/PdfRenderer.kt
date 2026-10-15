package com.zifiq.platform.documents

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder
import com.openhtmltopdf.svgsupport.BatikSVGDrawer
import java.io.ByteArrayOutputStream
import java.util.Locale
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.thymeleaf.TemplateEngine
import org.thymeleaf.context.Context
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver

/**
 * The single entry point for PDF documents (docs/03 §8.3): a Thymeleaf template under
 * `resources/templates/pdf/<name>.html`, rendered to PDF with OpenHTMLtoPDF. Templates are XHTML
 * and rendered in XML mode so the output stays well-formed for the PDF engine; `th:text` escapes
 * every value. Fonts are bundled (Noto Sans, which has the naira sign) so output doesn't depend on
 * the host. PDFs are rendered on demand, never stored: the documents they show are immutable.
 */
@Component
class PdfRenderer {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val engine = TemplateEngine().apply {
        setTemplateResolver(
            ClassLoaderTemplateResolver().apply {
                prefix = "templates/pdf/"
                suffix = ".html"
                templateMode = TemplateMode.XML
                characterEncoding = "UTF-8"
                isCacheable = true
            },
        )
    }

    /**
     * A document never fails because of its logo: if drawing fails with one (an SVG Batik can't
     * render), it is rendered again without it and the failure is logged.
     */
    fun render(templateName: String, model: Map<String, Any?>): ByteArray =
        runCatching { draw(templateName, model) }.getOrElse { failure ->
            if (model["logo"] == null) throw failure
            logger.warn("Rendering {} with the company logo failed ({}); rendering it without the logo", templateName, failure.message)
            draw(templateName, model + ("logo" to null))
        }

    private fun draw(templateName: String, model: Map<String, Any?>): ByteArray {
        val html = engine.process(templateName, Context(Locale.UK, model))
        val out = ByteArrayOutputStream()
        PdfRendererBuilder()
            .useFastMode()
            // SVG images (an SVG company logo) are drawn as vectors.
            .useSVGDrawer(BatikSVGDrawer())
            .withHtmlContent(html, PdfRenderer::class.java.getResource("/templates/pdf/")?.toExternalForm())
            .useFont({ font("NotoSans-Regular.ttf") }, FONT_FAMILY, 400, BaseRendererBuilder.FontStyle.NORMAL, true)
            .useFont({ font("NotoSans-Bold.ttf") }, FONT_FAMILY, 700, BaseRendererBuilder.FontStyle.NORMAL, true)
            .toStream(out)
            .run()
        return out.toByteArray()
    }

    private fun font(file: String) =
        PdfRenderer::class.java.getResourceAsStream("/fonts/$file") ?: error("Bundled font /fonts/$file is missing")

    companion object {
        /** Use this family in templates: `font-family: 'Noto Sans'`. */
        const val FONT_FAMILY = "Noto Sans"
    }
}

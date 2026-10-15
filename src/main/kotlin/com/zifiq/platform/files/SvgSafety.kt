package com.zifiq.platform.files

import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * SVG is XML that browsers execute: it can carry `<script>`, `on*` event handlers, `javascript:`
 * links, embedded HTML (`<foreignObject>`) and references that make the viewer fetch other URLs.
 * An uploaded SVG is accepted only when it has none of these — refused, not rewritten, so what is
 * stored is exactly what the user sent. Parsing is hardened: no DOCTYPE, no entities, no external
 * resolution (XXE).
 */
object SvgSafety {
    /** Null when the SVG is safe to store and serve; otherwise why it was refused. */
    fun problem(bytes: ByteArray): String? {
        val document = runCatching { parser().parse(ByteArrayInputStream(bytes)) }.getOrElse {
            return if (it.message?.contains("DOCTYPE", ignoreCase = true) == true) "SVG files with a DOCTYPE aren't accepted; export it again without one"
            else "This SVG isn't well-formed XML"
        }
        val root = document.documentElement
        if (root.localName?.lowercase() != "svg") return "The file's root element isn't <svg>"
        return problemIn(root)
    }

    private fun problemIn(element: Element): String? {
        val name = (element.localName ?: element.nodeName).lowercase()
        if (name in FORBIDDEN_ELEMENTS) return "SVG <$name> elements aren't accepted"
        if (name in ANIMATIONS && element.getAttribute("attributeName").lowercase().substringAfter(':') in LINK_ATTRIBUTES) {
            return "SVG animations that change links aren't accepted"
        }
        val attributes = element.attributes
        for (i in 0 until attributes.length) {
            val attribute = attributes.item(i)
            val attributeName = (attribute.localName ?: attribute.nodeName).lowercase()
            val value = attribute.nodeValue.filterNot { it.isWhitespace() || it.code < 32 }.lowercase()
            when {
                attributeName.startsWith("on") -> return "SVG event handlers (${attribute.nodeName}) aren't accepted"
                attributeName in LINK_ATTRIBUTES && !isInternal(value) -> return "SVG links to other files or pages aren't accepted"
                attributeName == "style" && unsafeCss(value) -> return "SVG styles that load other resources aren't accepted"
                "javascript:" in value -> return "SVG attributes containing javascript: aren't accepted"
            }
        }
        if (name == "style" && unsafeCss(element.textContent.filterNot { it.isWhitespace() }.lowercase())) {
            return "SVG styles that load other resources aren't accepted"
        }
        val children = element.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType == Node.ELEMENT_NODE) problemIn(child as Element)?.let { return it }
        }
        return null
    }

    /** A fragment in the same file (`#gradient`) or an embedded raster image. */
    private fun isInternal(value: String) = value.startsWith("#") || EMBEDDED_IMAGE.matches(value)

    /** `url(#id)` is a reference within the file; any other url(), @import or script is not. */
    private fun unsafeCss(css: String) = "@import" in css || "javascript:" in css || "expression(" in css ||
        URL.findAll(css).any { !isInternal(it.groupValues[1].trim('\'', '"')) }

    private fun parser() = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isXIncludeAware = false
        isExpandEntityReferences = false
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    }.newDocumentBuilder().apply { setErrorHandler(null) }

    private val FORBIDDEN_ELEMENTS = setOf("script", "foreignobject", "iframe", "embed", "object", "handler", "listener", "audio", "video")
    private val ANIMATIONS = setOf("set", "animate", "animatemotion", "animatetransform")
    private val LINK_ATTRIBUTES = setOf("href", "src")
    private val EMBEDDED_IMAGE = Regex("^data:image/(png|jpeg|gif|webp);base64,[a-z0-9+/=]*$")
    private val URL = Regex("""url\(([^)]*)\)""")
}

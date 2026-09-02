package dev.kstep.render

import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Shared, hardened XML parsing for this module's own tests -- every test in
 * `kstep-render/src/test` that parses a kSTEP-generated SVG string just to confirm it is
 * well-formed XML goes through this, rather than each test file hand-rolling its own
 * `DocumentBuilderFactory.newInstance()`.
 *
 * A plain `DocumentBuilderFactory.newInstance()` resolves DTDs and external entities by default.
 * That is harmless today -- this module's generated SVG never contains a `<!DOCTYPE ...>` -- but
 * these `parseXml`-style checks exist specifically to be the guard that catches an
 * [dev.kstep.render.svg.SvgEscaping] regression (a model string reaching the SVG unescaped). An
 * unhardened parser is the wrong guard for exactly that job: if a regression ever let a value like
 * `<!DOCTYPE x SYSTEM "file:///etc/passwd">` or an `http://`-SYSTEM-ID reach a rendered card, this
 * parser would silently resolve the external entity itself (a local file read, or an outbound
 * network call from CI) instead of surfacing the escaping regression it is meant to detect --
 * turning the detector into the vector.
 */
internal fun parseXmlSecurely(xml: String) {
    val factory = DocumentBuilderFactory.newInstance()
    factory.isNamespaceAware = true
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    factory.newDocumentBuilder().parse(xml.byteInputStream(Charsets.UTF_8))
}

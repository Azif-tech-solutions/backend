package com.zifiq.barcode.service

/**
 * Page/label CSS for the label PDF, and Zebra ZPL for thermal printers (docs/11 §4.5). The single
 * sizes print one label per page sized to the stock; A4_24 is a 3×8 grid of 70×37 mm on A4.
 */
object LabelLayout {
    fun css(size: String): String {
        val base = """
            body { margin: 0; font-family: 'Noto Sans', sans-serif; color: #000; }
            .name { font-weight: 700; overflow: hidden; }
            .sub { color: #222; }
            .price { font-weight: 700; }
            img.sym { display: block; margin: 0 auto; }
        """.trimIndent()
        return base + "\n" + when (size) {
            "40x25" -> """
                @page { size: 40mm 25mm; margin: 1.5mm; }
                .label { width: 37mm; height: 22mm; page-break-after: always; text-align: center; overflow: hidden; }
                .name { font-size: 6pt; height: 7mm; }
                img.sym { height: 9mm; max-width: 36mm; }
                .sub { font-size: 5pt; }
                .price { font-size: 7pt; }
            """.trimIndent()
            "100x50" -> """
                @page { size: 100mm 50mm; margin: 3mm; }
                .label { width: 94mm; height: 44mm; page-break-after: always; text-align: center; overflow: hidden; }
                .name { font-size: 12pt; height: 14mm; }
                img.sym { height: 20mm; max-width: 90mm; }
                .sub { font-size: 9pt; }
                .price { font-size: 13pt; }
            """.trimIndent()
            "A4_24" -> """
                @page { size: A4; margin: 10mm 5mm; }
                .label { display: inline-block; width: 70mm; height: 37mm; text-align: center; overflow: hidden; vertical-align: top; }
                .name { font-size: 8pt; height: 10mm; }
                img.sym { height: 14mm; max-width: 66mm; }
                .sub { font-size: 7pt; }
                .price { font-size: 9pt; }
            """.trimIndent()
            else -> """
                @page { size: 58mm 40mm; margin: 2mm; }
                .label { width: 54mm; height: 36mm; page-break-after: always; text-align: center; overflow: hidden; }
                .name { font-size: 8pt; height: 11mm; }
                img.sym { height: 14mm; max-width: 52mm; }
                .sub { font-size: 7pt; }
                .price { font-size: 9pt; }
            """.trimIndent()
        }
    }

    /** One Zebra label (203 dpi). ^FD fields are sanitised of ZPL control characters. */
    fun zpl(size: String, name: String, code: String, symbology: String, unit: String, priceText: String?): String {
        val dots = when (size) { "40x25" -> 320 to 200; "100x50" -> 800 to 400; "A4_24" -> 560 to 296; else -> 464 to 320 }
        val n = sanitise(name)
        val sub = sanitise("$code · $unit")
        val barcode = when (symbology) {
            "QR" -> "^FO40,70^BQN,2,6^FDLA,${sanitise(code)}^FS"
            "CODE_128" -> "^FO30,70^BCN,110,Y,N,N^FD${sanitise(code)}^FS"
            "EAN_8", "UPC_A", "EAN_13" -> "^FO40,70^BEN,110,Y,N^FD${sanitise(code)}^FS"
            else -> "^FO30,70^BCN,110,Y,N,N^FD${sanitise(code)}^FS"
        }
        val price = priceText?.let { "^FO20,${dots.second - 36}^A0N,28,28^FD${sanitise(it)}^FS" } ?: ""
        return "^XA^PW${dots.first}^LL${dots.second}^CI28" +
            "^FO20,16^A0N,26,26^FB${dots.first - 40},2,0,L^FD$n^FS" +
            barcode +
            "^FO20,${dots.second - 64}^A0N,22,22^FD$sub^FS" +
            price +
            "^XZ\n"
    }

    private fun sanitise(s: String): String = s.replace(Regex("[\\^~]"), " ").take(120)
}

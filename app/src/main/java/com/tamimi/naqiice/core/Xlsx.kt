package com.tamimi.naqiice.core

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Minimal single-sheet .xlsx writer matching the web export (src/lib/export.ts): a
 * right-to-left sheet, bold header row with a filter, 24-wide right-aligned wrapped
 * columns, numbers as numeric cells and text as inline strings.
 */
object Xlsx {
    const val MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    private const val HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
    private const val MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val PKG_REL = "http://schemas.openxmlformats.org/package/2006/relationships"

    /** Excel limits sheet names to 31 characters without \ / * ? : [ ]. */
    fun sheetName(title: String): String = title.take(31).replace(Regex("[\\\\/*?:\\[\\]]"), " ").ifBlank { "Sheet1" }

    private fun escape(text: String): String {
        val sb = StringBuilder(text.length + 8)
        for (ch in text) when {
            ch == '&' -> sb.append("&amp;")
            ch == '<' -> sb.append("&lt;")
            ch == '>' -> sb.append("&gt;")
            ch == '"' -> sb.append("&quot;")
            // Control characters are not valid XML 1.0 and would make Excel reject the file.
            ch < ' ' && ch != '\t' && ch != '\n' && ch != '\r' -> {}
            ch == '￾' || ch == '￿' -> {}
            else -> sb.append(ch)
        }
        return sb.toString()
    }

    fun column(index: Int): String {
        var n = index + 1
        val sb = StringBuilder()
        while (n > 0) { sb.insert(0, 'A' + (n - 1) % 26); n = (n - 1) / 26 }
        return sb.toString()
    }

    private fun number(value: Double): String =
        if (value == Math.rint(value) && kotlin.math.abs(value) < 9e15) value.toLong().toString() else value.toString()

    private fun cell(ref: String, value: Any?, style: Int): String = when {
        value == null || value == "" -> ""
        value is Number && value.toDouble().isFinite() -> "<c r=\"$ref\" s=\"$style\"><v>${number(value.toDouble())}</v></c>"
        else -> {
            val text = value.toString()
            // A leading = + @ - would be run as a formula by spreadsheet apps.
            val safe = if (text.first() in "=+@-") "'$text" else text
            "<c r=\"$ref\" s=\"$style\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${escape(safe)}</t></is></c>"
        }
    }

    fun sheetXml(table: ExportTable): String {
        val columns = maxOf(1, table.headers.size, table.rows.maxOfOrNull { it.size } ?: 0)
        val sb = StringBuilder(HEAD)
        sb.append("<worksheet xmlns=\"$MAIN\">")
        sb.append("<dimension ref=\"A1:${column(columns - 1)}${table.rows.size + 1}\"/>")
        sb.append("<sheetViews><sheetView rightToLeft=\"1\" workbookViewId=\"0\"/></sheetViews>")
        sb.append("<sheetFormatPr defaultRowHeight=\"15\"/>")
        sb.append("<cols><col min=\"1\" max=\"$columns\" width=\"24\" customWidth=\"1\" style=\"1\"/></cols>")
        sb.append("<sheetData>")
        (listOf(table.headers) + table.rows).forEachIndexed { r, row ->
            sb.append("<row r=\"${r + 1}\">")
            row.forEachIndexed { c, value -> sb.append(cell("${column(c)}${r + 1}", value, if (r == 0) 2 else 1)) }
            sb.append("</row>")
        }
        sb.append("</sheetData>")
        if (table.headers.isNotEmpty()) sb.append("<autoFilter ref=\"A1:${column(table.headers.size - 1)}1\"/>")
        sb.append("</worksheet>")
        return sb.toString()
    }

    private const val STYLES = "<styleSheet xmlns=\"$MAIN\">" +
        "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>" +
        "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>" +
        "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
        "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
        "<cellXfs count=\"3\">" +
        "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
        "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyAlignment=\"1\"><alignment horizontal=\"right\" wrapText=\"1\"/></xf>" +
        "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyAlignment=\"1\"><alignment horizontal=\"right\" wrapText=\"1\"/></xf>" +
        "</cellXfs></styleSheet>"

    fun write(table: ExportTable, out: OutputStream) {
        val parts = listOf(
            "[Content_Types].xml" to "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
                "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
                "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
                "</Types>",
            "_rels/.rels" to "<Relationships xmlns=\"$PKG_REL\"><Relationship Id=\"rId1\" Type=\"$REL/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>",
            "xl/workbook.xml" to "<workbook xmlns=\"$MAIN\" xmlns:r=\"$REL\"><sheets><sheet name=\"${escape(sheetName(table.title))}\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>",
            "xl/_rels/workbook.xml.rels" to "<Relationships xmlns=\"$PKG_REL\">" +
                "<Relationship Id=\"rId1\" Type=\"$REL/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
                "<Relationship Id=\"rId2\" Type=\"$REL/styles\" Target=\"styles.xml\"/></Relationships>",
            "xl/styles.xml" to STYLES,
        ).map { it.first to HEAD + it.second } + ("xl/worksheets/sheet1.xml" to sheetXml(table))
        ZipOutputStream(out).use { zip ->
            for ((name, xml) in parts) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(xml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }
}

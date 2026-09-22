package com.example.yasinreel.deck

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a real `.pptx`, using nothing but `java.util.zip` and string building.
 *
 * A .pptx is a ZIP of XML parts with a fixed relationship graph. The alternative was
 * Apache POI, which is roughly 20 MB of `poi-ooxml` plus `xmlbeans` plus four commons
 * libraries, a slow schema load on first use, and a genuine risk of a classloader fight
 * with the IntelliJ platform, which ships its own copies of several of those. That risk
 * arrives at demo time, which is the worst moment available.
 *
 * It is also simply how this codebase already solves things: the reel serves itself from
 * a fifty line HttpServer rather than taking a web framework, and vendors GSAP rather
 * than fetching it.
 *
 * None of that would have been worth the risk on an assumption, so the format was proved
 * first: a throwaway generator wrote a two slide package with the standard library alone
 * and a real office suite opened it and rendered it exactly as specified.
 *
 * Geometry arrives already decided, as [Shape]s in pixels. This file converts and nothing
 * else, which is what keeps it and the preview from ever disagreeing about a slide.
 */
object PptxWriter {

    private val logger = Logger.getInstance(PptxWriter::class.java)

    /** 914400 EMU to the inch, 96 pixels to the inch, so a pixel is exactly this. */
    private const val EMU = 9525

    private const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val NS =
        "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
            "xmlns:r=\"$REL\" " +
            "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\""

    private const val DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"

    /**
     * @param slides the finished art, one entry per slide
     * @param icons the PNG bytes for every icon any slide referenced, by name
     * @param target the file to write, which is created or replaced
     */
    fun write(
        slides: List<SlideArt>,
        icons: Map<String, ByteArray>,
        title: String,
        author: String,
        target: File
    ): File {
        if (slides.isEmpty()) throw IOException("A deck needs at least one slide.")
        target.parentFile?.mkdirs()

        // Only the icons actually placed are embedded. A deck that carries sixteen PNGs
        // it never draws is a deck that is three times bigger than it needs to be.
        val used = LinkedHashSet<String>()
        slides.forEach { slide -> slide.shapes.filterIsInstance<Pic>().forEach { used.add(it.icon) } }
        val media = used.filter { icons.containsKey(it) }.sorted()
        val mediaIndex = media.withIndex().associate { (i, name) -> name to i + 1 }

        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            fun put(path: String, body: String) = put(zip, path, body.toByteArray(StandardCharsets.UTF_8))

            put("[Content_Types].xml", contentTypes(slides.size))
            put("_rels/.rels", rels(
                relationship(1, "officeDocument", "ppt/presentation.xml"),
                relationship(2, "metadata/core-properties", "docProps/core.xml"),
                relationship(3, "extended-properties", "docProps/app.xml")
            ))
            put("docProps/core.xml", core(title, author))
            put("docProps/app.xml", app(slides))

            put("ppt/presentation.xml", presentation(slides.size))
            put("ppt/_rels/presentation.xml.rels", presentationRels(slides.size))
            put("ppt/theme/theme1.xml", theme("Nexus"))
            put("ppt/theme/theme2.xml", theme("Nexus Notes"))
            put("ppt/slideMasters/slideMaster1.xml", slideMaster())
            put("ppt/slideMasters/_rels/slideMaster1.xml.rels", rels(
                relationship(1, "slideLayout", "../slideLayouts/slideLayout1.xml"),
                relationship(2, "theme", "../theme/theme1.xml")
            ))
            put("ppt/slideLayouts/slideLayout1.xml", slideLayout())
            put("ppt/slideLayouts/_rels/slideLayout1.xml.rels", rels(
                relationship(1, "slideMaster", "../slideMasters/slideMaster1.xml")
            ))
            put("ppt/notesMasters/notesMaster1.xml", notesMaster())
            put("ppt/notesMasters/_rels/notesMaster1.xml.rels", rels(
                relationship(1, "theme", "../theme/theme2.xml")
            ))

            media.forEach { name ->
                put(zip, "ppt/media/image${mediaIndex[name]}.png", icons.getValue(name))
            }

            slides.forEachIndexed { index, slide ->
                val n = index + 1
                val onThisSlide = slide.shapes.filterIsInstance<Pic>().map { it.icon }.distinct()
                    .filter { mediaIndex.containsKey(it) }
                put("ppt/slides/slide$n.xml", slideXml(slide, onThisSlide))

                // rId1 is the layout and rId2 the notes on every slide, so the image
                // relationships start at 3 and the slide XML can count on it.
                val links = mutableListOf(
                    relationship(1, "slideLayout", "../slideLayouts/slideLayout1.xml"),
                    relationship(2, "notesSlide", "../notesSlides/notesSlide$n.xml")
                )
                onThisSlide.forEachIndexed { i, name ->
                    links.add(relationship(3 + i, "image", "../media/image${mediaIndex[name]}.png"))
                }
                put("ppt/slides/_rels/slide$n.xml.rels", rels(*links.toTypedArray()))

                put("ppt/notesSlides/notesSlide$n.xml", notesSlide(slide.notes))
                put("ppt/notesSlides/_rels/notesSlide$n.xml.rels", rels(
                    relationship(1, "notesMaster", "../notesMasters/notesMaster1.xml"),
                    relationship(2, "slide", "../slides/slide$n.xml")
                ))
            }
        }

        logger.info("Nexus Deck wrote ${slides.size} slides to ${target.absolutePath} (${target.length() / 1024}kB)")
        return target
    }

    private fun put(zip: ZipOutputStream, path: String, body: ByteArray) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(body)
        zip.closeEntry()
    }

    // ------------------------------------------------------------------ shapes

    private fun slideXml(slide: SlideArt, iconsOnSlide: List<String>): String {
        val body = StringBuilder()
        // Shape ids start at 2 because the group shape that wraps a slide's tree owns 1.
        var id = 2
        for (shape in slide.shapes) {
            body.append(
                when (shape) {
                    is Box -> boxXml(id, shape)
                    is Label -> labelXml(id, shape)
                    is Pic -> picXml(id, shape, 3 + iconsOnSlide.indexOf(shape.icon))
                }
            )
            id++
        }
        return DECL +
            "<p:sld $NS><p:cSld>" +
            "<p:bg><p:bgPr><a:solidFill><a:srgbClr val=\"${DeckTheme.PAPER}\"/></a:solidFill>" +
            "<a:effectLst/></p:bgPr></p:bg>" +
            "<p:spTree>${treeHeader()}$body</p:spTree></p:cSld>" +
            // The override is what stops a slide inheriting a colour map that would
            // reinterpret every srgbClr on it.
            "<p:clrMapOvr><a:overrideClrMapping " + CLR_MAP_ATTRS + "/></p:clrMapOvr></p:sld>"
    }

    private fun treeHeader(): String =
        "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>" +
            "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/>" +
            "<a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>"

    private fun xfrm(s: Shape): String =
        "<a:xfrm><a:off x=\"${s.x * EMU}\" y=\"${s.y * EMU}\"/>" +
            "<a:ext cx=\"${maxOf(s.w, 1) * EMU}\" cy=\"${maxOf(s.h, 1) * EMU}\"/></a:xfrm>"

    private fun boxXml(id: Int, box: Box): String {
        val geom = if (box.roundPct > 0) {
            "<a:prstGeom prst=\"roundRect\"><a:avLst>" +
                "<a:gd name=\"adj\" fmla=\"val ${box.roundPct * 1000}\"/></a:avLst></a:prstGeom>"
        } else {
            "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>"
        }
        val line = if (box.stroke != null) {
            "<a:ln w=\"${box.strokeWidth * EMU}\"><a:solidFill>" +
                "<a:srgbClr val=\"${box.stroke}\"/></a:solidFill></a:ln>"
        } else {
            "<a:ln><a:noFill/></a:ln>"
        }
        return "<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"box$id\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>" +
            "<p:spPr>${xfrm(box)}$geom" +
            "<a:solidFill><a:srgbClr val=\"${box.fill}\"/></a:solidFill>$line</p:spPr>" +
            "<p:txBody><a:bodyPr/><a:lstStyle/><a:p/></p:txBody></p:sp>"
    }

    private fun labelXml(id: Int, label: Label): String {
        val anchor = when (label.anchor) {
            Anchor.TOP -> "t"
            Anchor.MIDDLE -> "ctr"
            Anchor.BOTTOM -> "b"
        }
        val align = when (label.align) {
            Align.LEFT -> "l"
            Align.CENTER -> "ctr"
            Align.RIGHT -> "r"
        }
        val face = if (label.mono) DeckTheme.MONO else DeckTheme.FONT
        // Hundredths of a point, and a pixel is three quarters of a point at 96 dpi.
        val size = (label.sizePx * 75).coerceAtLeast(100)
        val paragraphs = StringBuilder()
        label.lines.forEachIndexed { i, line ->
            val before = if (i > 0 && label.gapPx > 0) "<a:spcBef><a:spcPts val=\"${label.gapPx * 75}\"/></a:spcBef>" else ""
            paragraphs.append(
                "<a:p><a:pPr algn=\"$align\" indent=\"0\" marL=\"0\">" +
                    "<a:lnSpc><a:spcPct val=\"${label.linePct * 1000}\"/></a:lnSpc>$before" +
                    "<a:buNone/></a:pPr>" +
                    "<a:r><a:rPr lang=\"en-US\" sz=\"$size\" b=\"${if (label.bold) 1 else 0}\"" +
                    " spc=\"${label.spacing}\" dirty=\"0\">" +
                    "<a:solidFill><a:srgbClr val=\"${label.color}\"/></a:solidFill>" +
                    "<a:latin typeface=\"$face\"/><a:cs typeface=\"$face\"/></a:rPr>" +
                    "<a:t>${escape(line)}</a:t></a:r></a:p>"
            )
        }
        return "<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"text$id\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr>" +
            "<p:spPr>${xfrm(label)}<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>" +
            "<a:noFill/></p:spPr>" +
            "<p:txBody><a:bodyPr wrap=\"square\" lIns=\"0\" tIns=\"0\" rIns=\"0\" bIns=\"0\"" +
            " rtlCol=\"0\" anchor=\"$anchor\">" +
            // The last line of defence on text length. Everything is measured and capped
            // before it gets here, but a reader that disagrees with the metric table by a
            // few percent should shrink the text rather than let it run off the slide.
            "<a:normAutofit/></a:bodyPr><a:lstStyle/>$paragraphs</p:txBody></p:sp>"
    }

    private fun picXml(id: Int, pic: Pic, relId: Int): String =
        "<p:pic><p:nvPicPr><p:cNvPr id=\"$id\" name=\"${escape(pic.icon)}\"/>" +
            "<p:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></p:cNvPicPr><p:nvPr/></p:nvPicPr>" +
            "<p:blipFill><a:blip r:embed=\"rId$relId\"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>" +
            "<p:spPr>${xfrm(pic)}<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr></p:pic>"

    // ------------------------------------------------------------------- parts

    private const val CLR_MAP_ATTRS =
        "bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" " +
            "accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" " +
            "hlink=\"hlink\" folHlink=\"folHlink\""

    private fun emptyTree(): String = "<p:spTree>${treeHeader()}</p:spTree>"

    private fun presentation(count: Int): String {
        val ids = (0 until count).joinToString("") {
            "<p:sldId id=\"${256 + it}\" r:id=\"rId${2 + it}\"/>"
        }
        return DECL + "<p:presentation $NS saveSubsetFonts=\"1\">" +
            "<p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst>" +
            "<p:notesMasterIdLst><p:notesMasterId r:id=\"rId${2 + count}\"/></p:notesMasterIdLst>" +
            "<p:sldIdLst>$ids</p:sldIdLst>" +
            "<p:sldSz cx=\"${DeckTheme.W * EMU}\" cy=\"${DeckTheme.H * EMU}\"/>" +
            "<p:notesSz cx=\"${DeckTheme.H * EMU}\" cy=\"${DeckTheme.W * EMU}\"/>" +
            "</p:presentation>"
    }

    private fun presentationRels(count: Int): String {
        val items = mutableListOf(relationship(1, "slideMaster", "slideMasters/slideMaster1.xml"))
        for (i in 0 until count) items.add(relationship(2 + i, "slide", "slides/slide${i + 1}.xml"))
        items.add(relationship(2 + count, "notesMaster", "notesMasters/notesMaster1.xml"))
        items.add(relationship(3 + count, "theme", "theme/theme1.xml"))
        return rels(*items.toTypedArray())
    }

    private fun slideMaster(): String =
        DECL + "<p:sldMaster $NS><p:cSld>" +
            "<p:bg><p:bgPr><a:solidFill><a:srgbClr val=\"${DeckTheme.PAPER}\"/></a:solidFill>" +
            "<a:effectLst/></p:bgPr></p:bg>${emptyTree()}</p:cSld>" +
            "<p:clrMap $CLR_MAP_ATTRS/>" +
            "<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst>" +
            "</p:sldMaster>"

    /**
     * One blank layout, on purpose.
     *
     * Every slide positions its own shapes absolutely, so there is nothing for a layout
     * to contribute except inherited properties that a different reader might interpret
     * differently. A deck that looks the same in PowerPoint, Keynote and Google Slides
     * is a deck that inherits as little as possible.
     */
    private fun slideLayout(): String =
        DECL + "<p:sldLayout $NS type=\"blank\" preserve=\"1\">" +
            "<p:cSld name=\"Blank\">${emptyTree()}</p:cSld>" +
            "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"

    private fun notesMaster(): String =
        DECL + "<p:notesMaster $NS><p:cSld>${emptyTree()}</p:cSld>" +
            "<p:clrMap $CLR_MAP_ATTRS/></p:notesMaster>"

    private fun notesSlide(notes: String?): String {
        val text = notes?.takeIf { it.isNotBlank() } ?: ""
        return DECL + "<p:notes $NS><p:cSld><p:spTree>${treeHeader()}" +
            "<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Notes Placeholder\"/>" +
            "<p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr>" +
            "<p:nvPr><p:ph type=\"body\" idx=\"1\"/></p:nvPr></p:nvSpPr>" +
            "<p:spPr><a:xfrm><a:off x=\"${68 * EMU}\" y=\"${110 * EMU}\"/>" +
            "<a:ext cx=\"${584 * EMU}\" cy=\"${700 * EMU}\"/></a:xfrm>" +
            "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr>" +
            "<p:txBody><a:bodyPr wrap=\"square\"><a:normAutofit/></a:bodyPr><a:lstStyle/>" +
            "<a:p><a:pPr><a:buNone/></a:pPr><a:r><a:rPr lang=\"en-US\" sz=\"1200\" dirty=\"0\"/>" +
            "<a:t>${escape(text)}</a:t></a:r></a:p></p:txBody></p:sp>" +
            "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:notes>"
    }

    private fun theme(name: String): String {
        val fill = "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>"
        val line = "<a:ln w=\"9525\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln>"
        val effect = "<a:effectStyle><a:effectLst/></a:effectStyle>"
        return DECL +
            "<a:theme xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" name=\"${escape(name)}\">" +
            "<a:themeElements><a:clrScheme name=\"Nexus\">" +
            "<a:dk1><a:srgbClr val=\"${DeckTheme.INK}\"/></a:dk1>" +
            "<a:lt1><a:srgbClr val=\"${DeckTheme.PAPER}\"/></a:lt1>" +
            "<a:dk2><a:srgbClr val=\"${DeckTheme.DIM}\"/></a:dk2>" +
            "<a:lt2><a:srgbClr val=\"${DeckTheme.WASH}\"/></a:lt2>" +
            DeckTheme.ACCENTS.mapIndexed { i, c -> "<a:accent${i + 1}><a:srgbClr val=\"$c\"/></a:accent${i + 1}>" }.joinToString("") +
            "<a:accent5><a:srgbClr val=\"00A6ED\"/></a:accent5>" +
            "<a:accent6><a:srgbClr val=\"F92F60\"/></a:accent6>" +
            "<a:hlink><a:srgbClr val=\"${DeckTheme.ACCENTS[0]}\"/></a:hlink>" +
            "<a:folHlink><a:srgbClr val=\"${DeckTheme.ACCENTS[3]}\"/></a:folHlink>" +
            "</a:clrScheme><a:fontScheme name=\"Nexus\">" +
            "<a:majorFont><a:latin typeface=\"${DeckTheme.FONT}\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:majorFont>" +
            "<a:minorFont><a:latin typeface=\"${DeckTheme.FONT}\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:minorFont>" +
            "</a:fontScheme><a:fmtScheme name=\"Nexus\">" +
            "<a:fillStyleLst>${fill.repeat(3)}</a:fillStyleLst>" +
            "<a:lnStyleLst>${line.repeat(3)}</a:lnStyleLst>" +
            "<a:effectStyleLst>${effect.repeat(3)}</a:effectStyleLst>" +
            "<a:bgFillStyleLst>${fill.repeat(3)}</a:bgFillStyleLst>" +
            "</a:fmtScheme></a:themeElements></a:theme>"
    }

    private fun contentTypes(slides: Int): String {
        val parts = StringBuilder()
        parts.append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        parts.append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        parts.append("<Default Extension=\"png\" ContentType=\"image/png\"/>")
        fun over(part: String, type: String) =
            parts.append("<Override PartName=\"$part\" ContentType=\"$type\"/>")
        val od = "application/vnd.openxmlformats-officedocument"
        over("/ppt/presentation.xml", "$od.presentationml.presentation.main+xml")
        over("/ppt/slideMasters/slideMaster1.xml", "$od.presentationml.slideMaster+xml")
        over("/ppt/slideLayouts/slideLayout1.xml", "$od.presentationml.slideLayout+xml")
        over("/ppt/notesMasters/notesMaster1.xml", "$od.presentationml.notesMaster+xml")
        over("/ppt/theme/theme1.xml", "$od.theme+xml")
        over("/ppt/theme/theme2.xml", "$od.theme+xml")
        for (i in 1..slides) {
            over("/ppt/slides/slide$i.xml", "$od.presentationml.slide+xml")
            over("/ppt/notesSlides/notesSlide$i.xml", "$od.presentationml.notesSlide+xml")
        }
        over("/docProps/core.xml", "application/vnd.openxmlformats-package.core-properties+xml")
        over("/docProps/app.xml", "$od.extended-properties+xml")
        return DECL +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">$parts</Types>"
    }

    private fun core(title: String, author: String): String =
        DECL + "<cp:coreProperties " +
            "xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\">" +
            "<dc:title>${escape(title)}</dc:title>" +
            "<dc:creator>${escape(author)}</dc:creator>" +
            "<cp:lastModifiedBy>${escape(author)}</cp:lastModifiedBy>" +
            "</cp:coreProperties>"

    private fun app(slides: List<SlideArt>): String {
        val titles = slides.joinToString("") { "<vt:lpstr>${escape(it.title)}</vt:lpstr>" }
        return DECL + "<Properties " +
            "xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\" " +
            "xmlns:vt=\"http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes\">" +
            "<Application>Nexus</Application><Slides>${slides.size}</Slides>" +
            "<TitlesOfParts><vt:vector size=\"${slides.size}\" baseType=\"lpstr\">$titles</vt:vector></TitlesOfParts>" +
            "</Properties>"
    }

    private fun relationship(id: Int, type: String, target: String): String =
        "<Relationship Id=\"rId$id\" Type=\"$REL/$type\" Target=\"${escape(target)}\"/>"

    private fun rels(vararg items: String): String =
        DECL + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            items.joinToString("") + "</Relationships>"

    /**
     * XML escaping, plus a scrub of anything XML 1.0 cannot carry at all.
     *
     * Every string on a slide came out of somebody's codebase, and a stray control byte
     * from a README is enough to make a package that no reader will open.
     */
    private fun escape(raw: String): String {
        val out = StringBuilder(raw.length + 16)
        for (ch in raw) {
            when {
                ch == '&' -> out.append("&amp;")
                ch == '<' -> out.append("&lt;")
                ch == '>' -> out.append("&gt;")
                ch == '"' -> out.append("&quot;")
                ch == '\'' -> out.append("&apos;")
                ch == '\t' || ch == '\n' -> out.append(' ')
                ch.code < 0x20 -> Unit
                // Unpaired surrogates and the two noncharacters are equally fatal.
                ch.isSurrogate() -> Unit
                ch.code == 0xFFFE || ch.code == 0xFFFF -> Unit
                else -> out.append(ch)
            }
        }
        return out.toString()
    }
}

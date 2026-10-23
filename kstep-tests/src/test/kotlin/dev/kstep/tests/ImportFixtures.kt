package dev.kstep.tests

/** Generated hostile STEP inputs for the import tests; nothing here is committed as a large file. */
internal object ImportFixtures {
    private const val ENDSEC = "ENDSEC;\nEND-ISO-10303-21;"

    private fun box(): String =
        requireNotNull(ImportFixtures::class.java.getResourceAsStream("/import/box-20x30x40.step")) { "box" }
            .use { it.readBytes().toString(Charsets.ISO_8859_1) }

    private fun withExtra(extra: String): ByteArray {
        val text = box()
        check(text.contains(ENDSEC))
        return text.replace(ENDSEC, extra + ENDSEC).toByteArray(Charsets.ISO_8859_1)
    }

    /**
     * The box product (`#6`) placed below a chain of [levels] nested assemblies: `levels` usages from the top
     * assembly down to the box, i.e. a structure depth of [levels]. Only the structure is generated; the depth
     * check runs before OCCT transfers anything.
     */
    fun assemblyChain(levels: Int): ByteArray {
        val sb = StringBuilder()
        var id = 1000
        val definitions = ArrayList<Int>()
        for (i in 0 until levels) {
            sb.append("#$id=PRODUCT('A$i','A$i',\$,(#2));\n")
            sb.append("#${id + 1}=PRODUCT_DEFINITION_FORMATION('',\$,#$id);\n")
            sb.append("#${id + 2}=PRODUCT_DEFINITION('',\$,#${id + 1},#5);\n")
            definitions.add(id + 2)
            id += 3
        }
        for (i in 0 until levels) {
            val child = if (i + 1 < levels) definitions[i + 1] else 6
            sb.append("#$id=NEXT_ASSEMBLY_USAGE_OCCURRENCE('n$i','n$i',\$,#${definitions[i]},#$child,'x');\n")
            id++
        }
        return withExtra(sb.toString())
    }

    /** [count] extra `CARTESIAN_POINT` entities: exceeds the entity limit when large enough. */
    fun manyEntities(count: Int): ByteArray {
        val sb = StringBuilder(count * 40)
        for (i in 0 until count) sb.append("#${10_000 + i}=CARTESIAN_POINT('',(0.,0.,0.));\n")
        return withExtra(sb.toString())
    }

    /** A parameter list nested [depth] parentheses deep. */
    fun deepParentheses(depth: Int): ByteArray = withExtra("#9999=PRODUCT(" + "(".repeat(depth) + ");\n")

    private fun resource(name: String): String =
        requireNotNull(ImportFixtures::class.java.getResourceAsStream("/import/$name")) { name }
            .use { it.readBytes().toString(Charsets.ISO_8859_1) }

    /**
     * `styled-box.step` plus [items] STYLED_ITEMs on one face, each with a style chain whose four lists (styled item ->
     * PSA -> SURFACE_SIDE_STYLE -> FILL_AREA_STYLE) have [width] entries each: [width]^4 colour-reader steps per item
     * while every single list stays within the list cap (security audit of Welle R, "nest" files).
     */
    fun nestedStyles(
        width: Int,
        items: Int,
    ): ByteArray {
        fun refs(id: String) = List(width) { id }.joinToString(",")
        val sb = StringBuilder()
        sb.append("#900000=FILL_AREA_STYLE('',(${refs("#361")}));\n")
        sb.append("#900001=SURFACE_STYLE_FILL_AREA(#900000);\n")
        sb.append("#900002=SURFACE_SIDE_STYLE('',(${refs("#900001")}));\n")
        sb.append("#900003=SURFACE_STYLE_USAGE(.BOTH.,#900002);\n")
        sb.append("#900004=PRESENTATION_STYLE_ASSIGNMENT((${refs("#900003")}));\n")
        for (i in 0 until items) sb.append("#${1_000_000 + i}=STYLED_ITEM('',(${refs("#900004")}),#344);\n")
        val text = resource("styled-box.step")
        check(text.contains(ENDSEC))
        return text.replace(ENDSEC, sb.toString() + ENDSEC).toByteArray(Charsets.ISO_8859_1)
    }

    /**
     * `nested-occt-reference.step` with the usage Unterbaugruppe -> Klotz replaced by [levels] intermediate
     * assemblies, each level holding [width] usages of the next one (a "diamond" chain): [width]^([levels] + 1)
     * occurrences of Klotz after expansion, from a file of a few KB per level. Every intermediate assembly is a
     * real product with a shape representation, so a small chain is an ordinary, importable assembly.
     */
    fun assemblyDiamond(
        levels: Int,
        width: Int,
    ): ByteArray {
        val src = resource("nested-occt-reference.step")
        var id = 5_000_000
        val lines = ArrayList<String>()
        val products = IntArray(levels + 2)
        val reps = IntArray(levels + 2)
        products[0] = 27
        reps[0] = 32
        products[levels + 1] = 50
        reps[levels + 1] = 55
        for (i in 1..levels) {
            val pc = ++id
            val pr = ++id
            val pf = ++id
            val pdc = ++id
            val pd = ++id
            val sr = ++id
            val pds = ++id
            val sdr = ++id
            lines += "#$pc=PRODUCT_CONTEXT('',#2,'mechanical');"
            lines += "#$pr=PRODUCT('C$i','C$i','',(#$pc));"
            lines += "#$pf=PRODUCT_DEFINITION_FORMATION('','',#$pr);"
            lines += "#$pdc=PRODUCT_DEFINITION_CONTEXT('part definition',#2,'design');"
            lines += "#$pd=PRODUCT_DEFINITION('design','',#$pf,#$pdc);"
            lines += "#$sr=SHAPE_REPRESENTATION('',(#11,#33),#37);"
            lines += "#$pds=PRODUCT_DEFINITION_SHAPE('','',#$pd);"
            lines += "#$sdr=SHAPE_DEFINITION_REPRESENTATION(#$pds,#$sr);"
            products[i] = pd
            reps[i] = sr
        }
        var k = 0
        for (i in 0..levels) {
            repeat(width) {
                k++
                val idt = ++id
                val rr = ++id
                val cd = ++id
                val pds = ++id
                val nauo = ++id
                lines += "#$idt=ITEM_DEFINED_TRANSFORMATION('','',#11,#33);"
                lines += "#$rr=( REPRESENTATION_RELATIONSHIP('','',#${reps[i + 1]},#${reps[i]}) " +
                    "REPRESENTATION_RELATIONSHIP_WITH_TRANSFORMATION(#$idt) SHAPE_REPRESENTATION_RELATIONSHIP() );"
                lines += "#$cd=CONTEXT_DEPENDENT_SHAPE_REPRESENTATION(#$rr,#$pds);"
                lines += "#$pds=PRODUCT_DEFINITION_SHAPE('Placement','Placement of an item',#$nauo);"
                lines += "#$nauo=NEXT_ASSEMBLY_USAGE_OCCURRENCE('n$k','N$k','',#${products[i]},#${products[i + 1]},\$);"
            }
        }
        // Drop the original usage Unterbaugruppe -> Klotz (#391..#395); the chain above replaces it.
        val original =
            Regex(
                """#391 = CONTEXT_DEPENDENT_SHAPE_REPRESENTATION\(#392,#394\);.*?""" +
                    """#395 = NEXT_ASSEMBLY_USAGE_OCCURRENCE\([^;]*;\n""",
                RegexOption.DOT_MATCHES_ALL,
            )
        check(original.containsMatchIn(src))
        val text = src.replace(original, "")
        check(text.contains(ENDSEC))
        return text.replace(ENDSEC, lines.joinToString("\n", postfix = "\n") + ENDSEC).toByteArray(Charsets.ISO_8859_1)
    }

    private fun styledBoxWith(
        extra: List<String>,
        topRepresentation: Int? = null,
    ): ByteArray {
        var text = resource("styled-box.step")
        check(text.contains(ENDSEC))
        if (topRepresentation != null) {
            val sdr = "#357=SHAPE_DEFINITION_REPRESENTATION(#356,#16);"
            check(text.contains(sdr))
            text = text.replace(sdr, "#357=SHAPE_DEFINITION_REPRESENTATION(#356,#$topRepresentation);")
        }
        return text.replace(ENDSEC, extra.joinToString("\n", postfix = "\n") + ENDSEC).toByteArray(Charsets.ISO_8859_1)
    }

    /**
     * `styled-box.step` plus one OVER_RIDING_STYLED_ITEM that overrides itself (audit of the worker wave, "oself"):
     * crashed the CLI process without any output before the override-chain check.
     */
    fun overrideSelf(): ByteArray = styledBoxWith(listOf("#1000000=OVER_RIDING_STYLED_ITEM('',(#366),#344,#1000000);"))

    /**
     * `styled-box.step` plus a chain of [depth] styled items, each overriding the previous one ("ocd<depth>"): a chain
     * of 3000 crashed the CLI process without any output before the override-chain check.
     */
    fun overrideChain(depth: Int): ByteArray =
        styledBoxWith(
            buildList {
                add("#1000000=STYLED_ITEM('',(#366),#344);")
                for (i in 1 until depth) {
                    add(
                        "#${1_000_000 + i}=OVER_RIDING_STYLED_ITEM('',(#366),#344,#${999_999 + i});",
                    )
                }
            },
        )

    /**
     * `styled-box.step` whose part representation is the top of a chain of [levels] representations, each holding one
     * MAPPED_ITEM of the next ("mc<levels>"): a chain of 1000 crashed the CLI process without any output.
     */
    fun mappedItemChain(levels: Int): ByteArray {
        fun rep(k: Int) = if (k == 0) 16 else 3_000_000 + k * 10
        val lines =
            buildList {
                for (k in 1..levels) {
                    add("#${rep(k) + 1}=REPRESENTATION_MAP(#17,#${rep(k - 1)});")
                    add("#${rep(k) + 2}=MAPPED_ITEM('',#${rep(k) + 1},#17);")
                    add("#${rep(k)}=SHAPE_REPRESENTATION('',(#17,#${rep(k) + 2}),#351);")
                }
            }
        return styledBoxWith(lines, rep(levels))
    }

    /** A representation that maps itself through a MAPPED_ITEM ("mcyc"): crashed the CLI process without any output. */
    fun mappedItemCycle(): ByteArray =
        styledBoxWith(
            listOf(
                "#3000001=REPRESENTATION_MAP(#17,#3000000);",
                "#3000002=MAPPED_ITEM('',#3000001,#17);",
                "#3000000=SHAPE_REPRESENTATION('',(#17,#3000002),#351);",
            ),
            3_000_000,
        )

    /**
     * `styled-box.step` with the face list of its CLOSED_SHELL repeated [times] times ("sh<times>_0"): 50000 repeats
     * (300000 references) took 26 s in OCCT's shell transfer before the shell-reference check.
     */
    fun repeatedShellFaces(times: Int): ByteArray {
        val faces = "#23,#143,#243,#290,#337,#344"
        val shell = "#22=CLOSED_SHELL('',($faces));"
        val text = resource("styled-box.step")
        check(text.contains(shell))
        val repeated = List(times) { faces }.joinToString(",")
        return text.replace(shell, "#22=CLOSED_SHELL('',($repeated));").toByteArray(Charsets.ISO_8859_1)
    }
}

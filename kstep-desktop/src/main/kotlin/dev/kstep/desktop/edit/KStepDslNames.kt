package dev.kstep.desktop.edit

/**
 * Names of the kSTEP script DSL that the editor highlights (ADR-0052, ADR-0089). The lists are checked-in data: the
 * desktop must not load the script module (ADR-0062), so a test checks [scriptNames] for completeness against the
 * `kstep-script` sources and [libraryNames] for existence.
 */
object KStepDslNames {
    /** Top-level functions of kstep-script plus the members of `KStepModelBuilder` and `ExportBlockBuilder`. */
    val scriptNames: Set<String> =
        setOf(
            "stepFile",
            "inputFile",
            "readInput",
            "roots",
            "root",
            "shape",
            "place",
            "style",
            "styleFaces",
            "styleEdges",
            "styleInstance",
            "export",
            "step",
            "glb",
            "stl",
            "threeMf",
        )

    /** Curated entry points of the shape and geometry libraries. */
    val libraryNames: Set<String> = setOf("vec", "OcctKernel")

    val names: Set<String> = scriptNames + libraryNames
}

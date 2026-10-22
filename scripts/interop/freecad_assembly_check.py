# FreeCAD headless check of a STEP assembly (ADR-0028). Run with freecadcmd, ONE mode per process:
#
#   STEP_FILE=out.step STEP_MODE=open   freecadcmd scripts/interop/freecad_assembly_check.py
#   STEP_FILE=out.step STEP_MODE=part   freecadcmd scripts/interop/freecad_assembly_check.py
#
# `open` uses Import.open (document with the object hierarchy), `part` uses Part.read (one merged
# shape). Never run both in the same process: FreeCAD crashes when Part.read and Import.* meet.
# Prints plain text only; writes nothing.
import os
import sys

path = os.environ["STEP_FILE"]
mode = os.environ.get("STEP_MODE", "open")

import FreeCAD  # noqa: E402

print("FreeCAD", ".".join(FreeCAD.Version()[:3]))
try:
    print("Mod/Import GroupImport/ImportMode:",
          FreeCAD.ParamGet("User parameter:BaseApp/Preferences/Mod/Import").GetInt("ImportMode", -1),
          "(unchanged by this script)")
except Exception as e:  # noqa: BLE001
    print("preferences not readable:", e)


def bbox_text(b):
    return "[%.3f,%.3f]x[%.3f,%.3f]x[%.3f,%.3f]" % (b.XMin, b.XMax, b.YMin, b.YMax, b.ZMin, b.ZMax)


if mode == "part":
    import Part

    shape = Part.read(path)
    solids = shape.Solids
    print("Part.read: solids=%d volume=%.3f bbox=%s valid=%s" % (len(solids), shape.Volume, bbox_text(shape.BoundBox), shape.isValid()))
    for s in solids:
        print("  solid volume=%.3f bbox=%s" % (s.Volume, bbox_text(s.BoundBox)))
else:
    import Import

    doc = FreeCAD.newDocument("check")
    Import.open(path, doc.Name)
    doc = FreeCAD.ActiveDocument
    print("objects:", len(doc.Objects))
    total = 0.0
    solids = 0
    for o in doc.Objects:
        parents = [p.Name for p in getattr(o, "InList", [])]
        line = "  %s label=%r type=%s parents=%s" % (o.Name, o.Label, o.TypeId, parents)
        if hasattr(o, "Shape") and not o.Shape.isNull() and o.Shape.Solids:
            try:
                s = o.Shape.copy()
                if hasattr(o, "getGlobalPlacement"):
                    s.Placement = o.getGlobalPlacement()
                line += " solids=%d volume=%.3f global_bbox=%s" % (len(s.Solids), s.Volume, bbox_text(s.BoundBox))
                if not o.Shape.Solids == [] and not [c for c in getattr(o, "OutList", []) if hasattr(c, "Shape") and c.Shape.Solids]:
                    total += s.Volume
                    solids += len(s.Solids)
            except Exception as e:  # noqa: BLE001
                line += " (shape inspection failed: %s)" % e
        print(line)
    print("leaf solids counted: %d, leaf volume sum: %.3f" % (solids, total))
sys.stdout.flush()
os._exit(0)

#!/usr/bin/env python3
"""Generates the Welle R import fixtures (ADR-0038) from box-20x30x40.step into build/interop/import-extras/.

Run as `python3 -B make_import_extras_fixtures.py [outdir]` so no __pycache__ appears next to the scripts.
The committed copies under kstep-tests/src/test/resources/import/ must be byte-identical to this output
(verify-import-extras.sh compares them with cmp). No /* */ comments are written: they would silently
disable the Part 21 enrichment.
"""
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
RES = os.path.join(ROOT, "kstep-tests", "src", "test", "resources", "import")
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "build", "interop", "import-extras")
os.makedirs(OUT, exist_ok=True)

with open(os.path.join(RES, "box-20x30x40.step"), "r", encoding="iso-8859-1", newline="") as f:
    BOX = f.read()
LINES = BOX.split("\n")


def entities(text):
    """id -> full entity text (single-line entities only, as in the box fixture)."""
    return {int(m.group(1)): m.group(2) for m in re.finditer(r"^#(\d+)=(.*);$", text, re.M)}


ENT = entities(BOX)


def top_face_id():
    """ADVANCED_FACE whose surface is a PLANE through z=40 with normal +Z (resolved via the entity dictionary)."""
    for eid, text in ENT.items():
        m = re.match(r"ADVANCED_FACE\('',\(#\d+\),#(\d+),", text)
        if not m:
            continue
        plane = ENT[int(m.group(1))]
        ax = ENT[int(re.match(r"PLANE\('',#(\d+)\)", plane).group(1))]
        am = re.match(r"AXIS2_PLACEMENT_3D\('',#(\d+),#(\d+),#\d+\)", ax)
        pt = ENT[int(am.group(1))]
        dr = ENT[int(am.group(2))]
        if "(0.,0.,40.)" in pt and "(0.,0.,1.)" in dr:
            return eid
    raise SystemExit("top face not found")


def next_id():
    return max(ENT) + 1


def insert_before_endsec(text, add):
    marker = "ENDSEC;\nEND-ISO-10303-21;"
    assert marker in text
    return text.replace(marker, "\n".join(add) + "\n" + marker)


def style_chain(first_id, r, g, b):
    n = first_id
    out = [
        "#%d=COLOUR_RGB('',%s,%s,%s);" % (n, r, g, b),
        "#%d=FILL_AREA_STYLE_COLOUR('',#%d);" % (n + 1, n),
        "#%d=FILL_AREA_STYLE('',(#%d));" % (n + 2, n + 1),
        "#%d=SURFACE_STYLE_FILL_AREA(#%d);" % (n + 3, n + 2),
        "#%d=SURFACE_SIDE_STYLE('',(#%d));" % (n + 4, n + 3),
        "#%d=SURFACE_STYLE_USAGE(.BOTH.,#%d);" % (n + 5, n + 4),
        "#%d=PRESENTATION_STYLE_ASSIGNMENT((#%d));" % (n + 6, n + 5),
    ]
    return out, n + 6, n + 7


def styled_box():
    n = next_id()
    face = top_face_id()
    add, psa1, n = style_chain(n, "1.", "0.5", "0.")
    add.append("#%d=STYLED_ITEM('',(#%d),#21);" % (n, psa1))
    si1 = n
    n += 1
    chain2, psa2, n = style_chain(n, "0.", "0.", "1.")
    add += chain2
    add.append("#%d=STYLED_ITEM('',(#%d),#%d);" % (n, psa2, face))
    si2 = n
    n += 1
    add.append(
        "#%d=MECHANICAL_DESIGN_GEOMETRIC_PRESENTATION_REPRESENTATION('',(#%d,#%d),#351);" % (n, si1, si2)
    )
    add.append("#%d=PRESENTATION_LAYER_ASSIGNMENT('Layer-A','',(#21));" % (n + 1))
    add.append("#%d=PRESENTATION_LAYER_ASSIGNMENT('Layer-F','',(#%d));" % (n + 2, face))
    return insert_before_endsec(BOX, add)


def with_unit(replacement_352, extra=()):
    text = BOX
    old = "#352=(LENGTH_UNIT() NAMED_UNIT(*) SI_UNIT(.MILLI.,.METRE.));"
    assert old in text
    text = text.replace(old, replacement_352)
    return insert_before_endsec(text, list(extra)) if extra else text


def conversion_unit(name, factor, base_si="SI_UNIT(.MILLI.,.METRE.)", dim_exp="1.,0.,0.,0.,0.,0.,0."):
    n = next_id()
    extra = [
        "#%d=LENGTH_MEASURE_WITH_UNIT(LENGTH_MEASURE(%s),#%d);" % (n, factor, n + 2),
        "#%d=DIMENSIONAL_EXPONENTS(%s);" % (n + 1, dim_exp),
        "#%d=(LENGTH_UNIT() NAMED_UNIT(*) %s);" % (n + 2, base_si),
    ]
    unit = "#352=(CONVERSION_BASED_UNIT('%s',#%d) LENGTH_UNIT() NAMED_UNIT(#%d));" % (name, n, n + 1)
    return with_unit(unit, extra)


def no_unit():
    text = BOX
    old = "GLOBAL_UNIT_ASSIGNED_CONTEXT((#352,#353,#354))"
    assert old in text
    text = text.replace(old, "GLOBAL_UNIT_ASSIGNED_CONTEXT((#353,#354))")
    # the length unit entity stays (the uncertainty measure still refers to it) but is no longer assigned
    return text


def structure_only():
    """Product structure and a shape representation that holds only an axis placement: no B-Rep at all."""
    out = []
    for line in LINES:
        m = re.match(r"^#(\d+)=", line)
        if m and 21 <= int(m.group(1)) <= 350:
            continue
        if line.startswith("#16=ADVANCED_BREP_SHAPE_REPRESENTATION"):
            line = "#16=SHAPE_REPRESENTATION('',(#17),#351);"
        out.append(line)
    return "\n".join(out)


def two_body_styled(base_path):
    """Two solids in one part (kSTEP export of export-two-bodies-one-part.kstep.kts): solid 1 red, solid 2 green."""
    with open(base_path, "r", encoding="iso-8859-1", newline="") as f:
        text = f.read()
    # kstep export stamps the current time into FILE_NAME; pin it so that the fixture is reproducible byte for byte
    text = re.sub(r"(FILE_NAME\('[^']*',')[^']*(')", r"\g<1>2026-10-23T00:00:00Z\g<2>", text, count=1)
    ent = entities(text)
    solids = sorted(i for i, t in ent.items() if t.startswith("MANIFOLD_SOLID_BREP"))
    assert len(solids) == 2, solids
    ctx = [i for i, t in ent.items() if t.startswith("(GEOMETRIC_REPRESENTATION_CONTEXT(3)")][0]
    n = max(ent) + 1
    add = []
    items = []
    for solid, rgb in zip(solids, (("1.", "0.", "0."), ("0.", "1.", "0."))):
        chain, psa, n = style_chain(n, *rgb)
        add += chain
        add.append("#%d=STYLED_ITEM('',(#%d),#%d);" % (n, psa, solid))
        items.append(n)
        n += 1
    add.append(
        "#%d=MECHANICAL_DESIGN_GEOMETRIC_PRESENTATION_REPRESENTATION('',(%s),#%d);"
        % (n, ",".join("#%d" % i for i in items), ctx)
    )
    return insert_before_endsec(text, add)


FILES = {
    "styled-box.step": styled_box(),
    "inch-box.step": conversion_unit("INCH", "25.4"),
    "cm-box.step": with_unit("#352=(LENGTH_UNIT() NAMED_UNIT(*) SI_UNIT(.CENTI.,.METRE.));"),
    "m-box.step": with_unit("#352=(LENGTH_UNIT() NAMED_UNIT(*) SI_UNIT($,.METRE.));"),
    "no-unit-box.step": no_unit(),
    "unresolved-unit-box.step": conversion_unit("BROKEN", "0."),
    "structure-only.step": structure_only(),
}
two_body_base = os.environ.get("TWO_BODY_BASE", os.path.join(OUT, "two-body-base.step"))
if os.path.isfile(two_body_base):
    FILES["two-body-styled.step"] = two_body_styled(two_body_base)
for name, text in FILES.items():
    with open(os.path.join(OUT, name), "w", encoding="iso-8859-1", newline="") as f:
        f.write(text)
    print("wrote", os.path.join(OUT, name))

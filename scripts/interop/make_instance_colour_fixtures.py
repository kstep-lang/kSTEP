#!/usr/bin/env python3
"""Appends the hand-written instance-colour pattern of ADR-0044 to a kSTEP placed-assembly export (gate fixtures).

  make_instance_colour_fixtures.py <base.step> <out.step> [--colour N=r,g,b ...] [--pds new|reuse] [--axis parent|own]
                                   [--no-sdr] [--per-msb]

N is the 1-based usage index in file order. --pds new writes a second PRODUCT_DEFINITION_SHAPE('Placement', ...) on the
NAUO (the plan of ADR-0044); --pds reuse hangs the SHAPE_DEFINITION_REPRESENTATION on the existing one (the pattern OCCT
writes). --axis own adds a dedicated placement axis to the context SHAPE_REPRESENTATION instead of reusing the parent axis
of the usage. --leaf-items all|first|axis (ADR-0046, sub-assembly children): all = one STYLED_ITEM per distinct leaf solid
below the usage, first = only the first leaf solid, axis = the origin axis of the synthetic SHAPE_REPRESENTATION of the
child. Without it the items of the child itself are used (a part: unchanged). --no-sdr omits the SHAPE_DEFINITION_REPRESENTATION (negative gate G7). --per-msb writes one STYLED_ITEM per
MANIFOLD_SOLID_BREP of the child. Uses only the standard library; reads and writes only the given files.
"""
import re
import sys


def fmt(v):
    s = repr(float(v))
    return s if "e" in s or "E" in s else (s if "." in s else s + ".")


def main(argv):
    base, out = argv[0], argv[1]
    colours, pds, axis, no_sdr, per_msb, leaf_items = {}, "new", "parent", False, False, None
    i = 2
    while i < len(argv):
        a = argv[i]
        if a == "--colour":
            n, rgb = argv[i + 1].split("=")
            colours[int(n)] = tuple(float(x) for x in rgb.split(","))
            i += 2
        elif a == "--pds":
            pds = argv[i + 1]; i += 2
        elif a == "--axis":
            axis = argv[i + 1]; i += 2
        elif a == "--no-sdr":
            no_sdr = True; i += 1
        elif a == "--leaf-items":
            leaf_items = argv[i + 1]; i += 2
            if leaf_items not in ("all", "first", "axis"):
                raise SystemExit("--leaf-items expects all|first|axis")
        elif a == "--per-msb":
            per_msb = True; i += 1
        else:
            raise SystemExit("unknown argument " + a)

    text = open(base, encoding="utf-8").read()
    head, data = text.split("DATA;\n", 1)
    body, tail = data.rsplit("ENDSEC;", 1)
    ents = {}
    for m in re.finditer(r"#(\d+)\s*=\s*(.*?);\n", body, re.S):
        ents[int(m.group(1))] = " ".join(m.group(2).split())
    nxt = max(ents) + 1

    nauos = [k for k, v in ents.items() if v.startswith("NEXT_ASSEMBLY_USAGE_OCCURRENCE(")]
    nauos.sort()
    new = []

    def add(text_):
        nonlocal nxt
        new.append((nxt, text_)); nxt += 1; return nxt - 1

    def usages_below(pd):
        return [(k, int(re.match(r"NEXT_ASSEMBLY_USAGE_OCCURRENCE\('[^']*','[^']*',\$,#(\d+),#(\d+)", v).group(2)))
                for k, v in ents.items()
                if v.startswith("NEXT_ASSEMBLY_USAGE_OCCURRENCE(")
                and int(re.match(r"NEXT_ASSEMBLY_USAGE_OCCURRENCE\('[^']*','[^']*',\$,#(\d+),#(\d+)", v).group(1)) == pd]

    def own_solids(pd):
        out_ = []
        for k, v in ents.items():
            if v.startswith("PRODUCT_DEFINITION_SHAPE('',") and v.endswith("#%d)" % pd):
                for v2 in ents.values():
                    m = re.match(r"SHAPE_DEFINITION_REPRESENTATION\(#%d,#(\d+)\)" % k, v2)
                    if m:
                        items = re.search(r"\('',\((.*?)\),#\d+\)$", ents[int(m.group(1))])
                        out_ += [x for x in (int(t) for t in items.group(1).replace("#", "").split(",")) if ents[x].startswith("MANIFOLD_SOLID_BREP(")]
        return out_

    def leaf_solids(pd):  # iterative, depth first in file order, deduplicated by id
        found, stack, seen = [], [pd], set()
        while stack:
            cur = stack.pop()
            if cur in seen:
                continue
            seen.add(cur)
            for s_ in own_solids(cur):
                if s_ not in found:
                    found.append(s_)
            for _, child in reversed(usages_below(cur)):
                stack.append(child)
        return found

    styled = []
    for idx, nauo in enumerate(nauos, start=1):
        if idx not in colours:
            continue
        r, g, b = colours[idx]
        pd_ = next(k for k, v in ents.items() if v.startswith("PRODUCT_DEFINITION_SHAPE('Placement'") and v.endswith("#%d)" % nauo))
        cdsr = next(v for v in ents.values() if v.startswith("CONTEXT_DEPENDENT_SHAPE_REPRESENTATION(") and v.endswith("#%d);" % pd_) or v.startswith("CONTEXT_DEPENDENT_SHAPE_REPRESENTATION(") and v.endswith("#%d)" % pd_))
        rr = int(re.match(r"CONTEXT_DEPENDENT_SHAPE_REPRESENTATION\(#(\d+),", cdsr).group(1))
        rrt = ents[rr]
        child_sr, parent_sr = (int(x) for x in re.search(r"REPRESENTATION_RELATIONSHIP\('','',#(\d+),#(\d+)\)", rrt).groups())
        idt = int(re.search(r"WITH_TRANSFORMATION\(#(\d+)\)", rrt).group(1))
        parent_axis = int(re.match(r"ITEM_DEFINED_TRANSFORMATION\('','',#\d+,#(\d+)\)", ents[idt]).group(1))
        ctx = int(re.search(r",#(\d+)\)$", ents[parent_sr]).group(1))
        child_items = [int(x) for x in re.search(r"\('',\((.*?)\),#\d+\)$", ents[child_sr]).group(1).replace("#", "").split(",")]
        msbs = [k for k in child_items if ents[k].startswith("MANIFOLD_SOLID_BREP(")]
        child_pd = int(re.match(r"NEXT_ASSEMBLY_USAGE_OCCURRENCE\('[^']*','[^']*',\$,#\d+,#(\d+)", ents[nauo]).group(1))
        if leaf_items == "all":
            msbs, per_msb_ = leaf_solids(child_pd), True
        elif leaf_items == "first":
            msbs, per_msb_ = leaf_solids(child_pd)[:1], False
        elif leaf_items == "axis":
            msbs, per_msb_ = child_items[:1], False
        else:
            per_msb_ = per_msb
        col = add("COLOUR_RGB('',%s,%s,%s)" % (fmt(r), fmt(g), fmt(b)))
        fasc = add("FILL_AREA_STYLE_COLOUR('',#%d)" % col)
        fas = add("FILL_AREA_STYLE('',(#%d))" % fasc)
        ssfa = add("SURFACE_STYLE_FILL_AREA(#%d)" % fas)
        sss = add("SURFACE_SIDE_STYLE('',(#%d))" % ssfa)
        ssu = add("SURFACE_STYLE_USAGE(.BOTH.,#%d)" % sss)
        if axis == "own":
            pos = ents[parent_axis]
            pt = int(re.search(r"AXIS2_PLACEMENT_3D\('',#(\d+),#(\d+),#(\d+)\)", pos).group(1))
            dz = int(re.search(r"AXIS2_PLACEMENT_3D\('',#(\d+),#(\d+),#(\d+)\)", pos).group(2))
            dx = int(re.search(r"AXIS2_PLACEMENT_3D\('',#(\d+),#(\d+),#(\d+)\)", pos).group(3))
            ctx_axis = add("AXIS2_PLACEMENT_3D('',#%d,#%d,#%d)" % (pt, dz, dx))
        else:
            ctx_axis = parent_axis
        sr = add("SHAPE_REPRESENTATION('',(#%d),#%d)" % (ctx_axis, ctx))
        pds_ref = pd_
        if pds == "new":
            pds_ref = add("PRODUCT_DEFINITION_SHAPE('Placement','Placement of an item',#%d)" % nauo)
        if not no_sdr:
            add("SHAPE_DEFINITION_REPRESENTATION(#%d,#%d)" % (pds_ref, sr))
        psbc = add("PRESENTATION_STYLE_BY_CONTEXT((#%d),#%d)" % (ssu, sr))
        for msb in (msbs if per_msb_ else msbs[:1]):
            styled.append(add("STYLED_ITEM('color',(#%d),#%d)" % (psbc, msb)))
        ctx_for_mdgpr = ctx
    if styled:
        add("MECHANICAL_DESIGN_GEOMETRIC_PRESENTATION_REPRESENTATION('',(%s),#%d)" % (",".join("#%d" % s for s in styled), ctx_for_mdgpr))

    # keep the instances at the very end, behind everything else (Byte-prefix property of ADR-0044)
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        f.write(head + "DATA;\n" + body + "".join("#%d=%s;\n" % (k, v) for k, v in new) + "ENDSEC;" + tail)


if __name__ == "__main__":
    main(sys.argv[1:])

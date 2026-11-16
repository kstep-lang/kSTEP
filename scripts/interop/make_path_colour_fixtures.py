#!/usr/bin/env python3
"""Appends the hand-written path-colour pattern of ADR-0048 (SPECIFIED_HIGHER_USAGE_OCCURRENCE) to a kSTEP placed-assembly export.

  make_path_colour_fixtures.py <base.step> <out.step> --path u2,u3:r,g,b[,a] [--path ...] [--no-sdr]
                               [--axis upper|leaf|own] [--tamper cycle|nonchain|selfref|deep:N|count:N] [--raw-t LEXEME]

The pattern is the one OCCT's own writer produces for XCAFDoc_ShapeTool::SetSHUO + a colour on the SHUO label (scenario S0 of
verify-path-colour-gate.sh): SPECIFIED_HIGHER_USAGE_OCCURRENCE(relating PD, related PD, upper NAUO, next NAUO),
PRODUCT_DEFINITION_SHAPE on it, a one-item SHAPE_REPRESENTATION, SHAPE_DEFINITION_REPRESENTATION, PRESENTATION_STYLE_BY_CONTEXT and
one STYLED_ITEM per leaf solid below the last usage. Only paths of exactly two usages are generated (the only length measured;
longer chains are not measured, see ADR-0048). uN is the 1-based NAUO index in file order. Per-usage and part colours are added
beforehand with make_instance_colour_fixtures.py (this script only appends, so the two compose). --tamper: cycle = the SHUO names
itself as next_usage; selfref = upper and next are the same usage; nonchain = the two usages do not chain (path reversed);
deep:N = a chain of N SHUOs each naming the previous as next_usage; count:N = N copies of the SHUO. Standard library only;
reads and writes only the given files.
"""
import re
import sys


def fmt(v):
    s = repr(float(v))
    return s if "e" in s or "E" in s else (s if "." in s else s + ".")


def main(argv):
    base, out = argv[0], argv[1]
    paths, axis, no_sdr, tamper, raw_t = [], "upper", False, None, None
    i = 2
    while i < len(argv):
        a = argv[i]
        if a == "--path":
            idx, rgb = argv[i + 1].split(":")
            paths.append(([int(x) for x in idx.replace("u", "").split(",")], tuple(float(x) for x in rgb.split(","))))
            i += 2
        elif a == "--axis":
            axis = argv[i + 1]; i += 2
            if axis not in ("upper", "leaf", "own"):
                raise SystemExit("--axis expects upper|leaf|own")
        elif a == "--no-sdr":
            no_sdr = True; i += 1
        elif a == "--tamper":
            tamper = argv[i + 1]; i += 2
        elif a == "--raw-t":
            raw_t = argv[i + 1]; i += 2
        else:
            raise SystemExit("unknown argument " + a)

    text = open(base, encoding="utf-8").read()
    head, data = text.split("DATA;\n", 1)
    body, tail = data.rsplit("ENDSEC;", 1)
    ents = {}
    for m in re.finditer(r"#(\d+)\s*=\s*(.*?);\n", body, re.S):
        ents[int(m.group(1))] = " ".join(m.group(2).split())
    nxt = max(ents) + 1
    nauos = sorted(k for k, v in ents.items() if v.startswith("NEXT_ASSEMBLY_USAGE_OCCURRENCE("))
    new = []

    def add(t):
        nonlocal nxt
        new.append((nxt, t)); nxt += 1; return nxt - 1

    nauo_re = r"NEXT_ASSEMBLY_USAGE_OCCURRENCE\('[^']*','[^']*',\$,#(\d+),#(\d+)"

    def ends(n):
        return tuple(int(x) for x in re.match(nauo_re, ents[n]).groups())

    def usages_below(pd):
        return [int(re.match(nauo_re, v).group(2)) for k, v in sorted(ents.items())
                if v.startswith("NEXT_ASSEMBLY_USAGE_OCCURRENCE(") and int(re.match(nauo_re, v).group(1)) == pd]

    def own_solids(pd):
        res = []
        for k, v in ents.items():
            if v.startswith("PRODUCT_DEFINITION_SHAPE('',") and v.endswith("#%d)" % pd):
                for v2 in ents.values():
                    m = re.match(r"SHAPE_DEFINITION_REPRESENTATION\(#%d,#(\d+)\)" % k, v2)
                    if m:
                        items = re.search(r"\('',\((.*?)\),#\d+\)$", ents[int(m.group(1))])
                        res += [x for x in (int(t) for t in items.group(1).replace("#", "").split(",")) if ents[x].startswith("MANIFOLD_SOLID_BREP(")]
        return res

    def leaf_solids(pd):
        found, stack, seen = [], [pd], set()
        while stack:
            cur = stack.pop()
            if cur in seen:
                continue
            seen.add(cur)
            for s_ in own_solids(cur):
                if s_ not in found:
                    found.append(s_)
            stack.extend(reversed(usages_below(cur)))
        return found

    def usage_geometry(nauo):
        pd_ = next(k for k, v in ents.items() if v.startswith("PRODUCT_DEFINITION_SHAPE('Placement'") and v.endswith("#%d)" % nauo))
        cdsr = next(v for v in ents.values() if v.startswith("CONTEXT_DEPENDENT_SHAPE_REPRESENTATION(") and re.search(r"#%d\);?$" % pd_, v))
        rr = int(re.match(r"CONTEXT_DEPENDENT_SHAPE_REPRESENTATION\(#(\d+),", cdsr).group(1))
        child_sr, parent_sr = (int(x) for x in re.search(r"REPRESENTATION_RELATIONSHIP\('','',#(\d+),#(\d+)\)", ents[rr]).groups())
        idt = int(re.search(r"WITH_TRANSFORMATION\(#(\d+)\)", ents[rr]).group(1))
        parent_axis = int(re.match(r"ITEM_DEFINED_TRANSFORMATION\('','',#\d+,#(\d+)\)", ents[idt]).group(1))
        ctx = int(re.search(r",#(\d+)\)$", ents[parent_sr]).group(1))
        return parent_axis, ctx

    styled, ctx_for_mdgpr = [], None
    for idxs, col in paths:
        if len(idxs) != 2:
            raise SystemExit("only paths of exactly two usages are generated")
        ua, ub = nauos[idxs[0] - 1], nauos[idxs[1] - 1]
        r, g, b = col[:3]
        alpha = col[3] if len(col) > 3 else 1.0
        relating = ends(ua)[0]
        related = ends(ub)[1]
        paxis, ctx = usage_geometry(ua)
        laxis, _ = usage_geometry(ub)
        ctx_for_mdgpr = ctx
        if axis == "own":
            m = re.search(r"AXIS2_PLACEMENT_3D\('',#(\d+),#(\d+),#(\d+)\)", ents[paxis])
            ctx_axis = add("AXIS2_PLACEMENT_3D('',#%s,#%s,#%s)" % m.groups())
        else:
            ctx_axis = paxis if axis == "upper" else laxis
        c = add("COLOUR_RGB('',%s,%s,%s)" % (fmt(r), fmt(g), fmt(b)))
        fasc = add("FILL_AREA_STYLE_COLOUR('',#%d)" % c)
        fas = add("FILL_AREA_STYLE('',(#%d))" % fasc)
        ssfa = add("SURFACE_STYLE_FILL_AREA(#%d)" % fas)
        if alpha < 1.0 or raw_t is not None:
            t_ = add("SURFACE_STYLE_TRANSPARENT(%s)" % (raw_t if raw_t is not None else fmt(1.0 - alpha)))
            rend = add("SURFACE_STYLE_RENDERING_WITH_PROPERTIES(.NORMAL_SHADING.,#%d,(#%d))" % (c, t_))
            sss = add("SURFACE_SIDE_STYLE('',(#%d,#%d))" % (ssfa, rend))
        else:
            sss = add("SURFACE_SIDE_STYLE('',(#%d))" % ssfa)
        ssu = add("SURFACE_STYLE_USAGE(.BOTH.,#%d)" % sss)
        sr = add("SHAPE_REPRESENTATION('',(#%d),#%d)" % (ctx_axis, ctx))
        copies = 1
        t_up, t_next = ua, ub
        if tamper == "nonchain":
            t_up, t_next = ub, ua
        elif tamper == "selfref":
            t_next = ua
        elif tamper and tamper.startswith("count:"):
            copies = int(tamper.split(":")[1])
        chain = int(tamper.split(":")[1]) if tamper and tamper.startswith("deep:") else 1
        shuo = None
        for n in range(copies):
            prev = None
            for d in range(chain):
                nid = nxt
                nextref = "#%d" % t_next
                if tamper == "cycle":
                    nextref = "#%d" % nid
                elif prev is not None:
                    nextref = "#%d" % prev
                prev = add("SPECIFIED_HIGHER_USAGE_OCCURRENCE('','',$,#%d,#%d,$,#%d,%s)" % (relating, related, t_up, nextref))
            shuo = prev
            pds = add("PRODUCT_DEFINITION_SHAPE('SHUO',$,#%d)" % shuo)
            if not no_sdr:
                add("SHAPE_DEFINITION_REPRESENTATION(#%d,#%d)" % (pds, sr))
        psbc = add("PRESENTATION_STYLE_BY_CONTEXT((#%d),#%d)" % (ssu, sr))
        for msb in leaf_solids(ends(ub)[1]):
            styled.append(add("STYLED_ITEM('color',(#%d),#%d)" % (psbc, msb)))
    if styled:
        add("MECHANICAL_DESIGN_GEOMETRIC_PRESENTATION_REPRESENTATION('',(%s),#%d)" % (",".join("#%d" % s for s in styled), ctx_for_mdgpr))
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        f.write(head + "DATA;\n" + body + "".join("#%d=%s;\n" % (k, v) for k, v in new) + "ENDSEC;" + tail)


if __name__ == "__main__":
    main(sys.argv[1:])

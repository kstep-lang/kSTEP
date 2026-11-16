#!/usr/bin/env python3
"""Appends hand-written PRESENTATION_LAYER_ASSIGNMENT candidates for assembly usages (Welle Z gate, ADR-0047).

  make_instance_layer_fixtures.py <base.step> <out.step> --layer N[,N...]=NAME [...] --pattern p1|p2|p2b|p3|p4|p5 [--no-sdr] [--raw-name TEXT]

N is the 1-based usage index in file order (NAUO order). The base may already carry instance colours written by
make_instance_colour_fixtures.py (needed for p1/p4 and for the layer-plus-colour scenario).
Patterns (what the PLA assigns):
  p1  the STYLED_ITEM of the usage's colour chain (needs a colour on the usage)
  p2  the placement axis in a fresh one-item SHAPE_REPRESENTATION bound by SDR to the usage's PRODUCT_DEFINITION_SHAPE('Placement')
  p2b like p2 but with a dedicated axis copy
  p3  the MANIFOLD_SOLID_BREP(s) of the child (control; expected to land on the shared part)
  p4  the MECHANICAL_DESIGN_GEOMETRIC_PRESENTATION_REPRESENTATION of the colour chain
  p5  the NAUO itself (schema-invalid LAYERED_ITEM, informational only)
Standard library only; reads and writes only the given files.
"""
import re
import sys


def main(argv):
    base, out = argv[0], argv[1]
    layers, pattern, no_sdr, raw_name = [], "p2", False, None
    i = 2
    while i < len(argv):
        a = argv[i]
        if a == "--layer":
            idx, name = argv[i + 1].split("=", 1)
            layers.append(([int(x) for x in idx.split(",")], name)); i += 2
        elif a == "--pattern":
            pattern = argv[i + 1]; i += 2
        elif a == "--no-sdr":
            no_sdr = True; i += 1
        elif a == "--raw-name":
            raw_name = argv[i + 1]; i += 2
        else:
            raise SystemExit("unknown argument " + a)
    text = open(base, encoding="utf-8").read()
    head, data = text.split("DATA;\n", 1)
    body, tail = data.rsplit("ENDSEC;", 1)
    ents = {}
    for m in re.finditer(r"#(\d+)\s*=\s*(.*?);\n", body, re.S):
        ents[int(m.group(1))] = " ".join(m.group(2).split())
    nxt = max(ents) + 1
    new = []

    def add(t):
        nonlocal nxt
        new.append((nxt, t)); nxt += 1; return nxt - 1

    nauos = sorted(k for k, v in ents.items() if v.startswith("NEXT_ASSEMBLY_USAGE_OCCURRENCE("))

    def placement_pds(nauo):
        return next(k for k, v in ents.items() if v.startswith("PRODUCT_DEFINITION_SHAPE('Placement'") and v.endswith("#%d)" % nauo))

    def info(nauo):
        pd_ = placement_pds(nauo)
        cdsr = next(v for v in ents.values() if v.startswith("CONTEXT_DEPENDENT_SHAPE_REPRESENTATION(") and v.rstrip(";").endswith("#%d)" % pd_))
        rr = int(re.match(r"CONTEXT_DEPENDENT_SHAPE_REPRESENTATION\(#(\d+),", cdsr).group(1))
        child_sr, parent_sr = (int(x) for x in re.search(r"REPRESENTATION_RELATIONSHIP\('','',#(\d+),#(\d+)\)", ents[rr]).groups())
        idt = int(re.search(r"WITH_TRANSFORMATION\(#(\d+)\)", ents[rr]).group(1))
        axis = int(re.match(r"ITEM_DEFINED_TRANSFORMATION\('','',#\d+,#(\d+)\)", ents[idt]).group(1))
        ctx = int(re.search(r",#(\d+)\)$", ents[parent_sr]).group(1))
        child_items = [int(x) for x in re.search(r"\('',\((.*?)\),#\d+\)$", ents[child_sr]).group(1).replace("#", "").split(",")]
        return pd_, axis, ctx, child_items

    def styled_items(nauo):
        pd_ = placement_pds(nauo)
        srs = [int(re.match(r"SHAPE_DEFINITION_REPRESENTATION\(#%d,#(\d+)\)" % pd_, v).group(1))
               for v in ents.values() if re.match(r"SHAPE_DEFINITION_REPRESENTATION\(#%d,#\d+\)" % pd_, v)]
        psbcs = [k for k, v in ents.items() if v.startswith("PRESENTATION_STYLE_BY_CONTEXT(") and any(v.endswith(",#%d)" % s) for s in srs)]
        return [k for k, v in ents.items() if v.startswith("STYLED_ITEM(") and any("(#%d)" % p in v for p in psbcs)]

    mdgpr = [k for k, v in ents.items() if v.startswith("MECHANICAL_DESIGN_GEOMETRIC_PRESENTATION_REPRESENTATION(")]
    assigned = {}  # layer name -> items
    order = []
    for idxs, name in layers:
        items = []
        for n in idxs:
            nauo = nauos[n - 1]
            pd_, axis, ctx, child_items = info(nauo)
            if pattern == "p1":
                items += styled_items(nauo)[:1]
            elif pattern == "p4":
                items += mdgpr[:1]
            elif pattern == "p3":
                items += [k for k in child_items if ents[k].startswith("MANIFOLD_SOLID_BREP(")]
            elif pattern == "p5":
                items.append(nauo)
            elif pattern in ("p2", "p2b"):
                ax = axis
                if pattern == "p2b":
                    m = re.search(r"AXIS2_PLACEMENT_3D\('',#(\d+),#(\d+),#(\d+)\)", ents[axis])
                    ax = add("AXIS2_PLACEMENT_3D('',#%s,#%s,#%s)" % m.groups())
                sr = add("SHAPE_REPRESENTATION('',(#%d),#%d)" % (ax, ctx))
                if not no_sdr:
                    add("SHAPE_DEFINITION_REPRESENTATION(#%d,#%d)" % (pd_, sr))
                items.append(ax)
            else:
                raise SystemExit("unknown pattern " + pattern)
        if name not in assigned:
            order.append(name); assigned[name] = []
        assigned[name] += items
    for name in order:
        lit = (raw_name if raw_name is not None else name).replace("'", "''")
        add("PRESENTATION_LAYER_ASSIGNMENT('%s','visible',(%s))" % (lit, ",".join("#%d" % x for x in assigned[name])))
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        f.write(head + "DATA;\n" + body + "".join("#%d=%s;\n" % (k, v) for k, v in new) + "ENDSEC;" + tail)


if __name__ == "__main__":
    main(sys.argv[1:])

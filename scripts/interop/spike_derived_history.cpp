// Spike for ADR-0034 (derived edge naming): measures what OCCT's Generated()/Modified()/IsDeleted() report for
// fillet, chamfer and boolean operations, so the bridge only builds what OCCT actually delivers.
// Standalone: does not use the kSTEP bridge. Build and run via scripts/interop/verify-derived-naming.sh --spike.
#include <BRepAlgoAPI_BooleanOperation.hxx>
#include <BRepAlgoAPI_Common.hxx>
#include <BRepAlgoAPI_Cut.hxx>
#include <BRepAlgoAPI_Fuse.hxx>
#include <BRepAdaptor_Curve.hxx>
#include <BRep_Tool.hxx>
#include <GeomAbs_CurveType.hxx>
#include <TopoDS_Edge.hxx>
#include <TopoDS_Vertex.hxx>
#include <BRepBuilderAPI_MakeShape.hxx>
#include <BRepFilletAPI_MakeChamfer.hxx>
#include <BRepFilletAPI_MakeFillet.hxx>
#include <BRepGProp.hxx>
#include <BRepPrimAPI_MakeBox.hxx>
#include <BRepPrimAPI_MakeCylinder.hxx>
#include <GProp_GProps.hxx>
#include <Standard_Version.hxx>
#include <TopAbs_ShapeEnum.hxx>
#include <TopExp.hxx>
#include <TopExp_Explorer.hxx>
#include <TopTools_IndexedMapOfShape.hxx>
#include <TopTools_ListIteratorOfListOfShape.hxx>
#include <TopTools_ListOfShape.hxx>
#include <TopoDS.hxx>
#include <TopoDS_Shape.hxx>
#include <gp_Ax2.hxx>
#include <gp_Dir.hxx>
#include <gp_Pnt.hxx>

#include <cstdio>
#include <iostream>
#include <string>

static const char* kindName(TopAbs_ShapeEnum t) {
    switch (t) {
        case TopAbs_VERTEX: return "V";
        case TopAbs_EDGE: return "E";
        case TopAbs_FACE: return "F";
        case TopAbs_SOLID: return "S";
        default: return "?";
    }
}

// Reports, for every sub-shape of `type` in `operand`, what Generated() and Modified() deliver and where it lands in `result`.
static void report(const char* label, BRepBuilderAPI_MakeShape& maker, const TopoDS_Shape& operand, TopAbs_ShapeEnum type,
                   const TopoDS_Shape& result) {
    TopTools_IndexedMapOfShape subs, resEdges, resFaces;
    TopExp::MapShapes(operand, type, subs);
    TopExp::MapShapes(result, TopAbs_EDGE, resEdges);
    TopExp::MapShapes(result, TopAbs_FACE, resFaces);
    int withGen = 0, withMod = 0, deleted = 0;
    for (int i = 1; i <= subs.Extent(); ++i) {
        const TopoDS_Shape& s = subs(i);
        std::string gen, mod;
        int nGen = 0, nMod = 0;
        for (TopTools_ListIteratorOfListOfShape it(maker.Generated(s)); it.More(); it.Next()) {
            ++nGen;
            const TopoDS_Shape& g = it.Value();
            int inE = g.ShapeType() == TopAbs_EDGE ? resEdges.FindIndex(g) : 0;
            int inF = g.ShapeType() == TopAbs_FACE ? resFaces.FindIndex(g) : 0;
            gen += std::string(" ") + kindName(g.ShapeType()) + (inE > 0 ? "@E" + std::to_string(inE - 1) : "") +
                   (inF > 0 ? "@F" + std::to_string(inF - 1) : (g.ShapeType() == TopAbs_FACE ? "@notInResult" : ""));
        }
        for (TopTools_ListIteratorOfListOfShape it(maker.Modified(s)); it.More(); it.Next()) {
            ++nMod;
            const TopoDS_Shape& g = it.Value();
            int inE = g.ShapeType() == TopAbs_EDGE ? resEdges.FindIndex(g) : 0;
            int inF = g.ShapeType() == TopAbs_FACE ? resFaces.FindIndex(g) : 0;
            mod += std::string(" ") + kindName(g.ShapeType()) + (inE > 0 ? "@E" + std::to_string(inE - 1) : "") +
                   (inF > 0 ? "@F" + std::to_string(inF - 1) : "");
        }
        bool del = maker.IsDeleted(s);
        if (nGen) ++withGen;
        if (nMod) ++withMod;
        if (del) ++deleted;
        if (nGen || nMod || del)
            std::printf("  %s %s%d: generated[%d]%s modified[%d]%s deleted=%d\n", label, kindName(type), i - 1, nGen, gen.c_str(),
                        nMod, mod.c_str(), del ? 1 : 0);
    }
    std::printf("  %s %s total=%d withGenerated=%d withModified=%d deleted=%d\n", label, kindName(type), subs.Extent(), withGen,
                withMod, deleted);
}

static void reportAll(const char* tag, BRepBuilderAPI_MakeShape& maker, const TopoDS_Shape& op, const char* opName,
                      const TopoDS_Shape& result) {
    std::string l = std::string(tag) + "/" + opName;
    report(l.c_str(), maker, op, TopAbs_FACE, result);
    report(l.c_str(), maker, op, TopAbs_EDGE, result);
    report(l.c_str(), maker, op, TopAbs_VERTEX, result);
}

static int count(const TopoDS_Shape& s, TopAbs_ShapeEnum t) {
    TopTools_IndexedMapOfShape m;
    TopExp::MapShapes(s, t, m);
    return m.Extent();
}

static double volume(const TopoDS_Shape& s) {
    GProp_GProps p;
    BRepGProp::VolumeProperties(s, p);
    return p.Mass();
}

static TopoDS_Shape unwrap(const TopoDS_Shape& s) {
    for (TopExp_Explorer ex(s, TopAbs_SOLID); ex.More(); ex.Next()) return ex.Current();
    return s;
}

static TopoDS_Shape plate() { return BRepPrimAPI_MakeBox(40, 40, 10).Shape(); }

static TopoDS_Shape drill(double r, double zStart, double depth) {
    return BRepPrimAPI_MakeCylinder(gp_Ax2(gp_Pnt(20, 20, zStart), gp_Dir(0, 0, -1)), r, depth).Shape();
}

// Booleans: reports before and after SimplifyResult. `ops` names the two operands.
static void runBoolean(const char* tag, BRepAlgoAPI_BooleanOperation& algo, const TopoDS_Shape& a, const TopoDS_Shape& b) {
    TopTools_ListOfShape args, tools;
    args.Append(a);
    tools.Append(b);
    algo.SetArguments(args);
    algo.SetTools(tools);
    algo.SetRunParallel(Standard_False);
    algo.Build();
    if (algo.HasErrors() || !algo.IsDone()) {
        std::printf("%s: FAILED\n", tag);
        return;
    }
    TopoDS_Shape pre = algo.Shape();
    std::printf("%s pre-Simplify: faces=%d edges=%d vol=%.3f\n", tag, count(pre, TopAbs_FACE), count(pre, TopAbs_EDGE), volume(pre));
    std::string t = std::string(tag) + "/pre";
    reportAll(t.c_str(), algo, a, "A", pre);
    reportAll(t.c_str(), algo, b, "B", pre);
    algo.SimplifyResult();
    TopoDS_Shape post = unwrap(algo.Shape());
    std::printf("%s post-Simplify+unwrap: faces=%d edges=%d vol=%.3f\n", tag, count(post, TopAbs_FACE), count(post, TopAbs_EDGE),
                volume(post));
    t = std::string(tag) + "/post";
    reportAll(t.c_str(), algo, a, "A", post);
    reportAll(t.c_str(), algo, b, "B", post);
}

int main() {
    std::printf("OCCT %s\n", OCC_VERSION_COMPLETE);

    // S1 / S2: fillet and chamfer on one vertical box edge. Find an edge parallel to Z at x=20,y=20 corner.
    for (int blend = 0; blend < 2; ++blend) {
        TopoDS_Shape box = BRepPrimAPI_MakeBox(20, 20, 10).Shape();
        TopTools_IndexedMapOfShape edges;
        TopExp::MapShapes(box, TopAbs_EDGE, edges);
        TopoDS_Edge pick;
        for (int i = 1; i <= edges.Extent(); ++i) {
            TopoDS_Edge e = TopoDS::Edge(edges(i));
            TopoDS_Vertex v1, v2;
            TopExp::Vertices(e, v1, v2);
            gp_Pnt p1 = BRep_Tool::Pnt(v1), p2 = BRep_Tool::Pnt(v2);
            if (std::abs(p1.X() - 20) < 1e-9 && std::abs(p1.Y() - 20) < 1e-9 && std::abs(p2.X() - 20) < 1e-9 &&
                std::abs(p2.Y() - 20) < 1e-9) {
                pick = e;
                break;
            }
        }
        if (blend == 0) {
            BRepFilletAPI_MakeFillet m(box);
            m.Add(2.0, pick);
            m.Build();
            TopoDS_Shape r = m.Shape();
            std::printf("S1 fillet r=2 on one vertical edge: faces=%d edges=%d vol=%.3f\n", count(r, TopAbs_FACE),
                        count(r, TopAbs_EDGE), volume(r));
            reportAll("S1", m, box, "box", r);
        } else {
            BRepFilletAPI_MakeChamfer m(box);
            m.Add(2.0, pick);
            m.Build();
            TopoDS_Shape r = m.Shape();
            std::printf("S2 chamfer d=2 on one vertical edge: faces=%d edges=%d vol=%.3f\n", count(r, TopAbs_FACE),
                        count(r, TopAbs_EDGE), volume(r));
            reportAll("S2", m, box, "box", r);
        }
    }

    // S3: through hole (cut cylinder r=4, start z=11, dir -Z, depth 12)
    {
        TopoDS_Shape a = plate(), b = drill(4, 11, 12);
        BRepAlgoAPI_Cut cut;
        runBoolean("S3 cut through-hole", cut, a, b);
    }
    // S4: blind hole depth 5 (start z=10 exactly at the top face, like the hole feature, depth 5)
    {
        TopoDS_Shape a = plate(), b = drill(4, 11, 6);  // bottom of the drill at z=5
        BRepAlgoAPI_Cut cut;
        runBoolean("S4 cut blind-hole", cut, a, b);
    }
    // S5 / S6: two offset boxes
    {
        TopoDS_Shape a = BRepPrimAPI_MakeBox(20, 20, 10).Shape();
        TopoDS_Shape b = BRepPrimAPI_MakeBox(gp_Pnt(10, 10, 0), 20, 20, 10).Shape();
        BRepAlgoAPI_Fuse fuse;
        runBoolean("S5 fuse offset boxes", fuse, a, b);
        BRepAlgoAPI_Common common;
        runBoolean("S6 common offset boxes", common, a, b);
    }
    // S7: fillet r=1 on the rim of the through hole (edges of the top cap circle)
    {
        TopoDS_Shape a = plate(), b = drill(4, 11, 12);
        BRepAlgoAPI_Cut cut;
        TopTools_ListOfShape args, tools;
        args.Append(a);
        tools.Append(b);
        cut.SetArguments(args);
        cut.SetTools(tools);
        cut.SetRunParallel(Standard_False);
        cut.Build();
        cut.SimplifyResult();
        TopoDS_Shape solid = unwrap(cut.Shape());
        TopTools_IndexedMapOfShape edges;
        TopExp::MapShapes(solid, TopAbs_EDGE, edges);
        BRepFilletAPI_MakeFillet m(solid);
        int added = 0;
        for (int i = 1; i <= edges.Extent(); ++i) {
            TopoDS_Edge e = TopoDS::Edge(edges(i));
            BRepAdaptor_Curve c(e);
            if (c.GetType() != GeomAbs_Circle) continue;
            gp_Pnt mid = c.Value((c.FirstParameter() + c.LastParameter()) / 2.0);
            if (std::abs(mid.Z() - 10.0) < 1e-6) {
                m.Add(1.0, e);
                ++added;
            }
        }
        std::printf("S7 rim fillet: circle edges at z=10 added=%d\n", added);
        m.Build();
        if (m.IsDone()) {
            TopoDS_Shape r = m.Shape();
            std::printf("S7 result: faces=%d edges=%d vol=%.3f\n", count(r, TopAbs_FACE), count(r, TopAbs_EDGE), volume(r));
            reportAll("S7", m, solid, "holed plate", r);
        } else {
            std::printf("S7: fillet failed\n");
        }
    }
    // S8: fillet on two edges meeting in a corner (vertex generation), documented only
    {
        TopoDS_Shape box = BRepPrimAPI_MakeBox(20, 20, 10).Shape();
        TopTools_IndexedMapOfShape edges;
        TopExp::MapShapes(box, TopAbs_EDGE, edges);
        BRepFilletAPI_MakeFillet m(box);
        int added = 0;
        for (int i = 1; i <= edges.Extent() && added < 2; ++i) {
            TopoDS_Edge e = TopoDS::Edge(edges(i));
            TopoDS_Vertex v1, v2;
            TopExp::Vertices(e, v1, v2);
            gp_Pnt p1 = BRep_Tool::Pnt(v1), p2 = BRep_Tool::Pnt(v2);
            bool atOrigin = (p1.Distance(gp_Pnt(0, 0, 0)) < 1e-9 || p2.Distance(gp_Pnt(0, 0, 0)) < 1e-9);
            if (atOrigin) {
                m.Add(2.0, e);
                ++added;
            }
        }
        m.Build();
        if (m.IsDone()) {
            TopoDS_Shape r = m.Shape();
            std::printf("S8 fillet on two edges meeting in a corner: faces=%d edges=%d\n", count(r, TopAbs_FACE),
                        count(r, TopAbs_EDGE));
            report("S8/box", m, box, TopAbs_VERTEX, r);
        }
    }
    return 0;
}

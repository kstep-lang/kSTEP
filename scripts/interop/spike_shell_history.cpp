// Spike for ADR-0037 (shell / hollowing): measures what BRepOffsetAPI_MakeThickSolid does for a box and a cylinder
// (sign of the offset, topology, edge history), how it fails, how long it takes on large inputs, and how long
// BRepExtrema_DistShapeShape needs for point-to-edge / point-to-face queries. Standalone; build and run via
// scripts/interop/verify-face-ops.sh --spike. All numbers in the ADR come from this program.
#include <BRepAlgoAPI_Cut.hxx>
#include <gp_Ax2.hxx>
#include <gp_Dir.hxx>
#include <BRep_Builder.hxx>
#include <BRep_Tool.hxx>
#include <TopoDS_Compound.hxx>
#include <TopoDS_Edge.hxx>
#include <TopoDS_Vertex.hxx>
#include <BRepBndLib.hxx>
#include <BRepBuilderAPI_MakeVertex.hxx>
#include <BRepCheck_Analyzer.hxx>
#include <BRepExtrema_DistShapeShape.hxx>
#include <BRepFilletAPI_MakeFillet.hxx>
#include <BRepGProp.hxx>
#include <BRepOffsetAPI_MakeThickSolid.hxx>
#include <BRepPrimAPI_MakeBox.hxx>
#include <BRepPrimAPI_MakeCylinder.hxx>
#include <BRepPrimAPI_MakePrism.hxx>
#include <BRepBuilderAPI_MakePolygon.hxx>
#include <BRepBuilderAPI_MakeFace.hxx>
#include <Bnd_Box.hxx>
#include <GProp_GProps.hxx>
#include <GeomAbs_JoinType.hxx>
#include <Precision.hxx>
#include <Standard_Failure.hxx>
#include <Standard_Version.hxx>
#include <TopAbs_ShapeEnum.hxx>
#include <TopExp.hxx>
#include <TopExp_Explorer.hxx>
#include <TopTools_IndexedMapOfShape.hxx>
#include <TopTools_ListIteratorOfListOfShape.hxx>
#include <TopTools_ListOfShape.hxx>
#include <TopoDS.hxx>
#include <TopoDS_Face.hxx>
#include <gp_Pnt.hxx>
#include <gp_Vec.hxx>

#include <chrono>
#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

static double volume(const TopoDS_Shape& s) {
    GProp_GProps p;
    BRepGProp::VolumeProperties(s, p);
    return p.Mass();
}

static int count(const TopoDS_Shape& s, TopAbs_ShapeEnum t) {
    TopTools_IndexedMapOfShape m;
    TopExp::MapShapes(s, t, m);
    return m.Extent();
}

static int countExplorer(const TopoDS_Shape& s, TopAbs_ShapeEnum t) {
    int n = 0;
    for (TopExp_Explorer ex(s, t); ex.More(); ex.Next()) ++n;
    return n;
}

static void box(const char* label, const TopoDS_Shape& s) {
    Bnd_Box b;
    BRepBndLib::AddOptimal(s, b, Standard_False, Standard_False);
    double x0, y0, z0, x1, y1, z1;
    b.Get(x0, y0, z0, x1, y1, z1);
    std::printf("   [%s] bbox x[%.6g,%.6g] y[%.6g,%.6g] z[%.6g,%.6g]\n", label, x0, x1, y0, y1, z0, z1);
}

// Index (1-based) of the face with the highest centroid z.
static int topFace(const TopoDS_Shape& s) {
    TopTools_IndexedMapOfShape faces;
    TopExp::MapShapes(s, TopAbs_FACE, faces);
    int best = 0;
    double bestZ = -1e300;
    for (int i = 1; i <= faces.Extent(); ++i) {
        GProp_GProps p;
        BRepGProp::SurfaceProperties(faces(i), p);
        double z = p.CentreOfMass().Z();
        if (z > bestZ + 1e-9) { bestZ = z; best = i; }
    }
    return best;
}

struct ShellResult {
    bool ok = false;
    bool threw = false;
    std::string what;
    TopoDS_Shape shape;
    double ms = 0.0;
};

static ShellResult thick(BRepOffsetAPI_MakeThickSolid& mk, const TopoDS_Shape& s, const std::vector<int>& open,
                         double offset, double tol, GeomAbs_JoinType join, bool intersection = false) {
    ShellResult r;
    TopTools_IndexedMapOfShape faces;
    TopExp::MapShapes(s, TopAbs_FACE, faces);
    TopTools_ListOfShape closing;
    for (int i : open) closing.Append(faces(i));
    auto t0 = std::chrono::steady_clock::now();
    try {
        mk.MakeThickSolidByJoin(s, closing, offset, tol, BRepOffset_Skin, intersection, Standard_False, join, Standard_False);
        mk.Build();
        r.ok = mk.IsDone();
        if (r.ok) r.shape = mk.Shape();
    } catch (const Standard_Failure& e) {
        r.threw = true;
        r.what = e.GetMessageString() ? e.GetMessageString() : "(no message)";
    }
    r.ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    return r;
}

static void describe(const char* label, const ShellResult& r) {
    if (r.threw) { std::printf("[%s] THREW: %s  (%.1f ms)\n", label, r.what.c_str(), r.ms); return; }
    if (!r.ok) { std::printf("[%s] IsDone()==false (%.1f ms)\n", label, r.ms); return; }
    BRepCheck_Analyzer a(r.shape);
    std::printf("[%s] type=%d solids=%d shells=%d faces=%d edges=%d vertices=%d valid=%d V=%.6f (%.1f ms)\n", label,
                static_cast<int>(r.shape.ShapeType()), countExplorer(r.shape, TopAbs_SOLID), countExplorer(r.shape, TopAbs_SHELL),
                count(r.shape, TopAbs_FACE), count(r.shape, TopAbs_EDGE), count(r.shape, TopAbs_VERTEX), a.IsValid() ? 1 : 0,
                volume(r.shape), r.ms);
    box(label, r.shape);
}

static void edgeHistory(const char* label, BRepOffsetAPI_MakeThickSolid& mk, const TopoDS_Shape& in, const TopoDS_Shape& out) {
    TopTools_IndexedMapOfShape inEdges, outEdges;
    TopExp::MapShapes(in, TopAbs_EDGE, inEdges);
    TopExp::MapShapes(out, TopAbs_EDGE, outEdges);
    int kept = 0, mod1 = 0, modN = 0, gen = 0, del = 0, lost = 0;
    for (int i = 1; i <= inEdges.Extent(); ++i) {
        const TopoDS_Shape& e = inEdges(i);
        int mapped = 0;
        const TopTools_ListOfShape& m = mk.Modified(e);
        for (TopTools_ListIteratorOfListOfShape it(m); it.More(); it.Next())
            if (it.Value().ShapeType() == TopAbs_EDGE && outEdges.FindIndex(it.Value()) > 0) ++mapped;
        int g = 0;
        for (TopTools_ListIteratorOfListOfShape it(mk.Generated(e)); it.More(); it.Next()) ++g;
        const bool isDel = mk.IsDeleted(e);
        const bool survives = outEdges.FindIndex(e) > 0;
        std::printf("   [%s] edge %2d: Modified->%d (list %d) Generated=%d IsDeleted=%d survivesByIdentity=%d\n", label, i - 1, mapped,
                    m.Extent(), g, isDel ? 1 : 0, survives ? 1 : 0);
        if (mapped == 1) ++mod1; else if (mapped > 1) ++modN;
        else if (m.IsEmpty() && survives) ++kept;
        else if (isDel) ++del; else ++lost;
        gen += g;
    }
    std::printf("   [%s] summary: kept=%d modified1=%d modifiedN=%d deleted=%d lost(no image, not deleted)=%d generated=%d\n", label, kept,
                mod1, modN, del, lost, gen);
}


// Compact identity-survival summary for the edge history decision (ADR-0037, D6).
static void identitySummary(const char* label, BRepOffsetAPI_MakeThickSolid& mk, const TopoDS_Shape& in, const TopoDS_Shape& out) {
    TopTools_IndexedMapOfShape inEdges, outEdges;
    TopExp::MapShapes(in, TopAbs_EDGE, inEdges);
    TopExp::MapShapes(out, TopAbs_EDGE, outEdges);
    int survive = 0, modNonEmpty = 0, modSelf = 0, modEdgeOther = 0, modNonEdge = 0, deleted = 0;
    for (int i = 1; i <= inEdges.Extent(); ++i) {
        const TopoDS_Shape& e = inEdges(i);
        if (outEdges.FindIndex(e) > 0) ++survive;
        if (mk.IsDeleted(e)) ++deleted;
        const TopTools_ListOfShape& m = mk.Modified(e);
        if (!m.IsEmpty()) ++modNonEmpty;
        for (TopTools_ListIteratorOfListOfShape it(m); it.More(); it.Next()) {
            if (it.Value().ShapeType() != TopAbs_EDGE) ++modNonEdge;
            else if (it.Value().IsSame(e)) ++modSelf;
            else ++modEdgeOther;
        }
    }
    std::printf("   [%s] edge identity: %d of %d input edges survive by identity; Modified non-empty for %d (self=%d, other edge=%d, non-edge=%d); IsDeleted=%d\n",
                label, survive, inEdges.Extent(), modNonEmpty, modSelf, modEdgeOther, modNonEdge, deleted);
}

static void faceHistory(const char* label, BRepOffsetAPI_MakeThickSolid& mk, const TopoDS_Shape& in, const TopoDS_Shape& out) {
    TopTools_IndexedMapOfShape inFaces, outFaces;
    TopExp::MapShapes(in, TopAbs_FACE, inFaces);
    TopExp::MapShapes(out, TopAbs_FACE, outFaces);
    for (int i = 1; i <= inFaces.Extent(); ++i) {
        const TopoDS_Shape& f = inFaces(i);
        int mapped = 0;
        for (TopTools_ListIteratorOfListOfShape it(mk.Modified(f)); it.More(); it.Next())
            if (outFaces.FindIndex(it.Value()) > 0) ++mapped;
        int g = 0;
        for (TopTools_ListIteratorOfListOfShape it(mk.Generated(f)); it.More(); it.Next()) ++g;
        std::printf("   [%s] face %d: Modified->%d Generated=%d IsDeleted=%d survivesByIdentity=%d\n", label, i - 1, mapped, g,
                    mk.IsDeleted(f) ? 1 : 0, outFaces.FindIndex(f) > 0 ? 1 : 0);
    }
}

// Regular n-gon prism with n side faces + 2 caps, oriented so the cap with the highest z can be opened.
static TopoDS_Shape prism(int n, double r, double h) {
    BRepBuilderAPI_MakePolygon poly;
    for (int i = 0; i < n; ++i) {
        double a = 2.0 * M_PI * i / n;
        poly.Add(gp_Pnt(r * std::cos(a), r * std::sin(a), 0.0));
    }
    poly.Close();
    BRepBuilderAPI_MakeFace face(poly.Wire(), Standard_True);
    return BRepPrimAPI_MakePrism(face.Face(), gp_Vec(0, 0, h)).Shape();
}

static void distanceTiming(const char* label, const TopoDS_Shape& s) {
    TopTools_IndexedMapOfShape edges, faces;
    TopExp::MapShapes(s, TopAbs_EDGE, edges);
    TopExp::MapShapes(s, TopAbs_FACE, faces);
    BRepBuilderAPI_MakeVertex mv(gp_Pnt(1000.0, 3.0, 2.0));
    TopoDS_Vertex v = mv.Vertex();
    for (int pass = 0; pass < 2; ++pass) {
        const TopTools_IndexedMapOfShape& m = pass == 0 ? edges : faces;
        auto t0 = std::chrono::steady_clock::now();
        int failed = 0;
        for (int i = 1; i <= m.Extent(); ++i) {
            BRepExtrema_DistShapeShape d(v, m(i));
            if (!d.IsDone()) ++failed;
        }
        double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
        std::printf("[%s] point->%s distances: %d queries, %d failed, %.1f ms total\n", label, pass == 0 ? "edge" : "face", m.Extent(),
                    failed, ms);
    }
}

int main() {
    std::printf("OCCT %s\n", OCC_VERSION_COMPLETE);
    const double tol = Precision::Confusion();
    TopoDS_Shape b = BRepPrimAPI_MakeBox(40, 30, 20).Shape();
    const int top = topFace(b);
    std::printf("== box 40x30x20 V=%.6f, open face index (0-based) %d\n", volume(b), top - 1);

    // 1+2: signature and sign, both join types.
    for (GeomAbs_JoinType j : {GeomAbs_Intersection, GeomAbs_Arc}) {
        const char* jn = j == GeomAbs_Intersection ? "Intersection" : "Arc";
        for (double off : {-2.0, 2.0}) {
            BRepOffsetAPI_MakeThickSolid mk;
            std::string label = std::string("box ") + jn + " offset " + (off < 0 ? "-2" : "+2");
            ShellResult r = thick(mk, b, {top}, off, tol, j, j == GeomAbs_Intersection);
            describe(label.c_str(), r);
            if (r.ok && j == GeomAbs_Intersection && off < 0) {
                // 4: history on the inward Intersection variant.
                edgeHistory("box inward", mk, b, r.shape);
                faceHistory("box inward", mk, b, r.shape);
            }
        }
    }
    {
        // Intersection=false with Intersection join (to document whether the flag matters).
        BRepOffsetAPI_MakeThickSolid mk;
        describe("box Intersection join, flag Intersection=false, offset +2", thick(mk, b, {top}, 2.0, tol, GeomAbs_Intersection, false));
    }
    {
        BRepOffsetAPI_MakeThickSolid mk;
        ShellResult r = thick(mk, b, {top}, 2.0, tol, GeomAbs_Intersection, true);
        if (r.ok) edgeHistory("box outward", mk, b, r.shape);
    }


    // 4b: identity survival across shapes and both directions.
    {
        struct Case { const char* name; TopoDS_Shape shape; double off; };
        TopoDS_Shape cyl = BRepPrimAPI_MakeCylinder(10.0, 20.0).Shape();
        TopoDS_Shape pr = prism(12, 30.0, 20.0);
        std::vector<Case> cases = {{"box inward", b, -2.0}, {"box outward", b, 2.0}, {"cylinder inward", cyl, -1.0},
                                   {"cylinder outward", cyl, 1.0}, {"12-prism inward", pr, -1.0}, {"12-prism outward", pr, 1.0}};
        for (const Case& c : cases) {
            BRepOffsetAPI_MakeThickSolid mk;
            ShellResult r = thick(mk, c.shape, {topFace(c.shape)}, c.off, tol, GeomAbs_Intersection, true);
            if (r.ok) identitySummary(c.name, mk, c.shape, r.shape); else std::printf("   [%s] failed\n", c.name);
        }
    }

    // 5: cylinder.
    {
        TopoDS_Shape c = BRepPrimAPI_MakeCylinder(10.0, 20.0).Shape();
        int ct = topFace(c);
        // topFace picks the cap by centroid z; the cylinder mantle centroid z is 10, the cap's 20.
        BRepOffsetAPI_MakeThickSolid mk;
        ShellResult r = thick(mk, c, {ct}, -1.0, tol, GeomAbs_Intersection, true);
        describe("cylinder r10 h20 t1 inward", r);
        std::printf("   expected V = %.6f\n", M_PI * (10.0 * 10.0 * 20.0 - 9.0 * 9.0 * 19.0));
    }

    // 6: failures.
    {
        // Fillet r=1 on the four vertical edges, then shell inward with t=2 > r.
        BRepFilletAPI_MakeFillet f(b);
        TopTools_IndexedMapOfShape edges;
        TopExp::MapShapes(b, TopAbs_EDGE, edges);
        for (int i = 1; i <= edges.Extent(); ++i) {
            const TopoDS_Edge e = TopoDS::Edge(edges(i));
            gp_Pnt p0 = BRep_Tool::Pnt(TopExp::FirstVertex(e));
            gp_Pnt p1 = BRep_Tool::Pnt(TopExp::LastVertex(e));
            if (std::abs(p0.X() - p1.X()) < 1e-9 && std::abs(p0.Y() - p1.Y()) < 1e-9) f.Add(1.0, e);
        }
        f.Build();
        if (f.IsDone()) {
            TopoDS_Shape fb = f.Shape();
            int ft = topFace(fb);
            std::printf("== filleted box (r=1 vertical edges): V=%.6f faces=%d\n", volume(fb), count(fb, TopAbs_FACE));
            for (double t : {2.0, 0.5}) {
                BRepOffsetAPI_MakeThickSolid mk;
                std::string label = "filleted box inward t=" + std::to_string(t);
                describe(label.c_str(), thick(mk, fb, {ft}, -t, tol, GeomAbs_Intersection, true));
            }
        }
    }
    {
        BRepOffsetAPI_MakeThickSolid mk;
        describe("box offset 0", thick(mk, b, {top}, 0.0, tol, GeomAbs_Intersection, true));
    }
    {
        BRepOffsetAPI_MakeThickSolid mk;
        TopTools_IndexedMapOfShape faces;
        TopExp::MapShapes(b, TopAbs_FACE, faces);
        std::vector<int> all;
        for (int i = 1; i <= faces.Extent(); ++i) all.push_back(i);
        describe("box ALL faces open", thick(mk, b, all, -2.0, tol, GeomAbs_Intersection, true));
    }
    {
        // Shell with NO open face (empty closing list) -> two shells.
        BRepOffsetAPI_MakeThickSolid mk;
        describe("box NO open face", thick(mk, b, {}, -2.0, tol, GeomAbs_Intersection, true));
    }
    {
        // Thickness at / above half the smallest extent.
        for (double t : {9.9, 10.0, 12.0}) {
            BRepOffsetAPI_MakeThickSolid mk;
            std::string label = "box inward t=" + std::to_string(t) + " (half of smallest extent is 10)";
            describe(label.c_str(), thick(mk, b, {top}, -t, tol, GeomAbs_Intersection, true));
        }
        BRepOffsetAPI_MakeThickSolid mk;
        describe("box outward t=12", thick(mk, b, {top}, 12.0, tol, GeomAbs_Intersection, true));
    }
    {
        // Two disjoint solids as one compound.
        BRep_Builder bb;
        TopoDS_Compound comp;
        bb.MakeCompound(comp);
        bb.Add(comp, BRepPrimAPI_MakeBox(10, 10, 10).Shape());
        bb.Add(comp, BRepPrimAPI_MakeBox(gp_Pnt(30, 0, 0), 10, 10, 10).Shape());
        BRepOffsetAPI_MakeThickSolid mk;
        ShellResult r = thick(mk, comp, {topFace(comp)}, -1.0, tol, GeomAbs_Intersection, true);
        describe("compound of two boxes (one open face)", r);
    }


    // 6b: search for real OCCT failures: large fillets shelled past their radius, thin walls, concave profiles.
    {
        for (double fr : {3.0, 8.0}) {
            BRepFilletAPI_MakeFillet f(b);
            TopTools_IndexedMapOfShape edges;
            TopExp::MapShapes(b, TopAbs_EDGE, edges);
            for (int i = 1; i <= edges.Extent(); ++i) {
                const TopoDS_Edge e = TopoDS::Edge(edges(i));
                gp_Pnt p0 = BRep_Tool::Pnt(TopExp::FirstVertex(e));
                gp_Pnt p1 = BRep_Tool::Pnt(TopExp::LastVertex(e));
                if (std::abs(p0.X() - p1.X()) < 1e-9 && std::abs(p0.Y() - p1.Y()) < 1e-9) f.Add(fr, e);
            }
            f.Build();
            if (!f.IsDone()) continue;
            TopoDS_Shape fb = f.Shape();
            for (double t : {fr - 0.5, fr + 0.5, 9.9}) {
                BRepOffsetAPI_MakeThickSolid mk;
                std::string label = "box fillet r=" + std::to_string(fr) + " inward t=" + std::to_string(t);
                describe(label.c_str(), thick(mk, fb, {topFace(fb)}, -t, tol, GeomAbs_Intersection, true));
            }
        }
        // L-shaped prism (concave corner): outward and inward.
        BRepBuilderAPI_MakePolygon poly;
        const double pts[6][2] = {{0, 0}, {40, 0}, {40, 10}, {10, 10}, {10, 40}, {0, 40}};
        for (auto& q : pts) poly.Add(gp_Pnt(q[0], q[1], 0));
        poly.Close();
        BRepBuilderAPI_MakeFace lf(poly.Wire(), Standard_True);
        TopoDS_Shape l = BRepPrimAPI_MakePrism(lf.Face(), gp_Vec(0, 0, 20)).Shape();
        for (double off : {-2.0, 2.0, -4.9, 4.9}) {
            BRepOffsetAPI_MakeThickSolid mk;
            std::string label = "L prism offset " + std::to_string(off);
            describe(label.c_str(), thick(mk, l, {topFace(l)}, off, tol, GeomAbs_Intersection, true));
        }
    }


    // 6c: more failure hunting: a plate with a drilled hole shelled thicker than the hole wall, near-radius cylinder.
    {
        TopoDS_Shape cyl = BRepPrimAPI_MakeCylinder(10.0, 20.0).Shape();
        for (double t : {9.99, 9.9999}) {
            BRepOffsetAPI_MakeThickSolid mk;
            std::string label = "cylinder r10 inward t=" + std::to_string(t);
            describe(label.c_str(), thick(mk, cyl, {topFace(cyl)}, -t, tol, GeomAbs_Intersection, true));
        }
        TopoDS_Shape plate = BRepPrimAPI_MakeBox(60, 60, 40).Shape();
        TopoDS_Shape pin = BRepPrimAPI_MakeCylinder(gp_Ax2(gp_Pnt(30, 30, -1), gp_Dir(0, 0, 1)), 6.0, 42.0).Shape();
        BRepAlgoAPI_Cut cut(plate, pin);
        TopoDS_Shape holed = cut.Shape();
        for (double off : {-2.0, -8.0, -9.5, 8.0, 12.0, 19.0}) {
            BRepOffsetAPI_MakeThickSolid mk;
            std::string label = "plate 60x60x40 with hole r=6, offset " + std::to_string(off);
            describe(label.c_str(), thick(mk, holed, {topFace(holed)}, off, tol, GeomAbs_Intersection, true));
        }
    }

    // 8: tolerance.
    for (double tl : {Precision::Confusion(), 1e-6, 1e-4}) {
        BRepOffsetAPI_MakeThickSolid mk;
        std::string label = "box inward t=2 tol=" + std::to_string(tl);
        describe(label.c_str(), thick(mk, b, {top}, -2.0, tl, GeomAbs_Intersection, true));
    }

    // 7: limits and time.
    for (int n : {50, 98, 198, 398}) {
        TopoDS_Shape p = prism(n, 30.0, 20.0);
        int pt = topFace(p);
        std::printf("== prism with %d side faces (%d faces total)\n", n, count(p, TopAbs_FACE));
        BRepOffsetAPI_MakeThickSolid mk;
        describe("prism inward t=1", thick(mk, p, {pt}, -1.0, tol, GeomAbs_Intersection, true));
        distanceTiming("prism", p);
    }
    {
        // Grid of ~5000 faces: 25 x 25 x 3 boxes fused by compound only (no booleans): a compound of 800 boxes = 4800 faces.
        BRep_Builder bb;
        TopoDS_Compound comp;
        bb.MakeCompound(comp);
        for (int i = 0; i < 28; ++i)
            for (int j = 0; j < 29; ++j)
                bb.Add(comp, BRepPrimAPI_MakeBox(gp_Pnt(i * 3.0, j * 3.0, 0), 2, 2, 2).Shape());
        std::printf("== grid compound: faces=%d edges=%d\n", count(comp, TopAbs_FACE), count(comp, TopAbs_EDGE));
        distanceTiming("grid", comp);
    }
    return 0;
}

// Spike for ADR-0040 (pocket / hole from a face, draft, thicken): measures on the installed OCCT what a
// prism-cut pocket, BRepOffsetAPI_DraftAngle and BRepOffset_MakeOffset do for a 40x30x20 box. Standalone; build and run
// via scripts/interop/verify-face-ops-2.sh --spike. All numbers in ADR-0040 come from this program.
#include <BRepAlgoAPI_Cut.hxx>
#include <BRepAlgoAPI_Fuse.hxx>
#include <BRepBndLib.hxx>
#include <BRepBuilderAPI_MakeFace.hxx>
#include <BRepBuilderAPI_MakePolygon.hxx>
#include <BRepBuilderAPI_MakeEdge.hxx>
#include <BRepBuilderAPI_MakeWire.hxx>
#include <BRepCheck_Analyzer.hxx>
#include <BRepClass_FaceClassifier.hxx>
#include <BRepGProp.hxx>
#include <BRepOffsetAPI_DraftAngle.hxx>
#include <BRepOffsetAPI_MakeThickSolid.hxx>
#include <BRepOffsetAPI_MakeOffsetShape.hxx>
#include <BRepOffset_MakeOffset.hxx>
#include <BRepPrimAPI_MakeBox.hxx>
#include <BRepPrimAPI_MakeCylinder.hxx>
#include <BRepPrimAPI_MakePrism.hxx>
#include <BRepAdaptor_Surface.hxx>
#include <Bnd_Box.hxx>
#include <GC_MakeCircle.hxx>
#include <GProp_GProps.hxx>
#include <Precision.hxx>
#include <Standard_Failure.hxx>
#include <Standard_Version.hxx>
#include <TopExp.hxx>
#include <TopExp_Explorer.hxx>
#include <TopTools_IndexedMapOfShape.hxx>
#include <TopTools_ListIteratorOfListOfShape.hxx>
#include <TopoDS.hxx>
#include <TopoDS_Face.hxx>
#include <gp_Ax2.hxx>
#include <gp_Circ.hxx>
#include <gp_Dir.hxx>
#include <gp_Pln.hxx>
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
static int explore(const TopoDS_Shape& s, TopAbs_ShapeEnum t) {
    int n = 0;
    for (TopExp_Explorer ex(s, t); ex.More(); ex.Next()) ++n;
    return n;
}
static double ms(std::chrono::steady_clock::time_point t0) {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
}
static void describe(const char* label, const TopoDS_Shape& r, double t) {
    BRepCheck_Analyzer a(r);
    Bnd_Box b;
    BRepBndLib::AddOptimal(r, b, Standard_False, Standard_False);
    double x0, y0, z0, x1, y1, z1;
    b.Get(x0, y0, z0, x1, y1, z1);
    std::printf("[%s] solids=%d shells=%d faces=%d edges=%d valid=%d V=%.6f bbox x[%.5g,%.5g] y[%.5g,%.5g] z[%.5g,%.5g] (%.1f ms)\n",
                label, explore(r, TopAbs_SOLID), explore(r, TopAbs_SHELL), count(r, TopAbs_FACE), count(r, TopAbs_EDGE),
                a.IsValid() ? 1 : 0, volume(r), x0, x1, y0, y1, z0, z1, t);
}
static void edgeHistory(const char* label, BRepBuilderAPI_MakeShape& mk, const TopoDS_Shape& in, const TopoDS_Shape& out) {
    TopTools_IndexedMapOfShape ie, oe;
    TopExp::MapShapes(in, TopAbs_EDGE, ie);
    TopExp::MapShapes(out, TopAbs_EDGE, oe);
    int kept = 0, mod1 = 0, modN = 0, del = 0, lost = 0, gen = 0;
    for (int i = 1; i <= ie.Extent(); ++i) {
        const TopoDS_Shape& e = ie(i);
        int mapped = 0;
        for (TopTools_ListIteratorOfListOfShape it(mk.Modified(e)); it.More(); it.Next())
            if (it.Value().ShapeType() == TopAbs_EDGE && oe.FindIndex(it.Value()) > 0) ++mapped;
        const bool survives = oe.FindIndex(e) > 0;
        for (TopTools_ListIteratorOfListOfShape it(mk.Generated(e)); it.More(); it.Next()) ++gen;
        if (mapped == 1) ++mod1;
        else if (mapped > 1) ++modN;
        else if (mk.Modified(e).IsEmpty() && survives) ++kept;
        else if (mk.IsDeleted(e)) ++del;
        else ++lost;
    }
    std::printf("   [%s] edges in=%d: kept=%d modified1=%d modifiedN=%d deleted=%d lost=%d generated=%d\n", label, ie.Extent(), kept,
                mod1, modN, del, lost, gen);
}

// Tool prism for a polygon / circle given in the local face frame (origin o, normal n, U, V).
struct Frame {
    gp_Pnt o;
    gp_Dir n, u, v;
};
static Frame frameOf(const gp_Pnt& centroid, const gp_Dir& n) {
    gp_Vec x(1, 0, 0);
    if (std::abs(n.Dot(gp_Dir(1, 0, 0))) > 1 - 1e-9) x = gp_Vec(0, 1, 0);
    gp_Vec u = x - gp_Vec(n) * x.Dot(gp_Vec(n));
    gp_Dir ud(u);
    gp_Dir vd(gp_Vec(n).Crossed(gp_Vec(ud)));
    return {centroid, n, ud, vd};
}
static gp_Pnt at(const Frame& f, double u, double v, double w = 0) {
    return gp_Pnt(f.o.XYZ() + f.u.XYZ() * u + f.v.XYZ() * v + f.n.XYZ() * w);
}
static TopoDS_Shape polyTool(const Frame& f, const std::vector<std::pair<double, double>>& pts, double startOut, double length) {
    BRepBuilderAPI_MakePolygon poly;
    for (auto& p : pts) poly.Add(at(f, p.first, p.second, startOut));
    poly.Close();
    BRepBuilderAPI_MakeFace face(poly.Wire(), Standard_True);
    return BRepPrimAPI_MakePrism(face.Face(), gp_Vec(f.n) * -length).Shape();
}
static TopoDS_Shape circTool(const Frame& f, double u, double v, double d, double startOut, double length) {
    gp_Circ c(gp_Ax2(at(f, u, v, startOut), f.n), d / 2.0);
    BRepBuilderAPI_MakeEdge e(c);
    BRepBuilderAPI_MakeWire w(e.Edge());
    BRepBuilderAPI_MakeFace face(w.Wire(), Standard_True);
    return BRepPrimAPI_MakePrism(face.Face(), gp_Vec(f.n) * -length).Shape();
}
static TopoDS_Face faceAt(const TopoDS_Shape& s, int idx) {
    TopTools_IndexedMapOfShape m;
    TopExp::MapShapes(s, TopAbs_FACE, m);
    return TopoDS::Face(m(idx));
}
static int faceWhere(const TopoDS_Shape& s, double nx, double ny, double nz) {
    TopTools_IndexedMapOfShape m;
    TopExp::MapShapes(s, TopAbs_FACE, m);
    for (int i = 1; i <= m.Extent(); ++i) {
        BRepAdaptor_Surface sa(TopoDS::Face(m(i)));
        if (sa.GetType() != GeomAbs_Plane) continue;
        gp_Dir d = sa.Plane().Axis().Direction();
        if (m(i).Orientation() == TopAbs_REVERSED) d.Reverse();
        if (d.Dot(gp_Dir(nx, ny, nz)) > 1 - 1e-9) return i;
    }
    return 0;
}
static Frame frameForFace(const TopoDS_Shape& s, int idx) {
    TopoDS_Face f = faceAt(s, idx);
    GProp_GProps p;
    BRepGProp::SurfaceProperties(f, p);
    BRepAdaptor_Surface sa(f);
    gp_Dir d = sa.Plane().Axis().Direction();
    if (f.Orientation() == TopAbs_REVERSED) d.Reverse();
    return frameOf(p.CentreOfMass(), d);
}

static TopoDS_Shape cut(const char* label, const TopoDS_Shape& body, const TopoDS_Shape& tool) {
    auto t0 = std::chrono::steady_clock::now();
    try {
        BRepAlgoAPI_Cut c(body, tool);
        if (!c.IsDone()) { std::printf("[%s] IsDone()==false\n", label); return TopoDS_Shape(); }
        describe(label, c.Shape(), ms(t0));
        edgeHistory(label, c, body, c.Shape());
        return c.Shape();
    } catch (const Standard_Failure& e) {
        std::printf("[%s] THREW %s\n", label, e.GetMessageString());
        return TopoDS_Shape();
    }
}

static const char* cls(TopAbs_State s) { return s == TopAbs_IN ? "IN" : s == TopAbs_ON ? "ON" : s == TopAbs_OUT ? "OUT" : "UNKNOWN"; }
static void classify(const char* label, const TopoDS_Face& face, const Frame& f, double u, double v) {
    BRepClass_FaceClassifier c(face, at(f, u, v), 1e-7);
    std::printf("   classify %s (%.3g,%.3g): %s\n", label, u, v, cls(c.State()));
}

static void s1() {
    std::printf("== S1 pocket (OCCT %s)\n", OCC_VERSION_COMPLETE);
    TopoDS_Shape box = BRepPrimAPI_MakeBox(40, 30, 20).Shape();
    const int top = faceWhere(box, 0, 0, 1);
    Frame f = frameForFace(box, top);
    std::printf("top face idx=%d centroid=(%.3g,%.3g,%.3g) n=(%.3g,%.3g,%.3g) U=(%.3g,%.3g,%.3g) V=(%.3g,%.3g,%.3g)\n", top, f.o.X(),
                f.o.Y(), f.o.Z(), f.n.X(), f.n.Y(), f.n.Z(), f.u.X(), f.u.Y(), f.u.Z(), f.v.X(), f.v.Y(), f.v.Z());
    std::vector<std::pair<double, double>> sq = {{-5, -5}, {5, -5}, {5, 5}, {-5, 5}};
    cut("rect flush depth5", box, polyTool(f, sq, 0, 5));
    const double eps = 1e-3;
    cut("rect eps-overshoot depth5", box, polyTool(f, sq, eps, 5 + eps));
    cut("circle d8 blind6 flush", box, circTool(f, 0, 0, 8, 0, 6));
    cut("circle d8 blind6 eps", box, circTool(f, 0, 0, 8, eps, 6 + eps));
    // through: extent along -n = 20, plus 2*margin
    const double margin = 1e-3 * std::sqrt(40 * 40 + 30 * 30 + 20 * 20);
    cut("circle d8 through flush", box, circTool(f, 0, 0, 8, 0, 20));
    cut("circle d8 through margin", box, circTool(f, 0, 0, 8, margin, 20 + 2 * margin));
    cut("rect touching +X edge", box, polyTool(f, {{15, -5}, {20, -5}, {20, 5}, {15, 5}}, eps, 5 + eps));
    // face-local frame on a +X side face
    const int side = faceWhere(box, 1, 0, 0);
    Frame fs = frameForFace(box, side);
    std::printf("+X face: centroid=(%.3g,%.3g,%.3g) U=(%.3g,%.3g,%.3g) V=(%.3g,%.3g,%.3g)\n", fs.o.X(), fs.o.Y(), fs.o.Z(), fs.u.X(),
                fs.u.Y(), fs.u.Z(), fs.v.X(), fs.v.Y(), fs.v.Z());
    // classification of contours against the top face
    TopoDS_Face tf = faceAt(box, top);
    classify("inside", tf, f, 0, 0);
    classify("beyond edge", tf, f, 25, 0);
    classify("on edge", tf, f, 20, 0);
    classify("on edge (offset 1e-9)", tf, f, 20 - 1e-9, 0);
    // what OCCT gives for a tool that crosses the rim
    cut("rect crossing rim (50x10)", box, polyTool(f, {{-25, -5}, {25, -5}, {25, 5}, {-25, 5}}, eps, 5 + eps));
    // repeated for determinism
    for (int i = 0; i < 3; ++i) cut("determinism rect", box, polyTool(f, sq, eps, 5 + eps));
}

static void s2() {
    std::printf("== S2 draft\n");
    TopoDS_Shape box = BRepPrimAPI_MakeBox(40, 30, 20).Shape();
    for (double deg : {5.0, -5.0, 44.0, 60.0}) {
        auto t0 = std::chrono::steady_clock::now();
        try {
            BRepOffsetAPI_DraftAngle da(box);
            gp_Dir pull(0, 0, 1);
            gp_Pln neutral(gp_Pnt(0, 0, 0), pull);
            bool allOk = true;
            TopTools_IndexedMapOfShape faces;
            TopExp::MapShapes(box, TopAbs_FACE, faces);
            for (int i = 1; i <= faces.Extent(); ++i) {
                BRepAdaptor_Surface sa(TopoDS::Face(faces(i)));
                gp_Dir n = sa.Plane().Axis().Direction();
                if (std::abs(n.Dot(pull)) > 0.5) continue;
                da.Add(TopoDS::Face(faces(i)), pull, deg * M_PI / 180.0, neutral);
                if (!da.AddDone()) {
                    std::printf("   Add failed face %d status=%d\n", i, static_cast<int>(da.Status()));
                    allOk = false;
                    break;
                }
            }
            if (!allOk) continue;
            da.Build();
            if (!da.IsDone()) { std::printf("[draft %g] IsDone()==false\n", deg); continue; }
            char lab[64];
            std::snprintf(lab, sizeof lab, "draft %+g deg", deg);
            describe(lab, da.Shape(), ms(t0));
            edgeHistory(lab, da, box, da.Shape());
        } catch (const Standard_Failure& e) {
            std::printf("[draft %g] THREW %s\n", deg, e.GetMessageString());
        }
    }
    const double a = 5 * M_PI / 180, t = 20 * std::tan(a);
    std::printf("hand: exact prismatoid inward = %.6f, outward = %.6f\n",
                24000 - 28000 * std::tan(a) + 32000.0 / 3 * std::tan(a) * std::tan(a),
                24000 + 28000 * std::tan(a) + 32000.0 / 3 * std::tan(a) * std::tan(a));
    const double a1 = 1200, a2 = (40 - 2 * t) * (30 - 2 * t);
    std::printf("hand: frustum formula (not exact) = %.6f, t=%.6f\n", 20.0 / 3 * (a1 + a2 + std::sqrt(a1 * a2)), t);
    // failure cases
    {
        BRepOffsetAPI_DraftAngle da(box);
        gp_Dir pull(0, 0, 1);
        gp_Pln neutral(gp_Pnt(0, 0, 0), pull);
        const int top = faceWhere(box, 0, 0, 1);
        da.Add(faceAt(box, top), pull, 5 * M_PI / 180, neutral);
        std::printf("draft on top face (parallel to neutral plane): AddDone=%d status=%d\n", da.AddDone() ? 1 : 0, static_cast<int>(da.Status()));
    }
    {
        // cylinder side
        TopoDS_Shape cyl = BRepPrimAPI_MakeCylinder(10, 20).Shape();
        TopTools_IndexedMapOfShape fs;
        TopExp::MapShapes(cyl, TopAbs_FACE, fs);
        for (int i = 1; i <= fs.Extent(); ++i) {
            BRepAdaptor_Surface sa(TopoDS::Face(fs(i)));
            if (sa.GetType() != GeomAbs_Cylinder) continue;
            try {
                BRepOffsetAPI_DraftAngle da(cyl);
                gp_Dir pull(0, 0, 1);
                da.Add(TopoDS::Face(fs(i)), pull, 5 * M_PI / 180, gp_Pln(gp_Pnt(0, 0, 0), pull));
                std::printf("draft on cylinder: AddDone=%d status=%d\n", da.AddDone() ? 1 : 0, static_cast<int>(da.Status()));
                if (da.AddDone()) { da.Build(); if (da.IsDone()) describe("draft cylinder", da.Shape(), 0); }
            } catch (const Standard_Failure& e) { std::printf("draft on cylinder THREW %s\n", e.GetMessageString()); }
        }
    }
    {
        // small height, big angle: top degenerates
        TopoDS_Shape flat = BRepPrimAPI_MakeBox(10, 10, 20).Shape();
        for (double deg : {30.0, 44.0}) {
            try {
                BRepOffsetAPI_DraftAngle da(flat);
                gp_Dir pull(0, 0, 1);
                TopTools_IndexedMapOfShape fs;
                TopExp::MapShapes(flat, TopAbs_FACE, fs);
                bool ok = true;
                for (int i = 1; i <= fs.Extent() && ok; ++i) {
                    BRepAdaptor_Surface sa(TopoDS::Face(fs(i)));
                    if (std::abs(sa.Plane().Axis().Direction().Dot(pull)) > 0.5) continue;
                    da.Add(TopoDS::Face(fs(i)), pull, deg * M_PI / 180, gp_Pln(gp_Pnt(0, 0, 0), pull));
                    ok = da.AddDone();
                }
                if (!ok) { std::printf("draft 10x10x20 %g: AddDone false status=%d\n", deg, static_cast<int>(da.Status())); continue; }
                da.Build();
                if (!da.IsDone()) { std::printf("draft 10x10x20 %g: IsDone false\n", deg); continue; }
                char lab[64];
                std::snprintf(lab, sizeof lab, "draft 10x10x20 %g", deg);
                describe(lab, da.Shape(), 0);
            } catch (const Standard_Failure& e) { std::printf("draft 10x10x20 %g THREW %s\n", deg, e.GetMessageString()); }
        }
    }
}

static void s3() {
    std::printf("== S3 thicken / offset\n");
    TopoDS_Shape box = BRepPrimAPI_MakeBox(40, 30, 20).Shape();
    const int top = faceWhere(box, 0, 0, 1);
    // C1a: MakeThickSolidByJoin with no closing faces (whole-solid offset): not per-face.
    // C1b: BRepOffset_MakeOffset::SetOffsetOnFace
    for (double d : {2.0, -2.0}) {
        try {
            auto t0 = std::chrono::steady_clock::now();
            BRepOffset_MakeOffset mo;
            mo.Initialize(box, 0.0, Precision::Confusion(), BRepOffset_Skin, Standard_True, Standard_False, GeomAbs_Intersection,
                          Standard_False);
            mo.SetOffsetOnFace(faceAt(box, top), d);
            mo.MakeOffsetShape();
            std::printf("C1 per-face offset d=%g: IsDone=%d error=%d\n", d, mo.IsDone() ? 1 : 0, static_cast<int>(mo.Error()));
            if (mo.IsDone()) {
                char lab[64];
                std::snprintf(lab, sizeof lab, "C1 top d=%g", d);
                describe(lab, mo.Shape(), ms(t0));
            }
        } catch (const Standard_Failure& e) { std::printf("C1 THREW %s\n", e.GetMessageString()); }
    }
    // C2: sheet face thickening
    for (double d : {2.0, -2.0}) {
        try {
            auto t0 = std::chrono::steady_clock::now();
            BRepOffset_MakeOffset mo;
            mo.Initialize(faceAt(box, top), d, Precision::Confusion(), BRepOffset_Skin, Standard_True, Standard_False,
                          GeomAbs_Intersection, Standard_True);
            mo.MakeOffsetShape();
            std::printf("C2 sheet thicken d=%g: IsDone=%d error=%d\n", d, mo.IsDone() ? 1 : 0, static_cast<int>(mo.Error()));
            if (mo.IsDone()) {
                char lab[64];
                std::snprintf(lab, sizeof lab, "C2 sheet d=%g", d);
                describe(lab, mo.Shape(), ms(t0));
            }
        } catch (const Standard_Failure& e) { std::printf("C2 THREW %s\n", e.GetMessageString()); }
    }
    // C3: pad (prism + fuse)
    {
        Frame f = frameForFace(box, top);
        TopoDS_Shape pad = BRepPrimAPI_MakePrism(faceAt(box, top), gp_Vec(f.n) * 2.0).Shape();
        BRepAlgoAPI_Fuse fu(box, pad);
        if (fu.IsDone()) describe("C3 pad d=2", fu.Shape(), 0);
    }
}

static TopoDS_Shape prism(int sides, double r, double h) {
    BRepBuilderAPI_MakePolygon poly;
    for (int i = 0; i < sides; ++i) poly.Add(gp_Pnt(r * std::cos(2 * M_PI * i / sides), r * std::sin(2 * M_PI * i / sides), 0));
    poly.Close();
    BRepBuilderAPI_MakeFace f(poly.Wire(), Standard_True);
    return BRepPrimAPI_MakePrism(f.Face(), gp_Vec(0, 0, h)).Shape();
}

static void s4() {
    std::printf("== S4 timing\n");
    for (int sides : {198, 398}) {
        TopoDS_Shape p = prism(sides, 100, 20);
        std::printf("prism with %d faces:\n", count(p, TopAbs_FACE));
        const int top = faceWhere(p, 0, 0, 1);
        Frame f = frameForFace(p, top);
        auto t0 = std::chrono::steady_clock::now();
        cut("pocket 10x10 d5", p, polyTool(f, {{-5, -5}, {5, -5}, {5, 5}, {-5, 5}}, 1e-3, 5 + 1e-3));
        std::printf("   pocket total %.1f ms\n", ms(t0));
        t0 = std::chrono::steady_clock::now();
        try {
            BRepOffsetAPI_DraftAngle da(p);
            gp_Dir pull(0, 0, 1);
            gp_Pln neutral(gp_Pnt(0, 0, 0), pull);
            TopTools_IndexedMapOfShape fs;
            TopExp::MapShapes(p, TopAbs_FACE, fs);
            int added = 0;
            for (int i = 1; i <= fs.Extent() && added < 64; ++i) {
                BRepAdaptor_Surface sa(TopoDS::Face(fs(i)));
                if (std::abs(sa.Plane().Axis().Direction().Dot(pull)) > 0.5) continue;
                da.Add(TopoDS::Face(fs(i)), pull, 3 * M_PI / 180, neutral);
                if (!da.AddDone()) break;
                ++added;
            }
            da.Build();
            std::printf("   draft %d faces: IsDone=%d %.1f ms\n", added, da.IsDone() ? 1 : 0, ms(t0));
            if (da.IsDone()) describe("draft big", da.Shape(), ms(t0));
        } catch (const Standard_Failure& e) { std::printf("   draft THREW %s\n", e.GetMessageString()); }
        t0 = std::chrono::steady_clock::now();
        try {
            BRepOffset_MakeOffset mo;
            mo.Initialize(p, 0.0, Precision::Confusion(), BRepOffset_Skin, Standard_True, Standard_False, GeomAbs_Intersection, Standard_False);
            mo.SetOffsetOnFace(faceAt(p, top), 2.0);
            mo.MakeOffsetShape();
            std::printf("   offset-on-face IsDone=%d %.1f ms\n", mo.IsDone() ? 1 : 0, ms(t0));
            if (mo.IsDone()) describe("offset big", mo.Shape(), ms(t0));
        } catch (const Standard_Failure& e) { std::printf("   offset THREW %s\n", e.GetMessageString()); }
    }
}


static void s5() {
    std::printf("== S5 supplementary\n");
    TopoDS_Shape box = BRepPrimAPI_MakeBox(40, 30, 20).Shape();
    // draft: face-level history and status on many faces
    {
        BRepOffsetAPI_DraftAngle da(box);
        gp_Dir pull(0, 0, 1);
        gp_Pln neutral(gp_Pnt(0, 0, 0), pull);
        TopTools_IndexedMapOfShape fs;
        TopExp::MapShapes(box, TopAbs_FACE, fs);
        for (int i = 1; i <= fs.Extent(); ++i) {
            BRepAdaptor_Surface sa(TopoDS::Face(fs(i)));
            if (std::abs(sa.Plane().Axis().Direction().Dot(pull)) > 0.5) continue;
            da.Add(TopoDS::Face(fs(i)), pull, 5 * M_PI / 180, neutral);
        }
        da.Build();
        TopTools_IndexedMapOfShape of;
        TopExp::MapShapes(da.Shape(), TopAbs_FACE, of);
        int modified = 0, identical = 0;
        for (int i = 1; i <= fs.Extent(); ++i) {
            if (of.FindIndex(fs(i)) > 0) ++identical;
            else if (!da.Modified(fs(i)).IsEmpty()) ++modified;
        }
        std::printf("draft face history: faces=%d identical=%d modified=%d\n", fs.Extent(), identical, modified);
        // reversed pull direction: sign flips?
        BRepOffsetAPI_DraftAngle db(box);
        gp_Dir pull2(0, 0, -1);
        gp_Pln neutral2(gp_Pnt(0, 0, 20), pull2);
        for (int i = 1; i <= fs.Extent(); ++i) {
            BRepAdaptor_Surface sa(TopoDS::Face(fs(i)));
            if (std::abs(sa.Plane().Axis().Direction().Dot(pull2)) > 0.5) continue;
            db.Add(TopoDS::Face(fs(i)), pull2, 5 * M_PI / 180, neutral2);
        }
        db.Build();
        if (db.IsDone()) describe("draft pull=-Z neutral z=20 +5", db.Shape(), 0);
        // neutral plane through middle
        BRepOffsetAPI_DraftAngle dc(box);
        gp_Pln neutral3(gp_Pnt(0, 0, 10), pull);
        for (int i = 1; i <= fs.Extent(); ++i) {
            BRepAdaptor_Surface sa(TopoDS::Face(fs(i)));
            if (std::abs(sa.Plane().Axis().Direction().Dot(pull)) > 0.5) continue;
            dc.Add(TopoDS::Face(fs(i)), pull, 5 * M_PI / 180, neutral3);
        }
        dc.Build();
        if (dc.IsDone()) describe("draft neutral z=10 +5", dc.Shape(), 0);
        // single face
        BRepOffsetAPI_DraftAngle dd(box);
        dd.Add(faceAt(box, faceWhere(box, 1, 0, 0)), pull, 5 * M_PI / 180, neutral);
        std::printf("single face +X: AddDone=%d status=%d\n", dd.AddDone() ? 1 : 0, static_cast<int>(dd.Status()));
        dd.Build();
        if (dd.IsDone()) describe("draft single +X face", dd.Shape(), 0);
    }
    // draft on a 12/24-gon prism with all sides
    for (int sides : {12, 24, 48, 198}) {
        TopoDS_Shape p = prism(sides, 100, 20);
        BRepOffsetAPI_DraftAngle da(p);
        gp_Dir pull(0, 0, 1);
        gp_Pln neutral(gp_Pnt(0, 0, 0), pull);
        TopTools_IndexedMapOfShape fs;
        TopExp::MapShapes(p, TopAbs_FACE, fs);
        int added = 0;
        bool ok = true;
        auto t0 = std::chrono::steady_clock::now();
        for (int i = 1; i <= fs.Extent() && added < 64; ++i) {
            BRepAdaptor_Surface sa(TopoDS::Face(fs(i)));
            if (std::abs(sa.Plane().Axis().Direction().Dot(pull)) > 0.5) continue;
            da.Add(TopoDS::Face(fs(i)), pull, 3 * M_PI / 180, neutral);
            if (!da.AddDone()) { ok = false; std::printf("   %d-gon: AddDone false after %d faces status=%d\n", sides, added, static_cast<int>(da.Status())); break; }
            ++added;
        }
        if (ok) { da.Build(); std::printf("   %d-gon: %d faces drafted IsDone=%d %.1f ms\n", sides, added, da.IsDone() ? 1 : 0, ms(t0)); }
    }
    // C1: two faces, determinism, big distance
    {
        const int top = faceWhere(box, 0, 0, 1), px = faceWhere(box, 1, 0, 0);
        for (int rep = 0; rep < 3; ++rep) {
            BRepOffset_MakeOffset mo;
            mo.Initialize(box, 0.0, Precision::Confusion(), BRepOffset_Skin, Standard_True, Standard_False, GeomAbs_Intersection, Standard_False);
            mo.SetOffsetOnFace(faceAt(box, top), 2.0);
            mo.SetOffsetOnFace(faceAt(box, px), 3.0);
            mo.MakeOffsetShape();
            if (mo.IsDone()) describe("C1 top+2 and +X+3", mo.Shape(), 0);
            // history through the offset algorithm
            if (rep == 0 && mo.IsDone()) {
                TopTools_IndexedMapOfShape fs, oe;
                TopExp::MapShapes(box, TopAbs_FACE, fs);
                TopExp::MapShapes(mo.Shape(), TopAbs_FACE, oe);
                int ident = 0;
                for (int i = 1; i <= fs.Extent(); ++i) if (oe.FindIndex(fs(i)) > 0) ++ident;
                std::printf("   offset: faces identical by identity=%d of %d (no Modified API on BRepOffset_MakeOffset)\n", ident, fs.Extent());
            }
        }
        BRepOffset_MakeOffset mo;
        mo.Initialize(box, 0.0, Precision::Confusion(), BRepOffset_Skin, Standard_True, Standard_False, GeomAbs_Intersection, Standard_False);
        mo.SetOffsetOnFace(faceAt(box, top), 1000.0);
        try {
            mo.MakeOffsetShape();
            std::printf("C1 d=1000: IsDone=%d\n", mo.IsDone() ? 1 : 0);
            if (mo.IsDone()) describe("C1 d=1000", mo.Shape(), 0);
        } catch (const Standard_Failure& e) { std::printf("C1 d=1000 THREW %s\n", e.GetMessageString()); }
        // cylinder side offset
        TopoDS_Shape cyl = BRepPrimAPI_MakeCylinder(10, 20).Shape();
        TopTools_IndexedMapOfShape cf;
        TopExp::MapShapes(cyl, TopAbs_FACE, cf);
        for (int i = 1; i <= cf.Extent(); ++i) {
            BRepAdaptor_Surface sa(TopoDS::Face(cf(i)));
            if (sa.GetType() != GeomAbs_Plane) continue;
            BRepOffset_MakeOffset m2;
            m2.Initialize(cyl, 0.0, Precision::Confusion(), BRepOffset_Skin, Standard_True, Standard_False, GeomAbs_Intersection, Standard_False);
            m2.SetOffsetOnFace(TopoDS::Face(cf(i)), 2.0);
            m2.MakeOffsetShape();
            if (m2.IsDone()) describe("C1 cylinder plane face +2", m2.Shape(), 0); else std::printf("C1 cylinder face %d not done\n", i);
        }
    }
}

int main() {
    s1();
    s2();
    s3();
    s4();
    s5();
    return 0;
}

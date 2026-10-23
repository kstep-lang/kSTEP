// Spike for ADR-0036 (mirror/scale as shape operations): measures what BRepBuilderAPI_Transform reports for a
// mirror/scale (Modified/Generated/IsDeleted per edge), the volume sign before/after, a STEP round trip, and the
// handedness of the mirrored surfaces. Standalone; build and run via scripts/interop/verify-mirror-scale.sh --spike.
#include <BRepAdaptor_Surface.hxx>
#include <BRepBuilderAPI_Transform.hxx>
#include <BRepCheck_Analyzer.hxx>
#include <BRepGProp.hxx>
#include <BRepPrimAPI_MakeBox.hxx>
#include <BRepPrimAPI_MakeCylinder.hxx>
#include <GProp_GProps.hxx>
#include <GeomAbs_SurfaceType.hxx>
#include <IFSelect_ReturnStatus.hxx>
#include <STEPControl_Reader.hxx>
#include <STEPControl_Writer.hxx>
#include <Standard_Version.hxx>
#include <TopAbs_ShapeEnum.hxx>
#include <TopExp.hxx>
#include <TopExp_Explorer.hxx>
#include <TopTools_IndexedMapOfShape.hxx>
#include <TopTools_ListIteratorOfListOfShape.hxx>
#include <TopoDS.hxx>
#include <TopoDS_Face.hxx>
#include <gp_Ax2.hxx>
#include <gp_Ax3.hxx>
#include <gp_Dir.hxx>
#include <gp_Pnt.hxx>
#include <gp_Trsf.hxx>

#include <cstdio>
#include <iostream>
#include <string>

static double volume(const TopoDS_Shape& s) {
    GProp_GProps p;
    BRepGProp::VolumeProperties(s, p);
    return p.Mass();
}

static void history(const char* label, BRepBuilderAPI_Transform& t, const TopoDS_Shape& in) {
    TopTools_IndexedMapOfShape inEdges, outEdges;
    TopExp::MapShapes(in, TopAbs_EDGE, inEdges);
    TopExp::MapShapes(t.Shape(), TopAbs_EDGE, outEdges);
    int mod1 = 0, modOther = 0, modNone = 0, gen = 0, del = 0;
    for (int i = 1; i <= inEdges.Extent(); ++i) {
        const TopoDS_Shape& e = inEdges(i);
        int n = 0, mapped = 0;
        for (TopTools_ListIteratorOfListOfShape it(t.Modified(e)); it.More(); it.Next()) {
            ++n;
            if (outEdges.FindIndex(it.Value()) > 0) ++mapped;
        }
        if (n == 1 && mapped == 1) ++mod1; else if (n == 0) ++modNone; else ++modOther;
        for (TopTools_ListIteratorOfListOfShape it(t.Generated(e)); it.More(); it.Next()) ++gen;
        if (t.IsDeleted(e)) ++del;
    }
    std::printf("[%s] edges in=%d out=%d  Modified==1 image:%d  Modified none:%d  Modified other:%d  Generated:%d  IsDeleted:%d\n",
                label, inEdges.Extent(), outEdges.Extent(), mod1, modNone, modOther, gen, del);
}

static void faceKinds(const char* label, const TopoDS_Shape& s) {
    int direct = 0, indirect = 0, reversed = 0, total = 0;
    for (TopExp_Explorer ex(s, TopAbs_FACE); ex.More(); ex.Next()) {
        TopoDS_Face f = TopoDS::Face(ex.Current());
        BRepAdaptor_Surface a(f, Standard_False);
        ++total;
        if (f.Orientation() == TopAbs_REVERSED) ++reversed;
        gp_Ax3 pos;
        if (a.GetType() == GeomAbs_Plane) pos = a.Plane().Position();
        else if (a.GetType() == GeomAbs_Cylinder) pos = a.Cylinder().Position();
        else continue;
        if (pos.Direct()) ++direct; else ++indirect;
    }
    std::printf("[%s] faces=%d  analytic direct=%d indirect=%d  face REVERSED=%d\n", label, total, direct, indirect, reversed);
}

static void roundTrip(const char* label, const TopoDS_Shape& s) {
    STEPControl_Writer w;
    w.Transfer(s, STEPControl_AsIs);
    std::string path = std::string("mirror-scale-spike-") + label + ".step";
    if (w.Write(path.c_str()) != IFSelect_RetDone) { std::printf("[%s] STEP write FAILED\n", label); return; }
    STEPControl_Reader r;
    if (r.ReadFile(path.c_str()) != IFSelect_RetDone) { std::printf("[%s] STEP read FAILED\n", label); return; }
    r.TransferRoots();
    TopoDS_Shape back = r.OneShape();
    BRepCheck_Analyzer an(back);
    std::printf("[%s] STEP round trip volume=%.6f valid=%d\n", label, volume(back), an.IsValid() ? 1 : 0);
}

int main() {
    std::printf("OCCT %s\n", OCC_VERSION_COMPLETE);
    TopoDS_Shape box = BRepPrimAPI_MakeBox(10, 20, 30).Shape();
    TopoDS_Shape cyl = BRepPrimAPI_MakeCylinder(5, 12).Shape();

    gp_Trsf mirror; mirror.SetMirror(gp_Ax2(gp_Pnt(0, 0, 0), gp_Dir(1, 0, 0)));
    std::printf("mirror IsNegative=%d\n", mirror.IsNegative() ? 1 : 0);
    BRepBuilderAPI_Transform mBox(box, mirror, Standard_True, Standard_False);
    std::printf("box volume before=%.6f after mirror=%.6f valid=%d\n", volume(box), volume(mBox.Shape()),
                BRepCheck_Analyzer(mBox.Shape()).IsValid() ? 1 : 0);
    history("mirror box", mBox, box);
    faceKinds("box", box);
    faceKinds("mirrored box", mBox.Shape());
    roundTrip("mirrored-box", mBox.Shape());

    BRepBuilderAPI_Transform mCyl(cyl, mirror, Standard_True, Standard_False);
    std::printf("cylinder volume before=%.6f after mirror=%.6f valid=%d\n", volume(cyl), volume(mCyl.Shape()),
                BRepCheck_Analyzer(mCyl.Shape()).IsValid() ? 1 : 0);
    history("mirror cylinder", mCyl, cyl);
    faceKinds("cylinder", cyl);
    faceKinds("mirrored cylinder", mCyl.Shape());
    roundTrip("mirrored-cylinder", mCyl.Shape());

    gp_Trsf scale; scale.SetScale(gp_Pnt(10, 20, 30), 2.0);
    BRepBuilderAPI_Transform sBox(box, scale, Standard_True, Standard_False);
    std::printf("box volume after scale 2 about (10,20,30)=%.6f valid=%d\n", volume(sBox.Shape()),
                BRepCheck_Analyzer(sBox.Shape()).IsValid() ? 1 : 0);
    history("scale box", sBox, box);
    roundTrip("scaled-box", sBox.Shape());

    for (double f : {1e-6, 1e-3, 0.5, 1e3, 1e6}) {
        gp_Trsf t; t.SetScale(gp_Pnt(0, 0, 0), f);
        BRepBuilderAPI_Transform s(box, t, Standard_True, Standard_False);
        std::printf("scale %g: volume=%.9g (expect %.9g) valid=%d\n", f, volume(s.Shape()), 6000.0 * f * f * f,
                    BRepCheck_Analyzer(s.Shape()).IsValid() ? 1 : 0);
    }
    return 0;
}

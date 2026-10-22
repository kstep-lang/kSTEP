// Reads a STEP file through OCCT's XCAF (STEPCAFControl_Reader) and prints the assembly tree that
// a CAD application would build: names, assembly/component/reference flags, locations, and for
// simple shapes the volume and bounding box (local and global).
//
//   dump_xcaf_tree dump <file.step>        print the XCAF tree of a file
//   dump_xcaf_tree reference <out.step>    write an OCCT-authored reference assembly (XCAF writer)
//   dump_xcaf_tree nested <out.step>       write an OCCT-authored NESTED assembly (Welle K import fixture)
//
// The `reference` mode documents OCCT's own entity convention for placed assemblies (which side
// of the representation relationship is the component, item order in ITEM_DEFINED_TRANSFORMATION)
// so that kSTEP's exporter follows evidence rather than guesses (ADR-0028).
#include <BRepBndLib.hxx>
#include <BRepGProp.hxx>
#include <BRepPrimAPI_MakeBox.hxx>
#include <Bnd_Box.hxx>
#include <GProp_GProps.hxx>
#include <Interface_Static.hxx>
#include <STEPCAFControl_Reader.hxx>
#include <STEPCAFControl_Writer.hxx>
#include <TCollection_AsciiString.hxx>
#include <TDF_Label.hxx>
#include <TDF_LabelSequence.hxx>
#include <TDF_Tool.hxx>
#include <STEPControl_StepModelType.hxx>
#include <gp_Pnt.hxx>
#include <gp_Dir.hxx>
#include <gp_Vec.hxx>
#include <TDataStd_Name.hxx>
#include <TDocStd_Document.hxx>
#include <TopLoc_Location.hxx>
#include <TopoDS_Shape.hxx>
#include <TopoDS_Iterator.hxx>
#include <TopAbs_ShapeEnum.hxx>
#include <XCAFApp_Application.hxx>
#include <XCAFDoc_DocumentTool.hxx>
#include <XCAFDoc_ShapeTool.hxx>
#include <gp_Ax1.hxx>
#include <gp_Trsf.hxx>
#include <cstdio>
#include <iostream>
#include <string>

static std::string nameOf(const TDF_Label& l) {
  Handle(TDataStd_Name) n;
  if (l.FindAttribute(TDataStd_Name::GetID(), n)) {
    TCollection_AsciiString a(n->Get());
    return a.ToCString();
  }
  return "<unnamed>";
}

static std::string entryOf(const TDF_Label& l) {
  TCollection_AsciiString e;
  TDF_Tool::Entry(l, e);
  return e.ToCString();
}

static std::string trsfText(const gp_Trsf& t) {
  char buf[512];
  gp_XYZ p = t.TranslationPart();
  std::snprintf(buf, sizeof buf,
                "t=(%.6f,%.6f,%.6f) R=[[%.6f %.6f %.6f][%.6f %.6f %.6f][%.6f %.6f %.6f]]", p.X(), p.Y(), p.Z(),
                t.Value(1, 1), t.Value(1, 2), t.Value(1, 3), t.Value(2, 1), t.Value(2, 2), t.Value(2, 3), t.Value(3, 1),
                t.Value(3, 2), t.Value(3, 3));
  return buf;
}

struct Totals {
  int freeShapes = 0, components = 0, simpleInstances = 0;
  double volume = 0;
  Bnd_Box box;
};

static void walk(const Handle(XCAFDoc_ShapeTool)& st, const TDF_Label& label, const TopLoc_Location& parent, int depth,
                 Totals& tot) {
  std::string pad(depth * 2, ' ');
  TDF_Label ref = label;
  bool isRef = XCAFDoc_ShapeTool::IsReference(label);
  if (isRef) XCAFDoc_ShapeTool::GetReferredShape(label, ref);
  TopLoc_Location loc = XCAFDoc_ShapeTool::GetLocation(label);
  TopLoc_Location global = parent * loc;
  std::cout << pad << "[" << entryOf(label) << "] name='" << nameOf(label) << "'"
            << " assembly=" << XCAFDoc_ShapeTool::IsAssembly(label) << " component=" << XCAFDoc_ShapeTool::IsComponent(label)
            << " reference=" << isRef;
  if (isRef) std::cout << " -> [" << entryOf(ref) << "] '" << nameOf(ref) << "'";
  std::cout << " simple=" << XCAFDoc_ShapeTool::IsSimpleShape(ref) << "\n";
  std::cout << pad << "  location: " << trsfText(loc.Transformation()) << "\n";
  if (XCAFDoc_ShapeTool::IsAssembly(ref)) {
    TDF_LabelSequence comps;
    XCAFDoc_ShapeTool::GetComponents(ref, comps);
    for (int i = 1; i <= comps.Length(); ++i) {
      tot.components++;
      walk(st, comps.Value(i), global, depth + 1, tot);
    }
    return;
  }
  TopoDS_Shape shape = XCAFDoc_ShapeTool::GetShape(ref);
  if (shape.IsNull()) return;
  {
    TopoDS_Iterator it(shape);
    if (shape.ShapeType() == TopAbs_COMPOUND && !it.More()) {
      std::printf("%s  (empty compound: the reader built no geometry for this label)\n", pad.c_str());
      return;
    }
  }
  GProp_GProps props;
  BRepGProp::VolumeProperties(shape, props);
  Bnd_Box local;
  local.SetGap(0);
  BRepBndLib::Add(shape, local, false);
  TopoDS_Shape placed = shape.Located(global);
  Bnd_Box glob;
  glob.SetGap(0);
  BRepBndLib::Add(placed, glob, false);
  double a, b, c, d, e, f;
  local.Get(a, b, c, d, e, f);
  std::printf("%s  volume=%.6f local bbox=[%.6f,%.6f]x[%.6f,%.6f]x[%.6f,%.6f]\n", pad.c_str(), props.Mass(), a, d, b, e,
              c, f);
  glob.Get(a, b, c, d, e, f);
  std::printf("%s  global bbox=[%.6f,%.6f]x[%.6f,%.6f]x[%.6f,%.6f]\n", pad.c_str(), a, d, b, e, c, f);
  tot.simpleInstances++;
  tot.volume += props.Mass();
  tot.box.Add(glob);
}

static int dump(const char* path) {
  Handle(TDocStd_Document) doc;
  Handle(XCAFApp_Application) app = XCAFApp_Application::GetApplication();
  app->NewDocument("MDTV-XCAF", doc);
  STEPCAFControl_Reader reader;
  reader.SetNameMode(true);
  reader.SetColorMode(false);
  reader.SetLayerMode(false);
  if (reader.ReadFile(path) != IFSelect_RetDone) {
    std::cout << "read status: NOT DONE\n";
    return 1;
  }
  if (!reader.Transfer(doc)) {
    std::cout << "transfer: FAILED\n";
    return 1;
  }
  std::cout << "transfer: OK\n";
  Handle(XCAFDoc_ShapeTool) st = XCAFDoc_DocumentTool::ShapeTool(doc->Main());
  TDF_LabelSequence frees;
  st->GetFreeShapes(frees);
  Totals tot;
  tot.freeShapes = frees.Length();
  for (int i = 1; i <= frees.Length(); ++i) walk(st, frees.Value(i), TopLoc_Location(), 0, tot);
  double a = 0, b = 0, c = 0, d = 0, e = 0, f = 0;
  if (!tot.box.IsVoid()) tot.box.Get(a, b, c, d, e, f);
  std::printf("SUMMARY free_shapes=%d components=%d placed_simple_instances=%d total_volume=%.6f total_bbox=[%.6f,%.6f]x[%.6f,%.6f]x[%.6f,%.6f]\n",
              tot.freeShapes, tot.components, tot.simpleInstances, tot.volume, a, d, b, e, c, f);
  return 0;
}

static int reference(const char* out) {
  Handle(TDocStd_Document) doc;
  Handle(XCAFApp_Application) app = XCAFApp_Application::GetApplication();
  app->NewDocument("MDTV-XCAF", doc);
  Handle(XCAFDoc_ShapeTool) st = XCAFDoc_DocumentTool::ShapeTool(doc->Main());
  TopoDS_Shape boxA = BRepPrimAPI_MakeBox(40, 40, 10).Shape();
  TopoDS_Shape boxB = BRepPrimAPI_MakeBox(10, 20, 5).Shape();
  TDF_Label a = st->AddShape(boxA, false);
  TDF_Label b = st->AddShape(boxB, false);
  TDataStd_Name::Set(a, "Housing");
  TDataStd_Name::Set(b, "Bracket");
  TDF_Label asmLabel = st->NewShape();
  TDataStd_Name::Set(asmLabel, "Baugruppe");
  st->AddComponent(asmLabel, a, TopLoc_Location());
  gp_Trsf t;
  t.SetRotation(gp_Ax1(gp_Pnt(0, 0, 0), gp_Dir(0, 0, 1)), M_PI / 2.0);
  t.SetTranslationPart(gp_Vec(30, 5, 10));
  st->AddComponent(asmLabel, b, TopLoc_Location(t));
  st->UpdateAssemblies();
  Interface_Static::SetCVal("write.step.schema", "AP242DIS");
  STEPCAFControl_Writer w;
  w.SetNameMode(true);
  if (!w.Transfer(doc, STEPControl_AsIs)) {
    std::cout << "writer transfer failed\n";
    return 1;
  }
  if (w.Write(out) != IFSelect_RetDone) {
    std::cout << "write failed\n";
    return 1;
  }
  std::cout << "wrote " << out << "\n";
  return 0;
}

// Nested reference (ADR-0030): Top -> Sub (Rz90, t=(100,0,0)) -> Box 10x20x5 (Rz90, t=(5,0,0)).
// Expected global bbox of the box: [90,100]x[-15,5]x[0,5]; applying the two placements in the wrong
// order would give [-5,5]x[80,100]x[0,5], which is what the import test discriminates.
static int nested(const char* out) {
  Handle(TDocStd_Document) doc;
  Handle(XCAFApp_Application) app = XCAFApp_Application::GetApplication();
  app->NewDocument("MDTV-XCAF", doc);
  Handle(XCAFDoc_ShapeTool) st = XCAFDoc_DocumentTool::ShapeTool(doc->Main());
  TDF_Label box = st->AddShape(BRepPrimAPI_MakeBox(10, 20, 5).Shape(), false);
  TDataStd_Name::Set(box, "Klotz");
  TDF_Label sub = st->NewShape();
  TDataStd_Name::Set(sub, "Unterbaugruppe");
  TDF_Label top = st->NewShape();
  TDataStd_Name::Set(top, "Hauptbaugruppe");
  gp_Trsf inner;
  inner.SetRotation(gp_Ax1(gp_Pnt(0, 0, 0), gp_Dir(0, 0, 1)), M_PI / 2.0);
  inner.SetTranslationPart(gp_Vec(5, 0, 0));
  TDF_Label innerUse = st->AddComponent(sub, box, TopLoc_Location(inner));
  TDataStd_Name::Set(innerUse, "NAUO-KLOTZ");
  gp_Trsf outer;
  outer.SetRotation(gp_Ax1(gp_Pnt(0, 0, 0), gp_Dir(0, 0, 1)), M_PI / 2.0);
  outer.SetTranslationPart(gp_Vec(100, 0, 0));
  TDF_Label outerUse = st->AddComponent(top, sub, TopLoc_Location(outer));
  TDataStd_Name::Set(outerUse, "NAUO-SUB");
  st->UpdateAssemblies();
  Interface_Static::SetCVal("write.step.schema", "AP242DIS");
  STEPCAFControl_Writer w;
  w.SetNameMode(true);
  if (!w.Transfer(doc, STEPControl_AsIs)) {
    std::cout << "writer transfer failed\n";
    return 1;
  }
  if (w.Write(out) != IFSelect_RetDone) {
    std::cout << "write failed\n";
    return 1;
  }
  std::cout << "wrote " << out << "\n";
  return 0;
}

int main(int argc, char** argv) {
  if (argc != 3) {
    std::cerr << "usage: dump_xcaf_tree dump <file.step> | reference <out.step> | nested <out.step>\n";
    return 2;
  }
  std::string mode = argv[1];
  if (mode == "dump") return dump(argv[2]);
  if (mode == "reference") return reference(argv[2]);
  if (mode == "nested") return nested(argv[2]);
  std::cerr << "unknown mode\n";
  return 2;
}

// Spike for ADR-0039 (colours and layers in the STEP export): measures what STEPCAFControl_Writer writes for a
// part colour, a face colour, layers, and RGBA, and how the shape representation differs from STEPControl_Writer.
// Standalone; build and run via scripts/interop/verify-export-colours-layers.sh --spike. All numbers in the ADR
// come from this program.
//   spike_export_styles <scenario> <assemblyMode 0|2> <out.step>
// scenarios: plain (STEPControl_Writer), xcaf-plain, part, face, layer, alpha, multi
#include <BRepPrimAPI_MakeBox.hxx>
#include <BRep_Builder.hxx>
#include <BRep_Tool.hxx>
#include <BRepTools.hxx>
#include <TopoDS.hxx>
#include <TopoDS_Face.hxx>
#include <Geom_Surface.hxx>
#include <gp_Pnt.hxx>
#include <cmath>
#include <Interface_Static.hxx>
#include <Message.hxx>
#include <Message_Messenger.hxx>
#include <Quantity_Color.hxx>
#include <Quantity_ColorRGBA.hxx>
#include <STEPCAFControl_Writer.hxx>
#include <STEPControl_Writer.hxx>
#include <TCollection_ExtendedString.hxx>
#include <TDF_Label.hxx>
#include <TDocStd_Document.hxx>
#include <TopExp.hxx>
#include <TopoDS_Compound.hxx>
#include <TopTools_IndexedMapOfShape.hxx>
#include <UnitsMethods_LengthUnit.hxx>
#include <XCAFApp_Application.hxx>
#include <XCAFDoc_ColorTool.hxx>
#include <XCAFDoc_DocumentTool.hxx>
#include <XCAFDoc_LayerTool.hxx>
#include <XCAFDoc_ShapeTool.hxx>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <string>

int main(int argc, char** argv) {
  if (argc < 4) { std::cerr << "usage: spike_export_styles <scenario> <0|2> <out.step>\n"; return 2; }
  std::string sc = argv[1];
  int assembly = std::atoi(argv[2]);
  const char* out = argv[3];
  TopoDS_Shape box = BRepPrimAPI_MakeBox(10, 20, 30).Shape();
  if (sc == "multi") {
    TopoDS_Compound c; BRep_Builder b; b.MakeCompound(c);
    b.Add(c, BRepPrimAPI_MakeBox(10, 20, 30).Shape());
    b.Add(c, BRepPrimAPI_MakeBox(gp_Pnt(50, 0, 0), 4, 4, 4).Shape());
    box = c;
  }
  Handle(XCAFApp_Application) app = XCAFApp_Application::GetApplication();
  if (sc == "plain") {
    STEPControl_Writer w;
    Interface_Static::SetCVal("write.step.schema", "AP242DIS");
    Interface_Static::SetIVal("write.step.assembly", assembly);
    w.Transfer(box, STEPControl_AsIs);
    return w.Write(out) == IFSelect_RetDone ? 0 : 1;
  }
  Handle(TDocStd_Document) doc;
  app->NewDocument("MDTV-XCAF", doc);
  XCAFDoc_DocumentTool::SetLengthUnit(doc, 1.0, UnitsMethods_LengthUnit_Millimeter);
  Handle(XCAFDoc_ShapeTool) st = XCAFDoc_DocumentTool::ShapeTool(doc->Main());
  Handle(XCAFDoc_ColorTool) ct = XCAFDoc_DocumentTool::ColorTool(doc->Main());
  Handle(XCAFDoc_LayerTool) lt = XCAFDoc_DocumentTool::LayerTool(doc->Main());
  TDF_Label part = st->AddShape(box, Standard_False);
  Quantity_Color orange(1.0, 0.5, 0.0, Quantity_TOC_sRGB);
  Quantity_Color blue(0.0, 0.0, 1.0, Quantity_TOC_sRGB);
  if (sc == "part" || sc == "multi") {
    ct->SetColor(part, orange, XCAFDoc_ColorSurf);
    lt->SetLayer(part, TCollection_ExtendedString("Layer-A", true), Standard_False);
  }
  if (sc == "face") {
    TopTools_IndexedMapOfShape faces; TopExp::MapShapes(box, TopAbs_FACE, faces);
    for (int i = 1; i <= faces.Extent(); ++i) {
      TopoDS_Shape f = faces.FindKey(i);
      gp_Pnt c; // top face = highest centre z: pick face with centre z == 30
      Handle(Geom_Surface) s = BRep_Tool::Surface(TopoDS::Face(f));
      Standard_Real u1, u2, v1, v2; BRepTools::UVBounds(TopoDS::Face(f), u1, u2, v1, v2);
      s->D0((u1 + u2) / 2, (v1 + v2) / 2, c);
      if (std::fabs(c.Z() - 30.0) < 1e-9) {
        TDF_Label sub = st->AddSubShape(part, f);
        std::printf("face index %d is top\n", i - 1);
        ct->SetColor(sub, blue, XCAFDoc_ColorSurf);
        lt->SetLayer(sub, TCollection_ExtendedString("Layer-F", true), Standard_False);
      }
    }
  }
  if (sc == "layer") {
    // LAYER=<name> overrides the name (used to measure how OCCT wraps long layer names).
    const char* custom = std::getenv("LAYER");
    lt->SetLayer(part, TCollection_ExtendedString(custom != nullptr ? custom : "Layer-A", true), Standard_False);
  }
  if (sc == "alpha") {
    ct->SetColor(part, Quantity_ColorRGBA(orange, 0.5f), XCAFDoc_ColorSurf);
  }
  if (sc == "unicode") {
    lt->SetLayer(part, TCollection_ExtendedString("O'Neil \\ \xc3\xa4\xc3\xb6\xc3\xbc \xf0\x9f\x98\x80", true), Standard_False);
  }
  STEPCAFControl_Writer w;
  w.SetColorMode(true); w.SetLayerMode(true);
  Interface_Static::SetCVal("write.step.schema", "AP242DIS");
  Interface_Static::SetIVal("write.step.assembly", assembly);
  if (!w.Transfer(doc, STEPControl_AsIs)) { std::cerr << "transfer failed\n"; return 1; }
  if (w.Write(out) != IFSelect_RetDone) { std::cerr << "write failed\n"; return 1; }
  app->Close(doc);
  return 0;
}

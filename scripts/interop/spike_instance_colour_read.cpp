// Gate for ADR-0044 (instance colours in the STEP export): reads a STEP file with OCCT's own XCAF reader and checks that a
// hand-written PRESENTATION_STYLE_BY_CONTEXT pattern is read as a colour of the component label.
//   spike_instance_colour_read <file.step> [--components N] [--volume V] [--part none|r,g,b[,a]]
//                              [--c1 none|r,g,b[,a]] [--c2 none|r,g,b[,a]] ...
// Prints one line per component ("component k colour r g b alpha" or "component k colour none"), the colour of the
// referenced part label, the layers, the number of free shapes and the summed volume. Exit 0 only when every given
// expectation holds (tolerance 1e-6, volume 1e-3). Standalone; run via scripts/interop/verify-export-instance-colours.sh.
#include <BRepGProp.hxx>
#include <GProp_GProps.hxx>
#include <Interface_Static.hxx>
#include <Quantity_Color.hxx>
#include <Quantity_ColorRGBA.hxx>
#include <STEPCAFControl_Reader.hxx>
#include <TColStd_HSequenceOfExtendedString.hxx>
#include <TCollection_AsciiString.hxx>
#include <TDF_Label.hxx>
#include <TDF_LabelSequence.hxx>
#include <TDocStd_Document.hxx>
#include <TopoDS_Shape.hxx>
#include <UnitsMethods_LengthUnit.hxx>
#include <XCAFApp_Application.hxx>
#include <XCAFDoc_ColorTool.hxx>
#include <XCAFDoc_DocumentTool.hxx>
#include <XCAFDoc_LayerTool.hxx>
#include <XCAFDoc_ShapeTool.hxx>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iostream>
#include <map>
#include <sstream>
#include <string>
#include <vector>

struct Col { bool has = false; double r = 0, g = 0, b = 0, a = 1; };

static Col readColour(const Handle(XCAFDoc_ColorTool)& ct, const TDF_Label& l) {
  Col out;
  Quantity_ColorRGBA c;
  if (ct->GetColor(l, XCAFDoc_ColorSurf, c) || ct->GetColor(l, XCAFDoc_ColorGen, c)) {
    out.has = true;
    c.GetRGB().Values(out.r, out.g, out.b, Quantity_TOC_sRGB);
    out.a = (double)c.Alpha();
  }
  return out;
}

static std::string show(const Col& c) {
  if (!c.has) return "none";
  char buf[128];
  std::snprintf(buf, sizeof buf, "%.6f %.6f %.6f alpha %.6f", c.r, c.g, c.b, c.a);
  return buf;
}

// "none" or "r,g,b[,a]"
static bool matches(const Col& got, const std::string& spec) {
  if (spec == "none") return !got.has;
  if (!got.has) return false;
  double v[4] = {0, 0, 0, 1};
  int n = std::sscanf(spec.c_str(), "%lf,%lf,%lf,%lf", &v[0], &v[1], &v[2], &v[3]);
  if (n < 3) return false;
  return std::fabs(got.r - v[0]) < 1e-6 && std::fabs(got.g - v[1]) < 1e-6 && std::fabs(got.b - v[2]) < 1e-6 &&
         std::fabs(got.a - v[3]) < 1e-6;
}

int main(int argc, char** argv) {
  if (argc < 2) { std::cerr << "usage: spike_instance_colour_read <file.step> [--components N] [--volume V] [--part spec] [--cK spec]...\n"; return 2; }
  std::map<std::string, std::string> expect;
  for (int i = 2; i + 1 < argc; i += 2) expect[argv[i]] = argv[i + 1];

  std::ifstream f(argv[1], std::ios::binary);
  if (!f) { std::cerr << "cannot open " << argv[1] << "\n"; return 2; }
  std::stringstream ss; ss << f.rdbuf();
  std::istringstream stream(ss.str());
  STEPCAFControl_Reader reader;
  reader.SetColorMode(true); reader.SetLayerMode(true); reader.SetNameMode(true);
  if (reader.ReadStream("x.stp", stream) != IFSelect_RetDone) { std::cerr << "read failed\n"; return 1; }
  Handle(XCAFApp_Application) app = XCAFApp_Application::GetApplication();
  Handle(TDocStd_Document) doc;
  app->NewDocument("MDTV-XCAF", doc);
  XCAFDoc_DocumentTool::SetLengthUnit(doc, 1.0, UnitsMethods_LengthUnit_Millimeter);
  if (!reader.Transfer(doc)) { std::cerr << "transfer failed\n"; return 1; }
  Handle(XCAFDoc_ShapeTool) st = XCAFDoc_DocumentTool::ShapeTool(doc->Main());
  Handle(XCAFDoc_ColorTool) ct = XCAFDoc_DocumentTool::ColorTool(doc->Main());
  Handle(XCAFDoc_LayerTool) lt = XCAFDoc_DocumentTool::LayerTool(doc->Main());
  TDF_LabelSequence frees;
  st->GetFreeShapes(frees);
  std::printf("free shapes %d\n", frees.Length());
  bool ok = true;
  auto check = [&](const std::string& key, const std::string& got, bool pass) {
    if (expect.count(key)) {
      std::printf("  expect %s %s -> %s\n", key.c_str(), expect[key].c_str(), pass ? "ok" : ("MISMATCH, got " + got).c_str());
      if (!pass) ok = false;
    }
  };
  check("--free", std::to_string(frees.Length()), expect.count("--free") && std::atoi(expect["--free"].c_str()) == frees.Length());
  double volume = 0; int componentCount = 0;
  for (int i = 1; i <= frees.Length(); ++i) {
    TDF_Label l = frees.Value(i);
    GProp_GProps p; BRepGProp::VolumeProperties(XCAFDoc_ShapeTool::GetShape(l), p);
    volume += p.Mass();
    TDF_LabelSequence comps;
    XCAFDoc_ShapeTool::GetComponents(l, comps);
    for (int k = 1; k <= comps.Length(); ++k) {
      ++componentCount;
      Col c = readColour(ct, comps.Value(k));
      std::printf("component %d colour %s\n", k, show(c).c_str());
      TDF_Label ref;
      if (XCAFDoc_ShapeTool::GetReferredShape(comps.Value(k), ref)) {
        Col pc = readColour(ct, ref);
        std::printf("component %d part colour %s\n", k, show(pc).c_str());
        if (k == 1 && !expect.count("--part")) {}  // the part colour is checked once below
        if (k == 1) check("--part", show(pc), expect.count("--part") && matches(pc, expect["--part"]));
      }
      Handle(TColStd_HSequenceOfExtendedString) ly;
      if (lt->GetLayers(comps.Value(k), ly) && !ly.IsNull())
        for (int m = 1; m <= ly->Length(); ++m) std::printf("component %d layer %s\n", k, TCollection_AsciiString(ly->Value(m)).ToCString());
      std::string key = "--c" + std::to_string(k);
      check(key, show(c), expect.count(key) && matches(c, expect[key]));
    }
    // the free shape itself must not carry the instance colour as its own product colour
    if (comps.Length() > 0) {
      Col top = readColour(ct, l);
      std::printf("assembly colour %s\n", show(top).c_str());
      check("--assembly", show(top), expect.count("--assembly") && matches(top, expect["--assembly"]));
    }
  }
  std::printf("components %d volume %.6f\n", componentCount, volume);
  check("--components", std::to_string(componentCount), expect.count("--components") && std::atoi(expect["--components"].c_str()) == componentCount);
  check("--volume", std::to_string(volume), expect.count("--volume") && std::fabs(std::atof(expect["--volume"].c_str()) - volume) < 1e-3);
  app->Close(doc);
  std::printf(ok ? "RESULT ok\n" : "RESULT mismatch\n");
  return ok ? 0 : 1;
}

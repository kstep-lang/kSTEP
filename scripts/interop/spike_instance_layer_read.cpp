// Gate for ADR-0047 (presentation layers on assembly usages): reads a STEP file with OCCT's own XCAF reader, walks every
// component on every level and prints the layers of the component label, of the referred part label, and of the part's
// sub-shapes (a layer that bleeds onto the shared part shows up there). Colour of components is printed as well.
//   spike_instance_layer_read <file.step> [--free N] [--components N] [--volume V] [--assembly none|a,b]
//        [--comp PATH=none|a,b]... [--part NAME=none|a,b]... [--partsub NAME=none|a,b]... [--compcol PATH=none|r,g,b]...
// PATH is the 1-based component index path below the free shape ("2", "2/1"). "none" = no layer. Exit 0 only when every
// expectation holds. Run via scripts/interop/verify-instance-layer-gate.sh.
#include <BRepGProp.hxx>
#include <GProp_GProps.hxx>
#include <Quantity_Color.hxx>
#include <Quantity_ColorRGBA.hxx>
#include <STEPCAFControl_Reader.hxx>
#include <TColStd_HSequenceOfExtendedString.hxx>
#include <TCollection_AsciiString.hxx>
#include <TDF_Label.hxx>
#include <TDF_LabelSequence.hxx>
#include <TDataStd_Name.hxx>
#include <TDocStd_Document.hxx>
#include <TopoDS_Shape.hxx>
#include <UnitsMethods_LengthUnit.hxx>
#include <XCAFApp_Application.hxx>
#include <XCAFDoc_ColorTool.hxx>
#include <XCAFDoc_DocumentTool.hxx>
#include <XCAFDoc_LayerTool.hxx>
#include <XCAFDoc_ShapeTool.hxx>
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <map>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

static std::string layersOf(const Handle(XCAFDoc_LayerTool)& lt, const TDF_Label& l) {
  Handle(TColStd_HSequenceOfExtendedString) ly;
  std::vector<std::string> v;
  if (lt->GetLayers(l, ly) && !ly.IsNull())
    for (int m = 1; m <= ly->Length(); ++m) v.push_back(TCollection_AsciiString(ly->Value(m)).ToCString());
  std::sort(v.begin(), v.end());
  if (v.empty()) return "none";
  std::string s;
  for (size_t i = 0; i < v.size(); ++i) s += (i ? "," : "") + v[i];
  return s;
}

static std::string colourOf(const Handle(XCAFDoc_ColorTool)& ct, const TDF_Label& l) {
  Quantity_ColorRGBA c;
  if (ct->GetColor(l, XCAFDoc_ColorSurf, c) || ct->GetColor(l, XCAFDoc_ColorGen, c)) {
    double r, g, b; c.GetRGB().Values(r, g, b, Quantity_TOC_sRGB);
    char buf[96]; std::snprintf(buf, sizeof buf, "%g,%g,%g", r, g, b); return buf;
  }
  return "none";
}

static std::string nameOf(const TDF_Label& l) {
  Handle(TDataStd_Name) n;
  if (l.FindAttribute(TDataStd_Name::GetID(), n)) return TCollection_AsciiString(n->Get()).ToCString();
  return "";
}

int main(int argc, char** argv) {
  if (argc < 2) { std::cerr << "usage: spike_instance_layer_read <file.step> [expectations]\n"; return 2; }
  std::map<std::string, std::string> scalar;
  std::vector<std::pair<std::string, std::string>> comps, parts, partsubs, compcols;
  for (int i = 2; i + 1 < argc; i += 2) {
    std::string k = argv[i], v = argv[i + 1];
    if (k == "--comp" || k == "--part" || k == "--partsub" || k == "--compcol") {
      auto eq = v.find('=');
      if (eq == std::string::npos) { std::cerr << "expected KEY=spec in " << v << "\n"; return 2; }
      auto kv = std::make_pair(v.substr(0, eq), v.substr(eq + 1));
      (k == "--comp" ? comps : k == "--part" ? parts : k == "--partsub" ? partsubs : compcols).push_back(kv);
    } else scalar[k] = v;
  }
  std::ifstream f(argv[1], std::ios::binary);
  if (!f) { std::cerr << "cannot open " << argv[1] << "\n"; return 2; }
  std::stringstream ss; ss << f.rdbuf();
  std::istringstream stream(ss.str());
  STEPCAFControl_Reader reader;
  reader.SetColorMode(true); reader.SetNameMode(true); reader.SetLayerMode(true);
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
  auto expectScalar = [&](const std::string& key, const std::string& got, bool pass) {
    if (scalar.count(key)) {
      std::printf("  expect %s %s -> %s\n", key.c_str(), scalar[key].c_str(), pass ? "ok" : ("MISMATCH, got " + got).c_str());
      if (!pass) ok = false;
    }
  };
  expectScalar("--free", std::to_string(frees.Length()), scalar.count("--free") && std::atoi(scalar["--free"].c_str()) == frees.Length());
  std::map<std::string, std::string> compL, compC, partL, partSubL;
  double volume = 0; int componentCount = 0;
  std::string asmLayers = "none";
  for (int i = 1; i <= frees.Length(); ++i) {
    GProp_GProps p; BRepGProp::VolumeProperties(XCAFDoc_ShapeTool::GetShape(frees.Value(i)), p);
    volume += p.Mass();
    if (i == 1) { asmLayers = layersOf(lt, frees.Value(i)); std::printf("assembly layers %s\n", asmLayers.c_str()); }
    std::vector<std::pair<TDF_Label, std::string>> stack{{frees.Value(i), ""}};
    while (!stack.empty() && componentCount < 100000) {
      auto [lab, path] = stack.back(); stack.pop_back();
      TDF_LabelSequence cs;
      XCAFDoc_ShapeTool::GetComponents(lab, cs);
      for (int k = cs.Length(); k >= 1; --k) {
        std::string cp = path.empty() ? std::to_string(k) : path + "/" + std::to_string(k);
        ++componentCount;
        compL[cp] = layersOf(lt, cs.Value(k)); compC[cp] = colourOf(ct, cs.Value(k));
        std::printf("component %s layers %s colour %s\n", cp.c_str(), compL[cp].c_str(), compC[cp].c_str());
        TDF_Label ref;
        if (XCAFDoc_ShapeTool::GetReferredShape(cs.Value(k), ref)) {
          std::string nm = nameOf(ref);
          partL[nm] = layersOf(lt, ref);
          TDF_LabelSequence subs; XCAFDoc_ShapeTool::GetSubShapes(ref, subs);
          std::string sl = "none";
          for (int s = 1; s <= subs.Length(); ++s) { std::string x = layersOf(lt, subs.Value(s)); if (x != "none") sl = x; }
          partSubL[nm] = sl;
          std::printf("part %s (at %s) layers %s, sub-shape layers %s\n", nm.c_str(), cp.c_str(), partL[nm].c_str(), sl.c_str());
          stack.push_back({ref, cp});
        }
      }
    }
  }
  std::printf("components %d volume %.6f\n", componentCount, volume);
  expectScalar("--components", std::to_string(componentCount), scalar.count("--components") && std::atoi(scalar["--components"].c_str()) == componentCount);
  expectScalar("--volume", std::to_string(volume), scalar.count("--volume") && std::fabs(std::atof(scalar["--volume"].c_str()) - volume) < 1e-3);
  expectScalar("--assembly", asmLayers, scalar.count("--assembly") && scalar["--assembly"] == asmLayers);
  auto check = [&](const char* what, const std::vector<std::pair<std::string, std::string>>& exp, const std::map<std::string, std::string>& have) {
    for (auto& [key, spec] : exp) {
      auto it = have.find(key);
      std::string got = it == have.end() ? "<absent>" : it->second;
      std::string want = spec; // layer lists are compared sorted
      if (want != "none") { std::vector<std::string> v; std::stringstream q(want); std::string t; while (std::getline(q, t, ',')) v.push_back(t); std::sort(v.begin(), v.end()); want.clear(); for (size_t i = 0; i < v.size(); ++i) want += (i ? "," : "") + v[i]; }
      bool pass = want == got;
      std::printf("  expect %s %s %s -> %s\n", what, key.c_str(), spec.c_str(), pass ? "ok" : ("MISMATCH, got " + got).c_str());
      if (!pass) ok = false;
    }
  };
  check("comp", comps, compL); check("part", parts, partL); check("partsub", partsubs, partSubL); check("compcol", compcols, compC);
  app->Close(doc);
  std::printf(ok ? "RESULT ok\n" : "RESULT mismatch\n");
  return ok ? 0 : 1;
}

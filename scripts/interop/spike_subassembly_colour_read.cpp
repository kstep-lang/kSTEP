// Gate for ADR-0046 (instance colours on occurrences of sub-assemblies): reads a STEP file with OCCT's own XCAF reader,
// walks every component on every level and prints the colour of the component label, the colour of the referred part
// label and the EFFECTIVE leaf colour computed by OCCT's own presentation logic (XCAFPrs_DocumentExplorer), which is the
// independent oracle for the precedence rule.
//   spike_subassembly_colour_read <file.step> [--free N] [--components N] [--volume V]
//                                 [--comp PATH=spec]... [--leaf PATH=spec]... [--part NAME=spec]...
// PATH is the 1-based component index path below the free shape, e.g. "2/1". spec is "none" or "r,g,b[,a]".
// Exit 0 only when every given expectation holds (colour tolerance 1e-6, volume 1e-3). Standalone; run via
// scripts/interop/verify-export-subassembly-colours.sh.
#include <BRepGProp.hxx>
#include <GProp_GProps.hxx>
#include <Quantity_Color.hxx>
#include <Quantity_ColorRGBA.hxx>
#include <STEPCAFControl_Reader.hxx>
#include <TCollection_AsciiString.hxx>
#include <TCollection_ExtendedString.hxx>
#include <TDF_Label.hxx>
#include <TDF_LabelSequence.hxx>
#include <TDataStd_Name.hxx>
#include <TDocStd_Document.hxx>
#include <TopoDS_Shape.hxx>
#include <UnitsMethods_LengthUnit.hxx>
#include <XCAFApp_Application.hxx>
#include <XCAFDoc_ColorTool.hxx>
#include <XCAFDoc_DocumentTool.hxx>
#include <XCAFDoc_ShapeTool.hxx>
#include <XCAFPrs_DocumentExplorer.hxx>
#include <XCAFPrs_Style.hxx>
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

static bool matches(const Col& got, const std::string& spec) {
  if (spec == "none") return !got.has;
  if (!got.has) return false;
  double v[4] = {0, 0, 0, 1};
  int n = std::sscanf(spec.c_str(), "%lf,%lf,%lf,%lf", &v[0], &v[1], &v[2], &v[3]);
  if (n < 3) return false;
  return std::fabs(got.r - v[0]) < 1e-6 && std::fabs(got.g - v[1]) < 1e-6 && std::fabs(got.b - v[2]) < 1e-6 &&
         std::fabs(got.a - v[3]) < 1e-6;
}

static std::string nameOf(const TDF_Label& l) {
  Handle(TDataStd_Name) n;
  if (l.FindAttribute(TDataStd_Name::GetID(), n)) return TCollection_AsciiString(n->Get()).ToCString();
  return "";
}

int main(int argc, char** argv) {
  if (argc < 2) { std::cerr << "usage: spike_subassembly_colour_read <file.step> [--free N] [--components N] [--volume V] [--comp P=spec] [--leaf P=spec] [--part NAME=spec]\n"; return 2; }
  std::map<std::string, std::string> scalar;
  std::vector<std::pair<std::string, std::string>> comps, leaves, parts;
  for (int i = 2; i + 1 < argc; i += 2) {
    std::string k = argv[i], v = argv[i + 1];
    if (k == "--comp" || k == "--leaf" || k == "--part") {
      auto eq = v.find('=');
      if (eq == std::string::npos) { std::cerr << "expected PATH=spec in " << v << "\n"; return 2; }
      auto kv = std::make_pair(v.substr(0, eq), v.substr(eq + 1));
      (k == "--comp" ? comps : k == "--leaf" ? leaves : parts).push_back(kv);
    } else scalar[k] = v;
  }
  std::ifstream f(argv[1], std::ios::binary);
  if (!f) { std::cerr << "cannot open " << argv[1] << "\n"; return 2; }
  std::stringstream ss; ss << f.rdbuf();
  std::istringstream stream(ss.str());
  STEPCAFControl_Reader reader;
  reader.SetColorMode(true); reader.SetNameMode(true);
  if (reader.ReadStream("x.stp", stream) != IFSelect_RetDone) { std::cerr << "read failed\n"; return 1; }
  Handle(XCAFApp_Application) app = XCAFApp_Application::GetApplication();
  Handle(TDocStd_Document) doc;
  app->NewDocument("MDTV-XCAF", doc);
  XCAFDoc_DocumentTool::SetLengthUnit(doc, 1.0, UnitsMethods_LengthUnit_Millimeter);
  if (!reader.Transfer(doc)) { std::cerr << "transfer failed\n"; return 1; }
  Handle(XCAFDoc_ShapeTool) st = XCAFDoc_DocumentTool::ShapeTool(doc->Main());
  Handle(XCAFDoc_ColorTool) ct = XCAFDoc_DocumentTool::ColorTool(doc->Main());
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

  // 1. component colours and part colours on every level (iterative, bounded)
  std::map<std::string, Col> compColour, partColour;
  double volume = 0; int componentCount = 0;
  for (int i = 1; i <= frees.Length(); ++i) {
    GProp_GProps p; BRepGProp::VolumeProperties(XCAFDoc_ShapeTool::GetShape(frees.Value(i)), p);
    volume += p.Mass();
    std::vector<std::pair<TDF_Label, std::string>> stack{{frees.Value(i), ""}};
    while (!stack.empty() && componentCount < 100000) {
      auto [lab, path] = stack.back(); stack.pop_back();
      TDF_LabelSequence cs;
      XCAFDoc_ShapeTool::GetComponents(lab, cs);
      for (int k = cs.Length(); k >= 1; --k) {
        std::string cp = path.empty() ? std::to_string(k) : path + "/" + std::to_string(k);
        ++componentCount;
        Col c = readColour(ct, cs.Value(k));
        compColour[cp] = c;
        std::printf("component %s colour %s\n", cp.c_str(), show(c).c_str());
        TDF_Label ref;
        if (XCAFDoc_ShapeTool::GetReferredShape(cs.Value(k), ref)) {
          Col pc = readColour(ct, ref);
          std::string nm = nameOf(ref);
          std::printf("part %s (at %s) colour %s\n", nm.c_str(), cp.c_str(), show(pc).c_str());
          partColour[nm] = pc;
          stack.push_back({ref, cp});
        }
      }
    }
  }
  std::printf("components %d volume %.6f\n", componentCount, volume);
  expectScalar("--components", std::to_string(componentCount), scalar.count("--components") && std::atoi(scalar["--components"].c_str()) == componentCount);
  expectScalar("--volume", std::to_string(volume), scalar.count("--volume") && std::fabs(std::atof(scalar["--volume"].c_str()) - volume) < 1e-3);
  auto check = [&](const char* what, const std::string& key, const std::string& spec, const std::map<std::string, Col>& have) {
    auto it = have.find(key);
    Col got = it == have.end() ? Col() : it->second;
    bool pass = it != have.end() && matches(got, spec);
    std::printf("  expect %s %s %s -> %s\n", what, key.c_str(), spec.c_str(), pass ? "ok" : ("MISMATCH, got " + show(got)).c_str());
    if (!pass) ok = false;
  };
  for (auto& e : comps) check("comp", e.first, e.second, compColour);
  for (auto& e : parts) check("part", e.first, e.second, partColour);

  // 2. effective colour of every leaf according to OCCT's own presentation logic
  std::map<std::string, Col> leafColour;
  {
    XCAFPrs_DocumentExplorer ex(doc, XCAFPrs_DocumentExplorerFlags_None);
    std::vector<int> counter;
    for (; ex.More(); ex.Next()) {
      const XCAFPrs_DocumentNode& node = ex.Current();
      int d = ex.CurrentDepth();
      if ((int)counter.size() <= d) counter.resize(d + 1, 0);
      counter.resize(d + 1);
      ++counter[d];
      std::string path;
      for (int q = 1; q <= d; ++q) path += (q > 1 ? "/" : "") + std::to_string(counter[q]);
      // reset deeper counters: resize above drops them
      if (d == 0) continue;
      Col c;
      if (node.Style.IsSetColorSurf()) {
        c.has = true;
        node.Style.GetColorSurfRGBA().GetRGB().Values(c.r, c.g, c.b, Quantity_TOC_sRGB);
        c.a = (double)node.Style.GetColorSurfRGBA().Alpha();
      }
      bool leaf = !node.IsAssembly;
      std::printf("%s %s effective colour %s\n", leaf ? "leaf" : "node", path.c_str(), show(c).c_str());
      if (leaf) leafColour[path] = c;
    }
  }
  for (auto& e : leaves) check("leaf", e.first, e.second, leafColour);
  app->Close(doc);
  std::printf(ok ? "RESULT ok\n" : "RESULT mismatch\n");
  return ok ? 0 : 1;
}

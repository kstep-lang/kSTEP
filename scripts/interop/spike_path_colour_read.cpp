// Gate for ADR-0048 (path-exact colours via SPECIFIED_HIGHER_USAGE_OCCURRENCE, Welle AA). Derived from
// spike_subassembly_colour_read.cpp, but reads with SetSHUOMode(true) and prints every SHUO of every component with its
// upper/next usage chain and colour. Additional modes:
//   --shuos N         expected number of SHUO labels that carry a colour
//   --s0 OUT.step     (instead of reading) writes OCCT's own pattern: ASM > SUB x2 > BOX x2 with SetSHUO on [u2,u3] coloured
//                     green, Assembly mode 2 (informative scenario S0)
// Original header: Gate for ADR-0046 (instance colours on occurrences of sub-assemblies): reads a STEP file with OCCT's own XCAF reader,
// walks every component on every level and prints the colour of the component label, the colour of the referred part
// label and the EFFECTIVE leaf colour computed by OCCT's own presentation logic (XCAFPrs_DocumentExplorer), which is the
// independent oracle for the precedence rule.
//   spike_subassembly_colour_read <file.step> [--free N] [--components N] [--volume V]
//                                 [--comp PATH=spec]... [--leaf PATH=spec]... [--part NAME=spec]...
// PATH is the 1-based component index path below the free shape, e.g. "2/1". spec is "none" or "r,g,b[,a]".
// Exit 0 only when every given expectation holds (colour tolerance 1e-6, volume 1e-3). Standalone; run via
// scripts/interop/verify-export-subassembly-colours.sh.
#include <BRepGProp.hxx>
#include <BRepPrimAPI_MakeBox.hxx>
#include <TopLoc_Location.hxx>
#include <gp_Trsf.hxx>
#include <gp_Vec.hxx>
#include <Interface_Static.hxx>
#include <STEPCAFControl_Writer.hxx>
#include <STEPControl_StepModelType.hxx>
#include <TDF_Tool.hxx>
#include <TopoDS_Iterator.hxx>
#include <Bnd_Box.hxx>
#include <BRepBndLib.hxx>
#include <TDF_AttributeSequence.hxx>
#include <XCAFDoc_GraphNode.hxx>
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

static int writeS0(const char* out, int assembly, bool deep) {
  Handle(XCAFApp_Application) app = XCAFApp_Application::GetApplication();
  Handle(TDocStd_Document) doc;
  app->NewDocument("MDTV-XCAF", doc);
  XCAFDoc_DocumentTool::SetLengthUnit(doc, 1.0, UnitsMethods_LengthUnit_Millimeter);
  Handle(XCAFDoc_ShapeTool) st = XCAFDoc_DocumentTool::ShapeTool(doc->Main());
  Handle(XCAFDoc_ColorTool) ct = XCAFDoc_DocumentTool::ColorTool(doc->Main());
  TDF_Label part = st->AddShape(BRepPrimAPI_MakeBox(10, 20, 30).Shape(), Standard_False);
  TDF_Label sub = st->NewShape();
  gp_Trsf a1, a2; a2.SetTranslationPart(gp_Vec(20, 0, 0));
  TDF_Label u3 = st->AddComponent(sub, part, TopLoc_Location(a1));
  TDF_Label u4 = st->AddComponent(sub, part, TopLoc_Location(a2));
  (void)u4;
  st->UpdateAssemblies();
  TDF_Label top = st->NewShape();
  gp_Trsf t1, t2; t2.SetTranslationPart(gp_Vec(100, 0, 0));
  TDF_Label u1 = st->AddComponent(top, sub, TopLoc_Location(t1));
  TDF_Label u2 = st->AddComponent(top, sub, TopLoc_Location(t2));
  (void)u1;
  st->UpdateAssemblies();
  TDF_LabelSequence path; path.Append(u2); path.Append(u3);
  if (deep) {
    // three levels: TOP > ASM x2 (at x=1000, x=2000) > SUB x2 (u1 at 0, u2 at 100) > BOX x2; path [asm2, u2, u3]
    TDF_Label asmL = st->NewShape();
    gp_Trsf s1, s2; s2.SetTranslationPart(gp_Vec(100, 0, 0));
    st->AddComponent(asmL, sub, TopLoc_Location(s1));
    st->AddComponent(asmL, sub, TopLoc_Location(s2));
    st->UpdateAssemblies();
    TDF_Label topL = st->NewShape();
    gp_Trsf q1, q2; q2.SetTranslationPart(gp_Vec(1000, 0, 0));
    st->AddComponent(topL, asmL, TopLoc_Location(q1));
    TDF_Label a2 = st->AddComponent(topL, asmL, TopLoc_Location(q2));
    st->UpdateAssemblies();
    TDF_LabelSequence asmComps; XCAFDoc_ShapeTool::GetComponents(asmL, asmComps);
    TDF_Label inner2 = asmComps.Value(2);
    path.Clear(); path.Append(a2); path.Append(inner2); path.Append(u3);
  }
  Handle(XCAFDoc_GraphNode) shuoNode;
  if (!st->SetSHUO(path, shuoNode)) { std::cerr << "SetSHUO failed\n"; return 1; }
  TDF_Label shuo = shuoNode->Label();
  ct->SetColor(shuo, Quantity_ColorRGBA(Quantity_Color(0, 1, 0, Quantity_TOC_sRGB), 1.0f), XCAFDoc_ColorSurf);
  STEPCAFControl_Writer w;
  w.SetColorMode(true); w.SetSHUOMode(true);
  Interface_Static::SetCVal("write.step.schema", "AP242DIS");
  Interface_Static::SetIVal("write.step.assembly", assembly);
  if (!w.Transfer(doc, STEPControl_AsIs)) { std::cerr << "transfer failed\n"; return 1; }
  if (w.Write(out) != IFSelect_RetDone) { std::cerr << "write failed\n"; return 1; }
  app->Close(doc);
  std::printf("S0 written to %s\n", out);
  return 0;
}

int main(int argc, char** argv) {
  if (argc >= 3 && std::string(argv[1]) == "--s0") return writeS0(argv[2], argc >= 4 ? std::atoi(argv[3]) : 2, false);
  if (argc >= 3 && std::string(argv[1]) == "--s0-deep") return writeS0(argv[2], 2, true);
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
  reader.SetColorMode(true); reader.SetNameMode(true); reader.SetSHUOMode(true);
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
  double volume = 0; int componentCount = 0; int shuoColoured = 0;
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
        {
          TDF_AttributeSequence shuos;
          XCAFDoc_ShapeTool::GetAllComponentSHUO(cs.Value(k), shuos);
          for (int q = 1; q <= shuos.Length() && q <= 4096; ++q) {
            TDF_Label sl = shuos.Value(q)->Label();
            Col sc = readColour(ct, sl);
            TDF_LabelSequence up, nx;
            XCAFDoc_ShapeTool::GetSHUOUpperUsage(sl, up);
            XCAFDoc_ShapeTool::GetSHUONextUsage(sl, nx);
            TCollection_AsciiString entry;
            TDF_Tool::Entry(sl, entry);
            std::printf("shuo %s of component %s upper %d next %d colour %s\n", entry.ToCString(), cp.c_str(), up.Length(), nx.Length(), show(sc).c_str());
            if (sc.has) ++shuoColoured;
          }
        }
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
  expectScalar("--shuos", std::to_string(shuoColoured), scalar.count("--shuos") && std::atoi(scalar["--shuos"].c_str()) == shuoColoured);
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
  // (b) XCAFDoc_ColorTool::GetInstanceColor on the located leaf shape, keyed by the leaf's bounding-box minX ("x<minX>")
  std::map<std::string, Col> instColour;
  for (int i = 1; i <= frees.Length(); ++i) {
    std::vector<std::pair<TopoDS_Shape, int>> stack{{XCAFDoc_ShapeTool::GetShape(frees.Value(i)), 0}};
    int guard = 0;
    while (!stack.empty() && ++guard < 100000) {
      auto [sh, depth] = stack.back(); stack.pop_back();
      if (sh.ShapeType() == TopAbs_COMPOUND && depth < 32) {
        for (TopoDS_Iterator it(sh); it.More(); it.Next()) stack.push_back({it.Value(), depth + 1});
        continue;
      }
      if (sh.ShapeType() != TopAbs_SOLID) continue;
      Bnd_Box bb; BRepBndLib::Add(sh, bb);
      double x0, y0, z0, x1, y1, z1; bb.Get(x0, y0, z0, x1, y1, z1);
      Col c; Quantity_ColorRGBA q;
      if (ct->GetInstanceColor(sh, XCAFDoc_ColorSurf, q) || ct->GetInstanceColor(sh, XCAFDoc_ColorGen, q)) {
        c.has = true; q.GetRGB().Values(c.r, c.g, c.b, Quantity_TOC_sRGB); c.a = (double)q.Alpha();
      }
      char key[64]; std::snprintf(key, sizeof key, "x%.0f", std::fabs(x0) < 0.5 ? 0.0 : x0);
      instColour[key] = c;
      std::printf("instance leaf %s GetInstanceColor %s\n", key, show(c).c_str());
    }
  }
  for (int i = 0; i < argc; ++i) if (std::string(argv[i]) == "--inst" && i + 1 < argc) {
    std::string v = argv[i + 1]; auto eq = v.find('=');
    check("inst", v.substr(0, eq), v.substr(eq + 1), instColour);
  }
  app->Close(doc);
  std::printf(ok ? "RESULT ok\n" : "RESULT mismatch\n");
  return ok ? 0 : 1;
}

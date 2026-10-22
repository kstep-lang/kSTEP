// Reads a STEP file with OCCT's typed STEP model and reports product structure.
#include <STEPControl_Reader.hxx>
#include <StepData_StepModel.hxx>
#include <StepBasic_Product.hxx>
#include <StepBasic_ProductDefinition.hxx>
#include <StepBasic_ProductDefinitionFormation.hxx>
#include <StepRepr_NextAssemblyUsageOccurrence.hxx>
#include <StepBasic_Approval.hxx>
#include <StepBasic_ProductDefinitionRelationship.hxx>
#include <Interface_Check.hxx>
#include <XSControl_WorkSession.hxx>
#include <Interface_CheckIterator.hxx>
#include <TCollection_HAsciiString.hxx>
#include <iostream>
#include <map>
static std::string s(const Handle(TCollection_HAsciiString)& h){return h.IsNull()?"<null>":h->ToCString();}
int main(int argc,char**argv){
  STEPControl_Reader r; IFSelect_ReturnStatus st=r.ReadFile(argv[1]);
  std::cout<<"read status: "<<(st==IFSelect_RetDone?"RetDone":"NOT DONE ("+std::to_string((int)st)+")")<<"\n";
  Handle(StepData_StepModel) m=r.StepModel(); if(m.IsNull()){std::cout<<"no model\n";return 1;}
  std::cout<<"entities: "<<m->NbEntities()<<"\n";
  std::map<std::string,int> kinds; int unknown=0;
  for(int i=1;i<=m->NbEntities();++i){
    Handle(Standard_Transient) e=m->Value(i);
    kinds[e->DynamicType()->Name()]++;
    if(auto p=Handle(StepBasic_Product)::DownCast(e)) std::cout<<"PRODUCT #"<<m->IdentLabel(e)<<" id="<<s(p->Id())<<" name="<<s(p->Name())<<"\n";
    if(auto d=Handle(StepBasic_ProductDefinition)::DownCast(e)) std::cout<<"PRODUCT_DEFINITION #"<<m->IdentLabel(e)<<" id="<<s(d->Id())<<" formation=#"<<m->IdentLabel(d->Formation())<<"\n";
    if(auto n=Handle(StepRepr_NextAssemblyUsageOccurrence)::DownCast(e)) std::cout<<"NAUO #"<<m->IdentLabel(e)<<" id="<<s(n->Id())<<" name="<<s(n->Name())<<" relating=#"<<m->IdentLabel(n->RelatingProductDefinition())<<" related=#"<<m->IdentLabel(n->RelatedProductDefinition())<<"\n";
    if(auto a=Handle(StepBasic_Approval)::DownCast(e)) std::cout<<"APPROVAL #"<<m->IdentLabel(e)<<" level="<<s(a->Level())<<"\n";
  }
  for(auto&k:kinds) if(k.first.rfind("StepData_UndefinedEntity",0)==0||k.first.find("Unknown")!=std::string::npos) unknown+=k.second;
  std::cout<<"entity types: "; for(auto&k:kinds) std::cout<<k.first<<"x"<<k.second<<" "; std::cout<<"\n";
  std::cout<<"undefined/unknown entities: "<<unknown<<"\n";
  Interface_CheckIterator ci=r.WS()->ModelCheckList(); ci.SetName(""); int fails=0,warns=0;
  for(ci.Start();ci.More();ci.Next()){const Handle(Interface_Check)&c=ci.Value();fails+=c->NbFails();warns+=c->NbWarnings();for(int i=1;i<=c->NbFails();++i)std::cout<<"FAIL: "<<c->CFail(i)<<"\n";for(int i=1;i<=c->NbWarnings();++i)std::cout<<"WARN: "<<c->CWarning(i)<<"\n";}
  std::cout<<"model check: fails="<<fails<<" warnings="<<warns<<"\n"; return 0;}

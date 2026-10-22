// kSTEP OCCT JNI bridge.
//
// A deliberately small, hand-written JNI shim around a system-installed OpenCASCADE Technology
// (OCCT) library -- see docs/adr/ADR-0005-occt-jni-bridge.adoc for why this exists instead of a
// generated binding (JavaCPP, occjava, Panama/FFM), what it covers in this wave (a box primitive,
// its B-Rep topology/volume, and STEP export), and OCCT's own license/provenance. kSTEP does not
// vendor OCCT itself anywhere in this repository -- this file only *links against* a system
// installation (Ubuntu `libocct-*-dev` packages) at build time; see kstep-geometry/build.gradle.kts.
//
// Every exported function below is `extern "C"` (no C++ name mangling -- JNI symbol resolution is
// by an exact, JNI-convention-mangled name computed from the *Java* declaring class and method
// name; see the `Java_dev_kstep_geometry_occt_OcctBridge_*` names below, and note that `OcctBridge`
// is a plain Kotlin `object` without `@JvmStatic`/`companion object`, so every native method is an
// *instance* method taking a `jobject` second parameter -- verify with
// `nm -D --defined-only <library>.so` after any signature change) and follows two invariants
// throughout:
//
//   1. No C++ exception ever crosses back across the JNI boundary. Standard_Failure (OCCT's own
//      exception hierarchy), std::exception, and a bare `catch (...)` are all caught inside every
//      exported function and translated into a Java exception via throwJava() before returning --
//      an uncaught C++ exception propagating out of a JNI-called function is undefined behavior
//      (typically process abort()), not something Java code could ever catch.
//   2. Every native shape handle is validated against g_shapes before use. An unknown/stale handle
//      throws a Java IllegalStateException and the function returns immediately -- the raw pointer
//      is NEVER dereferenced in that case, so a caller passing a bad handle cannot crash the JVM
//      (see lookupOrThrow()/nativeReleaseShape() below).
//
// Deliberately NOT called anywhere in this file: OSD::SetSignal(). It installs OS-level signal
// handlers that collide with the JVM's own SIGSEGV/SIGBUS handlers, turning a recoverable OCCT
// error into a JVM crash instead of a catchable exception -- the opposite of invariant 1 above.
#include <jni.h>

#include <cmath>
#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif
#include <mutex>
#include <sstream>
#include <string>
#include <unordered_map>

#include <BRepAdaptor_Curve.hxx>
#include <BRepAdaptor_Surface.hxx>
#include <BRepBndLib.hxx>
#include <BRepBuilderAPI_Copy.hxx>
#include <BRepBuilderAPI_MakeEdge.hxx>
#include <BRepExtrema_DistShapeShape.hxx>
#include <BRep_Builder.hxx>
#include <TopoDS_Compound.hxx>
#include <BRepBuilderAPI_MakeShape.hxx>
#include <BRepBuilderAPI_MakeWire.hxx>
#include <GC_MakeArcOfCircle.hxx>
#include <GCPnts_AbscissaPoint.hxx>
#include <GeomAbs_CurveType.hxx>
#include <GeomAbs_SurfaceType.hxx>
#include <Geom_TrimmedCurve.hxx>
#include <TopTools_ListIteratorOfListOfShape.hxx>
#include <gp_Circ.hxx>
#include <gp_Cylinder.hxx>
#include <gp_Lin.hxx>
#include <gp_Pln.hxx>
#include <BRepAlgoAPI_BooleanOperation.hxx>
#include <BRepAlgoAPI_Common.hxx>
#include <BRepAlgoAPI_Cut.hxx>
#include <BRepAlgoAPI_Fuse.hxx>
#include <BRepBuilderAPI_MakeFace.hxx>
#include <BRepBuilderAPI_MakePolygon.hxx>
#include <BRepBuilderAPI_Transform.hxx>
#include <BRepCheck_Analyzer.hxx>
#include <BRepFilletAPI_MakeChamfer.hxx>
#include <BRepFilletAPI_MakeFillet.hxx>
#include <BRepGProp.hxx>
#include <BRepLib_ToolTriangulatedShape.hxx>
#include <BRepMesh_IncrementalMesh.hxx>
#include <BRepPrimAPI_MakeBox.hxx>
#include <BRepPrimAPI_MakeCylinder.hxx>
#include <BRepPrimAPI_MakePrism.hxx>
#include <BRepPrimAPI_MakeRevol.hxx>
#include <BRepTools.hxx>
#include <BRep_Tool.hxx>
#include <Bnd_Box.hxx>
#include <GProp_GProps.hxx>
#include <IFSelect_ReturnStatus.hxx>
#include <Interface_Static.hxx>
#include <Poly_Connect.hxx>
#include <Poly_Triangle.hxx>
#include <Poly_Triangulation.hxx>
#include <Precision.hxx>
#include <STEPControl_StepModelType.hxx>
#include <Message.hxx>
#include <Message_Messenger.hxx>
#include <Message_SequenceOfPrinters.hxx>
#include <STEPControl_Writer.hxx>
#include <Standard_Failure.hxx>
#include <Standard_Version.hxx>
#include <TopAbs_ShapeEnum.hxx>
#include <TopExp.hxx>
#include <TopExp_Explorer.hxx>
#include <TopLoc_Location.hxx>
#include <TopTools_IndexedMapOfShape.hxx>
#include <TopTools_ListOfShape.hxx>
#include <TopoDS.hxx>
#include <TopoDS_Edge.hxx>
#include <TopoDS_Face.hxx>
#include <TopoDS_Shape.hxx>
#include <TopoDS_Wire.hxx>
#include <gp.hxx>
#include <gp_Ax1.hxx>
#include <gp_Ax2.hxx>
#include <gp_Dir.hxx>
#include <gp_Pnt.hxx>
#include <gp_Trsf.hxx>
#include <gp_Vec.hxx>
#include <gp_Vec3f.hxx>

// Welle K (STEP import, ADR-0030): XCAF document reading.
#include <Interface_InterfaceModel.hxx>
#include <STEPCAFControl_Reader.hxx>
#include <StepData_StepModel.hxx>
#include <StepData_UndefinedEntity.hxx>
#include <StepAP214_AppliedDocumentReference.hxx>
#include <StepBasic_Document.hxx>
#include <StepBasic_ProductDefinitionWithAssociatedDocuments.hxx>
#include <TCollection_AsciiString.hxx>
#include <TCollection_ExtendedString.hxx>
#include <TDF_Label.hxx>
#include <TDF_LabelSequence.hxx>
#include <TDF_Tool.hxx>
#include <TDataStd_Name.hxx>
#include <TDocStd_Document.hxx>
#include <UnitsMethods_LengthUnit.hxx>
#include <XCAFApp_Application.hxx>
#include <XCAFDoc_DocumentTool.hxx>
#include <XCAFDoc_ShapeTool.hxx>

#include <memory>
#include <vector>

namespace {

// Guards BOTH the JVM-side handle registry below (g_shapes/g_nextHandle) AND every call into
// Interface_Static -- OCCT's "write.step.schema" configuration is process-global mutable state
// (Interface_Static.hxx), not per-STEPControl_Writer-instance, so setting the schema and running
// the writer must be one atomic critical section across all threads. As a documented consequence,
// nativeWriteStep calls from different threads are fully serialized with each other AND with
// handle registration/release -- see OcctShape.writeStepFile's KDoc on the Kotlin side, which
// repeats this for callers who never read this file.
//
// Use-after-free fix: g_mutex now covers the *entire* lifetime of every dereference of a
// TopoDS_Shape*, not just the registry lookup. Every exported function that touches a shape
// (nativeShapeCounts, nativeShapeVolume, nativeWriteStep) finds the pointer AND runs every OCCT
// call that dereferences it inside the SAME lock_guard scope; nativeReleaseShape erases the
// handle from g_shapes under that same lock before its `delete` runs. That makes "erase from
// registry" and "any in-flight use of the shape" mutually exclusive: a reader can only ever
// observe a shape that is still registered (because releasing removes it from the map first, also
// under the lock), and a release can only run after every reader that already had the pointer has
// finished using it (because that use happened inside the reader's own critical section). See
// findShapeLocked()/throwUnknownHandle() below and every caller of them for the pattern: the
// not-found path still throws OUTSIDE the lock (see lookupOrThrow()'s historical comment, kept
// below, on why -- FindClass can trigger arbitrary Java code, which must never run while this
// thread holds a plain, non-recursive std::mutex it might then try to re-acquire).
std::mutex g_mutex;
std::unordered_map<jlong, TopoDS_Shape*> g_shapes;
jlong g_nextHandle = 1;

/**
 * Throws a Java exception of the given class with the given message onto `env`. Does NOT unwind
 * the C++ call stack the way a C++ `throw` would -- the caller must still return immediately
 * afterward without touching any JNI-observable state further; the pending exception is only
 * delivered to the Java caller once the native function actually returns.
 */
void throwJava(JNIEnv* env, const char* className, const std::string& message) {
    jclass cls = env->FindClass(className);
    if (cls != nullptr) {
        env->ThrowNew(cls, message.c_str());
        env->DeleteLocalRef(cls);
    }
    // If FindClass itself failed, a pending exception (typically NoClassDefFoundError) is
    // already set on env by the JVM -- nothing further to do here.
}

/**
 * Looks up a shape by handle. MUST be called with g_mutex already held by the caller, for exactly
 * as long as the returned pointer is going to be dereferenced -- see the use-after-free note on
 * g_mutex's declaration above. Returns nullptr if the handle is unknown; the caller must check for
 * that and, once it has released the lock, report it via throwUnknownHandle() below (never while
 * still holding g_mutex).
 */
TopoDS_Shape* findShapeLocked(jlong handle) {
    auto it = g_shapes.find(handle);
    return it != g_shapes.end() ? it->second : nullptr;
}

/**
 * Throws the standard "unknown handle" IllegalStateException. Callers MUST NOT hold g_mutex when
 * calling this -- throwJava() calls JNIEnv::FindClass, which can trigger JVM class loading and
 * therefore arbitrary Java code. Calling it while still holding g_mutex would risk that code path
 * blocking on g_mutex itself (e.g. a Cleaner action concurrently running nativeReleaseShape()),
 * which g_mutex being a plain, non-recursive std::mutex could turn into a deadlock.
 */
void throwUnknownHandle(JNIEnv* env, jlong handle) {
    std::ostringstream msg;
    msg << "Unknown native OCCT shape handle: " << handle;
    throwJava(env, "java/lang/IllegalStateException", msg.str());
}

/** Heap-allocates a copy of `shape`, registers it under a fresh handle, and returns that handle. */
jlong registerShape(TopoDS_Shape&& shape) {
    auto* heapShape = new TopoDS_Shape(std::move(shape));
    std::lock_guard<std::mutex> lock(g_mutex);
    jlong handle = g_nextHandle++;
    g_shapes[handle] = heapShape;
    return handle;
}

/** Same as registerShape, for a caller that ALREADY holds g_mutex (never call from inside one). */
jlong registerShapeLocked(TopoDS_Shape&& shape) {
    auto* heapShape = new TopoDS_Shape(std::move(shape));
    jlong handle = g_nextHandle++;
    g_shapes[handle] = heapShape;
    return handle;
}

// ------------------------------------------------------------------------------------------
// Welle K (STEP import). See docs/adr/ADR-0030-read-geometry.adoc.
// ------------------------------------------------------------------------------------------
constexpr int kImportKindPart = 0;
constexpr int kImportKindAssembly = 1;
constexpr int kImportKindComponent = 2;
constexpr int kMaxReportedUnknownTypes = 20;
constexpr int kImportLimitCount = 6;

struct ImportNode {
    int kind = kImportKindPart;
    int parent = -1;
    int referred = -1;
    int freeRoot = 0;
    int rigid = 1;
    std::u16string name;
    double m[12] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};
    int shapeSlot = -1;
};

struct ImportSession {
    std::vector<ImportNode> nodes;
    std::vector<TopoDS_Shape> partShapes;
    int entityCount = 0;
    int unknownEntityCount = 0;
    int freeRootCount = 0;
    std::vector<std::string> unknownTypes;
};

// Guarded by g_mutex. Handles come from g_nextHandle (shared with shapes, so they never collide).
std::unordered_map<jlong, ImportSession*> g_imports;


// ------------------------------------------------------------------------------------------
// Geometrie Welle 5a (extrude + fillet) additions below. See
// docs/adr/ADR-0008-occt-feature-operations.adoc.
// ------------------------------------------------------------------------------------------

// Mirror dev.kstep.geometry.OcctKernel's Kotlin-side constants of the same name. This file is
// reachable directly (dev.kstep.geometry.occt.OcctBridge is a public object, per this file's own
// header comment and OcctBridge.kt's KDoc), so every bound enforced on the Kotlin side is
// re-enforced here independently -- see OcctFeatureOperationsTest's T-19 for the regression test
// that these native checks hold even when OcctKernel's own validation is bypassed via reflection.
// Kept as plain constants, not shared with Kotlin, the same hand-synchronized-pair pattern
// ADR-0007 already uses for NativeConstraintKind -- see that ADR's Decision section.
constexpr int kMinProfilePoints = 3;
constexpr int kMaxProfilePoints = 512;
constexpr double kMaxAbsCoordinate = 1e7;
constexpr double kMinDimension = 1e-7;
constexpr double kMaxDimension = 1e7;
constexpr int kMaxFilletInputFaces = 200;
constexpr int kMaxFilletEdges = 64;
constexpr double kMinFilletRadius = 1e-7;
constexpr double kMaxFilletRadius = 1e7;

// ------------------------------------------------------------------------------------------
// Welle J (geometry operations: booleans, cylinder, revolve, chamfer, transform). See
// docs/adr/ADR-0029-geometry-operations.adoc for the measurements behind every limit below.
// Hand-synchronized with the constants of the same name in dev.kstep.geometry.OcctKernel.
// ------------------------------------------------------------------------------------------
constexpr double kMinChamferDistance = 1e-7;
constexpr double kMaxChamferDistance = 1e7;
constexpr int kMaxChamferEdges = 64;
constexpr int kMaxChamferInputFaces = 150;  // 64-edge chamfer: 113 ms @102 faces, 317 ms @200 faces (OCCT 7.9.3)
constexpr int kMaxBooleanInputFaces = 200;  // worst boolean at 200 faces: 132 ms (OCCT 7.9.3)
constexpr double kMinRevolveAngleDeg = 1e-4;
constexpr double kMaxRevolveAngleDeg = 360.0;
constexpr int kMaxRevolveProfilePoints = 512;  // 360 deg revolve of 512 points: 44 ms, 26 774 triangles
constexpr double kMinDirectionLength = 1e-9;
constexpr double kOrthonormalTolerance = 1e-9;

// ------------------------------------------------------------------------------------------
// Viewer-Welle 1 (triangulation) additions below. See
// docs/adr/ADR-0010-occt-triangulation-and-viewer.adoc.
// ------------------------------------------------------------------------------------------

// Speicher-Guard fuer den JNI-Rueckgabe-Array (9 Doubles je Dreieck = 72 B). Gemessen (OCCT
// 7.9.2, siehe ADR-0010): der teuerste ueber kSTEPs oeffentliche API erreichbare Shape -- ein
// 198-Eck-Prisma (200 Faces = kMaxFilletInputFaces) mit 64 Fillets (= kMaxFilletEdges) --
// ergibt 6 332 Dreiecke in 52,6 ms. 130 000 sind ~20x davon (9,4 MB), dieselbe
// "messen, dann Kopffreiheit lassen"-Methode wie kMaxProfilePoints. Die ZEIT-Schranke ist
// nicht dieser Wert, sondern transitiv kMaxFilletInputFaces/kMaxProfilePoints -- mirrors
// dev.kstep.geometry.OcctKernel.MAX_TRIANGLES (hand-synchronized pair, same pattern
// ADR-0007's NativeConstraintKind already uses).
// Welle J (ADR-0029): the 6 332 figure above is outdated -- a 512-point revolve reaches 26 774
// triangles (measured, OCCT 7.9.3), still ~5x below this cap.
constexpr jint kMaxTriangles = 130000;

// ------------------------------------------------------------------------------------------
// Viewer-Folge-Welle (smooth per-vertex normals). See docs/adr/ADR-0018-smooth-vertex-normals.adoc.
// ------------------------------------------------------------------------------------------

// Below this length, a per-vertex normal extracted from OCCT's own Poly_Triangulation is treated
// as degenerate and the triangle's flat face normal is substituted instead -- see
// nativeShapeTriangles's normal-extraction pass below. Same order of magnitude as
// DEGENERATE_NORMAL_LENGTH_EPSILON in kstep-render's GlbWriter.kt (independent, Kotlin-side
// degenerate-normal guard over user-supplied TriangleMesh data -- this one guards the native
// OCCT-computed normal instead).
constexpr double kMinNormalLength = 1e-9;

// Kein Qualitaetsparameter an der API-Grenze -- die Abweichung wird nativ aus der
// Bounding-Box-Diagonale abgeleitet. Ein Aufrufer mit deflection=1e-9 WAERE der DoS.
constexpr double kMeshDeflectionFactor = 0.005;
constexpr double kMinMeshDeflection = 1e-5;
constexpr double kMaxMeshDeflection = 1e3;
constexpr double kMeshAngularDeflection = 0.5;

/**
 * RAII wrapper around JNIEnv::GetDoubleArrayElements/ReleaseDoubleArrayElements. Always releases
 * with JNI_ABORT (discard any temporary copy, no write-back) -- every array this bridge reads
 * through this guard is input-only. `elements()` returns nullptr if the underlying array itself
 * was nullptr, OR if the JVM reports an OutOfMemoryError (in which case that error is already
 * pending on `env`) -- the caller must check for nullptr and return immediately in that case.
 * Copied verbatim (module-appropriate namespace only) from
 * kstep-constraints/src/main/cpp/kstep_planegcs_bridge.cpp's identical guard -- see that file for
 * the original. A third copy (this bridge's second RAII-guard pair after that one) is the trigger
 * point named in ADR-0008 for pulling both into a shared header across the two Gradle modules.
 */
class DoubleArrayGuard {
public:
    DoubleArrayGuard(JNIEnv* env, jdoubleArray array) : env_(env), array_(array), elements_(nullptr) {
        if (array_ != nullptr) {
            elements_ = env_->GetDoubleArrayElements(array_, nullptr);
        }
    }
    ~DoubleArrayGuard() {
        if (elements_ != nullptr) {
            env_->ReleaseDoubleArrayElements(array_, elements_, JNI_ABORT);
        }
    }
    DoubleArrayGuard(const DoubleArrayGuard&) = delete;
    DoubleArrayGuard& operator=(const DoubleArrayGuard&) = delete;

    jdouble* elements() const { return elements_; }

private:
    JNIEnv* env_;
    jdoubleArray array_;
    jdouble* elements_;
};

/** Same as DoubleArrayGuard, for jintArray/GetIntArrayElements. */
class IntArrayGuard {
public:
    IntArrayGuard(JNIEnv* env, jintArray array) : env_(env), array_(array), elements_(nullptr) {
        if (array_ != nullptr) {
            elements_ = env_->GetIntArrayElements(array_, nullptr);
        }
    }
    ~IntArrayGuard() {
        if (elements_ != nullptr) {
            env_->ReleaseIntArrayElements(array_, elements_, JNI_ABORT);
        }
    }
    IntArrayGuard(const IntArrayGuard&) = delete;
    IntArrayGuard& operator=(const IntArrayGuard&) = delete;

    jint* elements() const { return elements_; }

private:
    JNIEnv* env_;
    jintArray array_;
    jint* elements_;
};

/**
 * Ruft BRepTools::Clean(shape) im Destruktor -- also auf JEDEM Pfad, auch bei einer
 * Standard_Failure aus BRepMesh_IncrementalMesh heraus.
 *
 * Grund: BRepMesh_IncrementalMesh MUTIERT die Eingabe-Shape (die Triangulierung wird in der
 * TShape abgelegt). Ohne dieses Aufraeumen bliebe ein in g_shapes registrierter, nach aussen
 * "unveraenderlicher" Shape nach jedem Viewer-Aufruf dauerhaft groesser, und niemand saehe es
 * -- genau der "Resource-Leak bei nativen Meshes" der CLAUDE.md-Sicherheits-Pruefliste. Preis:
 * erneutes Meshen bei jedem Aufruf (gemessen 0,3-0,9 ms fuer Box/Fillet). Fuer eine statische
 * Einzeldarstellung ist das kein Preis.
 *
 * OCCTs Clean() entfernt polygonale Repraesentationen NICHT, wenn sie die einzige
 * Repraesentation der Shape sind (BRepTools.hxx) -- ein rein tesselierter Shape wird also nicht
 * beschaedigt. In kSTEP kann dieser Fall heute ohnehin nicht auftreten (es gibt nur den
 * STEP-Writer-Pfad, keinen Importer).
 */
class TriangulationCleanupGuard {
public:
    explicit TriangulationCleanupGuard(const TopoDS_Shape* shape) : shape_(shape) {}
    ~TriangulationCleanupGuard() {
        if (shape_ != nullptr) {
            try {
                BRepTools::Clean(*shape_);
            } catch (...) {
                // Destruktor darf nie werfen.
            }
        }
    }
    TriangulationCleanupGuard(const TriangulationCleanupGuard&) = delete;
    TriangulationCleanupGuard& operator=(const TriangulationCleanupGuard&) = delete;

private:
    const TopoDS_Shape* shape_;
};

// ------------------------------------------------------------------------------------------
// Welle J shared helpers. Every function below that reports an error does so through throwJava()
// and returns false; the caller must then return immediately. None of them ever touches g_mutex.
// ------------------------------------------------------------------------------------------

/**
 * Reads, validates and copies an x,y-pair profile array into `out`. Error texts are prefixed with
 * `fnName` and are word-for-word the ones nativeExtrudeProfile always produced (tests assert on
 * substrings of them).
 */
bool readProfileXy(JNIEnv* env, jdoubleArray profileXy, int maxPoints, const char* fnName, std::vector<double>& out) {
    const std::string fn(fnName);
    if (profileXy == nullptr) {
        throwJava(env, "java/lang/NullPointerException", fn + ": profileXy must not be null");
        return false;
    }
    jsize length = env->GetArrayLength(profileXy);
    if (length % 2 != 0) {
        throwJava(
            env,
            "java/lang/IllegalArgumentException",
            fn + ": profileXy length must be even (x,y pairs), got " + std::to_string(length));
        return false;
    }
    jsize pointCount = length / 2;
    if (pointCount < kMinProfilePoints || pointCount > maxPoints) {
        throwJava(
            env,
            "java/lang/IllegalArgumentException",
            fn + ": profile must have between " + std::to_string(kMinProfilePoints) + " and " +
                std::to_string(maxPoints) + " points, got " + std::to_string(pointCount));
        return false;
    }
    DoubleArrayGuard guard(env, profileXy);
    const jdouble* xy = guard.elements();
    if (xy == nullptr) {
        return false;  // OutOfMemoryError already pending
    }
    for (jsize i = 0; i < pointCount; ++i) {
        double x = xy[i * 2];
        double y = xy[i * 2 + 1];
        if (!std::isfinite(x) || !std::isfinite(y) || std::abs(x) > kMaxAbsCoordinate ||
            std::abs(y) > kMaxAbsCoordinate) {
            throwJava(
                env,
                "java/lang/IllegalArgumentException",
                fn + ": profile[" + std::to_string(i) + "] must be finite and within +/-" +
                    std::to_string(kMaxAbsCoordinate) + ", got (" + std::to_string(x) + ", " + std::to_string(y) + ")");
            return false;
        }
    }
    // See the long comment in the original nativeExtrudeProfile history (ADR-0008): MakePolygon
    // silently drops points within Precision::Confusion() of their predecessor, so reject them
    // up front with the same tolerance.
    const double confusionSquared = Precision::SquareConfusion();
    for (jsize i = 0; i < pointCount; ++i) {
        jsize next = (i + 1) % pointCount;
        double dx = xy[i * 2] - xy[next * 2];
        double dy = xy[i * 2 + 1] - xy[next * 2 + 1];
        if (dx * dx + dy * dy <= confusionSquared) {
            throwJava(
                env,
                "java/lang/IllegalArgumentException",
                fn + ": profile has coincident (or near-coincident, within " + std::to_string(Precision::Confusion()) +
                    ") consecutive points at index " + std::to_string(i));
            return false;
        }
    }
    out.assign(xy, xy + length);
    return true;
}

/** Builds the planar face (XY plane) of a validated profile. Throws IllegalArgumentException on failure. */
bool buildProfileFace(JNIEnv* env, const std::vector<double>& xy, const char* fnName, TopoDS_Face& out) {
    const std::string fn(fnName);
    BRepBuilderAPI_MakePolygon polygonMaker;
    for (size_t i = 0; i + 1 < xy.size(); i += 2) {
        polygonMaker.Add(gp_Pnt(xy[i], xy[i + 1], 0.0));
    }
    polygonMaker.Close();
    if (!polygonMaker.IsDone()) {
        throwJava(env, "java/lang/IllegalArgumentException", fn + ": failed to close polygon");
        return false;
    }
    TopoDS_Wire wire = polygonMaker.Wire();
    BRepBuilderAPI_MakeFace faceMaker(wire, Standard_True);
    if (!faceMaker.IsDone()) {
        throwJava(
            env,
            "java/lang/IllegalArgumentException",
            fn + ": failed to build a planar face from the profile (BRepBuilderAPI_FaceError=" +
                std::to_string(static_cast<int>(faceMaker.Error())) + ") -- profile may be non-planar or self-intersecting");
        return false;
    }
    out = faceMaker.Face();
    return true;
}

/** Number of TopAbs_SOLID sub-shapes (pure read; callers hold g_mutex if `shape` is registry-owned). */
int countSolids(const TopoDS_Shape& shape) {
    int n = 0;
    for (TopExp_Explorer ex(shape, TopAbs_SOLID); ex.More(); ex.Next()) {
        ++n;
    }
    return n;
}

/** Returns the first solid contained in `shape` (callers have checked countSolids() == 1). */
TopoDS_Shape unwrapSingleSolid(const TopoDS_Shape& shape) {
    TopExp_Explorer ex(shape, TopAbs_SOLID);
    return ex.More() ? TopoDS_Shape(ex.Current()) : shape;
}

/** Number of TopAbs_SHELL sub-shapes (pure read; callers hold g_mutex if `shape` is registry-owned). */
int countShells(const TopoDS_Shape& shape) {
    int n = 0;
    for (TopExp_Explorer ex(shape, TopAbs_SHELL); ex.More(); ex.Next()) {
        ++n;
    }
    return n;
}

/** Outcome of runSolidChecks(): pure data, so the check can run under g_mutex and be reported after. */
struct SolidCheckResult {
    bool valid = true;
    bool positiveVolume = true;
};

/**
 * BRepCheck validity + positive volume, WITHOUT throwing. Results of booleans/chamfers/fillets
 * share untouched sub-shapes (TShapes) with registered operands, and nativeShapeTriangles mutates
 * those (triangulate, then Clean) under g_mutex -- so for such results this MUST run while
 * g_mutex is held. Never calls throwJava.
 */
SolidCheckResult runSolidChecks(const TopoDS_Shape& shape) {
    SolidCheckResult r;
    BRepCheck_Analyzer analyzer(shape);
    if (!analyzer.IsValid()) {
        r.valid = false;
        return r;
    }
    GProp_GProps props;
    BRepGProp::VolumeProperties(shape, props);
    r.positiveVolume = props.Mass() > 0.0;
    return r;
}

/**
 * Mandatory post-check on a not-yet-registered result that shares NO sub-shapes with any
 * registered shape (e.g. a freshly built revolve). Throws a Java exception of `exClass` and
 * returns false when it fails. Must be called without g_mutex held (throwJava).
 */
bool postCheckSolid(JNIEnv* env, const TopoDS_Shape& shape, const char* fnName, const char* exClass) {
    const std::string fn(fnName);
    const SolidCheckResult r = runSolidChecks(shape);
    if (!r.valid) {
        throwJava(env, exClass, fn + ": resulting solid failed OCCT's own validity check");
        return false;
    }
    if (!r.positiveVolume) {
        throwJava(env, exClass, fn + ": resulting solid has zero volume");
        return false;
    }
    return true;
}

}  // namespace

// ------------------------------------------------------------------------------------------
// Welle L (stable edge selection + sketch regions). See docs/adr/ADR-0031-sketch-dsl-and-edge-selection.adoc.
// Hand-synchronized with dev.kstep.geometry.OcctKernel's MAX_QUERY_EDGES/MAX_QUERY_FACES and with
// dev.kstep.geometry.ProfileRegion's limits.
// ------------------------------------------------------------------------------------------
constexpr int kMaxQueryEdges = 20000;
constexpr int kMaxQueryFaces = 10000;
constexpr int kEdgeRecord = 20;
constexpr int kFaceRecord = 12;
constexpr size_t kMaxHistoryInts = 2000000;
constexpr int kMaxRegionLoops = 64;
constexpr int kMaxRegionSegments = 512;
constexpr int kSegmentRecord = 6;
constexpr double kRegionContinuityTolerance = 1e-7;
constexpr int kFateKept = 0;
constexpr int kFateModified = 1;
constexpr int kFateDeleted = 2;
constexpr int kFateLost = 3;

/**
 * Encodes, per operand edge, what the modelling algorithm `maker` did to it, expressed as 0-based
 * indices into the edge map of `result` (`TopExp::MapShapes(result, TopAbs_EDGE)`; that order is
 * deterministic for one TopoDS_Shape, and `result` is exactly the shape later registered).
 * Layout: [operandCount, (edgeCount, {status, imageCount, images...}*)*].
 * MUST run while g_mutex is held (operands and result share TShapes with registry shapes).
 * Returns false if the encoding would exceed kMaxHistoryInts. Never throws a Java exception.
 */
bool encodeEdgeHistory(
    BRepBuilderAPI_MakeShape& maker, const std::vector<const TopoDS_Shape*>& operands, const TopoDS_Shape& result,
    std::vector<jint>& out) {
    TopTools_IndexedMapOfShape resultEdges;
    TopExp::MapShapes(result, TopAbs_EDGE, resultEdges);
    out.clear();
    out.push_back(static_cast<jint>(operands.size()));
    for (const TopoDS_Shape* operand : operands) {
        TopTools_IndexedMapOfShape edges;
        TopExp::MapShapes(*operand, TopAbs_EDGE, edges);
        out.push_back(static_cast<jint>(edges.Extent()));
        for (int i = 1; i <= edges.Extent(); ++i) {
            const TopoDS_Shape& edge = edges(i);
            std::vector<jint> images;
            int status = kFateLost;
            // Order matters: BRepFilletAPI_MakeFillet/MakeChamfer report IsDeleted()==true for edges
            // that are in fact still present in the result (verified on a box with one filleted edge:
            // 8 of 12 edges were "deleted" although 7 of them survive), so IsDeleted() is consulted
            // only when the edge has neither a recorded successor nor survives by identity.
            const TopTools_ListOfShape& modified = maker.Modified(edge);
            for (TopTools_ListIteratorOfListOfShape it(modified); it.More(); it.Next()) {
                if (it.Value().ShapeType() != TopAbs_EDGE) continue;
                int idx = resultEdges.FindIndex(it.Value());
                if (idx > 0) {
                    jint image = static_cast<jint>(idx - 1);
                    bool seen = false;
                    for (jint known : images) seen = seen || known == image;
                    if (!seen) images.push_back(image);
                }
            }
            if (!images.empty()) {
                status = kFateModified;
            } else if (modified.IsEmpty()) {
                int idx = resultEdges.FindIndex(edge);
                if (idx > 0) {
                    status = kFateKept;
                    images.push_back(static_cast<jint>(idx - 1));
                } else if (maker.IsDeleted(edge)) {
                    status = kFateDeleted;
                }
            } else if (maker.IsDeleted(edge)) {
                status = kFateDeleted;
            }
            out.push_back(status);
            out.push_back(static_cast<jint>(images.size()));
            for (jint image : images) out.push_back(image);
            if (out.size() > kMaxHistoryInts) return false;
        }
    }
    return true;
}

/** Copies `history` into a new jintArray stored at outHistory[0]. Returns false with a pending JVM exception. */
bool publishHistory(JNIEnv* env, jobjectArray outHistory, const std::vector<jint>& history) {
    jintArray arr = env->NewIntArray(static_cast<jsize>(history.size()));
    if (arr == nullptr) return false;
    env->SetIntArrayRegion(arr, 0, static_cast<jsize>(history.size()), history.data());
    if (env->ExceptionCheck()) return false;
    env->SetObjectArrayElement(outHistory, 0, arr);
    env->DeleteLocalRef(arr);
    return !env->ExceptionCheck();
}

/** Validates the out-holder of a *Tracked entry point. Returns false with a pending exception. */
bool checkHistoryHolder(JNIEnv* env, jobjectArray outHistory, const char* fnName) {
    const std::string fn(fnName);
    if (outHistory == nullptr) {
        throwJava(env, "java/lang/NullPointerException", fn + ": outHistory must not be null");
        return false;
    }
    if (env->GetArrayLength(outHistory) < 1) {
        throwJava(env, "java/lang/IllegalArgumentException", fn + ": outHistory must have at least one slot");
        return false;
    }
    return true;
}

/** One parsed sketch-region loop; samples are a polygon approximation used for orientation/axis checks. */
struct RegionLoop {
    TopoDS_Wire wire;
    double signedArea = 0.0;
    std::vector<gp_Pnt> samples;
};

/**
 * Validates and builds the planar face (XY plane) of a region: an outer loop plus optional holes.
 * Layout: loopSizes[l] = segment count of loop l; segKinds[s] in {0 line, 1 arc, 2 circle};
 * segData has kSegmentRecord doubles per segment (line: x1 y1 x2 y2; arc: x1 y1 xm ym x2 y2;
 * circle: cx cy r). Loop 0 is the outer contour. Orientation is normalised natively (outer CCW,
 * holes CW). `allSamples` receives every sampled boundary point (for the revolve axis check).
 * Error texts use IllegalArgumentException; returns false with a pending exception.
 */
bool buildRegionFace(
    JNIEnv* env, jintArray loopSizesArr, jintArray segKindsArr, jdoubleArray segDataArr, const char* fnName,
    TopoDS_Face& out, std::vector<gp_Pnt>& allSamples) {
    const std::string fn(fnName);
    auto fail = [&](const std::string& msg) {
        throwJava(env, "java/lang/IllegalArgumentException", fn + ": " + msg);
        return false;
    };
    if (loopSizesArr == nullptr || segKindsArr == nullptr || segDataArr == nullptr) {
        throwJava(env, "java/lang/NullPointerException", fn + ": region arrays must not be null");
        return false;
    }
    jsize loopCount = env->GetArrayLength(loopSizesArr);
    jsize segCount = env->GetArrayLength(segKindsArr);
    jsize dataLength = env->GetArrayLength(segDataArr);
    if (loopCount < 1 || loopCount > kMaxRegionLoops) {
        return fail("region must have between 1 and " + std::to_string(kMaxRegionLoops) + " loops, got " + std::to_string(loopCount));
    }
    if (segCount < 1 || segCount > kMaxRegionSegments) {
        return fail(
            "region must have between 1 and " + std::to_string(kMaxRegionSegments) + " segments, got " +
            std::to_string(segCount));
    }
    if (static_cast<long long>(dataLength) != static_cast<long long>(segCount) * kSegmentRecord) {
        return fail("segData length must be " + std::to_string(kSegmentRecord) + " x segment count");
    }
    std::vector<jint> loopSizes(loopCount);
    std::vector<jint> kinds(segCount);
    std::vector<double> data(dataLength);
    env->GetIntArrayRegion(loopSizesArr, 0, loopCount, loopSizes.data());
    env->GetIntArrayRegion(segKindsArr, 0, segCount, kinds.data());
    env->GetDoubleArrayRegion(segDataArr, 0, dataLength, data.data());
    if (env->ExceptionCheck()) return false;
    long long total = 0;
    for (jint size : loopSizes) {
        if (size < 1) return fail("every loop must have at least one segment");
        total += size;
    }
    if (total != segCount) return fail("loop sizes must sum to the segment count");
    for (double v : data) {
        if (!std::isfinite(v) || std::abs(v) > kMaxAbsCoordinate) {
            return fail("segment data must be finite and within +/-" + std::to_string(kMaxAbsCoordinate));
        }
    }

    std::vector<RegionLoop> loops;
    int segIndex = 0;
    for (jsize l = 0; l < loopCount; ++l) {
        RegionLoop loop;
        BRepBuilderAPI_MakeWire wireMaker;
        const int n = loopSizes[l];
        gp_Pnt firstStart;
        gp_Pnt previousEnd;
        for (int k = 0; k < n; ++k, ++segIndex) {
            const double* d = &data[static_cast<size_t>(segIndex) * kSegmentRecord];
            const int kind = kinds[segIndex];
            if (kind == 2) {
                if (n != 1) return fail("a circle segment must be the only segment of its loop");
                double r = d[2];
                if (r < kMinDimension || r > kMaxDimension) {
                    return fail("circle radius must be within [" + std::to_string(kMinDimension) + ", " + std::to_string(kMaxDimension) + "]");
                }
                gp_Circ circ(gp_Ax2(gp_Pnt(d[0], d[1], 0.0), gp::DZ()), r);
                BRepBuilderAPI_MakeEdge edgeMaker(circ);
                if (!edgeMaker.IsDone()) return fail("failed to build a circle edge");
                wireMaker.Add(edgeMaker.Edge());
                for (int i = 0; i < 32; ++i) {
                    double a = 2.0 * M_PI * i / 32.0;
                    loop.samples.push_back(gp_Pnt(d[0] + r * std::cos(a), d[1] + r * std::sin(a), 0.0));
                }
                firstStart = gp_Pnt(d[0] + r, d[1], 0.0);
                previousEnd = firstStart;
            } else if (kind == 0 || kind == 1) {
                gp_Pnt a(d[0], d[1], 0.0);
                gp_Pnt b = kind == 0 ? gp_Pnt(d[2], d[3], 0.0) : gp_Pnt(d[4], d[5], 0.0);
                if (k == 0) {
                    firstStart = a;
                } else if (a.Distance(previousEnd) > kRegionContinuityTolerance) {
                    return fail("loop " + std::to_string(l) + " is not continuous at segment " + std::to_string(k));
                }
                if (a.Distance(b) <= Precision::Confusion()) {
                    return fail("segment " + std::to_string(segIndex) + " has coincident start and end");
                }
                if (kind == 0) {
                    BRepBuilderAPI_MakeEdge edgeMaker(a, b);
                    if (!edgeMaker.IsDone()) return fail("failed to build a line edge");
                    wireMaker.Add(edgeMaker.Edge());
                    loop.samples.push_back(a);
                } else {
                    gp_Pnt m(d[2], d[3], 0.0);
                    GC_MakeArcOfCircle arcMaker(a, m, b);
                    if (!arcMaker.IsDone()) return fail("segment " + std::to_string(segIndex) + " is not a valid three-point arc (collinear points?)");
                    Handle(Geom_TrimmedCurve) arc = arcMaker.Value();
                    BRepBuilderAPI_MakeEdge edgeMaker(arc, arc->FirstParameter(), arc->LastParameter());
                    if (!edgeMaker.IsDone()) return fail("failed to build an arc edge");
                    wireMaker.Add(edgeMaker.Edge());
                    for (int i = 0; i < 16; ++i) {
                        double u = arc->FirstParameter() + (arc->LastParameter() - arc->FirstParameter()) * i / 16.0;
                        loop.samples.push_back(arc->Value(u));
                    }
                }
                previousEnd = b;
            } else {
                return fail("segment kind must be 0 (line), 1 (arc) or 2 (circle), got " + std::to_string(kind));
            }
        }
        if (n > 1 && previousEnd.Distance(firstStart) > kRegionContinuityTolerance) {
            return fail("loop " + std::to_string(l) + " is not closed");
        }
        if (!wireMaker.IsDone()) {
            return fail("failed to assemble loop " + std::to_string(l) + " into a wire (BRepBuilderAPI_WireError=" + std::to_string(static_cast<int>(wireMaker.Error())) + ")");
        }
        loop.wire = wireMaker.Wire();
        double area = 0.0;
        for (size_t i = 0; i < loop.samples.size(); ++i) {
            const gp_Pnt& p = loop.samples[i];
            const gp_Pnt& q = loop.samples[(i + 1) % loop.samples.size()];
            area += p.X() * q.Y() - q.X() * p.Y();
        }
        loop.signedArea = area / 2.0;
        if (std::abs(loop.signedArea) <= 1e-12) return fail("loop " + std::to_string(l) + " encloses no area");
        for (const gp_Pnt& p : loop.samples) allSamples.push_back(p);
        loops.push_back(std::move(loop));
    }
    // Outer contour counter-clockwise (face normal +Z), holes clockwise.
    TopoDS_Wire outer = loops[0].wire;
    if (loops[0].signedArea < 0.0) outer.Reverse();
    BRepBuilderAPI_MakeFace faceMaker(outer, Standard_True);
    if (!faceMaker.IsDone()) {
        return fail("failed to build a planar face from the outer loop (BRepBuilderAPI_FaceError=" + std::to_string(static_cast<int>(faceMaker.Error())) + ")");
    }
    for (size_t l = 1; l < loops.size(); ++l) {
        TopoDS_Wire hole = loops[l].wire;
        if (loops[l].signedArea > 0.0) hole.Reverse();
        faceMaker.Add(hole);
    }
    if (!faceMaker.IsDone()) return fail("failed to add a hole loop to the face");
    out = faceMaker.Face();
    return true;
}

extern "C" {

namespace {
// Detaches OCCT's default message printers for its lifetime. See the call site in
// nativeWriteStep for why.
struct PrinterSilencer {
    Handle(Message_Messenger) messenger;
    Message_SequenceOfPrinters saved;
    PrinterSilencer() : messenger(Message::DefaultMessenger()) {
        if (!messenger.IsNull()) {
            saved = messenger->Printers();
            messenger->ChangePrinters().Clear();
        }
    }
    ~PrinterSilencer() {
        if (!messenger.IsNull()) {
            messenger->ChangePrinters() = saved;
        }
    }
    PrinterSilencer(const PrinterSilencer&) = delete;
    PrinterSilencer& operator=(const PrinterSilencer&) = delete;
};

// These helpers use C++ types in their signatures, so they must not have C linkage.
extern "C++" {
/** Closes the XCAF document on EVERY path: XCAFApp_Application keeps documents alive otherwise. */
struct XcafDocumentGuard {
    Handle(XCAFApp_Application) app;
    Handle(TDocStd_Document) doc;
    ~XcafDocumentGuard() {
        try {
            if (!app.IsNull() && !doc.IsNull()) {
                app->Close(doc);
            }
        } catch (...) {
        }
    }
};

std::string sanitizeTypeName(const char* raw) {
    std::string out;
    if (raw == nullptr) return out;
    for (const char* c = raw; *c != '\0' && out.size() < 64; ++c) {
        char ch = *c;
        if (ch >= 'a' && ch <= 'z') ch = static_cast<char>(ch - 'a' + 'A');
        if ((ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || ch == '_') out.push_back(ch);
    }
    return out;
}

std::u16string labelName(const TDF_Label& label, int maxChars) {
    Handle(TDataStd_Name) n;
    if (!label.FindAttribute(TDataStd_Name::GetID(), n)) return std::u16string();
    const TCollection_ExtendedString& ext = n->Get();
    int len = ext.Length();
    if (len > maxChars) len = maxChars;
    std::u16string out;
    out.reserve(static_cast<size_t>(len));
    const Standard_ExtString raw = ext.ToExtString();
    for (int i = 0; i < len; ++i) {
        const char16_t ch = static_cast<char16_t>(raw[i]);
        if (ch < 0x20 || (ch >= 0x7f && ch <= 0x9f)) continue;  // control characters never reach output
        out.push_back(ch);
    }
    return out;
}

std::string labelEntry(const TDF_Label& label) {
    TCollection_AsciiString e;
    TDF_Tool::Entry(label, e);
    return std::string(e.ToCString());
}

int countFaces(const TopoDS_Shape& shape) {
    TopTools_IndexedMapOfShape faces;
    TopExp::MapShapes(shape, TopAbs_FACE, faces);
    return faces.Extent();
}

/**
 * Reads `bytes` through STEPCAFControl_Reader into `out`. MUST be called with g_mutex held
 * (Interface_Static and XCAFApp_Application are process-global). On failure returns false and sets
 * errClass/errMsg; the caller throws AFTER releasing g_mutex. Never throws a C++ exception except
 * what the caller's catch cascade handles.
 */
bool readXcafLocked(
    const std::string& bytes, const int limits[kImportLimitCount], ImportSession& out, std::string& errClass,
    std::string& errMsg) {
    const int maxEntities = limits[0];
    const int maxNodes = limits[1];
    const int maxComponents = limits[2];
    const int maxParts = limits[3];
    const int maxFaces = limits[4];
    const int maxName = limits[5];

    PrinterSilencer silencer;
    std::istringstream stream(bytes);
    STEPCAFControl_Reader reader;
    reader.SetNameMode(true);
    reader.SetColorMode(false);
    reader.SetLayerMode(false);
    reader.SetPropsMode(false);
    reader.SetGDTMode(false);
    reader.SetMatMode(false);
    reader.SetViewMode(false);
    reader.SetSHUOMode(false);
    reader.SetMetaMode(false);
    reader.SetProductMetaMode(false);

    IFSelect_ReturnStatus status = reader.ReadStream("kstep-import.stp", stream);
    if (status != IFSelect_RetDone) {
        errClass = "java/lang/IllegalArgumentException";
        errMsg = "not a readable STEP file: status " + std::to_string(static_cast<int>(status));
        return false;
    }

    Handle(Interface_InterfaceModel) model = reader.ChangeReader().Model();
    if (model.IsNull()) {
        errClass = "java/lang/IllegalArgumentException";
        errMsg = "not a readable STEP file: no model";
        return false;
    }
    const int entityCount = model->NbEntities();
    out.entityCount = entityCount;
    if (entityCount > maxEntities) {
        errClass = "java/lang/IllegalArgumentException";
        errMsg = "limit: entity count " + std::to_string(entityCount) + " exceeds " + std::to_string(maxEntities);
        return false;
    }
    for (int i = 1; i <= entityCount; ++i) {
        // An entity type OCCT has no class for is stored as a StepData_UndefinedEntity (measured:
        // Interface_InterfaceModel::IsUnknownEntity does NOT flag it, so the type is tested directly).
        Handle(StepData_UndefinedEntity) undef = Handle(StepData_UndefinedEntity)::DownCast(model->Value(i));
        if (undef.IsNull() && !model->IsUnknownEntity(i)) continue;
        out.unknownEntityCount++;
        if (static_cast<int>(out.unknownTypes.size()) >= kMaxReportedUnknownTypes) continue;
        if (undef.IsNull()) continue;
        std::string type = sanitizeTypeName(undef->StepType());
        if (type.empty()) continue;
        bool known = false;
        for (const auto& t : out.unknownTypes) {
            if (t == type) {
                known = true;
                break;
            }
        }
        if (!known) out.unknownTypes.push_back(type);
    }

    // Extern-file references (ADR-0030): STEPConstruct_ExternRefs opens files named by document
    // entities during Transfer(), bypassing every limit here. The check is typed, not textual, so it
    // is independent of the AP203/AP214 long/short entity names. StepBasic_DocumentFile is a subtype
    // of StepBasic_Document. It MUST run before Transfer().
    for (int i = 1; i <= entityCount; ++i) {
        Handle(Standard_Transient) ent = model->Value(i);
        if (ent.IsNull()) continue;
        if (ent->IsKind(STANDARD_TYPE(StepBasic_Document)) ||
            ent->IsKind(STANDARD_TYPE(StepAP214_AppliedDocumentReference)) ||
            ent->IsKind(STANDARD_TYPE(StepBasic_ProductDefinitionWithAssociatedDocuments))) {
            errClass = "java/lang/IllegalArgumentException";
            errMsg = "external-reference: the file references external documents; external references are not followed";
            return false;
        }
    }

    XcafDocumentGuard guard;
    guard.app = XCAFApp_Application::GetApplication();
    guard.app->NewDocument("MDTV-XCAF", guard.doc);
    XCAFDoc_DocumentTool::SetLengthUnit(guard.doc, 1.0, UnitsMethods_LengthUnit_Millimeter);
    if (!reader.Transfer(guard.doc)) {
        errClass = "java/lang/IllegalStateException";
        errMsg = "STEP transfer into the XCAF document failed";
        return false;
    }

    Handle(XCAFDoc_ShapeTool) shapeTool = XCAFDoc_DocumentTool::ShapeTool(guard.doc->Main());
    TDF_LabelSequence frees;
    shapeTool->GetFreeShapes(frees);
    out.freeRootCount = frees.Length();

    // Iterative BFS; each label is visited exactly once (keyed by its entry string), so cycles in
    // the traversal are impossible. `queue` holds node indices of ASSEMBLY/PART nodes still to expand.
    std::unordered_map<std::string, int> indexByEntry;
    std::vector<TDF_Label> nodeLabels;
    long long totalFaces = 0;
    int componentCount = 0;
    int partCount = 0;

    auto fail = [&](const std::string& what, int value, int limit) {
        errClass = "java/lang/IllegalArgumentException";
        errMsg = "limit: " + what + " " + std::to_string(value) + " exceeds " + std::to_string(limit);
        return false;
    };

    // Returns node index for an item label (assembly or part), creating it if new; -1 on limit
    // violation (errMsg set via fail by caller through `limitHit`).
    bool limitHit = false;
    auto internItem = [&](const TDF_Label& label, int freeRoot) -> int {
        std::string key = labelEntry(label);
        auto it = indexByEntry.find(key);
        if (it != indexByEntry.end()) {
            if (freeRoot) out.nodes[static_cast<size_t>(it->second)].freeRoot = 1;
            return it->second;
        }
        if (static_cast<int>(out.nodes.size()) >= maxNodes) {
            limitHit = true;
            fail("node count", static_cast<int>(out.nodes.size()) + 1, maxNodes);
            return -1;
        }
        ImportNode node;
        node.freeRoot = freeRoot;
        node.name = labelName(label, maxName);
        if (XCAFDoc_ShapeTool::IsAssembly(label)) {
            node.kind = kImportKindAssembly;
        } else {
            node.kind = kImportKindPart;
            if (partCount >= maxParts) {
                limitHit = true;
                fail("part count", partCount + 1, maxParts);
                return -1;
            }
            ++partCount;
            TopoDS_Shape shape = XCAFDoc_ShapeTool::GetShape(label);
            if (!shape.IsNull()) {
                totalFaces += countFaces(shape);
                if (totalFaces > maxFaces) {
                    limitHit = true;
                    fail("face count", static_cast<int>(totalFaces), maxFaces);
                    return -1;
                }
                node.shapeSlot = static_cast<int>(out.partShapes.size());
                out.partShapes.push_back(shape);
            }
        }
        int index = static_cast<int>(out.nodes.size());
        out.nodes.push_back(node);
        nodeLabels.push_back(label);
        indexByEntry[key] = index;
        return index;
    };

    std::vector<int> queue;
    size_t head = 0;
    for (int i = 1; i <= frees.Length(); ++i) {
        int idx = internItem(frees.Value(i), 1);
        if (limitHit) return false;
        if (idx >= 0) queue.push_back(idx);
    }
    // A free label is never revisited through the queue twice: guard with `expanded`.
    std::vector<char> expanded;
    while (head < queue.size()) {
        int current = queue[head++];
        if (static_cast<size_t>(current) >= expanded.size()) expanded.resize(static_cast<size_t>(current) + 1, 0);
        if (expanded[static_cast<size_t>(current)]) continue;
        expanded[static_cast<size_t>(current)] = 1;
        if (out.nodes[static_cast<size_t>(current)].kind != kImportKindAssembly) continue;
        TDF_Label asmLabel = nodeLabels[static_cast<size_t>(current)];
        TDF_LabelSequence comps;
        XCAFDoc_ShapeTool::GetComponents(asmLabel, comps, Standard_False);
        for (int c = 1; c <= comps.Length(); ++c) {
            TDF_Label compLabel = comps.Value(c);
            if (componentCount >= maxComponents) return fail("component count", componentCount + 1, maxComponents);
            ++componentCount;
            if (static_cast<int>(out.nodes.size()) >= maxNodes) {
                return fail("node count", static_cast<int>(out.nodes.size()) + 1, maxNodes);
            }
            TDF_Label referred = compLabel;
            if (XCAFDoc_ShapeTool::IsReference(compLabel)) {
                XCAFDoc_ShapeTool::GetReferredShape(compLabel, referred);
            }
            int refIndex = internItem(referred, 0);
            if (limitHit) return false;
            ImportNode comp;
            comp.kind = kImportKindComponent;
            comp.parent = current;
            comp.referred = refIndex;
            comp.name = labelName(compLabel, maxName);
            TopLoc_Location loc = XCAFDoc_ShapeTool::GetLocation(compLabel);
            gp_Trsf t = loc.Transformation();
            for (int r = 1; r <= 3; ++r) {
                for (int col = 1; col <= 4; ++col) comp.m[(r - 1) * 4 + (col - 1)] = t.Value(r, col);
            }
            comp.rigid = (std::abs(t.ScaleFactor() - 1.0) <= 1e-9 && !t.IsNegative()) ? 1 : 0;
            int compIndex = static_cast<int>(out.nodes.size());
            out.nodes.push_back(comp);
            nodeLabels.push_back(compLabel);
            (void)compIndex;
            if (refIndex >= 0) queue.push_back(refIndex);
        }
    }
    return true;
}


}  // extern "C++"
}  // namespace

JNIEXPORT jstring JNICALL
Java_dev_kstep_geometry_occt_OcctBridge_nativeOcctVersion(JNIEnv* env, jobject /*self*/) {
    try {
        return env->NewStringUTF(OCC_VERSION_COMPLETE);
    } catch (const Standard_Failure& e) {
        throwJava(env, "java/lang/RuntimeException", std::string("OCCT error: ") + e.GetMessageString());
    } catch (const std::exception& e) {
        throwJava(env, "java/lang/RuntimeException", std::string("Native error: ") + e.what());
    } catch (...) {
        throwJava(env, "java/lang/RuntimeException", "Unknown native error in nativeOcctVersion");
    }
    return nullptr;
}

JNIEXPORT jlong JNICALL
Java_dev_kstep_geometry_occt_OcctBridge_nativeMakeBox(
    JNIEnv* env, jobject /*self*/, jdouble dx, jdouble dy, jdouble dz) {
    try {
        BRepPrimAPI_MakeBox maker(dx, dy, dz);
        maker.Build();
        if (!maker.IsDone()) {
            throwJava(env, "java/lang/IllegalStateException", "BRepPrimAPI_MakeBox did not complete");
            return 0;
        }
        // BRepBuilderAPI_MakeShape::Shape() (which BRepPrimAPI_MakeBox::Shape() overrides) returns
        // `const TopoDS_Shape&`, an lvalue reference -- it does NOT bind to registerShape's
        // `TopoDS_Shape&&` parameter directly (standard C++ overload-resolution rules say this
        // would fail to compile with "cannot bind rvalue reference ... to lvalue of type 'const
        // TopoDS_Shape'" against Ubuntu's OCCT 7.9.2 headers). NOTE: this file has now been
        // compiled against those headers (see the 2026-09-02 update in
        // docs/adr/ADR-0005-occt-jni-bridge.adoc's "Environment note"), but only with the
        // explicit copy below present -- the claim above about what happens *without* it has
        // still never actually been tried, and remains an assumption. The explicit
        // `TopoDS_Shape(...)` below makes one cheap
        // copy (a TopoDS_Shape is a small handle+location wrapper, not the underlying geometry)
        // to produce the rvalue registerShape expects to move from.
        return registerShape(TopoDS_Shape(maker.Shape()));
    } catch (const Standard_Failure& e) {
        throwJava(
            env, "java/lang/IllegalStateException", std::string("OCCT error building box: ") + e.GetMessageString());
    } catch (const std::exception& e) {
        throwJava(env, "java/lang/IllegalStateException", std::string("Native error building box: ") + e.what());
    } catch (...) {
        throwJava(env, "java/lang/IllegalStateException", "Unknown native error building box");
    }
    return 0;
}

JNIEXPORT jintArray JNICALL
Java_dev_kstep_geometry_occt_OcctBridge_nativeShapeCounts(JNIEnv* env, jobject /*self*/, jlong handle) {
    try {
        bool found = false;
        jint counts[5] = {0, 0, 0, 0, 0};
        {
            // Lookup AND every OCCT call that dereferences `shape` happen inside this one
            // critical section -- see the use-after-free note on g_mutex's declaration above.
            std::lock_guard<std::mutex> lock(g_mutex);
            TopoDS_Shape* shape = findShapeLocked(handle);
            if (shape != nullptr) {
                found = true;
                TopTools_IndexedMapOfShape solids;
                TopTools_IndexedMapOfShape shells;
                TopTools_IndexedMapOfShape faces;
                TopTools_IndexedMapOfShape edges;
                TopTools_IndexedMapOfShape vertices;
                // TopExp::MapShapes (an *indexed map*), never TopExp_Explorer: a naive explorer
                // walk visits each shared sub-shape once per parent that references it (e.g. a
                // box edge is shared by two adjacent faces, so an explorer walk over
                // faces-then-edges would count it twice -- 24 edges instead of 12, 24 vertices
                // instead of 8). MapShapes de-duplicates by shape identity first, so Extent()
                // below is a genuinely unique count. See docs/adr/ADR-0005-occt-jni-bridge.adoc,
                // "Stolperfallen".
                TopExp::MapShapes(*shape, TopAbs_SOLID, solids);
                TopExp::MapShapes(*shape, TopAbs_SHELL, shells);
                TopExp::MapShapes(*shape, TopAbs_FACE, faces);
                TopExp::MapShapes(*shape, TopAbs_EDGE, edges);
                TopExp::MapShapes(*shape, TopAbs_VERTEX, vertices);

                counts[0] = static_cast<jint>(solids.Extent());
                counts[1] = static_cast<jint>(shells.Extent());
                counts[2] = static_cast<jint>(faces.Extent());
                counts[3] = static_cast<jint>(edges.Extent());
                counts[4] = static_cast<jint>(vertices.Extent());
            }
        }
        if (!found) {
            throwUnknownHandle(env, handle);
            return nullptr;
        }
        jintArray result = env->NewIntArray(5);
        if (result == nullptr) {
            return nullptr;  // OutOfMemoryError already pending, thrown by the JVM itself
        }
        env->SetIntArrayRegion(result, 0, 5, counts);
        return result;
    } catch (const Standard_Failure& e) {
        throwJava(
            env,
            "java/lang/IllegalStateException",
            std::string("OCCT error counting topology: ") + e.GetMessageString());
    } catch (const std::exception& e) {
        throwJava(
            env, "java/lang/IllegalStateException", std::string("Native error counting topology: ") + e.what());
    } catch (...) {
        throwJava(env, "java/lang/IllegalStateException", "Unknown native error counting topology");
    }
    return nullptr;
}

JNIEXPORT jdouble JNICALL
Java_dev_kstep_geometry_occt_OcctBridge_nativeShapeVolume(JNIEnv* env, jobject /*self*/, jlong handle) {
    try {
        bool found = false;
        double volume = 0.0;
        {
            // Lookup AND the VolumeProperties() call that dereferences `shape` happen inside this
            // one critical section -- see the use-after-free note on g_mutex's declaration above.
            std::lock_guard<std::mutex> lock(g_mutex);
            TopoDS_Shape* shape = findShapeLocked(handle);
            if (shape != nullptr) {
                found = true;
                GProp_GProps props;
                BRepGProp::VolumeProperties(*shape, props);
                volume = props.Mass();
            }
        }
        if (!found) {
            throwUnknownHandle(env, handle);
            return 0.0;
        }
        return volume;
    } catch (const Standard_Failure& e) {
        throwJava(
            env,
            "java/lang/IllegalStateException",
            std::string("OCCT error computing volume: ") + e.GetMessageString());
    } catch (const std::exception& e) {
        throwJava(env, "java/lang/IllegalStateException", std::string("Native error computing volume: ") + e.what());
    } catch (...) {
        throwJava(env, "java/lang/IllegalStateException", "Unknown native error computing volume");
    }
    return 0.0;
}

JNIEXPORT jint JNICALL
Java_dev_kstep_geometry_occt_OcctBridge_nativeWriteStep(
    JNIEnv* env, jobject /*self*/, jlong handle, jstring absolutePath, jstring schema) {
    // GetStringUTFChars(env, nullptr) is undefined behavior (SIGSEGV, or a hard abort under
    // -Xcheck:jni) -- Kotlin's compile-time non-null checks on OcctBridge's declared parameter
    // types do NOT run here: `OcctBridge` is a public object precisely so out-of-module callers
    // (including test code) can reach these native methods directly, and a native method has no
    // Kotlin-generated bytecode body to carry an Intrinsics.checkNotNullParameter guard -- see
    // OcctBridge.kt's KDoc and this file's invariant #2 above ("a caller passing bad input cannot
    // crash the JVM"), which handle validation alone does not satisfy for these two parameters.
    if (absolutePath == nullptr || schema == nullptr) {
        throwJava(
            env,
            "java/lang/NullPointerException",
            "nativeWriteStep: absolutePath and schema must not be null");
        return 0;
    }

    const char* pathChars = env->GetStringUTFChars(absolutePath, nullptr);
    if (pathChars == nullptr) {
        return 0;  // OutOfMemoryError already pending
    }
    std::string path(pathChars);
    env->ReleaseStringUTFChars(absolutePath, pathChars);

    const char* schemaChars = env->GetStringUTFChars(schema, nullptr);
    if (schemaChars == nullptr) {
        return 0;  // OutOfMemoryError already pending
    }
    std::string schemaValue(schemaChars);
    env->ReleaseStringUTFChars(schema, schemaChars);

    try {
        // write.step.schema is process-global OCCT state (Interface_Static.hxx) -- serialize the
        // whole construct-writer-set-schema-then-write critical section under g_mutex so a
        // concurrent call from another thread can never observe (or silently overwrite) a
        // different schema mid-write.
        //
        // The STEPControl_Writer below is deliberately constructed BEFORE the SetCVal() call, not
        // after: "write.step.schema" is not yet a registered Interface_Static parameter until
        // some STEPControl_Writer has been constructed at least once in this process (its
        // constructor is what pulls in the STEP schema modules that register it) -- based on
        // OCCT 7.9.2's documented Interface_Static registration model: SetCVal
        // ("write.step.schema", ...) returning false, a no-op, when called first, and true once
        // a STEPControl_Writer already exists. Confirmed against a real OCCT 7.9.2 install on
        // 2026-09-02 (see docs/adr/ADR-0005-occt-jni-bridge.adoc's "Environment note"): the
        // AP203 export written by OcctBridgeSmokeTest carries
        // FILE_SCHEMA(('CONFIG_CONTROL_DESIGN')), which is only reachable if SetCVal returned
        // true here (a false return throws IllegalStateException below, before Write() runs).
        // If this ordering were wrong, calling SetCVal before constructing any writer would make
        // every STEP export in this codebase silently keep OCCT's own built-in default schema
        // instead of the caller-requested one. See docs/adr/ADR-0005-occt-jni-bridge.adoc.
        //
        // SetCVal()'s own success/failure, and the handle lookup itself, are captured here and
        // only acted on (via throwJava()/throwUnknownHandle()) AFTER the lock below is released --
        // see throwUnknownHandle()'s comment above on why throwJava() must never run while g_mutex
        // is held.
        //
        // The handle lookup happens INSIDE this same lock_guard scope, and `shape` is dereferenced
        // (Transfer()) only while still holding it -- see the use-after-free note on g_mutex's
        // declaration above. Folding the lookup in here (rather than looking it up once beforehand
        // and reusing the pointer) is what closes the race: a concurrent nativeReleaseShape() for
        // this handle cannot run until this whole critical section -- lookup through Write() --
        // has completed.
        bool found = false;
        bool schemaAccepted = false;
        IFSelect_ReturnStatus transferStatus = IFSelect_RetVoid;
        jint result = 0;
        {
            std::lock_guard<std::mutex> lock(g_mutex);
            TopoDS_Shape* shape = findShapeLocked(handle);
            if (shape != nullptr) {
                found = true;
                STEPControl_Writer writer;
                // OCCT's STEP writer prints "Statistics on Transfer" banners to the process's
                // stdout through the default messenger. For a CLI whose stdout is a machine-read
                // JSON document (or an MCP stdio stream) that is corruption, so the default
                // printers are detached for the duration of the write and restored afterwards
                // (RAII, also on exceptions). Still under g_mutex, so no concurrent bridge call
                // can observe the detached state.
                PrinterSilencer silencer;
                schemaAccepted = Interface_Static::SetCVal("write.step.schema", schemaValue.c_str());
                // A multi-body result (a compound of several solids, ADR-0032) is written as ONE shape
                // representation with one MANIFOLD_SOLID_BREP per body (assembly mode off) -- OCCT's default
                // (mode 2 = auto) would otherwise turn the compound into an assembly with one product per body.
                // The static parameter is process-global, so it is always set explicitly (never left sticky).
                Interface_Static::SetIVal("write.step.assembly", countSolids(*shape) > 1 ? 0 : 2);
                if (schemaAccepted) {
                    transferStatus = writer.Transfer(*shape, STEPControl_AsIs);
                    if (transferStatus != IFSelect_RetDone) {
                        result = static_cast<jint>(transferStatus);
                    } else {
                        IFSelect_ReturnStatus writeStatus = writer.Write(path.c_str());
                        result = static_cast<jint>(writeStatus);
                    }
                }
            }
        }
        if (!found) {
            throwUnknownHandle(env, handle);
            return 0;
        }
        if (!schemaAccepted) {
            // Reachable today (unlike a closed, exhaustively-tested enum's usual "impossible
            // default" branch): see the ordering note above -- this is not merely a guard against
            // a hypothetical future-wave schema addition.
            throwJava(
                env, "java/lang/IllegalStateException", "OCCT rejected STEP schema: " + schemaValue);
            return 0;
        }
        return result;
    } catch (const Standard_Failure& e) {
        throwJava(
            env,
            "java/lang/IllegalStateException",
            std::string("OCCT error writing STEP file: ") + e.GetMessageString());
    } catch (const std::exception& e) {
        throwJava(
            env, "java/lang/IllegalStateException", std::string("Native error writing STEP file: ") + e.what());
    } catch (...) {
        throwJava(env, "java/lang/IllegalStateException", "Unknown native error writing STEP file");
    }
    return 0;
}

JNIEXPORT void JNICALL
Java_dev_kstep_geometry_occt_OcctBridge_nativeReleaseShape(JNIEnv* env, jobject /*self*/, jlong handle) {
    // Idempotent by design: releasing an already-released (or never-registered) handle is a
    // silent no-op, not an error -- OcctShape.close() must be safely callable more than once
    // (AutoCloseable's contract) without the native side needing its own separate "already
    // closed" bookkeeping on top of the JVM-side `closed` flag.
    //
    // Erasing from g_shapes happens under g_mutex; the actual `delete` runs after the lock is
    // released, and that is safe -- not a use-after-free -- BECAUSE every reader
    // (nativeShapeCounts/nativeShapeVolume/nativeWriteStep) now finds its pointer AND finishes
    // every OCCT call that dereferences it inside its OWN g_mutex-held critical section (see the
    // note on g_mutex's declaration above). That makes erase-from-registry and
    // any-reader's-full-use of the same handle mutually exclusive: a reader can only ever obtain a
    // pointer that is still registered, and once erase runs here, no reader still holds one it
    // hasn't already finished with. So by the time `delete toDelete` executes, no other thread can
    // be dereferencing it.
    TopoDS_Shape* toDelete = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        auto it = g_shapes.find(handle);
        if (it != g_shapes.end()) {
            toDelete = it->second;
            g_shapes.erase(it);
        }
    }
    delete toDelete;
    // No JNI exception path in this function (TopoDS_Shape's destructor cannot throw), but `env`
    // is still part of the required JNI signature.
    (void)env;
}

// ------------------------------------------------------------------------------------------
// Geometrie Welle 5a: extrude + fillet. See docs/adr/ADR-0008-occt-feature-operations.adoc for
// the full design rationale, the DoS measurements behind every constant above, and the
// deadlock/exception-safety pitfalls the two functions below exist to avoid.
// ------------------------------------------------------------------------------------------

JNIEXPORT jlong JNICALL
Java_dev_kstep_geometry_occt_OcctBridge_nativeExtrudeProfile(
    JNIEnv* env, jobject /*self*/, jdoubleArray profileXy, jdouble height) {
    // Null/length/range/coincidence validation lives in readProfileXy (shared with
    // nativeRevolveProfile); its messages are word-for-word the ones this function always had.
    std::vector<double> xy;
    if (!readProfileXy(env, profileXy, kMaxProfilePoints, "nativeExtrudeProfile", xy)) {
        return 0;
    }
    if (!std::isfinite(height) || std::abs(height) <= kMinDimension || std::abs(height) > kMaxDimension) {
        throwJava(
            env,
            "java/lang/IllegalArgumentException",
            "nativeExtrudeProfile: height magnitude must be finite and within (" + std::to_string(kMinDimension) +
                ", " + std::to_string(kMaxDimension) + "], got " + std::to_string(height));
        return 0;
    }

    try {
        TopoDS_Face face;
        if (!buildProfileFace(env, xy, "nativeExtrudeProfile", face)) {
            return 0;
        }

        BRepPrimAPI_MakePrism prismMaker(face, gp_Vec(0.0, 0.0, height));
        prismMaker.Build();
        if (!prismMaker.IsDone()) {
            throwJava(env, "java/lang/IllegalStateException", "BRepPrimAPI_MakePrism did not complete");
            return 0;
        }
        // Same explicit-copy rationale as nativeMakeBox above: Shape() returns `const&`, which
        // does not bind to registerShape's `TopoDS_Shape&&` parameter directly.
        TopoDS_Shape result(prismMaker.Shape());

        // Mandatory post-check, the actual security value of this function: a degenerate profile
        // (collinear points, or a self-crossing "bowtie" polygon) reaches OCCT successfully and
        // produces a shape with IsDone()==true, but BRepCheck_Analyzer reports it invalid and/or
        // its volume is zero -- measured against OCCT 7.9.2. Neither check alone catches every
        // case (a 2-point-equivalent degenerate profile can be BRepCheck-valid with zero volume),
        // so both run.
        BRepCheck_Analyzer analyzer(result);
        if (!analyzer.IsValid()) {
            throwJava(
                env,
                "java/lang/IllegalArgumentException",
                "nativeExtrudeProfile: resulting solid failed OCCT's own validity check "
                "(collinear or self-intersecting profile?)");
            return 0;
        }
        GProp_GProps props;
        BRepGProp::VolumeProperties(result, props);
        if (!(props.Mass() > 0.0)) {
            throwJava(
                env,
                "java/lang/IllegalArgumentException",
                "nativeExtrudeProfile: resulting solid has zero volume (degenerate profile?)");
            return 0;
        }

        return registerShape(std::move(result));
    } catch (const Standard_Failure& e) {
        throwJava(
            env,
            "java/lang/IllegalStateException",
            std::string("OCCT error extruding profile: ") + e.GetMessageString());
    } catch (const std::exception& e) {
        throwJava(env, "java/lang/IllegalStateException", std::string("Native error extruding profile: ") + e.what());
    } catch (...) {
        throwJava(env, "java/lang/IllegalStateException", "Unknown native error extruding profile");
    }
    return 0;
}

static jlong filletEdgesImpl(
    JNIEnv* env, jlong handle, jintArray edgeIndices, jdouble radius, jobjectArray outHistory) {
    if (edgeIndices == nullptr) {
        throwJava(env, "java/lang/NullPointerException", "nativeFilletEdges: edgeIndices must not be null");
        return 0;
    }
    jsize indexCount = env->GetArrayLength(edgeIndices);
    if (indexCount < 1 || indexCount > kMaxFilletEdges) {
        throwJava(
            env,
            "java/lang/IllegalArgumentException",
            "nativeFilletEdges: edgeIndices must have between 1 and " + std::to_string(kMaxFilletEdges) +
                " entries, got " + std::to_string(indexCount));
        return 0;
    }
    if (!std::isfinite(radius) || radius < kMinFilletRadius || radius > kMaxFilletRadius) {
        throwJava(
            env,
            "java/lang/IllegalArgumentException",
            "nativeFilletEdges: radius must be finite and within [" + std::to_string(kMinFilletRadius) + ", " +
                std::to_string(kMaxFilletRadius) + "], got " + std::to_string(radius));
        return 0;
    }

    IntArrayGuard guard(env, edgeIndices);
    const jint* indices = guard.elements();
    if (indices == nullptr) {
        return 0;  // OutOfMemoryError already pending
    }

    try {
        bool found = false;
        bool tooManyFaces = false;
        int faceCount = 0;
        bool badIndex = false;
        jint offendingIndex = 0;
        int edgeCount = 0;
        bool built = false;
        SolidCheckResult check;
        std::vector<jint> history;
        bool historyTooLarge = false;
        // Result lives here, on the stack, as a plain local -- deliberately NOT registered while
        // g_mutex is held. registerShape() itself takes g_mutex (see its definition above), and
        // g_mutex is a plain, non-recursive std::mutex: calling registerShape() from inside the
        // critical section below would be a guaranteed self-deadlock. See
        // docs/adr/ADR-0008-occt-feature-operations.adoc's "Stolperfallen" for this exact trap.
        TopoDS_Shape result;
        {
            // Lookup AND every OCCT call that dereferences `shape` happen inside this one critical
            // section, exactly like every other reader in this file -- see the use-after-free note
            // on g_mutex's declaration above. A Standard_Failure thrown by Build() unwinds through
            // this block's closing brace, correctly releasing the lock via lock_guard's destructor
            // (RAII), before reaching the catch cascade below.
            std::lock_guard<std::mutex> lock(g_mutex);
            TopoDS_Shape* shape = findShapeLocked(handle);
            if (shape != nullptr) {
                found = true;
                TopTools_IndexedMapOfShape faces;
                TopExp::MapShapes(*shape, TopAbs_FACE, faces);
                faceCount = faces.Extent();
                if (faceCount > kMaxFilletInputFaces) {
                    tooManyFaces = true;
                } else {
                    TopTools_IndexedMapOfShape edges;
                    TopExp::MapShapes(*shape, TopAbs_EDGE, edges);
                    edgeCount = edges.Extent();
                    // Every index validated BEFORE the first Add() -- never partially build a
                    // fillet operation against a shape whose edge indices we have not fully
                    // checked yet.
                    for (jsize i = 0; i < indexCount && !badIndex; ++i) {
                        jint idx = indices[i];
                        if (idx < 0 || idx >= edgeCount) {
                            badIndex = true;
                            offendingIndex = idx;
                        }
                    }
                    if (!badIndex) {
                        BRepFilletAPI_MakeFillet filletMaker(*shape);
                        for (jsize i = 0; i < indexCount; ++i) {
                            // 0-based (Kotlin/caller-facing) -> OCCT's 1-based
                            // TopTools_IndexedMapOfShape indexing.
                            filletMaker.Add(radius, TopoDS::Edge(edges(indices[i] + 1)));
                        }
                        filletMaker.Build();  // can throw Standard_Failure -- see the try below
                        if (filletMaker.IsDone()) {
                            result = TopoDS_Shape(filletMaker.Shape());
                            built = true;
                            // Shares TShapes with the registered operand: check under g_mutex.
                            check = runSolidChecks(result);
                            if (outHistory != nullptr) {
                                historyTooLarge = !encodeEdgeHistory(filletMaker, {shape}, result, history);
                            }
                        }
                    }
                }
            }
        }  // g_mutex released here

        // Every throwJava() call below runs OUTSIDE the lock -- FindClass() can trigger arbitrary
        // Java class-loading code, which must never run while this thread still holds a plain,
        // non-recursive std::mutex it might then need to re-acquire (e.g. a Cleaner action
        // concurrently running nativeReleaseShape()) -- see throwUnknownHandle()'s own comment
        // above for the identical rationale.
        if (!found) {
            throwUnknownHandle(env, handle);
            return 0;
        }
        if (tooManyFaces) {
            throwJava(
                env,
                "java/lang/IllegalArgumentException",
                "nativeFilletEdges: input shape has " + std::to_string(faceCount) + " faces, exceeding the limit of " +
                    std::to_string(kMaxFilletInputFaces));
            return 0;
        }
        if (badIndex) {
            throwJava(
                env,
                "java/lang/IllegalArgumentException",
                "nativeFilletEdges: edge index " + std::to_string(offendingIndex) + " is out of range for a shape with " +
                    std::to_string(edgeCount) + " edges");
            return 0;
        }
        if (!built) {
            throwJava(
                env,
                "java/lang/IllegalStateException",
                "BRepFilletAPI_MakeFillet did not complete (radius too large for the local geometry?)");
            return 0;
        }

        // Same mandatory post-check as nativeExtrudeProfile, run on the local (not-yet-registered)
        // result -- cost measured well within the DoS budget at kMaxFilletInputFaces (see
        // docs/adr/ADR-0008-occt-feature-operations.adoc).
        if (!check.valid) {
            throwJava(
                env, "java/lang/IllegalStateException", "nativeFilletEdges: resulting solid failed OCCT's own validity check");
            return 0;
        }
        if (!check.positiveVolume) {
            throwJava(env, "java/lang/IllegalStateException", "nativeFilletEdges: resulting solid has zero volume");
            return 0;
        }
        if (historyTooLarge) {
            throwJava(env, "java/lang/IllegalArgumentException", "nativeFilletEdges: edge history exceeds the size limit");
            return 0;
        }
        if (outHistory != nullptr && !publishHistory(env, outHistory, history)) {
            return 0;
        }

        return registerShape(std::move(result));
    } catch (const Standard_Failure& e) {
        throwJava(
            env, "java/lang/IllegalStateException", std::string("OCCT error building fillet: ") + e.GetMessageString());
    } catch (const std::exception& e) {
        throwJava(env, "java/lang/IllegalStateException", std::string("Native error building fillet: ") + e.what());
    } catch (...) {
        throwJava(env, "java/lang/IllegalStateException", "Unknown native error building fillet");
    }
    return 0;
}

JNIEXPORT jlong JNICALL
Java_dev_kstep_geometry_occt_OcctBridge_nativeFilletEdges(
    JNIEnv* env, jobject /*self*/, jlong handle, jintArray edgeIndices, jdouble radius) {
    return filletEdgesImpl(env, handle, edgeIndices, radius, nullptr);
}

JNIEXPORT jlong JNICALL
Java_dev_kstep_geometry_occt_OcctBridge_nativeFilletEdgesTracked(
    JNIEnv* env, jobject /*self*/, jlong handle, jintArray edgeIndices, jdouble radius, jobjectArray outHistory) {
    if (!checkHistoryHolder(env, outHistory, "nativeFilletEdgesTracked")) {
        return 0;
    }
    return filletEdgesImpl(env, handle, edgeIndices, radius, outHistory);
}

// ------------------------------------------------------------------------------------------
// Welle J: transform, cylinder, booleans, revolve, chamfer. See
// docs/adr/ADR-0029-geometry-operations.adoc. Every function follows the invariants documented
// at the top of this file: lookup + every OCCT call on a registry shape inside ONE g_mutex
// critical section; throwJava()/registerShape() only OUTSIDE it; full catch cascade; nothing is
// registered on any failure path.
// ------------------------------------------------------------------------------------------

static jlong transformShapeImpl(JNIEnv* env, jlong handle, jdoubleArray matrix12, jobjectArray outHistory) {
    if (matrix12 == nullptr) {
        throwJava(env, "java/lang/NullPointerException", "nativeTransformShape: matrix must not be null");
        return 0;
    }
    if (env->GetArrayLength(matrix12) != 12) {
        throwJava(
            env,
            "java/lang/IllegalArgumentException",
            "nativeTransformShape: matrix must have exactly 12 entries (row-major 3x4), got " +
                std::to_string(env->GetArrayLength(matrix12)));
        return 0;
    }
    double m[12];
    {
        DoubleArrayGuard guard(env, matrix12);
        const jdouble* src = guard.elements();
        if (src == nullptr) {
            return 0;  // OutOfMemoryError already pending
        }
        for (int i = 0; i < 12; ++i) {
            m[i] = src[i];
        }
    }
    for (int i = 0; i < 12; ++i) {
        if (!std::isfinite(m[i])) {
            throwJava(
                env, "java/lang/IllegalArgumentException",
                "nativeTransformShape: matrix[" + std::to_string(i) + "] must be finite");
            return 0;
        }
    }
    for (int row = 0; row < 3; ++row) {
        if (std::abs(m[row * 4 + 3]) > kMaxAbsCoordinate) {
            throwJava(
                env, "java/lang/IllegalArgumentException",
                "nativeTransformShape: translation must be within +/-" + std::to_string(kMaxAbsCoordinate));
            return 0;
        }
    }
    // Rotation block R(i,j) = m[4*i + j]. Must be orthonormal (R^T R = I) with det > 0: a scaling
    // or reflection would silently corrupt the solid (and a reflection flips its orientation).
    for (int i = 0; i < 3; ++i) {
        for (int j = 0; j < 3; ++j) {
            double dot = 0.0;
            for (int k = 0; k < 3; ++k) {
                dot += m[4 * k + i] * m[4 * k + j];
            }
            double expected = (i == j) ? 1.0 : 0.0;
            if (std::abs(dot - expected) > kOrthonormalTolerance) {
                throwJava(
                    env, "java/lang/IllegalArgumentException",
                    "nativeTransformShape: rotation block must be orthonormal (no scaling or shear)");
                return 0;
            }
        }
    }
    double det = m[0] * (m[5] * m[10] - m[6] * m[9]) - m[1] * (m[4] * m[10] - m[6] * m[8]) +
                 m[2] * (m[4] * m[9] - m[5] * m[8]);
    if (!(det > 0.0)) {
        throwJava(
            env, "java/lang/IllegalArgumentException",
            "nativeTransformShape: rotation block must have a positive determinant (no reflection)");
        return 0;
    }

    try {
        bool found = false;
        bool historyTooLarge = false;
        std::vector<jint> history;
        TopoDS_Shape result;
        {
            std::lock_guard<std::mutex> lock(g_mutex);
            TopoDS_Shape* shape = findShapeLocked(handle);
            if (shape != nullptr) {
                found = true;
                gp_Trsf trsf;
                trsf.SetValues(m[0], m[1], m[2], m[3], m[4], m[5], m[6], m[7], m[8], m[9], m[10], m[11]);
                BRepBuilderAPI_Transform transformer(*shape, trsf, Standard_True);
                result = TopoDS_Shape(transformer.Shape());
                if (outHistory != nullptr) {
                    historyTooLarge = !encodeEdgeHistory(transformer, {shape}, result, history);
                }
            }
        }  // g_mutex released here
        if (!found) {
            throwUnknownHandle(env, handle);
            return 0;
        }
        if (historyTooLarge) {
            throwJava(env, "java/lang/IllegalArgumentException", "nativeTransformShape: edge history exceeds the size limit");
            return 0;
        }
        if (outHistory != nullptr && !publishHistory(env, outHistory, history)) {
            return 0;
        }
        return registerShape(std::move(result));
    } catch (const Standard_Failure& e) {
        throwJava(
            env, "java/lang/IllegalStateException", std::string("OCCT error transforming shape: ") + e.GetMessageString());
    } catch (const std::exception& e) {
        throwJava(env, "java/lang/IllegalStateException", std::string("Native error transforming shape: ") + e.what());
    } catch (...) {
        throwJava(env, "java/lang/IllegalStateException", "Unknown native error transforming shape");
    }
    return 0;
}

JNIEXPORT jlong JNICALL
Java_dev_kstep_geometry_occt_OcctBridge_nativeTransformShape(
    JNIEnv* env, jobject /*self*/, jlong handle, jdoubleArray matrix12) {
    return transformShapeImpl(env, handle, matrix12, nullptr);
}

JNIEXPORT jlong JNICALL
Java_dev_kstep_geometry_occt_OcctBridge_nativeTransformShapeTracked(
    JNIEnv* env, jobject /*self*/, jlong handle, jdoubleArray matrix12, jobjectArray outHistory) {
    if (!checkHistoryHolder(env, outHistory, "nativeTransformShapeTracked")) {
        return 0;
    }
    return transformShapeImpl(env, handle, matrix12, outHistory);
}

JNIEXPORT jlong JNICALL
Java_dev_kstep_geometry_occt_OcctBridge_nativeMakeCylinder(
    JNIEnv* env, jobject /*self*/, jdouble radius, jdouble height, jdouble ox, jdouble oy, jdouble oz, jdouble dx,
    jdouble dy, jdouble dz) {
    auto badDimension = [](double v) { return !std::isfinite(v) || v < kMinDimension || v > kMaxDimension; };
    if (badDimension(radius) || badDimension(height)) {
        throwJava(
            env,
            "java/lang/IllegalArgumentException",
            "nativeMakeCylinder: radius and height must be finite and within [" + std::to_string(kMinDimension) + ", " +
                std::to_string(kMaxDimension) + "], got radius=" + std::to_string(radius) +
                ", height=" + std::to_string(height));
        return 0;
    }
    for (double c : {ox, oy, oz}) {
        if (!std::isfinite(c) || std::abs(c) > kMaxAbsCoordinate) {
            throwJava(
                env, "java/lang/IllegalArgumentException",
                "nativeMakeCylinder: origin must be finite and within +/-" + std::to_string(kMaxAbsCoordinate));
            return 0;
        }
    }
    // Must be checked BEFORE gp_Dir, which throws Standard_ConstructionError on a null vector.
    if (!std::isfinite(dx) || !std::isfinite(dy) || !std::isfinite(dz) ||
        std::sqrt(dx * dx + dy * dy + dz * dz) < kMinDirectionLength) {
        throwJava(
            env, "java/lang/IllegalArgumentException",
            "nativeMakeCylinder: direction must be finite with length >= " + std::to_string(kMinDirectionLength));
        return 0;
    }
    try {
        gp_Ax2 axis(gp_Pnt(ox, oy, oz), gp_Dir(dx, dy, dz));
        BRepPrimAPI_MakeCylinder maker(axis, radius, height);
        maker.Build();
        if (!maker.IsDone()) {
            throwJava(env, "java/lang/IllegalStateException", "BRepPrimAPI_MakeCylinder did not complete");
            return 0;
        }
        TopoDS_Shape result(maker.Shape());
        return registerShape(std::move(result));
    } catch (const Standard_Failure& e) {
        throwJava(
            env, "java/lang/IllegalStateException", std::string("OCCT error building cylinder: ") + e.GetMessageString());
    } catch (const std::exception& e) {
        throwJava(env, "java/lang/IllegalStateException", std::string("Native error building cylinder: ") + e.what());
    } catch (...) {
        throwJava(env, "java/lang/IllegalStateException", "Unknown native error building cylinder");
    }
    return 0;
}

static jlong booleanOpImpl(JNIEnv* env, jint op, jlong left, jlong right, jobjectArray outHistory) {
    if (op < 0 || op > 2) {
        throwJava(
            env, "java/lang/IllegalArgumentException",
            "nativeBooleanOp: op must be 0 (fuse), 1 (cut) or 2 (common), got " + std::to_string(op));
        return 0;
    }
    const char* opName = op == 0 ? "BRepAlgoAPI_Fuse" : (op == 1 ? "BRepAlgoAPI_Cut" : "BRepAlgoAPI_Common");
    try {
        bool foundLeft = false;
        bool foundRight = false;
        bool tooManyFaces = false;
        int offendingFaces = 0;
        bool noSolid = false;
        bool opFailed = false;
        int solids = 0;
        int shells = 0;
        double volume = 0.0;
        SolidCheckResult check;
        std::vector<jint> history;
        bool historyTooLarge = false;
        TopoDS_Shape result;
        {
            // BOTH lookups and the whole boolean run in ONE critical section (left == right is
            // therefore harmless: there is exactly one lock acquisition). The work is deliberately
            // NOT moved out of the lock onto copied TopoDS_Shape handles: TShapes are shared and
            // nativeShapeTriangles mutates them (triangulate, then Clean) -- a data race.
            std::lock_guard<std::mutex> lock(g_mutex);
            TopoDS_Shape* a = findShapeLocked(left);
            TopoDS_Shape* b = findShapeLocked(right);
            foundLeft = a != nullptr;
            foundRight = b != nullptr;
            if (foundLeft && foundRight) {
                for (const TopoDS_Shape* operand : {a, b}) {
                    TopTools_IndexedMapOfShape faces;
                    TopExp::MapShapes(*operand, TopAbs_FACE, faces);
                    if (faces.Extent() > kMaxBooleanInputFaces) {
                        tooManyFaces = true;
                        offendingFaces = faces.Extent();
                    }
                    if (countSolids(*operand) < 1) {
                        noSolid = true;
                    }
                }
                if (!tooManyFaces && !noSolid) {
                    std::unique_ptr<BRepAlgoAPI_BooleanOperation> algo;
                    if (op == 0) {
                        algo.reset(new BRepAlgoAPI_Fuse());
                    } else if (op == 1) {
                        algo.reset(new BRepAlgoAPI_Cut());
                    } else {
                        algo.reset(new BRepAlgoAPI_Common());
                    }
                    TopTools_ListOfShape args;
                    TopTools_ListOfShape tools;
                    args.Append(*a);
                    tools.Append(*b);
                    algo->SetArguments(args);
                    algo->SetTools(tools);
                    algo->SetRunParallel(Standard_False);  // deterministic, no OCCT thread pool inside the JVM
                    algo->Build();
                    if (algo->HasErrors() || !algo->IsDone()) {
                        opFailed = true;
                    } else {
                        // Merge coplanar/co-cylindrical split faces (otherwise a union of two
                        // overlapping boxes keeps split cap faces and wrong face counts).
                        algo->SimplifyResult();
                        result = TopoDS_Shape(algo->Shape());
                        // The result shares untouched TShapes with the registered operands, so
                        // every read of it (counting, volume, BRepCheck) stays inside g_mutex;
                        // only plain values leave the critical section.
                        solids = countSolids(result);
                        if (solids > 0) {
                            GProp_GProps props;
                            BRepGProp::VolumeProperties(result, props);
                            volume = props.Mass();
                        }
                        if (solids == 1) {
                            result = unwrapSingleSolid(result);
                            shells = countShells(result);
                            if (shells == 1 && volume > Precision::Confusion()) {
                                check = runSolidChecks(result);
                                if (outHistory != nullptr) {
                                    historyTooLarge = !encodeEdgeHistory(*algo, {a, b}, result, history);
                                }
                            }
                        }
                    }
                }
            }
        }  // g_mutex released here

        if (!foundLeft || !foundRight) {
            throwUnknownHandle(env, foundLeft ? right : left);
            return 0;
        }
        if (tooManyFaces) {
            throwJava(
                env, "java/lang/IllegalArgumentException",
                "nativeBooleanOp: operand has " + std::to_string(offendingFaces) + " faces, exceeding the limit of " +
                    std::to_string(kMaxBooleanInputFaces));
            return 0;
        }
        if (noSolid) {
            throwJava(env, "java/lang/IllegalArgumentException", "nativeBooleanOp: every operand must contain a solid");
            return 0;
        }
        if (opFailed) {
            throwJava(env, "java/lang/IllegalStateException", std::string(opName) + " failed");
            return 0;
        }
        if (solids == 0 || !(volume > Precision::Confusion())) {
            throwJava(
                env, "dev/kstep/geometry/OcctEmptyResultException",
                std::string(opName) + " produced an empty result (no solid volume remains)");
            return 0;
        }
        if (solids > 1) {
            throwJava(
                env, "java/lang/IllegalStateException",
                std::string(opName) + ": result has " + std::to_string(solids) +
                    " disjoint solids; multi-body results are not supported yet");
            return 0;
        }
        if (shells > 1) {
            throwJava(
                env, "java/lang/IllegalStateException",
                std::string(opName) + ": result has an internal void (" + std::to_string(shells) +
                    " shells in one solid); internal voids are not supported yet");
            return 0;
        }
        if (!check.valid) {
            throwJava(
                env, "java/lang/IllegalStateException",
                "nativeBooleanOp: resulting solid failed OCCT's own validity check");
            return 0;
        }
        if (!check.positiveVolume) {
            throwJava(env, "java/lang/IllegalStateException", "nativeBooleanOp: resulting solid has zero volume");
            return 0;
        }
        if (historyTooLarge) {
            throwJava(env, "java/lang/IllegalArgumentException", "nativeBooleanOp: edge history exceeds the size limit");
            return 0;
        }
        if (outHistory != nullptr && !publishHistory(env, outHistory, history)) {
            return 0;
        }
        return registerShape(std::move(result));
    } catch (const Standard_Failure& e) {
        throwJava(
            env, "java/lang/IllegalStateException", std::string("OCCT error in boolean operation: ") + e.GetMessageString());
    } catch (const std::exception& e) {
        throwJava(env, "java/lang/IllegalStateException", std::string("Native error in boolean operation: ") + e.what());
    } catch (...) {
        throwJava(env, "java/lang/IllegalStateException", "Unknown native error in boolean operation");
    }
    return 0;
}

JNIEXPORT jlong JNICALL
Java_dev_kstep_geometry_occt_OcctBridge_nativeBooleanOp(JNIEnv* env, jobject /*self*/, jint op, jlong left, jlong right) {
    return booleanOpImpl(env, op, left, right, nullptr);
}

JNIEXPORT jlong JNICALL
Java_dev_kstep_geometry_occt_OcctBridge_nativeBooleanOpTracked(
    JNIEnv* env, jobject /*self*/, jint op, jlong left, jlong right, jobjectArray outHistory) {
    if (!checkHistoryHolder(env, outHistory, "nativeBooleanOpTracked")) {
        return 0;
    }
    return booleanOpImpl(env, op, left, right, outHistory);
}

JNIEXPORT jlong JNICALL
Java_dev_kstep_geometry_occt_OcctBridge_nativeRevolveProfile(
    JNIEnv* env, jobject /*self*/, jdoubleArray profileXy, jdouble angleDeg, jint axis) {
    std::vector<double> xy;
    if (!readProfileXy(env, profileXy, kMaxRevolveProfilePoints, "nativeRevolveProfile", xy)) {
        return 0;
    }
    if (!std::isfinite(angleDeg) || angleDeg < kMinRevolveAngleDeg || angleDeg > kMaxRevolveAngleDeg) {
        throwJava(
            env, "java/lang/IllegalArgumentException",
            "nativeRevolveProfile: angle must be finite and within [" + std::to_string(kMinRevolveAngleDeg) + ", " +
                std::to_string(kMaxRevolveAngleDeg) + "] degrees, got " + std::to_string(angleDeg));
        return 0;
    }
    if (axis != 0 && axis != 1) {
        throwJava(
            env, "java/lang/IllegalArgumentException",
            "nativeRevolveProfile: axis must be 0 (X) or 1 (Y), got " + std::to_string(axis));
        return 0;
    }
    // The whole profile must lie on ONE side of the rotation axis (points on the axis are fine):
    // crossing it would sweep the profile through itself and produce a self-penetrating solid.
    // Y axis -> the discriminating coordinate is x; X axis -> y.
    const size_t offset = axis == 1 ? 0 : 1;
    bool anyPositive = false;
    bool anyNegative = false;
    for (size_t i = 0; i + 1 < xy.size(); i += 2) {
        double c = xy[i + offset];
        if (c > 0.0) anyPositive = true;
        if (c < 0.0) anyNegative = true;
    }
    if (anyPositive && anyNegative) {
        throwJava(
            env, "java/lang/IllegalArgumentException",
            std::string("nativeRevolveProfile: profile crosses the rotation axis (all ") + (axis == 1 ? "x" : "y") +
                " coordinates must have the same sign)");
        return 0;
    }

    try {
        TopoDS_Face face;
        if (!buildProfileFace(env, xy, "nativeRevolveProfile", face)) {
            return 0;
        }
        gp_Ax1 rotationAxis(gp::Origin(), axis == 1 ? gp::DY() : gp::DX());
        // Full revolution uses the angle-less overload (a closed solid); passing 2*pi can leave
        // seam artefacts.
        bool full = std::abs(angleDeg - 360.0) <= 1e-9;
        TopoDS_Shape result;
        if (full) {
            BRepPrimAPI_MakeRevol maker(face, rotationAxis);
            maker.Build();
            if (!maker.IsDone()) {
                throwJava(env, "java/lang/IllegalStateException", "BRepPrimAPI_MakeRevol did not complete");
                return 0;
            }
            result = TopoDS_Shape(maker.Shape());
        } else {
            BRepPrimAPI_MakeRevol maker(face, rotationAxis, angleDeg * M_PI / 180.0);
            maker.Build();
            if (!maker.IsDone()) {
                throwJava(env, "java/lang/IllegalStateException", "BRepPrimAPI_MakeRevol did not complete");
                return 0;
            }
            result = TopoDS_Shape(maker.Shape());
        }
        // A degenerate profile is an input error (like extrude), hence IllegalArgumentException.
        if (!postCheckSolid(env, result, "nativeRevolveProfile", "java/lang/IllegalArgumentException")) {
            return 0;
        }
        return registerShape(std::move(result));
    } catch (const Standard_Failure& e) {
        throwJava(
            env, "java/lang/IllegalStateException", std::string("OCCT error revolving profile: ") + e.GetMessageString());
    } catch (const std::exception& e) {
        throwJava(env, "java/lang/IllegalStateException", std::string("Native error revolving profile: ") + e.what());
    } catch (...) {
        throwJava(env, "java/lang/IllegalStateException", "Unknown native error revolving profile");
    }
    return 0;
}

static jlong chamferEdgesImpl(
    JNIEnv* env, jlong handle, jintArray edgeIndices, jdouble distance, jobjectArray outHistory) {
    if (edgeIndices == nullptr) {
        throwJava(env, "java/lang/NullPointerException", "nativeChamferEdges: edgeIndices must not be null");
        return 0;
    }
    jsize indexCount = env->GetArrayLength(edgeIndices);
    if (indexCount < 1 || indexCount > kMaxChamferEdges) {
        throwJava(
            env, "java/lang/IllegalArgumentException",
            "nativeChamferEdges: edgeIndices must have between 1 and " + std::to_string(kMaxChamferEdges) +
                " entries, got " + std::to_string(indexCount));
        return 0;
    }
    if (!std::isfinite(distance) || distance < kMinChamferDistance || distance > kMaxChamferDistance) {
        throwJava(
            env, "java/lang/IllegalArgumentException",
            "nativeChamferEdges: distance must be finite and within [" + std::to_string(kMinChamferDistance) + ", " +
                std::to_string(kMaxChamferDistance) + "], got " + std::to_string(distance));
        return 0;
    }
    IntArrayGuard guard(env, edgeIndices);
    const jint* indices = guard.elements();
    if (indices == nullptr) {
        return 0;  // OutOfMemoryError already pending
    }

    try {
        bool found = false;
        bool tooManyFaces = false;
        int faceCount = 0;
        bool badIndex = false;
        jint offendingIndex = 0;
        int edgeCount = 0;
        bool built = false;
        SolidCheckResult check;
        std::vector<jint> history;
        bool historyTooLarge = false;
        TopoDS_Shape result;
        {
            std::lock_guard<std::mutex> lock(g_mutex);
            TopoDS_Shape* shape = findShapeLocked(handle);
            if (shape != nullptr) {
                found = true;
                TopTools_IndexedMapOfShape faces;
                TopExp::MapShapes(*shape, TopAbs_FACE, faces);
                faceCount = faces.Extent();
                if (faceCount > kMaxChamferInputFaces) {
                    tooManyFaces = true;
                } else {
                    TopTools_IndexedMapOfShape edges;
                    TopExp::MapShapes(*shape, TopAbs_EDGE, edges);
                    edgeCount = edges.Extent();
                    for (jsize i = 0; i < indexCount && !badIndex; ++i) {
                        if (indices[i] < 0 || indices[i] >= edgeCount) {
                            badIndex = true;
                            offendingIndex = indices[i];
                        }
                    }
                    if (!badIndex) {
                        BRepFilletAPI_MakeChamfer chamferMaker(*shape);
                        for (jsize i = 0; i < indexCount; ++i) {
                            chamferMaker.Add(distance, TopoDS::Edge(edges(indices[i] + 1)));
                        }
                        chamferMaker.Build();
                        if (chamferMaker.IsDone()) {
                            result = TopoDS_Shape(chamferMaker.Shape());
                            built = true;
                            // Shares TShapes with the registered operand: check under g_mutex.
                            check = runSolidChecks(result);
                            if (outHistory != nullptr) {
                                historyTooLarge = !encodeEdgeHistory(chamferMaker, {shape}, result, history);
                            }
                        }
                    }
                }
            }
        }  // g_mutex released here

        if (!found) {
            throwUnknownHandle(env, handle);
            return 0;
        }
        if (tooManyFaces) {
            throwJava(
                env, "java/lang/IllegalArgumentException",
                "nativeChamferEdges: input shape has " + std::to_string(faceCount) + " faces, exceeding the limit of " +
                    std::to_string(kMaxChamferInputFaces));
            return 0;
        }
        if (badIndex) {
            throwJava(
                env, "java/lang/IllegalArgumentException",
                "nativeChamferEdges: edge index " + std::to_string(offendingIndex) + " is out of range for a shape with " +
                    std::to_string(edgeCount) + " edges");
            return 0;
        }
        if (!built) {
            throwJava(
                env, "java/lang/IllegalStateException",
                "BRepFilletAPI_MakeChamfer did not complete (distance too large for the local geometry?)");
            return 0;
        }
        if (!check.valid) {
            throwJava(
                env, "java/lang/IllegalStateException",
                "nativeChamferEdges: resulting solid failed OCCT's own validity check");
            return 0;
        }
        if (!check.positiveVolume) {
            throwJava(env, "java/lang/IllegalStateException", "nativeChamferEdges: resulting solid has zero volume");
            return 0;
        }
        if (historyTooLarge) {
            throwJava(env, "java/lang/IllegalArgumentException", "nativeChamferEdges: edge history exceeds the size limit");
            return 0;
        }
        if (outHistory != nullptr && !publishHistory(env, outHistory, history)) {
            return 0;
        }
        return registerShape(std::move(result));
    } catch (const Standard_Failure& e) {
        throwJava(
            env, "java/lang/IllegalStateException", std::string("OCCT error building chamfer: ") + e.GetMessageString());
    } catch (const std::exception& e) {
        throwJava(env, "java/lang/IllegalStateException", std::string("Native error building chamfer: ") + e.what());
    } catch (...) {
        throwJava(env, "java/lang/IllegalStateException", "Unknown native error building chamfer");
    }
    return 0;
}

JNIEXPORT jlong JNICALL
Java_dev_kstep_geometry_occt_OcctBridge_nativeChamferEdges(
    JNIEnv* env, jobject /*self*/, jlong handle, jintArray edgeIndices, jdouble distance) {
    return chamferEdgesImpl(env, handle, edgeIndices, distance, nullptr);
}

JNIEXPORT jlong JNICALL
Java_dev_kstep_geometry_occt_OcctBridge_nativeChamferEdgesTracked(
    JNIEnv* env, jobject /*self*/, jlong handle, jintArray edgeIndices, jdouble distance, jobjectArray outHistory) {
    if (!checkHistoryHolder(env, outHistory, "nativeChamferEdgesTracked")) {
        return 0;
    }
    return chamferEdgesImpl(env, handle, edgeIndices, distance, outHistory);
}

// ------------------------------------------------------------------------------------------
// Viewer-Welle 1: OCCT triangulation. See docs/adr/ADR-0010-occt-triangulation-and-viewer.adoc
// for the design rationale, the DoS measurements behind kMaxTriangles, and the two classic OCCT
// pitfalls (REVERSED-face winding, TopLoc_Location transforms) this function's regression tests
// specifically prove it avoids. Extended with smooth per-vertex normals in a later wave -- see
// docs/adr/ADR-0018-smooth-vertex-normals.adoc for the header-prefixed return layout, the
// BRepLib_ToolTriangulatedShape::ComputeNormals pass, and why REVERSED faces need their computed
// normal negated (empirically verified: ComputeNormals ignores TopAbs_REVERSED entirely).
// ------------------------------------------------------------------------------------------

JNIEXPORT jdoubleArray JNICALL
Java_dev_kstep_geometry_occt_OcctBridge_nativeShapeTriangles(JNIEnv* env, jobject /*self*/, jlong handle) {
    try {
        bool found = false;
        bool tooManyTriangles = false;
        bool meshingIncomplete = false;
        int totalFaces = 0;
        int unmeshedFaces = 0;
        long long triangleCount = 0;
        std::vector<double> coords;
        // Non-empty only when EVERY non-null triangulation in this shape carries per-vertex
        // normals after the ComputeNormals pass below -- all-or-nothing, never partially filled.
        // See docs/adr/ADR-0018-smooth-vertex-normals.adoc's Decision.
        std::vector<double> normals;
        {
            // Lookup AND every OCCT call that dereferences `shape` happen inside this one
            // critical section, exactly like every other reader in this file -- see the
            // use-after-free note on g_mutex's declaration above.
            std::lock_guard<std::mutex> lock(g_mutex);
            TopoDS_Shape* shape = findShapeLocked(handle);
            if (shape != nullptr) {
                found = true;

                // Registers BRepTools::Clean(*shape) to run when this scope exits (success,
                // early "no faces" return, or a Standard_Failure unwind alike) -- see the class
                // KDoc above. Constructed BEFORE meshing, still under g_mutex, so the cleanup
                // itself runs against the same shape under the same lock that protects every
                // other reader/writer of it. No registerShape() call anywhere in this function
                // -- nativeShapeTriangles produces no new shape, so the self-deadlock trap
                // ADR-0008 documents for registerShape-inside-the-critical-section simply does
                // not apply here; noted so a future edit does not add one inside this block.
                TriangulationCleanupGuard cleanup(shape);

                Bnd_Box box;
                BRepBndLib::Add(*shape, box, Standard_True);
                if (!box.IsVoid()) {
                    double xMin = 0.0, yMin = 0.0, zMin = 0.0, xMax = 0.0, yMax = 0.0, zMax = 0.0;
                    box.Get(xMin, yMin, zMin, xMax, yMax, zMax);
                    double dx = xMax - xMin;
                    double dy = yMax - yMin;
                    double dz = zMax - zMin;
                    double diagonal = std::sqrt(dx * dx + dy * dy + dz * dz);
                    double deflection = diagonal * kMeshDeflectionFactor;
                    if (deflection < kMinMeshDeflection) {
                        deflection = kMinMeshDeflection;
                    } else if (deflection > kMaxMeshDeflection) {
                        deflection = kMaxMeshDeflection;
                    }

                    // isRelative=false, isInParallel=false -- deterministic, reproducible
                    // triangle counts (see nativeShapeTriangles's Kotlin-side KDoc and T-8's
                    // "two consecutive calls produce identical output" regression test).
                    BRepMesh_IncrementalMesh mesher(*shape, deflection, Standard_False, kMeshAngularDeflection, Standard_False);
                    // IsDone()==false means the algorithm did not complete for (at least) one
                    // face -- surfaced below as meshingIncomplete, same "check status, don't just
                    // trust Shape()/the output" rule this file already applies to every other OCCT
                    // *Maker (BRepBuilderAPI_MakePolygon/MakeFace, BRepPrimAPI_MakePrism,
                    // BRepFilletAPI_MakeFillet, all above). Left unchecked, a face that fails to
                    // mesh has BRep_Tool::Triangulation(...) return IsNull() below and is silently
                    // `continue`d past -- a "holey" triangle soup returned as if it were a complete,
                    // valid result, with no exception anywhere.
                    bool meshDone = mesher.IsDone();

                    TopTools_IndexedMapOfShape faces;
                    // TopExp::MapShapes, never TopExp_Explorer -- see nativeShapeCounts's
                    // identical rationale above (a shared face visited twice would double-count
                    // its triangles and corrupt the signed-volume regression tests).
                    TopExp::MapShapes(*shape, TopAbs_FACE, faces);
                    totalFaces = faces.Extent();

                    // Pass 1: count only, so kMaxTriangles is enforced BEFORE any coordinate is
                    // extracted and BEFORE `coords` is sized. Also tallies unmeshedFaces (a face
                    // whose triangulation is null despite the shape having faces at all) --
                    // together with `meshDone`, this is what meshingIncomplete below is built
                    // from, so a partially-failed mesh fails loudly instead of returning silently
                    // corrupt data.
                    for (int i = 1; i <= faces.Extent() && !tooManyTriangles; ++i) {
                        const TopoDS_Face& face = TopoDS::Face(faces(i));
                        TopLoc_Location loc;
                        const auto& tri = BRep_Tool::Triangulation(face, loc);
                        if (tri.IsNull()) {
                            ++unmeshedFaces;
                            continue;
                        }
                        triangleCount += tri->NbTriangles();
                        if (triangleCount > kMaxTriangles) {
                            tooManyTriangles = true;
                        }
                    }
                    // Gated on totalFaces > 0: a shape with zero faces at all (a wire/edge/vertex
                    // compound) is the pre-existing "empty result is valid, non-exceptional" case
                    // documented below -- meshDone can reasonably be false for a face-less shape
                    // with nothing to mesh, and that must NOT be reinterpreted as a failure.
                    meshingIncomplete = totalFaces > 0 && (!meshDone || unmeshedFaces > 0);

                    // Normal-computation pass: runs BEFORE extraction, over the same faces map,
                    // so allFacesHaveNormals is fully decided before a single coordinate or
                    // normal is written -- same "decide first, extract second" shape as the
                    // counting pass above. Skipped entirely when the shape is already going to be
                    // rejected (tooManyTriangles) or has no triangles at all -- no point paying
                    // for ComputeNormals in either case. One Poly_Connect instance reused across
                    // every face via the 3-argument ComputeNormals overload -- the 2-argument
                    // convenience overload would construct a fresh Poly_Connect per face, avoidable
                    // allocation overhead at up to kMaxTriangles triangles. See
                    // docs/adr/ADR-0018-smooth-vertex-normals.adoc.
                    bool allFacesHaveNormals = false;
                    if (!tooManyTriangles && triangleCount > 0) {
                        allFacesHaveNormals = true;
                        Poly_Connect polyConnect;
                        for (int i = 1; i <= faces.Extent(); ++i) {
                            const TopoDS_Face& face = TopoDS::Face(faces(i));
                            TopLoc_Location loc;
                            const auto& tri = BRep_Tool::Triangulation(face, loc);
                            if (tri.IsNull()) {
                                continue;
                            }
                            if (!tri->HasNormals()) {
                                // ComputeNormals derives normals from UV coordinates + the
                                // face's underlying surface -- undocumented without UV nodes, so
                                // this guard makes the (empirically always-true, see the ADR's
                                // Measurements table) precondition explicit rather than implicit.
                                if (tri->HasUVNodes()) {
                                    BRepLib_ToolTriangulatedShape::ComputeNormals(face, tri, polyConnect);
                                }
                            }
                            if (!tri->HasNormals()) {
                                allFacesHaveNormals = false;
                            }
                        }
                    }

                    if (!tooManyTriangles && triangleCount > 0) {
                        coords.reserve(static_cast<size_t>(triangleCount) * 9);
                        if (allFacesHaveNormals) {
                            normals.reserve(static_cast<size_t>(triangleCount) * 9);
                        }
                        for (int i = 1; i <= faces.Extent(); ++i) {
                            const TopoDS_Face& face = TopoDS::Face(faces(i));
                            TopLoc_Location loc;
                            const auto& tri = BRep_Tool::Triangulation(face, loc);
                            if (tri.IsNull()) {
                                continue;
                            }
                            const gp_Trsf& trsf = loc.Transformation();
                            bool identity = loc.IsIdentity();
                            // REVERSED faces must have their winding flipped, or the resulting
                            // normal points inward -- see this function's KDoc and ADR-0010's
                            // measured -8000-vs-24000 regression case.
                            bool reversed = (face.Orientation() == TopAbs_REVERSED);
                            for (int t = 1; t <= tri->NbTriangles(); ++t) {
                                int n1 = 0, n2 = 0, n3 = 0;
                                tri->Triangle(t).Get(n1, n2, n3);
                                if (reversed) {
                                    std::swap(n2, n3);
                                }
                                gp_Pnt p1 = tri->Node(n1);
                                gp_Pnt p2 = tri->Node(n2);
                                gp_Pnt p3 = tri->Node(n3);
                                if (!identity) {
                                    p1.Transform(trsf);
                                    p2.Transform(trsf);
                                    p3.Transform(trsf);
                                }
                                if (allFacesHaveNormals) {
                                    // Fallback face normal, computed lazily (only if a degenerate
                                    // per-vertex normal is actually encountered below) from the
                                    // ALREADY-TRANSFORMED positions, so it is consistent with what
                                    // this same function already writes into `coords`.
                                    gp_Vec fallbackFaceNormal;
                                    bool fallbackComputed = false;
                                    int nodeIndices[3] = {n1, n2, n3};
                                    for (int corner = 0; corner < 3; ++corner) {
                                        gp_Vec3f raw;
                                        tri->Normal(nodeIndices[corner], raw);
                                        gp_Vec v(static_cast<double>(raw.x()), static_cast<double>(raw.y()),
                                                 static_cast<double>(raw.z()));
                                        // ComputeNormals derives the surface normal from the
                                        // face's underlying geometry and ignores TopAbs_REVERSED
                                        // entirely -- empirically verified (see the ADR's
                                        // Measurements table: a REVERSED and a FORWARD box face
                                        // produce IDENTICAL raw normals). Without this negation,
                                        // half of a closed solid's vertex normals point inward.
                                        if (reversed) {
                                            v.Reverse();
                                        }
                                        if (!identity) {
                                            v.Transform(trsf); // rotation/scale only -- gp_Vec::Transform
                                                                // never applies trsf's translation part.
                                        }
                                        double len = v.Magnitude();
                                        if (len > kMinNormalLength) {
                                            v.Divide(len);
                                        } else {
                                            if (!fallbackComputed) {
                                                gp_Vec u12(p1, p2);
                                                gp_Vec u13(p1, p3);
                                                fallbackFaceNormal = u12.Crossed(u13);
                                                double faceLen = fallbackFaceNormal.Magnitude();
                                                if (faceLen > kMinNormalLength) {
                                                    fallbackFaceNormal.Divide(faceLen);
                                                }
                                                fallbackComputed = true;
                                            }
                                            v = fallbackFaceNormal;
                                        }
                                        normals.push_back(v.X());
                                        normals.push_back(v.Y());
                                        normals.push_back(v.Z());
                                    }
                                }
                                coords.push_back(p1.X());
                                coords.push_back(p1.Y());
                                coords.push_back(p1.Z());
                                coords.push_back(p2.X());
                                coords.push_back(p2.Y());
                                coords.push_back(p2.Z());
                                coords.push_back(p3.X());
                                coords.push_back(p3.Y());
                                coords.push_back(p3.Z());
                            }
                        }
                    }
                }
                // TriangulationCleanupGuard::~TriangulationCleanupGuard runs here (end of scope,
                // still under g_mutex), discarding the triangulation this block just extracted
                // coordinates from.
            }
        }  // g_mutex released here

        // Every throwJava() call below runs OUTSIDE the lock -- see throwUnknownHandle()'s own
        // comment above for the identical FindClass-can-run-arbitrary-Java-code rationale.
        if (!found) {
            throwUnknownHandle(env, handle);
            return nullptr;
        }
        if (tooManyTriangles) {
            throwJava(
                env,
                "java/lang/IllegalArgumentException",
                "nativeShapeTriangles: shape triangulates to more than " + std::to_string(kMaxTriangles) +
                    " triangles (got at least " + std::to_string(triangleCount) + ")");
            return nullptr;
        }
        if (meshingIncomplete) {
            // IllegalStateException, not OcctGeometryException's usual RuntimeException wrapping
            // on the Kotlin side -- mirrors BRepPrimAPI_MakePrism's/BRepFilletAPI_MakeFillet's
            // identical "did not complete" IllegalStateException above, both of which are also
            // reached only via the general RuntimeException catch in OcctShape.triangulate() (this
            // is NOT the tooManyTriangles DoS guard, so it deliberately does not reuse
            // IllegalArgumentException -- see that function's ordering comment for why the
            // distinction matters).
            throwJava(
                env,
                "java/lang/IllegalStateException",
                "nativeShapeTriangles: BRepMesh_IncrementalMesh did not fully triangulate the shape (" +
                    std::to_string(unmeshedFaces) + " of " + std::to_string(totalFaces) +
                    " face(s) have no triangulation) -- refusing to return a partial triangle mesh");
            return nullptr;
        }

        // Header-prefixed layout: result[0] = triangleCount (exact as a double -- triangleCount
        // is bounded by kMaxTriangles, far below 2^53), followed by the 9*triangleCount position
        // doubles (unchanged order/content), followed by 9*triangleCount normal doubles IF
        // `normals` is non-empty. Without this header element, a caller cannot distinguish "no
        // normals, N triangles" from "normals present, N/2 triangles" for any even N -- both
        // encode to the same array length 9*N. See docs/adr/ADR-0018-smooth-vertex-normals.adoc's
        // Decision for the full rationale (this replaces the pre-Welle header-less
        // length-9*triangleCount layout). An empty array's worth of geometry (0 triangles, e.g. a
        // shape with no faces) is still a valid, non-exceptional result -- it now carries just the
        // one header element instead of zero elements total.
        size_t totalLength = 1 + coords.size() + normals.size();
        jdoubleArray result = env->NewDoubleArray(static_cast<jsize>(totalLength));
        if (result == nullptr) {
            return nullptr;  // OutOfMemoryError already pending, thrown by the JVM itself
        }
        jdouble header = static_cast<jdouble>(coords.size() / 9);
        env->SetDoubleArrayRegion(result, 0, 1, &header);
        if (!coords.empty()) {
            env->SetDoubleArrayRegion(result, 1, static_cast<jsize>(coords.size()), coords.data());
        }
        if (!normals.empty()) {
            env->SetDoubleArrayRegion(
                result, static_cast<jsize>(1 + coords.size()), static_cast<jsize>(normals.size()), normals.data());
        }
        return result;
    } catch (const Standard_Failure& e) {
        throwJava(
            env,
            "java/lang/IllegalStateException",
            std::string("OCCT error triangulating shape: ") + e.GetMessageString());
    } catch (const std::exception& e) {
        throwJava(
            env, "java/lang/IllegalStateException", std::string("Native error triangulating shape: ") + e.what());
    } catch (...) {
        throwJava(env, "java/lang/IllegalStateException", "Unknown native error triangulating shape");
    }
    return nullptr;
}

// ------------------------------------------------------------------------------------------
// Welle K: STEP import (XCAF read session). See docs/adr/ADR-0030-read-geometry.adoc.
// Invariants as everywhere in this file: every dereference of a registry object happens inside
// ONE g_mutex critical section; throwJava() only OUTSIDE it; full catch cascade; nothing is
// registered on any failure path.
// ------------------------------------------------------------------------------------------

namespace {
void throwUnknownImportHandle(JNIEnv* env, jlong handle) {
    throwJava(env, "java/lang/IllegalStateException", "Unknown native STEP import session handle: " + std::to_string(handle));
}
}  // namespace

JNIEXPORT jlong JNICALL
Java_dev_kstep_geometry_occt_OcctBridge_nativeReadStepXcaf(JNIEnv* env, jobject /*self*/, jbyteArray content, jintArray limits) {
    if (content == nullptr || limits == nullptr) {
        throwJava(env, "java/lang/NullPointerException", "nativeReadStepXcaf: content and limits must not be null");
        return 0;
    }
    if (env->GetArrayLength(limits) != kImportLimitCount) {
        throwJava(
            env, "java/lang/IllegalArgumentException",
            "nativeReadStepXcaf: limits must have exactly " + std::to_string(kImportLimitCount) + " entries");
        return 0;
    }
    int lim[kImportLimitCount];
    {
        IntArrayGuard guard(env, limits);
        const jint* src = guard.elements();
        if (src == nullptr) {
            return 0;  // OutOfMemoryError already pending
        }
        for (int i = 0; i < kImportLimitCount; ++i) lim[i] = src[i];
    }
    for (int i = 0; i < kImportLimitCount; ++i) {
        if (lim[i] <= 0) {
            throwJava(
                env, "java/lang/IllegalArgumentException",
                "nativeReadStepXcaf: limits[" + std::to_string(i) + "] must be positive");
            return 0;
        }
    }
    jsize length = env->GetArrayLength(content);
    std::string bytes;
    try {
        bytes.resize(static_cast<size_t>(length));
    } catch (const std::bad_alloc&) {
        throwJava(env, "java/lang/OutOfMemoryError", "nativeReadStepXcaf: cannot allocate input buffer");
        return 0;
    }
    if (length > 0) {
        env->GetByteArrayRegion(content, 0, length, reinterpret_cast<jbyte*>(&bytes[0]));
        if (env->ExceptionCheck()) {
            return 0;
        }
    }

    std::string errClass;
    std::string errMsg;
    jlong handle = 0;
    try {
        std::unique_ptr<ImportSession> session(new ImportSession());
        {
            std::lock_guard<std::mutex> lock(g_mutex);
            if (readXcafLocked(bytes, lim, *session, errClass, errMsg)) {
                handle = g_nextHandle++;
                g_imports[handle] = session.release();
            }
        }  // g_mutex released here; `session` (if still owned) is freed on scope exit
    } catch (const Standard_Failure& e) {
        errClass = "java/lang/IllegalStateException";
        errMsg = std::string("OCCT error reading STEP file: ") + e.GetMessageString();
        handle = 0;
    } catch (const std::exception& e) {
        errClass = "java/lang/IllegalStateException";
        errMsg = std::string("Native error reading STEP file: ") + e.what();
        handle = 0;
    } catch (...) {
        errClass = "java/lang/IllegalStateException";
        errMsg = "Unknown native error reading STEP file";
        handle = 0;
    }
    if (handle == 0) {
        if (errClass.empty()) {
            errClass = "java/lang/IllegalStateException";
            errMsg = "STEP read failed";
        }
        throwJava(env, errClass.c_str(), errMsg);
        return 0;
    }
    return handle;
}

JNIEXPORT jintArray JNICALL
Java_dev_kstep_geometry_occt_OcctBridge_nativeImportNodeInts(JNIEnv* env, jobject /*self*/, jlong session) {
    std::vector<jint> out;
    bool found = false;
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        auto it = g_imports.find(session);
        if (it != g_imports.end()) {
            found = true;
            for (const ImportNode& n : it->second->nodes) {
                out.push_back(n.kind);
                out.push_back(n.parent);
                out.push_back(n.referred);
                out.push_back(n.freeRoot);
                out.push_back(n.rigid);
                out.push_back(n.shapeSlot);
            }
        }
    } catch (...) {
        throwJava(env, "java/lang/IllegalStateException", "Native error reading import nodes");
        return nullptr;
    }
    if (!found) {
        throwUnknownImportHandle(env, session);
        return nullptr;
    }
    jintArray result = env->NewIntArray(static_cast<jsize>(out.size()));
    if (result == nullptr) return nullptr;
    if (!out.empty()) env->SetIntArrayRegion(result, 0, static_cast<jsize>(out.size()), out.data());
    return result;
}

JNIEXPORT jdoubleArray JNICALL
Java_dev_kstep_geometry_occt_OcctBridge_nativeImportNodeMatrices(JNIEnv* env, jobject /*self*/, jlong session) {
    std::vector<jdouble> out;
    bool found = false;
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        auto it = g_imports.find(session);
        if (it != g_imports.end()) {
            found = true;
            for (const ImportNode& n : it->second->nodes) {
                for (int i = 0; i < 12; ++i) out.push_back(n.m[i]);
            }
        }
    } catch (...) {
        throwJava(env, "java/lang/IllegalStateException", "Native error reading import matrices");
        return nullptr;
    }
    if (!found) {
        throwUnknownImportHandle(env, session);
        return nullptr;
    }
    jdoubleArray result = env->NewDoubleArray(static_cast<jsize>(out.size()));
    if (result == nullptr) return nullptr;
    if (!out.empty()) env->SetDoubleArrayRegion(result, 0, static_cast<jsize>(out.size()), out.data());
    return result;
}

JNIEXPORT jstring JNICALL
Java_dev_kstep_geometry_occt_OcctBridge_nativeImportNodeName(JNIEnv* env, jobject /*self*/, jlong session, jint index) {
    std::u16string name;
    bool found = false;
    bool inRange = false;
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        auto it = g_imports.find(session);
        if (it != g_imports.end()) {
            found = true;
            if (index >= 0 && static_cast<size_t>(index) < it->second->nodes.size()) {
                inRange = true;
                name = it->second->nodes[static_cast<size_t>(index)].name;
            }
        }
    } catch (...) {
        throwJava(env, "java/lang/IllegalStateException", "Native error reading import node name");
        return nullptr;
    }
    if (!found) {
        throwUnknownImportHandle(env, session);
        return nullptr;
    }
    if (!inRange) {
        throwJava(env, "java/lang/IllegalArgumentException", "node index out of range: " + std::to_string(index));
        return nullptr;
    }
    // UTF-16 straight through: NewStringUTF would use modified UTF-8 and mangle non-BMP characters
    // and embedded NULs.
    return env->NewString(reinterpret_cast<const jchar*>(name.data()), static_cast<jsize>(name.size()));
}

JNIEXPORT jintArray JNICALL
Java_dev_kstep_geometry_occt_OcctBridge_nativeImportStats(JNIEnv* env, jobject /*self*/, jlong session) {
    jint stats[4] = {0, 0, 0, 0};
    bool found = false;
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        auto it = g_imports.find(session);
        if (it != g_imports.end()) {
            found = true;
            stats[0] = it->second->entityCount;
            stats[1] = it->second->unknownEntityCount;
            stats[2] = it->second->freeRootCount;
            stats[3] = static_cast<jint>(it->second->nodes.size());
        }
    } catch (...) {
        throwJava(env, "java/lang/IllegalStateException", "Native error reading import stats");
        return nullptr;
    }
    if (!found) {
        throwUnknownImportHandle(env, session);
        return nullptr;
    }
    jintArray result = env->NewIntArray(4);
    if (result == nullptr) return nullptr;
    env->SetIntArrayRegion(result, 0, 4, stats);
    return result;
}

JNIEXPORT jstring JNICALL
Java_dev_kstep_geometry_occt_OcctBridge_nativeImportUnknownTypes(JNIEnv* env, jobject /*self*/, jlong session) {
    std::string joined;
    bool found = false;
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        auto it = g_imports.find(session);
        if (it != g_imports.end()) {
            found = true;
            for (size_t i = 0; i < it->second->unknownTypes.size(); ++i) {
                if (i > 0) joined.push_back('\n');
                joined += it->second->unknownTypes[i];
            }
        }
    } catch (...) {
        throwJava(env, "java/lang/IllegalStateException", "Native error reading unknown types");
        return nullptr;
    }
    if (!found) {
        throwUnknownImportHandle(env, session);
        return nullptr;
    }
    // sanitizeTypeName guarantees [A-Z0-9_] only, so plain UTF is exact here.
    return env->NewStringUTF(joined.c_str());
}

JNIEXPORT jlong JNICALL
Java_dev_kstep_geometry_occt_OcctBridge_nativeImportPartShape(JNIEnv* env, jobject /*self*/, jlong session, jint nodeIndex) {
    bool found = false;
    std::string argError;
    jlong handle = 0;
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        auto it = g_imports.find(session);
        if (it != g_imports.end()) {
            found = true;
            ImportSession* s = it->second;
            if (nodeIndex < 0 || static_cast<size_t>(nodeIndex) >= s->nodes.size()) {
                argError = "node index out of range: " + std::to_string(nodeIndex);
            } else {
                const ImportNode& n = s->nodes[static_cast<size_t>(nodeIndex)];
                if (n.kind != kImportKindPart) {
                    argError = "node " + std::to_string(nodeIndex) + " is not a part";
                } else if (n.shapeSlot < 0 || static_cast<size_t>(n.shapeSlot) >= s->partShapes.size()) {
                    argError = "part " + std::to_string(nodeIndex) + " has no geometry";
                } else {
                    handle = registerShapeLocked(TopoDS_Shape(s->partShapes[static_cast<size_t>(n.shapeSlot)]));
                }
            }
        }
    } catch (const Standard_Failure& e) {
        throwJava(env, "java/lang/IllegalStateException", std::string("OCCT error copying part shape: ") + e.GetMessageString());
        return 0;
    } catch (...) {
        throwJava(env, "java/lang/IllegalStateException", "Native error copying part shape");
        return 0;
    }
    if (!found) {
        throwUnknownImportHandle(env, session);
        return 0;
    }
    if (!argError.empty()) {
        throwJava(env, "java/lang/IllegalArgumentException", argError);
        return 0;
    }
    return handle;
}

JNIEXPORT void JNICALL
Java_dev_kstep_geometry_occt_OcctBridge_nativeReleaseImport(JNIEnv* env, jobject /*self*/, jlong session) {
    // Idempotent, like nativeReleaseShape: an unknown handle is a silent no-op.
    ImportSession* toDelete = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        auto it = g_imports.find(session);
        if (it != g_imports.end()) {
            toDelete = it->second;
            g_imports.erase(it);
        }
    }
    delete toDelete;
    (void)env;
}

JNIEXPORT jint JNICALL
Java_dev_kstep_geometry_occt_OcctBridge_nativeImportSessionCount(JNIEnv* env, jobject /*self*/) {
    (void)env;
    std::lock_guard<std::mutex> lock(g_mutex);
    return static_cast<jint>(g_imports.size());
}

JNIEXPORT jdoubleArray JNICALL
Java_dev_kstep_geometry_occt_OcctBridge_nativeShapeBoundingBox(JNIEnv* env, jobject /*self*/, jlong handle) {
    bool found = false;
    bool isVoid = true;
    double box[6] = {0, 0, 0, 0, 0, 0};
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        TopoDS_Shape* shape = findShapeLocked(handle);
        if (shape != nullptr) {
            found = true;
            Bnd_Box b;
            b.SetGap(0.0);
            // AddOptimal without triangulation and without tolerance inflation: the exact box.
            BRepBndLib::AddOptimal(*shape, b, Standard_False, Standard_False);
            if (!b.IsVoid()) {
                isVoid = false;
                b.Get(box[0], box[1], box[2], box[3], box[4], box[5]);
            }
        }
    } catch (const Standard_Failure& e) {
        throwJava(env, "java/lang/IllegalStateException", std::string("OCCT error computing bounding box: ") + e.GetMessageString());
        return nullptr;
    } catch (const std::exception& e) {
        throwJava(env, "java/lang/IllegalStateException", std::string("Native error computing bounding box: ") + e.what());
        return nullptr;
    } catch (...) {
        throwJava(env, "java/lang/IllegalStateException", "Unknown native error computing bounding box");
        return nullptr;
    }
    if (!found) {
        throwUnknownHandle(env, handle);
        return nullptr;
    }
    jdoubleArray result = env->NewDoubleArray(isVoid ? 0 : 6);
    if (result == nullptr) return nullptr;
    if (!isVoid) env->SetDoubleArrayRegion(result, 0, 6, box);
    return result;
}

// ------------------------------------------------------------------------------------------
// Welle L: edge/face geometry queries and sketch regions. See ADR-0031. Same invariants as the
// rest of this file: every dereference of a registry shape inside ONE g_mutex critical section;
// throwJava()/NewXArray()/registerShape() only OUTSIDE it; nothing is registered on a failure path.
// Edge order = TopExp::MapShapes(TopAbs_EDGE), face order = MapShapes(TopAbs_FACE).
// ------------------------------------------------------------------------------------------

JNIEXPORT jdoubleArray JNICALL
Java_dev_kstep_geometry_occt_OcctBridge_nativeShapeEdgeInfo(JNIEnv* env, jobject /*self*/, jlong handle) {
    bool found = false;
    bool tooMany = false;
    int edgeCount = 0;
    std::vector<double> rec;
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        TopoDS_Shape* shape = findShapeLocked(handle);
        if (shape != nullptr) {
            found = true;
            TopTools_IndexedMapOfShape edges;
            TopExp::MapShapes(*shape, TopAbs_EDGE, edges);
            TopTools_IndexedMapOfShape faces;
            TopExp::MapShapes(*shape, TopAbs_FACE, faces);
            edgeCount = edges.Extent();
            if (edgeCount > kMaxQueryEdges || faces.Extent() > kMaxQueryFaces) {
                tooMany = true;
            } else {
                std::vector<double> seam(static_cast<size_t>(edgeCount), 0.0);
                for (int f = 1; f <= faces.Extent(); ++f) {
                    const TopoDS_Face face = TopoDS::Face(faces(f));
                    TopTools_IndexedMapOfShape local;
                    TopExp::MapShapes(face, TopAbs_EDGE, local);
                    for (int i = 1; i <= local.Extent(); ++i) {
                        const TopoDS_Edge e = TopoDS::Edge(local(i));
                        if (BRep_Tool::IsClosed(e, face)) {
                            int idx = edges.FindIndex(e);
                            if (idx > 0) seam[static_cast<size_t>(idx - 1)] = 1.0;
                        }
                    }
                }
                rec.assign(static_cast<size_t>(edgeCount) * kEdgeRecord, 0.0);
                for (int i = 1; i <= edgeCount; ++i) {
                    double* r = &rec[static_cast<size_t>(i - 1) * kEdgeRecord];
                    const TopoDS_Edge e = TopoDS::Edge(edges(i));
                    r[18] = seam[static_cast<size_t>(i - 1)];
                    if (BRep_Tool::Degenerated(e)) {
                        r[0] = 2.0;
                        r[19] = 1.0;
                        continue;
                    }
                    BRepAdaptor_Curve curve(e);
                    const double first = curve.FirstParameter();
                    const double last = curve.LastParameter();
                    gp_Pnt s = curve.Value(first);
                    gp_Pnt t = curve.Value(last);
                    gp_Pnt m = curve.Value((first + last) / 2.0);
                    r[1] = GCPnts_AbscissaPoint::Length(curve);
                    r[2] = s.X(); r[3] = s.Y(); r[4] = s.Z();
                    r[5] = t.X(); r[6] = t.Y(); r[7] = t.Z();
                    r[8] = m.X(); r[9] = m.Y(); r[10] = m.Z();
                    if (curve.GetType() == GeomAbs_Line) {
                        r[0] = 0.0;
                        gp_Vec v(s, t);
                        if (v.Magnitude() > Precision::Confusion()) {
                            v.Normalize();
                            r[11] = v.X(); r[12] = v.Y(); r[13] = v.Z();
                        }
                    } else if (curve.GetType() == GeomAbs_Circle) {
                        r[0] = 1.0;
                        gp_Circ c = curve.Circle();
                        gp_Dir axis = c.Axis().Direction();
                        r[11] = axis.X(); r[12] = axis.Y(); r[13] = axis.Z();
                        r[14] = c.Location().X(); r[15] = c.Location().Y(); r[16] = c.Location().Z();
                        r[17] = c.Radius();
                    } else {
                        r[0] = 2.0;
                    }
                }
            }
        }
    } catch (const Standard_Failure& e) {
        throwJava(env, "java/lang/IllegalStateException", std::string("OCCT error reading edge info: ") + e.GetMessageString());
        return nullptr;
    } catch (const std::exception& e) {
        throwJava(env, "java/lang/IllegalStateException", std::string("Native error reading edge info: ") + e.what());
        return nullptr;
    } catch (...) {
        throwJava(env, "java/lang/IllegalStateException", "Unknown native error reading edge info");
        return nullptr;
    }
    if (!found) {
        throwUnknownHandle(env, handle);
        return nullptr;
    }
    if (tooMany) {
        throwJava(
            env, "java/lang/IllegalArgumentException",
            "nativeShapeEdgeInfo: shape exceeds the query limits (" + std::to_string(kMaxQueryEdges) + " edges / " +
                std::to_string(kMaxQueryFaces) + " faces)");
        return nullptr;
    }
    jdoubleArray result = env->NewDoubleArray(static_cast<jsize>(rec.size()));
    if (result == nullptr) return nullptr;
    if (!rec.empty()) env->SetDoubleArrayRegion(result, 0, static_cast<jsize>(rec.size()), rec.data());
    return result;
}

JNIEXPORT jdoubleArray JNICALL
Java_dev_kstep_geometry_occt_OcctBridge_nativeShapeFaceInfo(JNIEnv* env, jobject /*self*/, jlong handle) {
    bool found = false;
    bool tooMany = false;
    std::vector<double> rec;
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        TopoDS_Shape* shape = findShapeLocked(handle);
        if (shape != nullptr) {
            found = true;
            TopTools_IndexedMapOfShape faces;
            TopExp::MapShapes(*shape, TopAbs_FACE, faces);
            if (faces.Extent() > kMaxQueryFaces) {
                tooMany = true;
            } else {
                rec.assign(static_cast<size_t>(faces.Extent()) * kFaceRecord, 0.0);
                for (int i = 1; i <= faces.Extent(); ++i) {
                    double* r = &rec[static_cast<size_t>(i - 1) * kFaceRecord];
                    const TopoDS_Face face = TopoDS::Face(faces(i));
                    BRepAdaptor_Surface surf(face);
                    GProp_GProps props;
                    BRepGProp::SurfaceProperties(face, props);
                    r[1] = props.Mass();
                    gp_Pnt c = props.CentreOfMass();
                    r[2] = c.X(); r[3] = c.Y(); r[4] = c.Z();
                    if (surf.GetType() == GeomAbs_Plane) {
                        r[0] = 0.0;
                        gp_Dir n = surf.Plane().Axis().Direction();
                        double sign = face.Orientation() == TopAbs_REVERSED ? -1.0 : 1.0;
                        r[5] = sign * n.X(); r[6] = sign * n.Y(); r[7] = sign * n.Z();
                    } else if (surf.GetType() == GeomAbs_Cylinder) {
                        r[0] = 1.0;
                        gp_Cylinder cyl = surf.Cylinder();
                        gp_Dir a = cyl.Axis().Direction();
                        r[5] = a.X(); r[6] = a.Y(); r[7] = a.Z();
                        r[8] = cyl.Location().X(); r[9] = cyl.Location().Y(); r[10] = cyl.Location().Z();
                        r[11] = cyl.Radius();
                    } else {
                        r[0] = 2.0;
                    }
                }
            }
        }
    } catch (const Standard_Failure& e) {
        throwJava(env, "java/lang/IllegalStateException", std::string("OCCT error reading face info: ") + e.GetMessageString());
        return nullptr;
    } catch (const std::exception& e) {
        throwJava(env, "java/lang/IllegalStateException", std::string("Native error reading face info: ") + e.what());
        return nullptr;
    } catch (...) {
        throwJava(env, "java/lang/IllegalStateException", "Unknown native error reading face info");
        return nullptr;
    }
    if (!found) {
        throwUnknownHandle(env, handle);
        return nullptr;
    }
    if (tooMany) {
        throwJava(
            env, "java/lang/IllegalArgumentException",
            "nativeShapeFaceInfo: shape has more than " + std::to_string(kMaxQueryFaces) + " faces");
        return nullptr;
    }
    jdoubleArray result = env->NewDoubleArray(static_cast<jsize>(rec.size()));
    if (result == nullptr) return nullptr;
    if (!rec.empty()) env->SetDoubleArrayRegion(result, 0, static_cast<jsize>(rec.size()), rec.data());
    return result;
}

/** CSR layout: [faceCount, offsets (faceCount+1)..., edge indices...] into the global edge map. */
JNIEXPORT jintArray JNICALL
Java_dev_kstep_geometry_occt_OcctBridge_nativeShapeFaceEdges(JNIEnv* env, jobject /*self*/, jlong handle) {
    bool found = false;
    bool tooMany = false;
    std::vector<jint> csr;
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        TopoDS_Shape* shape = findShapeLocked(handle);
        if (shape != nullptr) {
            found = true;
            TopTools_IndexedMapOfShape edges;
            TopExp::MapShapes(*shape, TopAbs_EDGE, edges);
            TopTools_IndexedMapOfShape faces;
            TopExp::MapShapes(*shape, TopAbs_FACE, faces);
            if (edges.Extent() > kMaxQueryEdges || faces.Extent() > kMaxQueryFaces) {
                tooMany = true;
            } else {
                std::vector<jint> offsets;
                std::vector<jint> indices;
                offsets.push_back(0);
                for (int f = 1; f <= faces.Extent(); ++f) {
                    TopTools_IndexedMapOfShape local;
                    TopExp::MapShapes(faces(f), TopAbs_EDGE, local);
                    for (int i = 1; i <= local.Extent(); ++i) {
                        int idx = edges.FindIndex(local(i));
                        if (idx > 0) indices.push_back(static_cast<jint>(idx - 1));
                    }
                    offsets.push_back(static_cast<jint>(indices.size()));
                }
                csr.push_back(static_cast<jint>(faces.Extent()));
                csr.insert(csr.end(), offsets.begin(), offsets.end());
                csr.insert(csr.end(), indices.begin(), indices.end());
            }
        }
    } catch (const Standard_Failure& e) {
        throwJava(env, "java/lang/IllegalStateException", std::string("OCCT error reading face edges: ") + e.GetMessageString());
        return nullptr;
    } catch (const std::exception& e) {
        throwJava(env, "java/lang/IllegalStateException", std::string("Native error reading face edges: ") + e.what());
        return nullptr;
    } catch (...) {
        throwJava(env, "java/lang/IllegalStateException", "Unknown native error reading face edges");
        return nullptr;
    }
    if (!found) {
        throwUnknownHandle(env, handle);
        return nullptr;
    }
    if (tooMany) {
        throwJava(
            env, "java/lang/IllegalArgumentException",
            "nativeShapeFaceEdges: shape exceeds the query limits (" + std::to_string(kMaxQueryEdges) + " edges / " +
                std::to_string(kMaxQueryFaces) + " faces)");
        return nullptr;
    }
    jintArray result = env->NewIntArray(static_cast<jsize>(csr.size()));
    if (result == nullptr) return nullptr;
    env->SetIntArrayRegion(result, 0, static_cast<jsize>(csr.size()), csr.data());
    return result;
}

JNIEXPORT jlong JNICALL
Java_dev_kstep_geometry_occt_OcctBridge_nativeExtrudeRegion(
    JNIEnv* env, jobject /*self*/, jintArray loopSizes, jintArray segKinds, jdoubleArray segData, jdouble height) {
    if (!std::isfinite(height) || std::abs(height) <= kMinDimension || std::abs(height) > kMaxDimension) {
        throwJava(
            env, "java/lang/IllegalArgumentException",
            "nativeExtrudeRegion: height magnitude must be finite and within (" + std::to_string(kMinDimension) + ", " +
                std::to_string(kMaxDimension) + "], got " + std::to_string(height));
        return 0;
    }
    try {
        TopoDS_Face face;
        std::vector<gp_Pnt> samples;
        if (!buildRegionFace(env, loopSizes, segKinds, segData, "nativeExtrudeRegion", face, samples)) {
            return 0;
        }
        BRepPrimAPI_MakePrism prismMaker(face, gp_Vec(0.0, 0.0, height));
        prismMaker.Build();
        if (!prismMaker.IsDone()) {
            throwJava(env, "java/lang/IllegalStateException", "BRepPrimAPI_MakePrism did not complete");
            return 0;
        }
        TopoDS_Shape result(prismMaker.Shape());
        if (!postCheckSolid(env, result, "nativeExtrudeRegion", "java/lang/IllegalArgumentException")) {
            return 0;
        }
        return registerShape(std::move(result));
    } catch (const Standard_Failure& e) {
        throwJava(env, "java/lang/IllegalStateException", std::string("OCCT error extruding region: ") + e.GetMessageString());
    } catch (const std::exception& e) {
        throwJava(env, "java/lang/IllegalStateException", std::string("Native error extruding region: ") + e.what());
    } catch (...) {
        throwJava(env, "java/lang/IllegalStateException", "Unknown native error extruding region");
    }
    return 0;
}

JNIEXPORT jlong JNICALL
Java_dev_kstep_geometry_occt_OcctBridge_nativeRevolveRegion(
    JNIEnv* env, jobject /*self*/, jintArray loopSizes, jintArray segKinds, jdoubleArray segData, jdouble angleDeg,
    jint axis) {
    if (!std::isfinite(angleDeg) || angleDeg < kMinRevolveAngleDeg || angleDeg > kMaxRevolveAngleDeg) {
        throwJava(
            env, "java/lang/IllegalArgumentException",
            "nativeRevolveRegion: angle must be finite and within [" + std::to_string(kMinRevolveAngleDeg) + ", " +
                std::to_string(kMaxRevolveAngleDeg) + "] degrees, got " + std::to_string(angleDeg));
        return 0;
    }
    if (axis != 0 && axis != 1) {
        throwJava(
            env, "java/lang/IllegalArgumentException",
            "nativeRevolveRegion: axis must be 0 (X) or 1 (Y), got " + std::to_string(axis));
        return 0;
    }
    try {
        TopoDS_Face face;
        std::vector<gp_Pnt> samples;
        if (!buildRegionFace(env, loopSizes, segKinds, segData, "nativeRevolveRegion", face, samples)) {
            return 0;
        }
        bool anyPositive = false;
        bool anyNegative = false;
        for (const gp_Pnt& p : samples) {
            double c = axis == 1 ? p.X() : p.Y();
            if (c > 1e-9) anyPositive = true;
            if (c < -1e-9) anyNegative = true;
        }
        if (anyPositive && anyNegative) {
            throwJava(
                env, "java/lang/IllegalArgumentException",
                std::string("nativeRevolveRegion: region crosses the rotation axis (all ") + (axis == 1 ? "x" : "y") +
                    " coordinates must have the same sign)");
            return 0;
        }
        gp_Ax1 rotationAxis(gp::Origin(), axis == 1 ? gp::DY() : gp::DX());
        bool full = std::abs(angleDeg - 360.0) <= 1e-9;
        TopoDS_Shape result;
        if (full) {
            BRepPrimAPI_MakeRevol maker(face, rotationAxis);
            maker.Build();
            if (!maker.IsDone()) {
                throwJava(env, "java/lang/IllegalStateException", "BRepPrimAPI_MakeRevol did not complete");
                return 0;
            }
            result = TopoDS_Shape(maker.Shape());
        } else {
            BRepPrimAPI_MakeRevol maker(face, rotationAxis, angleDeg * M_PI / 180.0);
            maker.Build();
            if (!maker.IsDone()) {
                throwJava(env, "java/lang/IllegalStateException", "BRepPrimAPI_MakeRevol did not complete");
                return 0;
            }
            result = TopoDS_Shape(maker.Shape());
        }
        if (!postCheckSolid(env, result, "nativeRevolveRegion", "java/lang/IllegalArgumentException")) {
            return 0;
        }
        return registerShape(std::move(result));
    } catch (const Standard_Failure& e) {
        throwJava(env, "java/lang/IllegalStateException", std::string("OCCT error revolving region: ") + e.GetMessageString());
    } catch (const std::exception& e) {
        throwJava(env, "java/lang/IllegalStateException", std::string("Native error revolving region: ") + e.what());
    } catch (...) {
        throwJava(env, "java/lang/IllegalStateException", "Unknown native error revolving region");
    }
    return 0;
}

// ------------------------------------------------------------------------------------------
// Welle M (multi-body results). See docs/adr/ADR-0032-sketch-extensions.adoc.
// ------------------------------------------------------------------------------------------
constexpr jsize kMaxCompoundParts = 32;
constexpr jsize kMaxSolidSummaries = 1024;

/**
 * Combines 1..32 registered single-solid shapes into ONE compound of independent copies. The parts must
 * be pairwise separated by more than Precision::Confusion() -- touching or overlapping solids are
 * rejected (IllegalArgumentException), so the compound is a set of disjoint bodies.
 */
JNIEXPORT jlong JNICALL
Java_dev_kstep_geometry_occt_OcctBridge_nativeMakeCompound(JNIEnv* env, jobject /*self*/, jlongArray handles) {
    if (handles == nullptr) {
        throwJava(env, "java/lang/NullPointerException", "nativeMakeCompound: handles must not be null");
        return 0;
    }
    jsize n = env->GetArrayLength(handles);
    if (n < 1 || n > kMaxCompoundParts) {
        throwJava(
            env, "java/lang/IllegalArgumentException",
            "nativeMakeCompound: between 1 and " + std::to_string(kMaxCompoundParts) + " parts are required, got " +
                std::to_string(n));
        return 0;
    }
    std::vector<jlong> ids(static_cast<size_t>(n));
    env->GetLongArrayRegion(handles, 0, n, ids.data());
    if (env->ExceptionCheck()) {
        return 0;
    }
    try {
        std::vector<TopoDS_Shape> copies;
        copies.reserve(static_cast<size_t>(n));
        bool allFound = true;
        jlong missing = 0;
        bool notSingle = false;
        {
            std::lock_guard<std::mutex> lock(g_mutex);
            for (jlong id : ids) {
                TopoDS_Shape* shape = findShapeLocked(id);
                if (shape == nullptr) {
                    allFound = false;
                    missing = id;
                    break;
                }
                if (countSolids(*shape) != 1) {
                    notSingle = true;
                    break;
                }
                // Independent copy: the compound must share no TShape with a registered shape (mesh
                // generation mutates TShapes under g_mutex).
                BRepBuilderAPI_Copy copier(unwrapSingleSolid(*shape), Standard_True, Standard_False);
                copies.push_back(copier.Shape());
            }
        }
        if (!allFound) {
            throwUnknownHandle(env, missing);
            return 0;
        }
        if (notSingle) {
            throwJava(env, "java/lang/IllegalArgumentException", "nativeMakeCompound: every part must be exactly one solid");
            return 0;
        }
        // Pairwise separation: bounding boxes first, exact distance only for boxes that overlap.
        std::vector<Bnd_Box> boxes(copies.size());
        for (size_t i = 0; i < copies.size(); ++i) {
            BRepBndLib::Add(copies[i], boxes[i]);
            boxes[i].SetGap(Precision::Confusion());
        }
        for (size_t i = 0; i < copies.size(); ++i) {
            for (size_t j = i + 1; j < copies.size(); ++j) {
                if (boxes[i].IsOut(boxes[j])) {
                    continue;
                }
                BRepExtrema_DistShapeShape dist(copies[i], copies[j]);
                if (!dist.IsDone() || dist.Value() <= Precision::Confusion()) {
                    throwJava(
                        env, "java/lang/IllegalArgumentException",
                        "nativeMakeCompound: solids " + std::to_string(i) + " and " + std::to_string(j) +
                            " touch or overlap");
                    return 0;
                }
            }
        }
        BRep_Builder builder;
        TopoDS_Compound compound;
        builder.MakeCompound(compound);
        for (const TopoDS_Shape& part : copies) {
            builder.Add(compound, part);
        }
        TopoDS_Shape result(compound);
        if (countSolids(result) != static_cast<int>(n)) {
            throwJava(env, "java/lang/IllegalStateException", "nativeMakeCompound: unexpected solid count");
            return 0;
        }
        if (!postCheckSolid(env, result, "nativeMakeCompound", "java/lang/IllegalArgumentException")) {
            return 0;
        }
        return registerShape(std::move(result));
    } catch (const Standard_Failure& e) {
        throwJava(env, "java/lang/IllegalStateException", std::string("OCCT error building compound: ") + e.GetMessageString());
    } catch (const std::exception& e) {
        throwJava(env, "java/lang/IllegalStateException", std::string("Native error building compound: ") + e.what());
    } catch (...) {
        throwJava(env, "java/lang/IllegalStateException", "Unknown native error building compound");
    }
    return 0;
}

/** Per-solid summary of a shape: [volume, faceCount] for each solid, at most 1024 solids. */
JNIEXPORT jdoubleArray JNICALL
Java_dev_kstep_geometry_occt_OcctBridge_nativeSolidSummaries(JNIEnv* env, jobject /*self*/, jlong handle) {
    std::vector<jdouble> out;
    bool found = true;
    bool tooMany = false;
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        TopoDS_Shape* shape = findShapeLocked(handle);
        if (shape == nullptr) {
            found = false;
        } else if (countSolids(*shape) > kMaxSolidSummaries) {
            tooMany = true;
        } else {
            for (TopExp_Explorer ex(*shape, TopAbs_SOLID); ex.More(); ex.Next()) {
                GProp_GProps props;
                BRepGProp::VolumeProperties(ex.Current(), props);
                TopTools_IndexedMapOfShape faces;
                TopExp::MapShapes(ex.Current(), TopAbs_FACE, faces);
                out.push_back(props.Mass());
                out.push_back(static_cast<jdouble>(faces.Extent()));
            }
        }
    } catch (const Standard_Failure& e) {
        throwJava(env, "java/lang/IllegalStateException", std::string("OCCT error summarising solids: ") + e.GetMessageString());
        return nullptr;
    } catch (const std::exception& e) {
        throwJava(env, "java/lang/IllegalStateException", std::string("Native error summarising solids: ") + e.what());
        return nullptr;
    } catch (...) {
        throwJava(env, "java/lang/IllegalStateException", "Unknown native error summarising solids");
        return nullptr;
    }
    if (!found) {
        throwUnknownHandle(env, handle);
        return nullptr;
    }
    if (tooMany) {
        throwJava(env, "java/lang/IllegalArgumentException", "nativeSolidSummaries: more than 1024 solids");
        return nullptr;
    }
    jdoubleArray result = env->NewDoubleArray(static_cast<jsize>(out.size()));
    if (result == nullptr) return nullptr;
    env->SetDoubleArrayRegion(result, 0, static_cast<jsize>(out.size()), out.data());
    return result;
}

}  // extern "C"

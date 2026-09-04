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
#include <mutex>
#include <sstream>
#include <string>
#include <unordered_map>

#include <BRepBndLib.hxx>
#include <BRepBuilderAPI_MakeFace.hxx>
#include <BRepBuilderAPI_MakePolygon.hxx>
#include <BRepCheck_Analyzer.hxx>
#include <BRepFilletAPI_MakeFillet.hxx>
#include <BRepGProp.hxx>
#include <BRepLib_ToolTriangulatedShape.hxx>
#include <BRepMesh_IncrementalMesh.hxx>
#include <BRepPrimAPI_MakeBox.hxx>
#include <BRepPrimAPI_MakePrism.hxx>
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
#include <STEPControl_Writer.hxx>
#include <Standard_Failure.hxx>
#include <Standard_Version.hxx>
#include <TopAbs_ShapeEnum.hxx>
#include <TopExp.hxx>
#include <TopLoc_Location.hxx>
#include <TopTools_IndexedMapOfShape.hxx>
#include <TopoDS.hxx>
#include <TopoDS_Edge.hxx>
#include <TopoDS_Face.hxx>
#include <TopoDS_Shape.hxx>
#include <TopoDS_Wire.hxx>
#include <gp_Pnt.hxx>
#include <gp_Trsf.hxx>
#include <gp_Vec.hxx>
#include <gp_Vec3f.hxx>

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

}  // namespace

extern "C" {

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
                schemaAccepted = Interface_Static::SetCVal("write.step.schema", schemaValue.c_str());
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
    // GetArrayLength(env, nullptr) is undefined behavior -- OcctBridge is public precisely so
    // out-of-module callers (including test code, via reflection) can reach this method directly
    // with hostile input that OcctKernel.extrudeProfile's own Kotlin-side validation would never
    // let through -- see this method's KDoc in OcctBridge.kt and nativeWriteStep's identical null
    // check above for the same rationale.
    if (profileXy == nullptr) {
        throwJava(env, "java/lang/NullPointerException", "nativeExtrudeProfile: profileXy must not be null");
        return 0;
    }

    // Length checked BEFORE any GetDoubleArrayElements call, per this file's own convention.
    jsize length = env->GetArrayLength(profileXy);
    if (length % 2 != 0) {
        throwJava(
            env,
            "java/lang/IllegalArgumentException",
            "nativeExtrudeProfile: profileXy length must be even (x,y pairs), got " + std::to_string(length));
        return 0;
    }
    jsize pointCount = length / 2;
    if (pointCount < kMinProfilePoints || pointCount > kMaxProfilePoints) {
        throwJava(
            env,
            "java/lang/IllegalArgumentException",
            "nativeExtrudeProfile: profile must have between " + std::to_string(kMinProfilePoints) + " and " +
                std::to_string(kMaxProfilePoints) + " points, got " + std::to_string(pointCount));
        return 0;
    }

    DoubleArrayGuard guard(env, profileXy);
    const jdouble* xy = guard.elements();
    if (xy == nullptr) {
        return 0;  // OutOfMemoryError already pending
    }

    for (jsize i = 0; i < pointCount; ++i) {
        double x = xy[i * 2];
        double y = xy[i * 2 + 1];
        if (!std::isfinite(x) || !std::isfinite(y) || std::abs(x) > kMaxAbsCoordinate ||
            std::abs(y) > kMaxAbsCoordinate) {
            throwJava(
                env,
                "java/lang/IllegalArgumentException",
                "nativeExtrudeProfile: profile[" + std::to_string(i) + "] must be finite and within +/-" +
                    std::to_string(kMaxAbsCoordinate) + ", got (" + std::to_string(x) + ", " + std::to_string(y) +
                    ")");
            return 0;
        }
    }
    if (!std::isfinite(height) || std::abs(height) <= kMinDimension || std::abs(height) > kMaxDimension) {
        throwJava(
            env,
            "java/lang/IllegalArgumentException",
            "nativeExtrudeProfile: height magnitude must be finite and within (" + std::to_string(kMinDimension) +
                ", " + std::to_string(kMaxDimension) + "], got " + std::to_string(height));
        return 0;
    }
    // MakePolygon silently drops a consecutive point within Precision::Confusion() (1e-7) of its
    // predecessor -- not just an exact bit-for-bit duplicate (measured against OCCT 7.9.2: a
    // 4-point profile with one duplicate quietly became a triangle, with no error at all; the same
    // is true for a "duplicate" that is merely 1e-8 apart, well inside OCCT's own confusion
    // tolerance) -- reject that up front, using the SAME tolerance OCCT itself applies internally,
    // rather than let a caller's profile be silently reinterpreted into a different polygon.
    // Ordinary subtraction already treats -0.0 and 0.0 as equal (their difference is exactly 0.0),
    // so that case is still caught here as one instance of the general distance check, with no
    // special-casing needed.
    const double confusionSquared = Precision::SquareConfusion();
    for (jsize i = 0; i < pointCount; ++i) {
        jsize next = (i + 1) % pointCount;
        double dx = xy[i * 2] - xy[next * 2];
        double dy = xy[i * 2 + 1] - xy[next * 2 + 1];
        if (dx * dx + dy * dy <= confusionSquared) {
            throwJava(
                env,
                "java/lang/IllegalArgumentException",
                "nativeExtrudeProfile: profile has coincident (or near-coincident, within " +
                    std::to_string(Precision::Confusion()) + ") consecutive points at index " + std::to_string(i));
            return 0;
        }
    }

    try {
        BRepBuilderAPI_MakePolygon polygonMaker;
        for (jsize i = 0; i < pointCount; ++i) {
            polygonMaker.Add(gp_Pnt(xy[i * 2], xy[i * 2 + 1], 0.0));
        }
        polygonMaker.Close();
        if (!polygonMaker.IsDone()) {
            throwJava(env, "java/lang/IllegalArgumentException", "nativeExtrudeProfile: failed to close polygon");
            return 0;
        }
        TopoDS_Wire wire = polygonMaker.Wire();

        BRepBuilderAPI_MakeFace faceMaker(wire, Standard_True);
        if (!faceMaker.IsDone()) {
            throwJava(
                env,
                "java/lang/IllegalArgumentException",
                "nativeExtrudeProfile: failed to build a planar face from the profile (BRepBuilderAPI_FaceError=" +
                    std::to_string(static_cast<int>(faceMaker.Error())) + ") -- profile may be non-planar or self-intersecting");
            return 0;
        }
        TopoDS_Face face = faceMaker.Face();

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

JNIEXPORT jlong JNICALL
Java_dev_kstep_geometry_occt_OcctBridge_nativeFilletEdges(
    JNIEnv* env, jobject /*self*/, jlong handle, jintArray edgeIndices, jdouble radius) {
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
        BRepCheck_Analyzer analyzer(result);
        if (!analyzer.IsValid()) {
            throwJava(
                env, "java/lang/IllegalStateException", "nativeFilletEdges: resulting solid failed OCCT's own validity check");
            return 0;
        }
        GProp_GProps props;
        BRepGProp::VolumeProperties(result, props);
        if (!(props.Mass() > 0.0)) {
            throwJava(env, "java/lang/IllegalStateException", "nativeFilletEdges: resulting solid has zero volume");
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

}  // extern "C"

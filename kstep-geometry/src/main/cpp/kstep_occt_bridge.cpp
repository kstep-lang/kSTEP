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

#include <mutex>
#include <sstream>
#include <string>
#include <unordered_map>

#include <BRepGProp.hxx>
#include <BRepPrimAPI_MakeBox.hxx>
#include <GProp_GProps.hxx>
#include <IFSelect_ReturnStatus.hxx>
#include <Interface_Static.hxx>
#include <STEPControl_StepModelType.hxx>
#include <STEPControl_Writer.hxx>
#include <Standard_Failure.hxx>
#include <Standard_Version.hxx>
#include <TopAbs_ShapeEnum.hxx>
#include <TopExp.hxx>
#include <TopTools_IndexedMapOfShape.hxx>
#include <TopoDS_Shape.hxx>

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

}  // extern "C"

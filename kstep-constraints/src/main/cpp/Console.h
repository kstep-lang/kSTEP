// kSTEP-authored replacement for planegcs/headers/Console.h, whose upstream version is
// Emscripten-only (#include <emscripten.h>, EM_JS) and cannot compile natively -- see
// third_party/planegcs/PROVENANCE.adoc for why it was excluded from vendoring.
//
// PlaneGCS calls Console::Log(str.c_str()) with a RUNTIME string in the format-string position in
// several places (see third_party/planegcs/GCS.cpp:306, 2090, 2105, 2117, 2154, 2217, 2336, 2389,
// 2546, 2560 -- verified by grep against the vendored source) -- passing that straight to any
// vsnprintf/vsprintf-family function, the way upstream's own Emscripten Console::Log does
// (`vsprintf(buffer, format, args)`), is a textbook format-string vulnerability: a solver
// diagnostic string that happens to contain "%s"/"%n" would then be interpreted as format
// directives against whatever varargs happen to be on the stack. This shim therefore NEVER treats
// its `format` argument as an actual format string -- it does not call any printf-family function
// at all, so the runtime-string-in-format-position callers above are made harmless by construction,
// not merely "usually safe in practice".
//
// The bridge (kstep_planegcs_bridge.cpp) also always sets `GCS::System::debugMode = GCS::NoDebug`
// before solving, so in practice none of these Log() calls are even reached -- this shim is a
// defense-in-depth backstop, not the only thing standing between untrusted input and a crash.
//
// #include <Console.h> (angle brackets, matching third_party/planegcs/GCS.cpp:99) resolves to THIS
// file only because kstep-constraints/build.gradle.kts puts `src/main/cpp` on the compiler's -I
// search path BEFORE third_party/planegcs's own include path -- see that build file's comment on
// include-path ordering.
#ifndef KSTEP_CONSTRAINTS_CONSOLE_H
#define KSTEP_CONSTRAINTS_CONSOLE_H

class Console {
public:
    // Intentionally variadic to match the call sites' signature (some pass extra arguments, e.g.
    // Console::Log("...%d...", someInt)), but the arguments are never read or formatted -- this is
    // a silent no-op, by design (see file header above).
    static void Log(const char* /*format*/, ...) {}
};

#endif  // KSTEP_CONSTRAINTS_CONSOLE_H

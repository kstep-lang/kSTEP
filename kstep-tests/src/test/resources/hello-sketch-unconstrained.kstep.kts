// hello-sketch-unconstrained.kstep.kts -- negative case (ADR-0031): the rectangle's corner is not fixed,
// so the sketch keeps two degrees of freedom and `solve()` fails with KSTEP-S-010 (UNDER_CONSTRAINED).
// This fails in plain Kotlin before the solver is called, so it needs neither PlaneGCS nor OCCT.
val solved = sketch { rectangle(0.0, 0.0, 40.0, 20.0, fixCorner = false) }.solve()

stepFile(fileName = "never-written.step") { }

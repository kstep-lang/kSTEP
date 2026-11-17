kSTEP native runtime archive (opt-in, host platform only)
==========================================================

This archive contains the kSTEP CLI plus the two JNI bridge libraries under
lib/native/<platform>/ (libkstep_occt_bridge, libkstep_planegcs_bridge). bin/kstep points the JVM
at them. It requires a JDK 21+ on PATH or JAVA_HOME.

IMPORTANT: OpenCascade (OCCT) is NOT bundled. The OCCT bridge was linked against the OCCT of the
machine that built the archive, with that library directory baked in as an absolute rpath. The
archive therefore only works on a machine that has OCCT 7.9.x installed at the same location
(Ubuntu: /usr/lib/x86_64-linux-gnu; macOS Homebrew: /opt/homebrew/opt/opencascade/lib). It is not
relocatable across different OCCT installs. Without OCCT the CLI still runs, but geometry features
report "unavailable" and a hint is printed.

  Ubuntu: sudo apt install libocct-data-exchange-dev libocct-modeling-algorithms-dev
  macOS:  brew install opencascade

macOS: a downloaded, unsigned dylib may be blocked by Gatekeeper (xattr -d com.apple.quarantine).

Licensing: OCCT is LGPL 2.1 with an exception and is linked dynamically, not redistributed here.
The PlaneGCS sources are vendored and compiled into libkstep_planegcs_bridge; see NOTICE.

Check the installation with:  bin/kstep --version

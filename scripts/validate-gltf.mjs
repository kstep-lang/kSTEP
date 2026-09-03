#!/usr/bin/env node
// validate-gltf.mjs -- runs a kstep-cli `render -f glb` output through the official Khronos
// gltf-validator and reports the result as JSON. Dev-only tooling: never invoked from a Gradle
// task (see docs/adr/ADR-0016-gltf-glb-export.adoc's "no network access in the build" security
// row), and node_modules/ here is gitignored.
//
// Usage:
//   node validate-gltf.mjs <path-to.glb> [--expect-triangles N] [--expect-vertices M]
//
// Exit code 0 only if the validator reports zero errors AND zero warnings, and (when given)
// info.totalTriangleCount/totalVertexCount match --expect-triangles/--expect-vertices exactly.
// Exit code 1 otherwise (including "file not found" and "node_modules missing" -- this script is
// meant to be a hard gate, not a silent skip; see the ADR's Stolperfalle 12).

import fs from "fs";
import { validateBytes } from "gltf-validator";

function parseArgs(argv) {
  const args = { path: null, expectTriangles: null, expectVertices: null };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === "--expect-triangles") {
      args.expectTriangles = Number.parseInt(argv[++i], 10);
    } else if (arg === "--expect-vertices") {
      args.expectVertices = Number.parseInt(argv[++i], 10);
    } else if (!arg.startsWith("--")) {
      args.path = arg;
    } else {
      console.error(`unknown argument: ${arg}`);
      process.exit(1);
    }
  }
  if (!args.path) {
    console.error("usage: node validate-gltf.mjs <path-to.glb> [--expect-triangles N] [--expect-vertices M]");
    process.exit(1);
  }
  return args;
}

async function main() {
  const args = parseArgs(process.argv.slice(2));

  if (!fs.existsSync(args.path)) {
    console.error(`file not found: ${args.path}`);
    process.exit(1);
  }
  const bytes = fs.readFileSync(args.path);

  const report = await validateBytes(new Uint8Array(bytes));
  console.log(JSON.stringify(report, null, 2));

  const errors = report.issues.numErrors;
  const warnings = report.issues.numWarnings;
  let ok = errors === 0 && warnings === 0;
  if (!ok) {
    console.error(`FAIL: ${errors} error(s), ${warnings} warning(s) -- see the report above.`);
  }

  if (args.expectTriangles !== null) {
    const actual = report.info?.totalTriangleCount;
    if (actual !== args.expectTriangles) {
      console.error(`FAIL: expected totalTriangleCount=${args.expectTriangles}, got ${actual}`);
      ok = false;
    }
  }
  if (args.expectVertices !== null) {
    const actual = report.info?.totalVertexCount;
    if (actual !== args.expectVertices) {
      console.error(`FAIL: expected totalVertexCount=${args.expectVertices}, got ${actual}`);
      ok = false;
    }
  }

  process.exit(ok ? 0 : 1);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});

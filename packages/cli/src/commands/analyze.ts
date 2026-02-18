import fs from "node:fs";
import path from "node:path";
import { run, runInherit } from "../lib/proc.js";
import { ensureDir } from "../lib/fs.js";
import { assetPath } from "../lib/assets.js";

export async function analyzeCmd(repoPath: string) {
  const repoRoot = path.resolve(repoPath);
  const outDir = path.join(repoRoot, ".xray");
  ensureDir(outDir);

  const java = await run("java", ["-version"]);
  if (java.code !== 0) {
    console.error("Java runtime not found. Run: xray doctor");
    process.exit(1);
  }

  const jar = assetPath("xray-engine.jar");
  if (!fs.existsSync(jar)) {
    console.error("Engine jar not found:", jar);
    console.error("Once engine-java exists, run: npm run bundle -w xray");
    process.exit(1);
  }

  console.log("Analyzing repo:", repoRoot);
  console.log("Output dir:", outDir);

  const code = await runInherit("java", ["-jar", jar, "--input", repoRoot, "--out", outDir]);
  if (code !== 0) {
    console.error(`Analyzer failed (exit code ${code}).`);
    process.exit(code);
  }

  console.log("Done. Artifacts in:", outDir);
}

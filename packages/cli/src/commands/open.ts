import fs from "node:fs";
import path from "node:path";
import { spawn } from "node:child_process";
import open from "open";
import { assetPath } from "../lib/assets.js";
import { getFreePort } from "../lib/ports.js";

export async function openCmd(repoPath: string) {
  const repoRoot = path.resolve(repoPath);

  const serverFile = assetPath("server", "server.cjs");
  const uiDir = assetPath("ui");

  if (!fs.existsSync(serverFile)) {
    console.error("Server bundle not found:", serverFile);
    console.error("Run: npm run bundle -w xray");
    process.exit(1);
  }
  if (!fs.existsSync(path.join(uiDir, "index.html"))) {
    console.error("UI assets not found:", uiDir);
    console.error("Run: npm run bundle -w xray");
    process.exit(1);
  }

  const port = await getFreePort();

  const child = spawn(process.execPath, [serverFile], {
    stdio: ["ignore", "pipe", "pipe"],
    env: {
      ...process.env,
      XRAY_PORT: String(port),
      XRAY_UI_DIR: uiDir,
      XRAY_REPO_ROOT: repoRoot
    }
  });

  child.stdout.on("data", (d) => process.stdout.write(d));
  child.stderr.on("data", (d) => process.stderr.write(d));

  const url = `http://127.0.0.1:${port}`;
  await open(url);

  child.on("exit", (code) => process.exit(code ?? 0));
}

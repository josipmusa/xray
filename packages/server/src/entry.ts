import { startServer } from "./server";


const port = Number(process.env.XRAY_PORT || "4173");
const uiDir = process.env.XRAY_UI_DIR!;
const repoRoot = process.env.XRAY_REPO_ROOT!;

if (!uiDir || !repoRoot) {
    console.error("Missing XRAY_UI_DIR or XRAY_REPO_ROOT");
    process.exit(1);
}

startServer({ port, uiDir, repoRoot });
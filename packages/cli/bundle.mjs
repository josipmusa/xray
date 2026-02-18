import path from "node:path";
import fs from "node:fs";
import { fileURLToPath } from "node:url";


const __dirname = path.dirname(fileURLToPath(import.meta.url));
// packages/cli
const cliDir = __dirname;
// repo root
const rootDir = path.resolve(cliDir, "..", "..");

const assetsDir = path.join(cliDir, "assets");
const assetsServerDir = path.join(assetsDir, "server");
const assetsUiDir = path.join(assetsDir, "ui");

const engineJarSrc = path.join(rootDir, "engine-java", "target", "xray-engine.jar");
const serverSrc = path.join(rootDir, "packages", "server", "dist", "server.cjs");
const uiDistSrc = path.join(rootDir, "packages", "ui", "dist");

function ensureDir(p) {
    fs.mkdirSync(p, { recursive: true });
}

function copyFileOrThrow(src, dst) {
    if (!fs.existsSync(src)) {
        throw new Error(`Missing file: ${src}`);
    }
    ensureDir(path.dirname(dst));
    fs.copyFileSync(src, dst);
}

function copyDirOrThrow(srcDir, dstDir) {
    if (!fs.existsSync(srcDir)) {
        throw new Error(`Missing directory: ${srcDir}`);
    }
    fs.rmSync(dstDir, { recursive: true, force: true });
    ensureDir(dstDir);

    for (const entry of fs.readdirSync(srcDir, { withFileTypes: true })) {
        const src = path.join(srcDir, entry.name);
        const dst = path.join(dstDir, entry.name);
        if (entry.isDirectory()) copyDirOrThrow(src, dst);
        else copyFileOrThrow(src, dst);
    }
}

function main() {
    fs.rmSync(assetsDir, { recursive: true, force: true });
    ensureDir(assetsDir);

    copyFileOrThrow(engineJarSrc, path.join(assetsDir, "xray-engine.jar"));

    copyFileOrThrow(serverSrc, path.join(assetsServerDir, "server.cjs"));

    copyDirOrThrow(uiDistSrc, assetsUiDir);

    console.log("Bundled assets into:", assetsDir);
    console.log(" -", path.join(assetsDir, "xray-engine.jar"));
    console.log(" -", path.join(assetsServerDir, "server.cjs"));
    console.log(" -", assetsUiDir);
}

try {
    main();
} catch(e) {
    console.error(String(e?.stack || e));
    process.exit(1);
}
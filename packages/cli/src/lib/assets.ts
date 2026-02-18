import path from "node:path";

export function assetPath(...parts: string[]) {
  const exe = path.resolve(process.argv[1]);        // .../dist/index.cjs
  const pkgRoot = path.dirname(path.dirname(exe));  // .../packages/cli
  return path.join(pkgRoot, "assets", ...parts);
}

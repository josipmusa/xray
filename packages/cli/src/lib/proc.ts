import { spawn } from "node:child_process";

export type RunResult = { code: number; stdout: string; stderr: string };

export function run(cmd: string, args: string[]): Promise<RunResult> {
  return new Promise((resolve) => {
    const p = spawn(cmd, args, { stdio: ["ignore", "pipe", "pipe"] });
    let stdout = "";
    let stderr = "";
    p.stdout.on("data", (d) => (stdout += d.toString()));
    p.stderr.on("data", (d) => (stderr += d.toString()));
    p.on("close", (code) => resolve({ code: code ?? 0, stdout, stderr }));
    p.on("error", () => resolve({ code: 127, stdout, stderr }));
  });
}

export function runInherit(cmd: string, args: string[], env?: NodeJS.ProcessEnv): Promise<number> {
  return new Promise((resolve) => {
    const p = spawn(cmd, args, {
      stdio: "inherit",
      env: env ? { ...process.env, ...env } : process.env
    });
    p.on("close", (code) => resolve(code ?? 0));
    p.on("error", () => resolve(127));
  });
}

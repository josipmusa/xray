import { run } from "../lib/proc.js";

export async function doctorCmd() {
  console.log("xray doctor");
  console.log(`node: ${process.version}`);

  const java = await run("java", ["-version"]);
  if (java.code !== 0) {
    console.error("\nJava runtime not found.");
    console.error("Install Java 17+ and ensure `java` is on PATH.");
    process.exit(1);
  }

  console.log("java:", (java.stderr || java.stdout).trim().split("\n")[0]);
  console.log("\nOK");
}

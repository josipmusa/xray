import { Command } from "commander";
import { doctorCmd } from "./commands/doctor.js";
import { openCmd } from "./commands/open.js";
import { analyzeCmd } from "./commands/analyze.js";

const program = new Command();

program.name("xray").description("Spring Boot codebase X-Ray").version("0.0.1");

program
    .command("doctor")
    .description("Check required runtimes")
    .action(doctorCmd);

program
    .command("open")
    .argument("[path]", "repo path", ".")
    .description("Start local dashboard")
    .action(openCmd);

program
    .command("analyze")
    .argument("[path]", "repo path", ".")
    .description("Analyze project and generate .xray artifacts")
    .action(analyzeCmd);

program.parse();

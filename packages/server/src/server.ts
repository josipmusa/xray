import express from "express";
import path from "path";

export type StartOpts = {
    port: number;
    uiDir: string;
    repoRoot: string;
}

export function startServer(opts: StartOpts) {
    const app = express();

    app.get("/api/meta", (_req, res) => {
        res.json({ ok: true, repoRoot: opts.repoRoot });
    });

    app.use(express.static(opts.uiDir));
    app.get("*", (_req, res) => {
        res.sendFile(path.join(opts.uiDir, "index.html"));
    });

    return app.listen(opts.port, "127.0.0.1", () => {
        console.log(`XRAY_SERVER_URL=http://127.0.0.1:${opts.port}`);
    })
}
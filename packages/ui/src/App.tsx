import { useEffect, useState } from "react";
import "./App.css";

type Meta = {
  ok: boolean;
  repoRoot: string;
};

export default function App() {
  const [meta, setMeta] = useState<Meta | null>(null);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    fetch("/api/meta")
      .then(async (r) => {
        if (!r.ok) throw new Error(`HTTP ${r.status}`);
        return (await r.json()) as Meta;
      })
      .then(setMeta)
      .catch((e) => setErr(String(e)));
  }, []);

  return (
    <div style={{ padding: 24, fontFamily: "system-ui, sans-serif" }}>
      <h1 style={{ margin: 0 }}>X-Ray Dashboard</h1>
      <p style={{ marginTop: 8, opacity: 0.8 }}>
        UI build + bundle smoke test
      </p>

      <div style={{ marginTop: 16 }}>
        <h2 style={{ fontSize: 16 }}>Server /api/meta</h2>
        {err && <pre style={{ whiteSpace: "pre-wrap" }}>{err}</pre>}
        {!err && !meta && <div>Loading…</div>}
        {meta && (
          <pre style={{ whiteSpace: "pre-wrap" }}>
            {JSON.stringify(meta, null, 2)}
          </pre>
        )}
      </div>
    </div>
  );
}

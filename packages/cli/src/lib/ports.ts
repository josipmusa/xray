import net from "node:net";

export async function getFreePort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.listen(0, "127.0.0.1", () => {
      const addr = srv.address();
      srv.close(() => {
        if (typeof addr === "object" && addr?.port) resolve(addr.port);
        else reject(new Error("Could not get free port"));
      });
    });
    srv.on("error", reject);
  });
}

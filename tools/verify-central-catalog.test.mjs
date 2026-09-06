import assert from "node:assert/strict";
import { once } from "node:events";
import { mkdtemp, writeFile } from "node:fs/promises";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";

import { verifyCentralCatalog } from "./verify-central-catalog.mjs";

const coordinate = "org.modeljars.huggingface:example.model:q4.2";

async function fixture(responses) {
  const directory = await mkdtemp(path.join(tmpdir(), "modeljars-central-gate-"));
  const catalog = path.join(directory, "catalog.json");
  await writeFile(catalog, JSON.stringify([{ id: "example", markerCoordinate: coordinate }]));
  const requests = [];
  const server = createServer((request, response) => {
    requests.push(request.url);
    response.writeHead(responses[request.url] ?? 404).end();
  });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  return {
    catalog,
    requests,
    repository: `http://127.0.0.1:${server.address().port}`,
    close: () => server.close(),
  };
}

const pom = "/org/modeljars/huggingface/example.model/q4.2/example.model-q4.2.pom";
const jar = "/org/modeljars/huggingface/example.model/q4.2/example.model-q4.2.jar";

test("accepts a public catalog only when every marker POM and JAR resolves", async (t) => {
  const server = await fixture({ [pom]: 200, [jar]: 200 });
  t.after(server.close);

  await assert.doesNotReject(
    verifyCentralCatalog(server.catalog, { repository: server.repository, attempts: 1 }),
  );
  assert.deepEqual(server.requests, [pom, jar]);
});

test("fails closed when a public marker is absent", async (t) => {
  const server = await fixture({ [pom]: 200 });
  t.after(server.close);

  await assert.rejects(
    verifyCentralCatalog(server.catalog, { repository: server.repository, attempts: 1 }),
    /example JAR.*404/s,
  );
});

test("fails closed when Central cannot be reached", async () => {
  const directory = await mkdtemp(path.join(tmpdir(), "modeljars-central-gate-"));
  const catalog = path.join(directory, "catalog.json");
  await writeFile(catalog, JSON.stringify([{ id: "example", markerCoordinate: coordinate }]));

  await assert.rejects(
    verifyCentralCatalog(catalog, {
      repository: "http://127.0.0.1:1",
      attempts: 1,
    }),
    /example\.model.*unreachable/s,
  );
});

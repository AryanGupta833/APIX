import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { executeLocalRequest, parseTarget, validateJob } from '../apix-agent.mjs';

const grant = port => [{ host: '127.0.0.1', port }];
const job = (port, overrides = {}) => ({
  id: '7af48ae8-3bc4-4ebd-86bb-7de21fd16d44', method: 'GET', url: `http://127.0.0.1:${port}/health`,
  headers: {}, body: null, timeoutMs: 1000, maxResponseBytes: 1_048_576, ...overrides,
});

async function withServer(handler, run) {
  const server = createServer(handler);
  try {
    await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
    await run(server.address().port);
  } finally {
    await new Promise(resolve => server.close(() => resolve()));
  }
}

test('only explicit loopback grants can be configured', () => {
  assert.deepEqual(parseTarget('127.0.0.1:8080'), { host: '127.0.0.1', port: 8080 });
  assert.deepEqual(parseTarget('[::1]:8080'), { host: '::1', port: 8080 });
  assert.throws(() => parseTarget('192.168.1.4:8080'));
  assert.throws(() => parseTarget('localhost:65536'));
});

test('agent validates URL scheme, exact approved port, method, headers, and body before connecting', () => {
  assert.throws(() => validateJob(job(8080, { url: 'file:///etc/passwd' }), grant(8080)));
  assert.throws(() => validateJob(job(8080, { url: 'http://2130706433:8080/health' }), grant(8080)));
  assert.throws(() => validateJob(job(8081), grant(8080)));
  assert.throws(() => validateJob(job(8080, { method: 'CONNECT' }), grant(8080)));
  assert.throws(() => validateJob(job(8080, { headers: { Host: 'attacker.test' } }), grant(8080)));
  assert.throws(() => validateJob(job(8080, { body: 'x'.repeat(1_048_577) }), grant(8080)));
});

test('executes a bounded local HTTP request and returns a structured response', async () => {
  await withServer((request, response) => {
    response.writeHead(200, { 'content-type': 'text/plain' }); response.end(`ok:${request.method}`);
  }, async port => {
    const result = await executeLocalRequest(job(port), grant(port));
    assert.equal(result.statusCode, 200);
    assert.equal(result.body, 'ok:GET');
    assert.equal(result.error, null);
    assert.ok(result.elapsedMs >= 0);
  });
});

test('returns redirects as responses and does not follow them', async () => {
  let secondHit = false;
  const second = createServer((_request, response) => { secondHit = true; response.end('unexpected'); });
  try {
    await new Promise((resolve, reject) => { second.once('error', reject); second.listen(0, '127.0.0.1', resolve); });
    await withServer((_request, response) => {
      response.writeHead(302, { location: `http://127.0.0.1:${second.address().port}/secret` }); response.end('redirect');
    }, async port => {
      const result = await executeLocalRequest(job(port), grant(port));
      assert.equal(result.statusCode, 302);
      assert.equal(result.body, 'redirect');
      assert.equal(secondHit, false);
    });
  } finally { await new Promise(resolve => second.close(() => resolve())); }
});

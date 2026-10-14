#!/usr/bin/env node
import { request as httpRequest } from 'node:http';
import { request as httpsRequest } from 'node:https';
import { lookup } from 'node:dns';
import { isIP } from 'node:net';
import { dirname, join, resolve } from 'node:path';
import { homedir } from 'node:os';
import { mkdir, readFile, writeFile, chmod } from 'node:fs/promises';
import { createInterface } from 'node:readline';
import { fileURLToPath } from 'node:url';

const MAX_BODY_BYTES = 1_048_576;
const MAX_RESPONSE_BYTES = 1_048_576;
const MAX_TIMEOUT_MS = 30_000;
const MAX_HEADERS = 50;
const POLL_MS = 20_000;
const methods = new Set(['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS']);
const forbiddenHeaders = new Set(['host', 'connection', 'content-length', 'transfer-encoding', 'upgrade', 'proxy-authorization', 'proxy-connection', 'te', 'trailer']);

function argumentsMap(args) {
  const result = new Map();
  for (const arg of args) {
    const match = /^--([a-z-]+)=(.*)$/.exec(arg);
    if (!match) throw new Error('Expected --name=value options');
    if (match[1] === 'allow') {
      const current = result.get('allow') ?? [];
      current.push(match[2]); result.set('allow', current);
    } else result.set(match[1], match[2]);
  }
  return result;
}

export function parseTarget(value) {
  const match = /^(localhost|127\.0\.0\.1|\[::1\]):([1-9][0-9]{0,4})$/i.exec(value);
  if (!match || Number(match[2]) > 65535) throw new Error(`Invalid --allow target: ${value}`);
  return { host: match[1].replace(/^\[|\]$/g, '').toLowerCase(), port: Number(match[2]) };
}

function sameTargets(left, right) {
  const key = target => `${target.host.toLowerCase()}:${target.port}`;
  return left.length === right.length && [...left].map(key).sort().join('|') === [...right].map(key).sort().join('|');
}

function configPath() {
  const root = process.env.APPDATA || join(homedir(), '.config');
  return join(root, 'apix-local-agent', 'agent.json');
}

async function loadConfig(path) {
  try { return JSON.parse(await readFile(path, 'utf8')); }
  catch (error) { if (error.code === 'ENOENT') return null; throw new Error('Agent credential file is invalid or unreadable'); }
}

async function saveConfig(path, config) {
  await mkdir(dirname(path), { recursive: true, mode: 0o700 });
  await writeFile(path, `${JSON.stringify(config)}\n`, { encoding: 'utf8', mode: 0o600, flag: 'wx' });
  try { await chmod(path, 0o600); } catch { /* Windows relies on the current user's profile ACL. */ }
}

function readHidden(prompt) {
  return new Promise((resolve, reject) => {
    const input = process.stdin;
    if (!input.isTTY) {
      const rl = createInterface({ input, terminal: false });
      rl.once('line', line => { rl.close(); resolve(line.trim()); });
      rl.once('error', reject);
      return;
    }
    process.stdout.write(prompt);
    input.setRawMode(true); input.resume(); input.setEncoding('utf8');
    let value = '';
    const cleanup = () => { input.setRawMode(false); input.pause(); input.removeListener('data', onData); };
    const onData = chunk => {
      for (const char of chunk) {
        if (char === '\u0003') { cleanup(); process.stdout.write('\n'); reject(new Error('Pairing cancelled')); return; }
        if (char === '\r' || char === '\n') { cleanup(); process.stdout.write('\n'); resolve(value.trim()); return; }
        if (char === '\u007f' || char === '\b') value = value.slice(0, -1);
        else if (char >= ' ' && char <= '~' && value.length < 100) value += char;
      }
    };
    input.on('data', onData);
  });
}

async function apiFetch(base, path, { credential, method = 'GET', body, timeoutMs = 10_000 } = {}) {
  const url = new URL(path, base);
  if (url.origin !== base.origin || url.username || url.password) throw new Error('Unsafe APiX server URL');
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(url, {
      method, redirect: 'error', signal: controller.signal,
      headers: { ...(credential ? { 'X-APiX-Agent-Credential': credential } : {}), ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
    if (!response.ok) { const error = new Error(`APiX returned HTTP ${response.status}`); error.status = response.status; throw error; }
    if (response.status === 204) return null;
    return response.json();
  } finally { clearTimeout(timer); }
}

function loopback(address) {
  const family = isIP(address);
  if (family === 4) return Number(address.split('.')[0]) === 127;
  return family === 6 && address.toLowerCase().split('%')[0] === '::1';
}

function guardedLookup(expectedHost) {
  return (host, options, callback) => {
    if (host.toLowerCase() !== expectedHost) return callback(new Error('Unexpected DNS lookup host'));
    lookup(host, { all: true, verbatim: true }, (error, answers) => {
      if (error || !answers?.length || answers.some(answer => !loopback(answer.address))) return callback(new Error('Target DNS answers are not loopback-only'));
      const answer = answers[0];
      if (options?.all) callback(null, [answer]);
      else callback(null, answer.address, answer.family);
    });
  };
}

export function validateJob(job, grants) {
  if (!job || typeof job.id !== 'string' || !/^[0-9a-f-]{36}$/i.test(job.id)) throw new Error('Malformed job');
  if (!methods.has(job.method) || typeof job.url !== 'string' || job.url.length > 4000) throw new Error('Unsupported request');
  const authority = /^https?:\/\/([^/?#]*)/i.exec(job.url)?.[1];
  if (!authority || authority.includes('@') || authority.includes('\\') || authority.includes('%')) throw new Error('Ambiguous target URL');
  const url = new URL(job.url);
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.hash) throw new Error('Target URL is not permitted');
  const host = url.hostname.replace(/^\[|\]$/g, '').toLowerCase();
  const rawHost = authority.startsWith('[') ? authority.slice(1, authority.indexOf(']')).toLowerCase()
    : authority.replace(/:\d+$/, '').toLowerCase();
  if (rawHost !== host) throw new Error('Ambiguous target hostname');
  if (/^(?:0x[0-9a-f]+|[0-9]+)(?:\.(?:0x[0-9a-f]+|[0-9]+)){0,3}$/i.test(rawHost)
      && !/^127\.0\.0\.1$/.test(rawHost)) throw new Error('Ambiguous numeric hostname');
  const port = Number(url.port || (url.protocol === 'https:' ? 443 : 80));
  if (!grants.some(grant => grant.host === host && grant.port === port)) throw new Error('Target host and port are not approved');
  if (!['localhost', '127.0.0.1', '::1'].includes(host)) throw new Error('Only loopback destinations are supported');
  if (job.body !== null && job.body !== undefined && (typeof job.body !== 'string' || Buffer.byteLength(job.body) > MAX_BODY_BYTES)) throw new Error('Request body exceeds limit');
  if (!Number.isInteger(job.timeoutMs) || job.timeoutMs < 100 || job.timeoutMs > MAX_TIMEOUT_MS) throw new Error('Request timeout exceeds limit');
  if (job.maxResponseBytes !== MAX_RESPONSE_BYTES) throw new Error('Unsupported response limit');
  const headers = job.headers ?? {};
  if (typeof headers !== 'object' || Array.isArray(headers) || Object.keys(headers).length > MAX_HEADERS) throw new Error('Invalid request headers');
  for (const [name, value] of Object.entries(headers)) {
    if (!/^[!#$%&'*+.^_`|~0-9A-Za-z-]+$/.test(name) || forbiddenHeaders.has(name.toLowerCase())
        || typeof value !== 'string' || Buffer.byteLength(value) > 8192 || /[\r\n]/.test(value)) throw new Error('Invalid request headers');
  }
  return { url, host, port, headers };
}

export function executeLocalRequest(job, grants) {
  return new Promise(resolve => {
    const started = Date.now();
    let parsed;
    try { parsed = validateJob(job, grants); }
    catch (error) { resolve({ statusCode: 0, headers: {}, body: '', elapsedMs: 0, error: error.message }); return; }
    const options = {
      protocol: parsed.url.protocol, hostname: parsed.host, port: parsed.port,
      path: `${parsed.url.pathname}${parsed.url.search}`, method: job.method,
      headers: parsed.headers, lookup: guardedLookup(parsed.host), maxHeaderSize: 16 * 1024,
      ...(parsed.url.protocol === 'https:' && !isIP(parsed.host) ? { servername: parsed.host } : {}),
    };
    const sender = parsed.url.protocol === 'https:' ? httpsRequest : httpRequest;
    let settled = false;
    const finish = result => { if (settled) return; settled = true; resolve({ ...result, elapsedMs: Date.now() - started }); };
    let request;
    try {
      request = sender(options, response => {
        const headers = {};
        const keys = Object.keys(response.headers);
        if (keys.length > 100) { response.destroy(); finish({ statusCode: 0, headers: {}, body: '', error: 'Response header limit exceeded' }); return; }
        for (const name of keys) {
          const value = response.headers[name];
          if (value !== undefined) headers[name] = Array.isArray(value) ? value.join(', ') : String(value).slice(0, 8192);
        }
        const chunks = []; let size = 0;
        response.on('data', chunk => {
          size += chunk.length;
          if (size > MAX_RESPONSE_BYTES) { response.destroy(); finish({ statusCode: 0, headers: {}, body: '', error: 'Response body limit exceeded' }); }
          else chunks.push(chunk);
        });
        response.on('end', () => finish({ statusCode: response.statusCode ?? 0, headers, body: Buffer.concat(chunks).toString('utf8'), error: null }));
        response.on('error', () => finish({ statusCode: 0, headers: {}, body: '', error: 'Local request failed' }));
      });
      request.setTimeout(job.timeoutMs, () => request.destroy(new Error('timeout')));
      request.on('error', () => finish({ statusCode: 0, headers: {}, body: '', error: 'Local request failed or timed out' }));
      if (job.body) request.write(job.body);
      request.end();
    } catch { finish({ statusCode: 0, headers: {}, body: '', error: 'Local request failed' }); }
  });
}

async function run() {
  const args = argumentsMap(process.argv.slice(2));
  const server = args.get('server');
  if (!server) throw new Error('Pass --server=https://your-apix-host');
  const base = new URL(server);
  if (!['https:', 'http:'].includes(base.protocol) || base.username || base.password || base.search || base.hash)
    throw new Error('Server must be an absolute HTTP(S) origin without credentials, query, or fragment');
  if (base.protocol === 'http:' && !['localhost', '127.0.0.1', '[::1]'].includes(base.hostname)) throw new Error('Use HTTPS for remote APiX servers');
  const configuredTargets = (args.get('allow') ?? []).map(parseTarget);
  if (!configuredTargets.length || new Set(configuredTargets.map(x => `${x.host}:${x.port}`)).size !== configuredTargets.length)
    throw new Error('Specify unique explicitly approved loopback targets with --allow=localhost:PORT');
  const file = configPath();
  let config = await loadConfig(file);
  if (config && (config.server !== base.origin || !sameTargets(config.allowedTargets ?? [], configuredTargets)))
    throw new Error('Saved agent scope differs; choose a new config path to pair a separate agent');
  if (!config) {
    const pairingCode = await readHidden('Enter the one-time APiX pairing code: ');
    const paired = await apiFetch(base, '/apix/agents/pair', { method: 'POST', body: { pairingCode } });
    if (!sameTargets(paired.allowedTargets ?? [], configuredTargets)) throw new Error('Agent local allowlist must exactly match the APiX-approved grant');
    config = { server: base.origin, agentId: paired.agentId, credential: paired.credential, allowedTargets: paired.allowedTargets };
    await saveConfig(file, config);
    console.log(`Paired local agent ${config.agentId}. Credential saved to the restricted agent config file.`);
  }
  const targets = config.allowedTargets;
  let retry = 500;
  let running = true;
  const stop = () => { running = false; console.log('Stopping after the current poll/request.'); };
  process.once('SIGINT', stop); process.once('SIGTERM', stop);
  console.log(`APiX local agent ${config.agentId} running with ${targets.length} explicit loopback grant(s). Press Ctrl+C to stop.`);
  while (running) {
    try {
      const job = await apiFetch(base, `/apix/agents/channel/jobs?waitMs=${POLL_MS}`, { credential: config.credential, timeoutMs: POLL_MS + 5_000 });
      retry = 500;
      if (!job) continue;
      const result = await executeLocalRequest(job, targets);
      try {
        await apiFetch(base, `/apix/agents/channel/jobs/${encodeURIComponent(job.id)}/result`, {
          credential: config.credential, method: 'POST', body: result, timeoutMs: 10_000,
        });
      } catch {
        // Never repeat an HTTP request when result delivery is uncertain.
        console.error(`Result delivery failed for job ${job.id}; the request will not be replayed.`);
      }
    } catch (error) {
      if (error.name === 'AbortError') continue;
      if ([401, 403, 410].includes(error.status)) { console.error('Agent credential was rejected or revoked; stopping.'); break; }
      await new Promise(resolve => setTimeout(resolve, retry + Math.floor(Math.random() * retry / 4)));
      retry = Math.min(retry * 2, 30_000);
    }
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url))
  run().catch(error => { console.error(error.message); process.exitCode = 1; });

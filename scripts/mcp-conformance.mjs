#!/usr/bin/env node
// Exercises NORA's MCP server over the wire, against a running deployment.
//
// WHY THIS EXISTS. ADR 0041 decided NORA would be an MCP server, and US27 built it with thirteen
// integration tests plus four tenant-isolation cases. All of them run in-process against
// MockMvc. What none of them do is speak the protocol over HTTP to a server that is actually
// running, which means the conformance claim rests on reading the specification correctly — and
// the one thing a specification cannot tell you is whether your implementation of it works.
//
// This closes the smaller half of that gap: it drives the real endpoint over the real transport
// with a real credential. It is NOT a substitute for the larger half, which is a third-party
// client (Claude Desktop, an IDE) connecting successfully. Nothing here proves interoperability
// with an implementation nobody in this repository wrote. Run this first because it is cheap; if
// a real client then fails, this tells you whether the fault is on our side of the wire.
//
// USAGE
//   node scripts/mcp-conformance.mjs --url https://nora.systems --token nora_mcp_xxx
//   NORA_URL=... NORA_MCP_TOKEN=... node scripts/mcp-conformance.mjs
//
// Mint the token in the web app (Settings › MCP). It is shown once; only its SHA-256 is stored
// (ADR 0041 §3). Zero dependencies — Node 18+ for the built-in fetch.
//
// Exits 0 when every check passes, 1 otherwise, and prints one line per check.

const args = process.argv.slice(2)
const arg = (name) => {
  const i = args.indexOf(`--${name}`)
  return i >= 0 ? args[i + 1] : undefined
}

const BASE = (arg('url') || process.env.NORA_URL || '').replace(/\/+$/, '')
const TOKEN = arg('token') || process.env.NORA_MCP_TOKEN || ''
const ENDPOINT = `${BASE}/mcp`

// Must match api/mcp/McpProtocol.java. A drift here is itself a finding.
const PREFERRED_VERSION = '2025-11-25'
const ALSO_SUPPORTED = '2025-06-18'
const VERSION_HEADER = 'MCP-Protocol-Version'
const EXPECTED_TOOLS = [
  'list_meetings',
  'get_meeting',
  'search_meetings',
  'list_tasks',
  'get_customer_confidence',
]

if (!BASE || !TOKEN) {
  console.error('usage: node scripts/mcp-conformance.mjs --url <base> --token <nora_mcp_...>')
  console.error('   or: NORA_URL=<base> NORA_MCP_TOKEN=<token> node scripts/mcp-conformance.mjs')
  process.exit(2)
}

let passed = 0
let failed = 0
const ok = (msg, detail) => { passed++; console.log(`  PASS  ${msg}${detail ? ` — ${detail}` : ''}`) }
const bad = (msg, detail) => { failed++; console.log(`  FAIL  ${msg}${detail ? ` — ${detail}` : ''}`) }

let nextId = 1
async function rpc(method, params, { token = TOKEN, version = PREFERRED_VERSION } = {}) {
  const headers = { 'content-type': 'application/json', accept: 'application/json' }
  if (token) headers.authorization = `Bearer ${token}`
  if (version) headers[VERSION_HEADER] = version
  const body = JSON.stringify({ jsonrpc: '2.0', id: nextId++, method, ...(params ? { params } : {}) })
  let res
  try {
    res = await fetch(ENDPOINT, { method: 'POST', headers, body })
  } catch (e) {
    // A transport failure is not a conformance finding, it is a wrong URL or a server that is
    // not running. Reported as a sentence rather than as a Node stack trace, because the person
    // running this is checking a deployment and not debugging this script.
    console.error(`\nCannot reach ${ENDPOINT}`)
    console.error(`  ${e?.cause?.code ?? e?.name ?? 'error'}: ${e?.cause?.message ?? e?.message}`)
    console.error('\nCheck --url (it is the API base, and this script appends /mcp) and that the')
    console.error('deployment is up. Nothing about protocol conformance was measured.')
    process.exit(2)
  }
  let json = null
  try { json = await res.json() } catch { /* a non-JSON body is itself the finding */ }
  return { status: res.status, json }
}

console.log(`MCP conformance against ${ENDPOINT}\n`)

// --- 1. Handshake -----------------------------------------------------------
console.log('1. initialize')
const init = await rpc('initialize', {
  protocolVersion: PREFERRED_VERSION,
  capabilities: {},
  clientInfo: { name: 'nora-conformance', version: '1.0.0' },
})
if (init.status !== 200) {
  bad('initialize returns 200', `got ${init.status}`)
} else if (init.json?.error) {
  bad('initialize succeeds', JSON.stringify(init.json.error))
} else {
  ok('initialize returns 200 with a result')
  const r = init.json?.result ?? {}
  r.protocolVersion === PREFERRED_VERSION
    ? ok('server echoes the negotiated protocol version', r.protocolVersion)
    : bad('server echoes the negotiated protocol version', `got ${r.protocolVersion}`)
  r.serverInfo?.name
    ? ok('serverInfo carries a name', r.serverInfo.name)
    : bad('serverInfo carries a name')
  r.capabilities?.tools
    ? ok('server advertises the tools capability')
    : bad('server advertises the tools capability', JSON.stringify(r.capabilities))
}

// The second supported revision has to work too, since ADR 0041's whole point is reaching the
// client base that exists rather than only the newest one.
const initOld = await rpc('initialize', {
  protocolVersion: ALSO_SUPPORTED,
  capabilities: {},
  clientInfo: { name: 'nora-conformance', version: '1.0.0' },
}, { version: ALSO_SUPPORTED })
initOld.status === 200 && !initOld.json?.error
  ? ok(`the second supported revision also negotiates`, ALSO_SUPPORTED)
  : bad(`the second supported revision also negotiates`, `status ${initOld.status}`)

// --- 2. Refusals ------------------------------------------------------------
// These matter more than the happy path: a server that degrades quietly on an unknown version, or
// that answers at all without a credential, is the failure that does not announce itself.
console.log('\n2. refusals')
const badVersion = await rpc('initialize', {
  protocolVersion: '1999-01-01', capabilities: {}, clientInfo: { name: 'x', version: '1' },
}, { version: '1999-01-01' })
if (badVersion.json?.error || badVersion.status >= 400) {
  const names = JSON.stringify(badVersion.json?.error?.data ?? badVersion.json?.error?.message ?? '')
  names.includes(PREFERRED_VERSION)
    ? ok('an unsupported protocol version is refused, and the error names what IS supported')
    : bad('the refusal should name the supported versions so a dual-era client can fall back', names)
} else {
  bad('an unsupported protocol version must be refused, not silently accepted')
}

const noAuth = await rpc('tools/list', {}, { token: null })
noAuth.status === 401 || noAuth.status === 403
  ? ok('a request with no credential is refused', `${noAuth.status}`)
  : bad('a request with no credential must be refused', `got ${noAuth.status}`)

const wrongAuth = await rpc('tools/list', {}, { token: 'nora_mcp_definitely_not_a_real_token' })
wrongAuth.status === 401 || wrongAuth.status === 403
  ? ok('a request with an invalid credential is refused', `${wrongAuth.status}`)
  : bad('a request with an invalid credential must be refused', `got ${wrongAuth.status}`)

// The credential is scoped to /mcp exactly (ADR 0041 §4 as a property of the token, not of which
// tools happen to exist). If it ever authenticates the REST API, read-only stops being enforced.
const restRes = await fetch(`${BASE}/meetings`, {
  headers: { authorization: `Bearer ${TOKEN}`, accept: 'application/json' },
})
restRes.status === 401 || restRes.status === 403
  ? ok('the MCP token does NOT authenticate the REST API', `GET /meetings -> ${restRes.status}`)
  : bad('the MCP token must not authenticate the REST API', `GET /meetings -> ${restRes.status}`)

// --- 3. Tool catalogue ------------------------------------------------------
console.log('\n3. tools/list')
const list = await rpc('tools/list', {})
const tools = list.json?.result?.tools ?? []
if (!tools.length) {
  bad('tools/list returns a non-empty catalogue', `status ${list.status}`)
} else {
  ok('tools/list returns a catalogue', `${tools.length} tool(s)`)
  for (const name of EXPECTED_TOOLS) {
    const t = tools.find((x) => x.name === name)
    if (!t) { bad(`catalogue contains ${name}`); continue }
    t.inputSchema && typeof t.inputSchema === 'object'
      ? ok(`${name} declares an inputSchema`)
      : bad(`${name} declares an inputSchema`)
    // Descriptions land verbatim in an external model's context. Nothing here can prove a
    // description is harmless, but an empty one is a defect a client will surface.
    if (!t.description) bad(`${name} has a description`)
  }
}

// --- 4. Tool invocation -----------------------------------------------------
console.log('\n4. tools/call')
const called = await rpc('tools/call', { name: 'list_meetings', arguments: { limit: 1 } })
if (called.json?.error) {
  bad('list_meetings answers', JSON.stringify(called.json.error))
} else {
  const result = called.json?.result
  result ? ok('list_meetings answers with a result') : bad('list_meetings answers with a result')
  Array.isArray(result?.content)
    ? ok('the result carries a content array, as the specification requires')
    : bad('the result carries a content array', JSON.stringify(result)?.slice(0, 120))
}

const unknown = await rpc('tools/call', { name: 'definitely_not_a_tool', arguments: {} })
unknown.json?.error
  ? ok('an unknown tool name is an error, not an empty success')
  : bad('an unknown tool name must be an error')

const unknownMethod = await rpc('nora/not_a_method', {})
unknownMethod.json?.error?.code === -32601
  ? ok('an unknown method returns JSON-RPC METHOD_NOT_FOUND (-32601)')
  : bad('an unknown method should return -32601', JSON.stringify(unknownMethod.json?.error))

// --- summary ----------------------------------------------------------------
console.log(`\n${passed} passed, ${failed} failed`)
if (failed === 0) {
  console.log('\nThis proves the server speaks the protocol over the wire. It does NOT prove a')
  console.log('third-party client can connect — that still needs Claude Desktop or an IDE, and')
  console.log('ADR 0041 §3 records the known limit: clients that only speak the specification\'s')
  console.log('OAuth flow will not connect without a manually pasted token.')
}
process.exit(failed === 0 ? 0 : 1)

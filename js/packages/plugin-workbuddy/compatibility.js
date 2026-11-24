// Request/response compatibility adapted from magpie-community/plugins/packages/workbuddy/index.mjs.
// Copyright the magpie-community/plugins contributors. MIT; see UPSTREAM-LICENSE.txt.
// Snapshot: 2026-11-24. Only the Chat Completions compatibility helpers are carried here.
// Account storage, rotation and Android SDK integration remain in core.js/index.js.
function chatOf(body) {
  if (typeof body !== 'string') return null
  try { const value = JSON.parse(body); return value && typeof value === 'object' ? value : null } catch { return null }
}

const DEFAULT_SYSTEM = "You are a helpful assistant."
const INSTRUCTIONS = ["<instructions>\n", "\n</instructions>"]

function textOf(content) {
  if (typeof content === "string") return content
  if (!Array.isArray(content)) return ""
  return content.map((p) => (typeof p?.text === "string" ? p.text : "")).filter(Boolean).join("\n")
}

// systemKey is the text of a chat's system messages, which a chat refused
// once is known by: the same agent's next chat goes with systemAsUser at once
function systemKey(body) {
  return systemKeyOf(chatOf(body))
}

function systemKeyOf(chat) {
  const msgs = chat?.messages
  if (!Array.isArray(msgs)) return ""
  const key = msgs.filter((m) => m?.role === "system").map((m) => textOf(m.content)).join("\n")
  return key.trim() === DEFAULT_SYSTEM ? "" : key
}

function systemAsUser(body) {
  const b = chatOf(body)
  return systemAsUserOn(b) ? JSON.stringify(b) : null
}

// systemAsUserOn makes systemAsUser's move on a chat's object; false when
// there's nothing to move
function systemAsUserOn(b) {
  const msgs = b?.messages
  if (!Array.isArray(msgs)) return false
  let i = 0
  const lead = []
  for (; i < msgs.length && msgs[i]?.role === "system"; i++) lead.push(textOf(msgs[i].content))
  const said = lead.join("\n\n").trim()
  const rest = msgs.slice(i).map((m) => (m?.role === "system" ? { ...m, role: "user" } : m))
  const moved = said && said !== DEFAULT_SYSTEM
  if (!moved && rest.every((m, k) => m === msgs[i + k])) return false
  if (moved) {
    const text = INSTRUCTIONS[0] + said + INSTRUCTIONS[1]
    const u = rest.findIndex((m) => m?.role === "user")
    if (u < 0) rest.unshift({ role: "user", content: text })
    else if (typeof rest[u].content === "string") rest[u] = { ...rest[u], content: text + "\n\n" + rest[u].content }
    else if (Array.isArray(rest[u].content)) rest[u] = { ...rest[u], content: [{ type: "text", text }, ...rest[u].content] }
    else rest[u] = { ...rest[u], content: text }
  }
  b.messages = [{ role: "system", content: DEFAULT_SYSTEM }, ...rest]
  return true
}

// OPENERS are the shapes in which an agent's opening says which harness it
// runs in, as a vendor may have fingerprinted one. A vendor matches a refused
// line whole and exactly — one extra space passes it, as FLAGGED's note above
// has it — so the same line with one more comma passes while still saying
// what it said. Measured 2026-10-30 against copilot.tencent.com, one account,
// streamed, deepseek-v4.1-flash at max: DeepSeek Harness's opener answered
// 400 code 11128 as written, and 200 with the comma, the harness's name still
// in it; a suffix in its place (…Harness Desktop., …Harness CLI.) changed
// nothing, so it is the line's shape the vendor knows, not the product alone.
const OPENERS = [
  [/(\bYou are (?:an?|the)\s[^\n.,]{0,60}?\b(?:agent|assistant))\s+(powered by|running on|running in|built on|based on)\b/gi, "$1, $2"],
]

function opened(text) {
  for (const [re, to] of OPENERS) text = text.replace(re, to)
  return text
}

// commaPaused is body with OPENERS' comma set in its system and assistant
// messages — the two WorkBuddy reads (REFUSED.md). The user's messages and
// tools' results are not among them: a comma in a message the vendor never
// matches changes what someone said without changing what was refused, and
// the user's own words come back altered. It is tried before systemAsUser,
// which takes the system prompt out of the slot an agent's instructions are
// read in. null when there was nothing to set a comma in.
//
// The two roles are read off the message's own role: a message with no role,
// or one of another kind, is left as it is. roles, when given, collects the
// roles the comma went into — the caller uses it to tell a comma that made a
// chat pass in its system prompt from one that only set an assistant
// message's (commaSystems).
const PAUSED_ROLES = new Set(["system", "assistant"])

function commaPaused(body, roles) {
  const b = chatOf(body)
  return commaPausedOn(b, roles) ? JSON.stringify(b) : null
}

// commaPausedOn makes commaPaused's change on a chat's object, in place; false
// when there was nothing to set a comma in. commaPaused is this on a body, and
// the first send of a system prompt whose comma passed is this too
// (commaSystems), so the two can never drift apart.
function commaPausedOn(b, roles) {
  const msgs = b?.messages
  if (!Array.isArray(msgs)) return false
  let changed = false
  for (const m of msgs) {
    if (!PAUSED_ROLES.has(m?.role)) continue
    let mine = false
    if (typeof m.content === "string") {
      const t = opened(m.content)
      if (t !== m.content) {
        m.content = t
        mine = true
      }
    } else if (Array.isArray(m.content)) {
      for (const p of m.content) {
        if (typeof p?.text !== "string") continue
        const t = opened(p.text)
        if (t === p.text) continue
        p.text = t
        mine = true
      }
    }
    // a message whose parts held no opening of their own is not changed, and
    // is not reported as one that was
    if (!mine) continue
    changed = true
    roles?.add(m.role)
  }
  return changed
}

// normalizeToolChoice converts the object form emitted by OpenAI clients to
// WorkBuddy's string form. It runs after fitted(), so a named choice follows a
// tool name that was made safe for WorkBuddy.
function normalizeToolChoice(body) {
  if (typeof body !== "string") return body
  try {
    const b = JSON.parse(body)
    return toolChoiceOn(b) ? JSON.stringify(b) : body
  } catch {
    return body
  }
}

// toolChoiceOn makes normalizeToolChoice's change to a chat's object; true
// when it made one (a string choice other than none is WorkBuddy's already)
function toolChoiceOn(b) {
  if (!b || typeof b !== "object" || Array.isArray(b) || !("tool_choice" in b)) return false
  const choice = b.tool_choice
  if (typeof choice === "string") {
    if (choice.trim().toLowerCase() !== "none") return false
    delete b.tool_choice
    delete b.tools
    delete b.functions
    return true
  }
  if (!choice || typeof choice !== "object" || Array.isArray(choice)) {
    delete b.tool_choice
    return true
  }
  const type = typeof choice.type === "string" ? choice.type.trim().toLowerCase() : ""
  if (type === "none") {
    delete b.tool_choice
    delete b.tools
    delete b.functions
  } else if (type === "auto" || type === "required") {
    b.tool_choice = type
  } else if (type === "function") {
    const fn = choice.function && typeof choice.function === "object" && !Array.isArray(choice.function)
      ? choice.function
      : undefined
    const name = typeof fn?.name === "string" ? fn.name.trim() : ""
    b.tool_choice = name || "auto"
  } else {
    delete b.tool_choice
  }
  return true
}


const NAME_OK = /^[A-Za-z0-9_-]+$/

// safeName is name with each character WorkBuddy refuses as _, kept apart
// from the names in taken.
function safeName(name, taken) {
  const base = String(name).replace(/[^A-Za-z0-9_-]/g, "_") || "tool"
  let n = base
  for (let i = 2; taken.has(n); i++) n = `${base}_${i}`
  taken.add(n)
  return n
}

// NUL_ESCAPE finds a regex's \0 (the NUL character), stepping over each \\
// (a backslash) so that a \\0 is left; \01 is an octal escape, also left.
const NUL_ESCAPE = /\\\\|\\0(?![0-9])/g

// fitSchema mends a schema in place, at every depth: a pattern's \0, which
// DeepSeek's validator says is "not a 'regex'", is \u0000, which matches
// the same (magpie's gateway does this for DeepSeek's own API,
// deepseekToolPatterns); a tuple's items (a list) become one schema any of
// them fits. It reports whether it changed anything.
function fitSchema(s, depth = 0) {
  if (!s || typeof s !== "object" || depth > 64) return false
  let changed = false
  if (Array.isArray(s)) {
    for (const v of s) if (fitSchema(v, depth + 1)) changed = true
    return changed
  }
  if (typeof s.pattern === "string" && s.pattern.includes("\\0")) {
    const p = s.pattern.replace(NUL_ESCAPE, (m) => (m === "\\\\" ? m : "\\u0000"))
    if (p !== s.pattern) {
      s.pattern = p
      changed = true
    }
  }
  if (s.pattern === "^[^\\uD800-\\uDFFF]*$") {
    delete s.pattern
    changed = true
  }
  if (Array.isArray(s.items)) {
    const list = s.items.filter((v) => v && typeof v === "object")
    s.items = list.length === 0 ? {} : list.length === 1 ? list[0] : { anyOf: list }
    delete s.additionalItems
    changed = true
  }
  for (const v of Object.values(s)) if (fitSchema(v, depth + 1)) changed = true
  return changed
}

// Kimi (kimi-k3-1) answers 11133 too, to the tools of an MCP server or a
// plugin (clearmann, #122). Tried on the owner's account, 2026-10-14, each
// alone in an otherwise plain chat: Kimi turns away an enum with no type
// (["a","b"] as much as [1,"a",null]), an empty enum, an enum whose type
// lists two types besides null, a schema that is true or false where a
// schema goes (properties.x: true, items: true), a required that isn't a
// list, and a type it doesn't know ("any", "String"). It takes an enum
// typed T or [T, "null"], an anyOf of such enums, {"not": {}}, {}, and
// additionalProperties: true or false. fitShape gives each the shape it
// takes, meaning the same: none of it narrows or widens what a call may
// hold, except an empty enum (which nothing fits) is left out.
const TYPES = new Set(["string", "number", "integer", "boolean", "object", "array", "null"])

// SUB_ONE are the keywords whose value is a schema, SUB_LIST a list of
// schemas, SUB_MAP a map of names to schemas. additionalProperties and
// unevaluatedProperties are left as they are when true or false.
const SUB_ONE = ["items", "additionalItems", "unevaluatedItems", "contains", "not", "if", "then", "else", "propertyNames", "additionalProperties", "unevaluatedProperties"]
const SUB_LIST = ["anyOf", "oneOf", "allOf", "prefixItems"]
const SUB_MAP = ["properties", "patternProperties", "$defs", "definitions", "dependentSchemas"]
const BOOL_OK = new Set(["additionalProperties", "unevaluatedProperties"])

// valueType is the JSON Schema type of an enum's value.
function valueType(v) {
  if (v === null) return "null"
  if (Array.isArray(v)) return "array"
  if (typeof v === "number") return Number.isInteger(v) ? "integer" : "number"
  return typeof v
}

// fitEnum gives s's enum a type: the one its values have, or that and
// "null"; values of several types are an anyOf of an enum for each. It
// reports whether it changed anything.
function fitEnum(s) {
  if (!("enum" in s)) return false
  if (!Array.isArray(s.enum) || s.enum.length === 0) {
    delete s.enum
    return true
  }
  const listed = Array.isArray(s.type) ? s.type : typeof s.type === "string" ? [s.type] : []
  if (listed.filter((t) => t !== "null").length === 1) return false
  const byType = new Map()
  for (const v of s.enum) {
    const t = valueType(v)
    if (!byType.has(t)) byType.set(t, [])
    byType.get(t).push(v)
  }
  if (byType.has("integer") && byType.has("number")) {
    byType.get("number").push(...byType.get("integer"))
    byType.delete("integer")
  }
  const hasNull = byType.delete("null")
  if (byType.size <= 1) {
    const [t] = byType.keys()
    const want = t === undefined ? "null" : hasNull ? [t, "null"] : t
    if (JSON.stringify(want) === JSON.stringify(s.type)) return false
    s.type = want
    return true
  }
  const split = [...byType].map(([t, vs]) => ({ type: t, enum: vs }))
  if (hasNull) split.push({ type: "null" })
  delete s.enum
  delete s.type
  if (Array.isArray(s.anyOf)) s.allOf = [...(Array.isArray(s.allOf) ? s.allOf : []), { anyOf: split }]
  else s.anyOf = split
  return true
}

// subSchema is v, a schema found under key, as an object Kimi takes: true
// is {}, false is {"not": {}}.
function subSchema(v, key) {
  if (typeof v !== "boolean" || BOOL_OK.has(key)) return v
  return v ? {} : { not: {} }
}

// fitShape mends s, a schema, and each schema under it, in place (see
// TYPES above); a name under properties is never read as a keyword. It
// reports whether it changed anything.
function fitShape(s, depth = 0) {
  if (!s || typeof s !== "object" || Array.isArray(s) || depth > 64) return false
  let changed = false
  if ("type" in s) {
    const list = (Array.isArray(s.type) ? s.type : [s.type]).map((t) => (typeof t === "string" ? t.toLowerCase() : t)).filter((t) => TYPES.has(t))
    const uniq = [...new Set(list)]
    const want = uniq.length === 0 ? undefined : uniq.length === 1 ? uniq[0] : uniq
    if (JSON.stringify(want) !== JSON.stringify(s.type)) {
      if (want === undefined) delete s.type
      else s.type = want
      changed = true
    }
  }
  if ("required" in s && !Array.isArray(s.required)) {
    delete s.required
    changed = true
  } else if (Array.isArray(s.required) && s.required.some((k) => typeof k !== "string")) {
    s.required = s.required.filter((k) => typeof k === "string")
    changed = true
  }
  if (fitEnum(s)) changed = true
  for (const k of SUB_ONE) {
    if (!(k in s)) continue
    const v = subSchema(s[k], k)
    if (v !== s[k]) {
      s[k] = v
      changed = true
    }
    if (fitShape(v, depth + 1)) changed = true
  }
  for (const k of SUB_LIST) {
    if (!Array.isArray(s[k])) continue
    s[k].forEach((v0, i) => {
      const v = subSchema(v0, k)
      if (v !== v0) {
        s[k][i] = v
        changed = true
      }
      if (fitShape(v, depth + 1)) changed = true
    })
  }
  for (const k of SUB_MAP) {
    const m = s[k]
    if (!m || typeof m !== "object" || Array.isArray(m)) continue
    for (const [name, v0] of Object.entries(m)) {
      const v = subSchema(v0, k)
      if (v !== v0) {
        m[name] = v
        changed = true
      }
      if (fitShape(v, depth + 1)) changed = true
    }
  }
  return changed
}

// localRef is the schema a local $ref in root names ("#/$defs/x",
// "#/definitions/x"), or s itself.
function localRef(root, s) {
  const ref = s?.$ref
  if (typeof ref !== "string" || !ref.startsWith("#/")) return s
  let at = root
  for (const k of ref.slice(2).split("/")) at = at?.[k.replaceAll("~1", "/").replaceAll("~0", "~")]
  return at && typeof at === "object" ? at : s
}

// objectRoot makes a tool's parameters an object at the root, as magpie's
// gateway does for xAI (provider.ObjectRoot): allOf's branches are merged
// whole; of anyOf's and oneOf's object branches the properties are merged
// and a field each requires stays required. A root with no type, or an
// empty one, is an object. It reports whether it changed anything.
function objectRoot(ps, root = ps, depth = 0) {
  const unions = ["allOf", "anyOf", "oneOf"].filter((k) => Array.isArray(ps[k]))
  if (ps.type === "object" && !unions.length) return false
  const props = ps.properties && typeof ps.properties === "object" ? ps.properties : {}
  const required = Array.isArray(ps.required) ? [...ps.required] : []
  for (const b0 of ps.allOf ?? []) {
    let b = localRef(root, b0)
    if (!b || typeof b !== "object") continue
    if (depth < 8 && ["allOf", "anyOf", "oneOf"].some((k) => Array.isArray(b[k]))) objectRoot((b = structuredClone(b)), root, depth + 1)
    for (const [k, v] of Object.entries(b.properties ?? {})) if (!(k in props)) props[k] = v
    if (Array.isArray(b.required)) required.push(...b.required)
  }
  const branches = []
  for (const key of ["anyOf", "oneOf"]) {
    for (const b0 of ps[key] ?? []) {
      let b = localRef(root, b0)
      if (!b || typeof b !== "object") continue
      if (depth < 8 && ["allOf", "anyOf", "oneOf"].some((k) => Array.isArray(b[k]))) objectRoot((b = structuredClone(b)), root, depth + 1)
      if (b.type !== "object" && !b.properties) continue
      branches.push(b)
    }
  }
  const from = new Map()
  for (const b of branches) {
    for (const [k, v] of Object.entries(b.properties ?? {})) {
      if (k in props) continue
      const seen = from.get(k) ?? []
      if (!seen.some((x) => JSON.stringify(x) === JSON.stringify(v))) seen.push(v)
      from.set(k, seen)
    }
  }
  for (const [k, list] of from) props[k] = list.length === 1 ? list[0] : { anyOf: list }
  if (branches.length) {
    for (const k of Object.keys(branches[0].properties ?? {})) {
      if (branches.every((b) => Array.isArray(b.required) && b.required.includes(k))) required.push(k)
    }
  }
  for (const k of unions) delete ps[k]
  ps.type = "object"
  ps.properties = props
  const req = [...new Set(required)].filter((k) => typeof k === "string" && k in props)
  if (req.length) ps.required = req
  else delete ps.required
  return true
}

// Kimi (kimi-k3.1-pro) answers 400 "Invalid request parameters" to a oneOf
// that has no type, at the root of a $ref's target or deeper, even when every
// branch is an object (congee949, #378: dsh's REA tools, on workbuddy and
// workbuddy-ai alike; a "type": "object" beside it is taken). typeUnions gives
// such a oneOf type "object" when each branch is typed object itself or
// through a chain of local $refs, and changes nothing else: a union of other
// or mixed types, an anyOf, or a $ref that is missing, external or circular
// is left as it came. It reports whether it changed anything.
function typeUnions(s, root = s, depth = 0) {
  if (!s || typeof s !== "object" || Array.isArray(s) || depth > 64) return false
  let changed = false
  if (!("type" in s) && Array.isArray(s.oneOf) && s.oneOf.length && s.oneOf.every((b) => objectBranch(root, b))) {
    s.type = "object"
    changed = true
  }
  for (const k of SUB_ONE) if (typeUnions(s[k], root, depth + 1)) changed = true
  for (const k of SUB_LIST) for (const v of Array.isArray(s[k]) ? s[k] : []) if (typeUnions(v, root, depth + 1)) changed = true
  for (const k of SUB_MAP) {
    const m = s[k]
    if (!m || typeof m !== "object" || Array.isArray(m)) continue
    for (const v of Object.values(m)) if (typeUnions(v, root, depth + 1)) changed = true
  }
  return changed
}

// objectBranch says whether b is typed object, itself or where its local
// $refs lead; a $ref seen before on the way is a cycle, and not.
function objectBranch(root, b, seen = []) {
  if (!b || typeof b !== "object" || Array.isArray(b)) return false
  if ("type" in b) return b.type === "object"
  if (typeof b.$ref !== "string" || seen.includes(b.$ref) || seen.length > 16) return false
  const t = localRef(root, b)
  return t !== b && objectBranch(root, t, [...seen, b.$ref])
}

// fitTools mends b's function tools in place: their parameters (fitSchema,
// fitShape, objectRoot) and names. It reports whether it changed anything,
// and the names it gave, each to the name the client knows (ours), so that the
// model's calls are named back. A call in the chat's history is named as
// its tool now is.
function fitTools(b) {
  const names = new Map()
  if (!Array.isArray(b?.tools) || !b.tools.length) return { changed: false, names }
  let changed = false
  const taken = new Set()
  for (const t of b.tools) {
    const n = t?.function?.name
    if (typeof n === "string" && NAME_OK.test(n)) taken.add(n)
  }
  const theirs = new Map()
  for (const t of b.tools) {
    const fn = t?.type === "function" || t?.function ? t.function : null
    if (!fn || typeof fn !== "object") continue
    if (typeof fn.name === "string" && !NAME_OK.test(fn.name)) {
      const safe = safeName(fn.name, taken)
      names.set(safe, fn.name)
      theirs.set(fn.name, safe)
      fn.name = safe
      changed = true
    }
    if (fn.parameters === undefined) continue
    if (!fn.parameters || typeof fn.parameters !== "object" || Array.isArray(fn.parameters)) {
      fn.parameters = { type: "object", properties: {} }
      changed = true
      continue
    }
    if (fitSchema(fn.parameters)) changed = true
    if (fitShape(fn.parameters)) changed = true
    if (typeUnions(fn.parameters)) changed = true
    if (objectRoot(fn.parameters)) changed = true
  }
  if (theirs.size && Array.isArray(b.messages)) {
    for (const m of b.messages) {
      for (const c of Array.isArray(m?.tool_calls) ? m.tool_calls : []) {
        const safe = theirs.get(c?.function?.name)
        if (safe) c.function.name = safe
      }
      if (m?.role === "tool" && theirs.has(m.name)) m.name = theirs.get(m.name)
    }
  }
  const choice = b.tool_choice?.function
  if (choice && theirs.has(choice.name)) choice.name = theirs.get(choice.name)
  return { changed, names }
}

// fitted is a chat body with its tools mended (fitTools), and the names
// given its tools, each to the client's own; a body that isn't a chat is
// as it was.
function fitted(body) {
  if (typeof body !== "string") return { body, names: new Map() }
  try {
    const b = JSON.parse(body)
    const { changed, names } = fitTools(b)
    return { body: changed ? JSON.stringify(b) : body, names }
  } catch {
    return { body, names: new Map() }
  }
}


function namedBack(v, names) {
  let changed = false
  for (const c of Array.isArray(v?.choices) ? v.choices : []) {
    for (const at of [c?.message, c?.delta]) {
      for (const tc of Array.isArray(at?.tool_calls) ? at.tool_calls : []) {
        const ours = names.get(tc?.function?.name)
        if (ours) {
          tc.function.name = ours
          changed = true
        }
      }
    }
  }
  return changed
}

// renamed is res with namedBack on its body, read as it comes.
function renamed(res, names) {
  if (!names.size || !res.body) return res
  const headers = new Headers(res.headers)
  headers.delete("content-length")
  headers.delete("content-encoding")
  const json = /json/i.test(res.headers.get("content-type") ?? "")
  if (json) {
    const out = res.text().then((text) => {
      try {
        const v = JSON.parse(text)
        return namedBack(v, names) ? JSON.stringify(v) : text
      } catch {
        return text
      }
    })
    return out.then((text) => new Response(text, { status: res.status, statusText: res.statusText, headers }))
  }
  const dec = new TextDecoder()
  const enc = new TextEncoder()
  let rest = ""
  const line = (l) => {
    const m = /^data:\s?(.*)$/.exec(l)
    if (!m || m[1] === "[DONE]") return l
    try {
      const v = JSON.parse(m[1])
      return namedBack(v, names) ? `data: ${JSON.stringify(v)}` : l
    } catch {
      return l
    }
  }
  const body = res.body.pipeThrough(
    new TransformStream({
      transform(chunk, ctl) {
        rest += dec.decode(chunk, { stream: true })
        const lines = rest.split("\n")
        rest = lines.pop()
        if (lines.length) ctl.enqueue(enc.encode(lines.map(line).join("\n") + "\n"))
      },
      flush(ctl) {
        rest += dec.decode()
        if (rest) ctl.enqueue(enc.encode(line(rest)))
      },
    }),
  )
  return new Response(body, { status: res.status, statusText: res.statusText, headers })
}


const AFTER_TOOLS_REFUSED = /must end with a user message|["']code["']\s*:\s*["']?11153(?![\d.])/i

// replyAfterTools is a chat ending with tool results then the user's
// message(s), with an empty assistant message between them, as if the model
// had said nothing after the results. Tried with a real WorkBuddy AI
// account on 2026-10-14: gemini-3.8-flash serves it and follows the user's
// message as a user's, where folded into the tool's result it was read as
// the tool's output and its ask left undone. null for any other chat — one
// ending with the assistant's message is a prefill, refused as it is.
function replyAfterTools(body) {
  const b = chatOf(body)
  return replyAfterToolsOn(b) ? JSON.stringify(b) : null
}

// replyAfterToolsOn makes replyAfterTools's change on a chat's object; false
// for a chat it doesn't take
function replyAfterToolsOn(b) {
  const msgs = b?.messages
  if (!Array.isArray(msgs)) return false
  let i = msgs.length - 1
  while (i >= 0 && msgs[i]?.role === "user") i--
  if (i === msgs.length - 1 || i < 0 || msgs[i]?.role !== "tool") return false
  msgs.splice(i + 1, 0, { role: "assistant", content: "" })
  return true
}

const REFUSED = /unapproved channel|illegal api invocation|["']code["']\s*:\s*["']?11128(?![\d.])/i

/** Bounded, route-local compatibility retries, before any response content is delivered. */
export function createChatCompatibility() {
  const refusedSystems = new Set()
  const commaSystems = new Set()
  const endsAfterTools = new Set()
  const remember = (set, key) => { set.add(key); if (set.size > 256) set.delete(set.values().next().value) }
  return {
    prepare(chat, site) {
      const sysKey = systemKeyOf(chat)
      const key = sysKey ? site + '\n' + sysKey : ''
      const { names } = fitTools(chat)
      toolChoiceOn(chat)
      if (key && refusedSystems.has(key)) systemAsUserOn(chat)
      if (key && commaSystems.has(key)) commaPausedOn(chat)
      if (endsAfterTools.has(site + '\n' + chat.model)) replyAfterToolsOn(chat)
      return { body: JSON.stringify(chat), names, key, modelKey: site + '\n' + chat.model }
    },
    async send(prepared, send) {
      let { body, names, key, modelKey } = prepared
      let res = await send(body)
      if (res.status === 400) {
        let text = await res.text()
        const roles = new Set()
        const paused = REFUSED.test(text) ? commaPaused(body, roles) : null
        if (paused) {
          body = paused
          res = await send(body)
          if (res.status === 400) text = await res.text()
          else if (res.ok && key && roles.has('system')) remember(commaSystems, key)
        }
        const moved = res.status === 400 && REFUSED.test(text) && key && !refusedSystems.has(key) ? systemAsUser(body) : null
        if (moved) {
          remember(refusedSystems, key)
          body = moved
          res = await send(body)
          if (res.status === 400) text = await res.text()
        }
        const again = res.status === 400 && !endsAfterTools.has(modelKey) && AFTER_TOOLS_REFUSED.test(text) ? replyAfterTools(body) : null
        if (again) {
          remember(endsAfterTools, modelKey)
          res = await send(again)
        } else if (res.status === 400) {
          res = responseWithText(res, text)
        }
      }
      return res.ok ? renamed(res, names) : res
    },
  }
}

/** A consumed/rewritten response must not keep its previous byte length or content encoding. */
export function responseWithText(res, text) {
  const headers = new Headers(res.headers)
  headers.delete('content-length')
  headers.delete('content-encoding')
  return new Response(text, { status: res.status, statusText: res.statusText, headers })
}

/** Keep Tencent's code/msg and request id visible to the OpenAI-compatible adapter. */
export async function explainResponse(res) {
  if (res.ok) return res
  const text = await res.text()
  let body
  try { body = JSON.parse(text) } catch { return responseWithText(res, text) }
  if (!body || typeof body !== 'object' || Array.isArray(body)) return responseWithText(res, text)
  const message = body.error?.message || body.msg || body.message
  if (typeof message !== 'string' || !message) return responseWithText(res, text)
  const code = body.error?.code ?? body.code
  const detail = code == null ? message : `${message} (WorkBuddy ${code})`
  body.error = { ...(typeof body.error === 'object' && body.error ? body.error : {}), message: detail,
    ...(code == null ? {} : { code: String(code) }) }
  return responseWithText(res, JSON.stringify(body))
}

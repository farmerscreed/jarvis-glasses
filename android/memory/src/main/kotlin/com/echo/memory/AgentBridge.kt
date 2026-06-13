package com.echo.memory

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Client for the JARVIS Agent Bridge — the **deliberate lane** that delegates heavy, tool-using
 * tasks to headless Claude Code on the director's PC (`claude -p`, Max subscription). See
 * `agent-bridge/server.js` and `docs/AGENT_DELEGATION.md`.
 *
 * Capability waves (`docs/AGENT_DELEGATION.md` §5):
 *  - **M1 research** — read-only + web, verify with WebSearch + cite.
 *  - **M2 coding** — edit/run in the repo working dir; never commits/pushes (the director reviews);
 *    a separate confirmed [commitChanges] gate handles committing.
 *  - **M3 email/calendar** — Gmail/Calendar MCP. Email is DRAFT-ONLY by construction (the Gmail MCP
 *    has no send tool). Calendar reads freely; **adding** an event is gated by a spoken confirm in
 *    the caller. Updating/deleting events is intentionally not exposed yet.
 *
 * Phase 1 / M1–M3: synchronous + local; the dev app reaches the bridge over `adb reverse tcp:8765`.
 * Prod URL is empty ⇒ [isConfigured] false ⇒ the voice loop falls back to a normal chat answer.
 * Phase 2 (M4) moves the bridge to a hosted env with async tickets + push.
 *
 * The [http] client given here MUST have a long read timeout (coding can take many minutes).
 */
class AgentBridge(
    private val baseUrl: String,
    private val token: String,
    private val http: OkHttpClient,
    private val json: Json,
) {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /** True when a bridge URL is configured (dev builds). Prod = false until Phase 2 (hosted). */
    val isConfigured: Boolean get() = baseUrl.isNotBlank()

    /**
     * M1 — research: Claude Code with read-only + web tools, instructed to verify time-sensitive/
     * factual claims with WebSearch and cite sources (heads off the model over-trusting training).
     */
    suspend fun research(query: String): AgentResult = task(
        prompt = query,
        allowedTools = "WebSearch,WebFetch,Read,Glob,Grep",
        appendSystemPrompt = RESEARCH_PRESET,
        timeoutMs = 240_000,
    )

    /**
     * M2 — coding: edit/run in the repo working dir (the bridge's default cwd). The preset forbids
     * commit/push/delete and disallowedTools blocks the dangerous Bash patterns as defense-in-depth.
     * Changes are left in the working tree for the director to review (git diff) — never committed.
     */
    suspend fun coding(taskText: String): AgentResult = task(
        prompt = taskText,
        allowedTools = "Read,Edit,Write,Glob,Grep,Bash",
        disallowedTools = CODE_DENY,
        appendSystemPrompt = CODING_PRESET,
        timeoutMs = 540_000,
    )

    /** M2 — commit the current working-tree changes locally (NO push). Gated by a spoken confirm. */
    suspend fun commitChanges(): AgentResult = task(
        prompt = "Stage all current changes and commit them locally. Do not push.",
        allowedTools = "Bash,Read,Glob,Grep",
        disallowedTools = COMMIT_DENY,
        appendSystemPrompt = COMMIT_PRESET,
        timeoutMs = 120_000,
    )

    /** M3 — read the director's calendar (no side effects). */
    suspend fun calendarQuery(query: String): AgentResult = task(
        prompt = query,
        allowedTools = CAL_READ,
        appendSystemPrompt = CALENDAR_READ_PRESET,
        timeoutMs = 120_000,
    )

    /** M3 — add an event to the calendar. Gated by a spoken confirm in the caller. */
    suspend fun calendarAdd(query: String): AgentResult = task(
        prompt = query,
        allowedTools = CAL_ADD,
        appendSystemPrompt = CALENDAR_ADD_PRESET,
        timeoutMs = 120_000,
    )

    /** M3 — draft an email (DRAFT ONLY; the Gmail MCP exposes no send tool). Safe, no confirm. */
    suspend fun emailDraft(query: String): AgentResult = task(
        prompt = query,
        allowedTools = GMAIL_DRAFT,
        appendSystemPrompt = EMAIL_DRAFT_PRESET,
        timeoutMs = 150_000,
    )

    /** Low-level: POST a task to the bridge and normalize the result. Never throws — returns ok=false. */
    suspend fun task(
        prompt: String,
        allowedTools: String,
        timeoutMs: Long,
        appendSystemPrompt: String? = null,
        disallowedTools: String? = null,
        cwd: String? = null,
    ): AgentResult = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext AgentResult(false, "The agent isn't available in this build.", 0)
        runCatching {
            val body = json.encodeToString(
                AgentTaskRequest.serializer(),
                AgentTaskRequest(prompt, allowedTools, timeoutMs, appendSystemPrompt, disallowedTools, cwd),
            )
            val req = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/task")
                .apply { if (token.isNotBlank()) addHeader("Authorization", "Bearer $token") }
                .post(body.toRequestBody(jsonMedia))
                .build()
            http.newCall(req).execute().use { resp ->
                val txt = resp.body?.string().orEmpty()
                val parsed = runCatching { json.decodeFromString(AgentTaskResponse.serializer(), txt) }.getOrNull()
                when {
                    parsed != null && parsed.status == "ok" && parsed.result.isNotBlank() ->
                        AgentResult(true, parsed.result.trim(), parsed.durationMs)
                    parsed?.status == "timeout" ->
                        AgentResult(false, "That took too long, so I stopped. Try a narrower request.", parsed.durationMs)
                    else -> {
                        val why = parsed?.error ?: "HTTP ${resp.code}"
                        AgentResult(false, "I couldn't complete that ($why).", parsed?.durationMs ?: 0)
                    }
                }
            }
        }.getOrElse {
            // Bridge unreachable (PC off / adb reverse not set) — be honest, don't fake a result.
            AgentResult(false, "I couldn't reach my agent on the PC. Is the bridge running?", 0)
        }
    }

    companion object {
        // ---- MCP tool allowlists (names from `claude mcp list`; see deferred-tools registry) ------
        private const val CAL = "mcp__claude_ai_Google_Calendar"
        private const val GMAIL = "mcp__claude_ai_Gmail"
        const val CAL_READ = "${CAL}__list_calendars,${CAL}__list_events,${CAL}__get_event"
        // Add = read + create + suggest_time. update/delete are deliberately omitted (no edit/remove).
        const val CAL_ADD = "$CAL_READ,${CAL}__create_event,${CAL}__suggest_time"
        // Draft-only: create_draft + read tools for context. The Gmail MCP has NO send tool by design.
        const val GMAIL_DRAFT =
            "${GMAIL}__create_draft,${GMAIL}__list_drafts,${GMAIL}__search_threads,${GMAIL}__get_thread,${GMAIL}__list_labels"

        // ---- disallowedTools (defense-in-depth for the Bash-enabled lanes) ------------------------
        const val CODE_DENY = "Bash(git push:*),Bash(git commit:*),Bash(rm:*),Bash(git reset:*),Bash(sudo:*)"
        const val COMMIT_DENY = "Bash(git push:*),Bash(rm:*),Bash(git reset:*),Bash(sudo:*)"

        /**
         * Research preset (M1). Enforces the SOUL truth charter on the deliberate lane: verify, never
         * guess, answer in a form that reads aloud well. The app speaks the part before "Sources:".
         */
        val RESEARCH_PRESET = """
            You are JARVIS's research agent, acting for the director. Be truthful above all.
            ALWAYS verify time-sensitive or factual claims with the WebSearch tool before answering —
            do not rely on training memory for anything that could be out of date (versions, prices,
            news, dates, current facts). If sources conflict or you can't verify, say so plainly
            rather than guessing.
            Answer in a natural SPOKEN style: 3 to 6 short sentences, no markdown, no bullet points,
            and no URLs in the spoken part. Then, on a final line, write "Sources:" followed by the
            source URLs you actually used, one per line.
        """.trimIndent()

        /** Coding preset (M2). Edits are reversible via git; never commits/pushes/deletes. */
        val CODING_PRESET = """
            You are JARVIS's coding agent, working on the director's machine in the current repository.
            Make the requested change with the smallest sensible diff. You may read, edit, and create
            files and run commands (build/tests) to verify your work.
            STRICT RULES: do NOT git commit, do NOT git push, do NOT delete files or branches, and do
            NOT run destructive commands. Leave all changes in the working tree for the director to
            review.
            When finished, reply in a natural SPOKEN style (3 to 6 short sentences, no markdown): say
            what you changed, name the files, whether it builds / tests pass if you ran them, and
            remind the director they can review with "git diff" or undo with "git checkout". If you
            could not do it, say plainly why.
        """.trimIndent()

        /** Commit preset (M2). Runs only after a spoken confirm; one local commit, no push. */
        val COMMIT_PRESET = """
            You are JARVIS's coding agent. Stage all current changes and create ONE git commit with a
            concise, accurate message describing them. Do NOT push. Do NOT amend existing commits.
            After committing, reply in a natural SPOKEN style (1 to 3 sentences, no markdown): the
            commit message you used and how many files changed. If there was nothing to commit, say so.
        """.trimIndent()

        /** Calendar read preset (M3). No side effects. */
        val CALENDAR_READ_PRESET = """
            You are JARVIS's calendar assistant, acting for the director. Use the Google Calendar
            tools to answer the director's question about their schedule. Assume their local timezone
            and primary calendar unless they say otherwise.
            Reply in a natural SPOKEN style (no markdown, no URLs): summarize the relevant events with
            their times, concisely. If there are none, say the calendar is clear for that period.
        """.trimIndent()

        /** Calendar add preset (M3). Runs only after a spoken confirm; creates, never edits/deletes. */
        val CALENDAR_ADD_PRESET = """
            You are JARVIS's calendar assistant, acting for the director. Create the calendar event
            the director asked for using the create_event tool, inferring a sensible title, date,
            time and duration from their request (assume the director's primary calendar and local
            timezone unless told otherwise; default duration 1 hour). Do NOT modify or delete any
            existing events.
            After creating it, reply in a natural SPOKEN style (1 to 3 sentences, no markdown):
            confirm the title, date and time you set.
        """.trimIndent()

        /** Email draft preset (M3). DRAFT ONLY — there is intentionally no send capability. */
        val EMAIL_DRAFT_PRESET = """
            You are JARVIS's email assistant, acting for the director. Compose the email the director
            asked for and save it as a DRAFT using the create_draft tool — never send it (you have no
            send capability, and that is intentional). Write a clear subject and a well-written body
            in the director's voice. If they referenced an existing conversation, you may search and
            read it for context.
            After saving the draft, reply in a natural SPOKEN style (1 to 3 sentences, no markdown):
            confirm you drafted it, to whom, and the subject, and tell the director it's waiting in
            their Gmail drafts to review and send.
        """.trimIndent()
    }
}

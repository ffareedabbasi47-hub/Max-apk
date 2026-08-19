package com.example.data.api

/**
 * SINGLE SOURCE OF TRUTH for which Gemini model IDs MAX calls.
 *
 * BUGFIX (Aug 2026 audit): the project previously hardcoded
 * "gemini-2.0-flash", "gemini-1.5-flash", "gemini-1.5-pro" in two
 * different files (GeminiProvider.kt and GeminiDiagnosticService.kt).
 * As of this audit, Gemini 2.0 models are already shut down and Gemini
 * 1.5 is long deprecated -- every call to those IDs returns HTTP 404,
 * which was silently swallowed and made a perfectly valid API key look
 * broken (the diagnostic button reported FAILED, and real chat calls
 * fell straight through to canned/offline behavior).
 *
 * Verified against Google's official Gemini API release notes
 * (ai.google.dev/gemini-api/docs/changelog) at time of writing:
 *   - Gemini 3.6 Flash (gemini-3.6-flash): GA/stable, current default
 *     Flash model -- good balance of speed/cost/quality.
 *   - Gemini 3.5 Flash-Lite (gemini-3.5-flash-lite): GA, cheaper/faster,
 *     good fallback if 3.6 Flash errors or rate-limits.
 *   - Gemini 2.5 Flash (gemini-2.5-flash): still functional as a last
 *     resort, but Google has announced it shuts down October 16, 2026
 *     (Developer API). Kept only as a third fallback; remove after that
 *     date or sooner if Google retires it early.
 *
 * If Google ships a newer stable model after this list was written,
 * update ONLY this file -- both GeminiProvider.generateResponse and
 * generateVisionResponse read from here, so there's no more risk of the
 * two call sites drifting out of sync the way they did before.
 */
object GeminiModels {
    val TEXT_AND_VISION_MODELS: List<String> = listOf(
        "gemini-3.6-flash",
        "gemini-3.5-flash-lite",
        "gemini-2.5-flash" // fallback only -- scheduled to shut down 2026-10-16
    )

    /** Used by the Settings "Test Connection" diagnostic ping. */
    const val DIAGNOSTIC_DEFAULT_MODEL = "gemini-3.6-flash"
}

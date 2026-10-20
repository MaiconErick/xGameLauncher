package com.maiconerick.xgamelauncher.art

/** One candidate image the user can pick for a game — surfaced from any source (SGDB,
 *  ScreenScraper, RAWG, IGDB). `kind` = "cover" or "hero" — determines which slot it lands
 *  in when picked. */
data class ArtCandidate(
    val url: String,
    val source: String,      // "sgdb" | "screenscraper" | "rawg" | "igdb"
    val kind: String,        // "cover" | "hero"
)

data class GameArtCandidates(
    val covers: List<ArtCandidate>,
    val heroes: List<ArtCandidate>,
) {
    val isEmpty: Boolean get() = covers.isEmpty() && heroes.isEmpty()
    companion object { val EMPTY = GameArtCandidates(emptyList(), emptyList()) }
}

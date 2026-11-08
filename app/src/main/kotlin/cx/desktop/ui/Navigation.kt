package cx.desktop.ui

import androidx.compose.runtime.mutableStateListOf
import com.lagradost.cloudstream3.MainAPI

/** The screens after activation; Back returns to the previous one. */
sealed interface Screen {
    data object Home : Screen
    data class Search(val query: String) : Screen
    data class Details(val api: MainAPI, val url: String, val title: String, val poster: String?) : Screen
    data class Player(val request: PlayRequest) : Screen
    data object Settings : Screen
}

/** What the player needs: the title being watched and the episode (or movie) data for loadLinks. */
data class PlayRequest(
    val api: MainAPI,
    val url: String,
    val title: String,
    val poster: String?,
    val episodeData: String,
    val episodeLabel: String?,
    val startMs: Long,
    /** Following episodes, for "Next episode". */
    val next: List<Pair<String, String?>> = emptyList(),
)

class Navigator {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val current: Screen get() = stack.last()
    fun open(screen: Screen) {
        stack.add(screen)
    }
    fun replace(screen: Screen) {
        stack[stack.lastIndex] = screen
    }
    fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
    fun home() {
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
}

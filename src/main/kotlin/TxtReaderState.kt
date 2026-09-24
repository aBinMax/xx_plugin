import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.APP)
@State(
    name = "MyTxtReaderState",
    storages = [
        Storage("MyTxtReader.xml")
    ]
)
class TxtReaderState :
    PersistentStateComponent<TxtReaderState.State> {

    data class State(

        /**
         * 每个 TXT 文件上次阅读的位置。
         *
         * filePath -> UTF-8 byte offset
         */
        var positions: MutableMap<String, Long> =
            mutableMapOf(),

        /**
         * 字体大小
         */
        var fontSize: Int = 18,

        /**
         * 文字颜色。
         *
         * 使用 Color.rgb / Color.argb 对应的 Int。
         *
         * 0 表示没有设置过，
         * MyToolWindow 会使用默认颜色。
         */
        var textColor: Int = 0
    )

    private var state =
        State()

    // =========================================================
    // PersistentStateComponent
    // =========================================================

    override fun getState(): State {
        return state
    }

    override fun loadState(
        state: State
    ) {
        this.state = state
    }

    // =========================================================
    // 阅读位置
    // =========================================================

    fun getPosition(
        filePath: String
    ): Long {

        return state.positions[
            filePath
        ] ?: 0L
    }

    fun savePosition(
        filePath: String,
        position: Long
    ) {

        state.positions[filePath] =
            position
    }

    // =========================================================
    // 字体大小
    // =========================================================

    var fontSize: Int
        get() = state.fontSize
        set(value) {
            state.fontSize = value
        }

    // =========================================================
    // 文字颜色
    // =========================================================

    var textColor: Int
        get() = state.textColor
        set(value) {
            state.textColor = value
        }
}
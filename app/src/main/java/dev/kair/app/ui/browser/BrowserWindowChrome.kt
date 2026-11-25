package dev.kair.app.ui.browser

import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.kair.app.R
import dev.kair.app.core.browser.BrowserPanelState

/** 顶栏高度：Agent / 接管两种模式保持一致，切模式不改变页面视口。 */
internal const val TOP_BAR_HEIGHT_DP = 32

/** 贴边半隐时露出的把手宽度。 */
internal const val EDGE_HANDLE_WIDTH_DP = 26

/** 贴边把手高度。 */
private const val EDGE_HANDLE_HEIGHT_DP = 64

/**
 * 常驻小窗的视图装配：顶栏 + 页面容器 + 挡板 + 贴边把手。
 *
 * 只负责「长什么样」与控件回调，窗口位置、类型、显隐由 [BrowserWindowHost] 管。
 * 顶栏在页面**之外**（纵向排列），截图按 WebView 位置裁 rect 时不会把顶栏截进画面。
 */
internal class BrowserWindowChrome(
    private val context: Context,
    onTouchBar: (View, MotionEvent) -> Boolean,
    onClose: () -> Unit,
    onExpand: () -> Unit,
    onSubmitUrl: () -> Unit,
) {

    private val density = context.resources.displayMetrics.density

    val pageArea: FrameLayout
    val pageFrame: FrameLayout = FrameLayout(context)
    val blocker: View
    val edgeHandle: FrameLayout
    val root: FrameLayout

    private val bar: LinearLayout
    private val titleView: TextView
    private val urlInput: EditText

    init {
        titleView = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 11f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            text = context.getString(R.string.browser_plugin_name)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        urlInput = EditText(context).apply {
            setTextColor(Color.WHITE)
            setHintTextColor(Color.argb(0x99, 0xFF, 0xFF, 0xFF))
            hint = context.getString(R.string.browser_url_hint)
            textSize = 10.5f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(dp(6), 0, dp(6), 0)
            background = rounded(Color.argb(0x33, 0xFF, 0xFF, 0xFF), dp(7))
            layoutParams = LinearLayout.LayoutParams(0, dp(24), 1f)
            visibility = View.GONE
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    onSubmitUrl()
                    true
                } else {
                    false
                }
            }
        }
        val close = barButton("×", onClose)
        bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(2), 0)
            background = rounded(Color.argb(0xF0, 0x1E, 0x1E, 0x22), 0)
            setOnTouchListener { view, event -> onTouchBar(view, event) }
        }
        bar.addView(titleView)
        bar.addView(urlInput)
        bar.addView(close)

        blocker = View(context).apply {
            isClickable = true
            setOnTouchListener { _, _ -> true }
        }
        pageArea = FrameLayout(context)
        pageArea.addView(
            pageFrame,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        pageArea.addView(
            blocker,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        edgeHandle = FrameLayout(context).apply {
            background = rounded(Color.argb(0xF0, 0x1E, 0x1E, 0x22), dp(12))
            visibility = View.GONE
            setOnClickListener { onExpand() }
            addView(
                TextView(context).apply {
                    text = "‹"
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    gravity = Gravity.CENTER
                },
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }

        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(
            bar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(TOP_BAR_HEIGHT_DP)),
        )
        column.addView(pageArea, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0))

        root = FrameLayout(context).apply {
            background = rounded(Color.TRANSPARENT, dp(14))
            clipToOutline = true
        }
        root.addView(
            column,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            edgeHandle,
            FrameLayout.LayoutParams(
                dp(EDGE_HANDLE_WIDTH_DP),
                dp(EDGE_HANDLE_HEIGHT_DP),
                Gravity.CENTER_VERTICAL or Gravity.END,
            ),
        )
    }

    fun render(state: BrowserPanelState) {
        titleView.text = state.title.takeIf { it.isNotBlank() }
            ?: state.url.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.browser_plugin_name)
        if (urlInput.visibility == View.VISIBLE && !urlInput.hasFocus() && urlInput.text.toString() != state.url) {
            urlInput.setText(state.url)
        }
    }

    /** 接管态：标题换成地址输入框；挡板收起，页面接收触摸。 */
    fun setHuman(human: Boolean, url: String) {
        blocker.visibility = if (human) View.GONE else View.VISIBLE
        titleView.visibility = if (human) View.GONE else View.VISIBLE
        urlInput.visibility = if (human) View.VISIBLE else View.GONE
        if (human && !urlInput.hasFocus()) urlInput.setText(url)
    }

    fun setPageHeight(height: Int) {
        pageArea.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            height,
        )
    }

    /** 贴边：toRight 时窗口在右边缘，可见的是窗口左侧，把手放左边。 */
    fun showEdgeHandle(toRight: Boolean) {
        edgeHandle.visibility = View.VISIBLE
        val params = edgeHandle.layoutParams as FrameLayout.LayoutParams
        params.gravity = Gravity.CENTER_VERTICAL or (if (toRight) Gravity.START else Gravity.END)
        edgeHandle.layoutParams = params
        (edgeHandle.getChildAt(0) as? TextView)?.text = if (toRight) "‹" else "›"
    }

    fun hideEdgeHandle() {
        edgeHandle.visibility = View.GONE
    }

    fun urlText(): String = urlInput.text?.toString()?.trim().orEmpty()

    private fun barButton(label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 10.5f
            gravity = Gravity.CENTER
            setPadding(dp(7), dp(2), dp(7), dp(2))
            background = rounded(Color.argb(0x33, 0xFF, 0xFF, 0xFF), dp(999))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun dp(value: Int): Int = (value * density).toInt()

    private fun rounded(color: Int, radius: Int) = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        cornerRadius = radius.toFloat()
        setColor(color)
    }
}

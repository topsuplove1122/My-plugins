package com.github.razertexz

import androidx.constraintlayout.widget.ConstraintLayout
import android.content.Context
import android.widget.ImageView
import android.view.View
import android.graphics.Color
import androidx.recyclerview.widget.RecyclerView
import android.view.ViewGroup

import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.*
import com.aliucord.Utils
import com.aliucord.utils.DimenUtils

import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.entries.MessageEntry
import com.discord.utilities.view.text.SimpleDraweeSpanTextView

import com.lytefast.flexinput.R

@AliucordPlugin(requiresRestart = true)
class Main : Plugin() {
    private val ID_BTN_COPY_TEXT = 1001 
    private val ID_BTN_COPY_LINK = 1002

    override fun start(ctx: Context) {
        val btnSize = DimenUtils.dpToPx(40)
        val btnPadding = DimenUtils.dpToPx(8)
        
        // 【關鍵調整 1】原本是正數 (4)，現在改成負數 (-5)
        // 這會把按鈕往上拉約 5dp。如果不夠高，可以改成 -8 或 -10
        val btnTopMargin = DimenUtils.dpToPx(-5) 
        
        val btnGap = DimenUtils.dpToPx(4) // 按鈕之間的間距

        val iconText = ctx.getDrawable(R.e.ic_copy_24dp)?.mutate()
        iconText?.setTint(Color.CYAN)

        val iconLink = ctx.getDrawable(R.e.ic_copy_24dp)?.mutate()
        iconLink?.setTint(Color.RED)

        patcher.after<WidgetChatListAdapterItemMessage>(
            "processMessageText", 
            SimpleDraweeSpanTextView::class.java, 
            MessageEntry::class.java
        ) {
            val messageEntry = it.args[1] as MessageEntry
            val holder = it.thisObject as RecyclerView.ViewHolder
            val root = holder.itemView as ConstraintLayout

            // ==========================================
            // 1. 藍色按鈕 (文字) - 主定位點
            // ==========================================
            var btnText = root.findViewById<ImageView>(ID_BTN_COPY_TEXT)
            if (btnText == null) {
                btnText = ImageView(root.context).apply {
                    id = ID_BTN_COPY_TEXT
                    if (iconText != null) setImageDrawable(iconText)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(btnPadding, btnPadding, btnPadding, btnPadding)
                    
                    layoutParams = ConstraintLayout.LayoutParams(btnSize, btnSize).apply {
                        topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                        endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                        
                        // 這裡使用負邊距把它往上提
                        topMargin = btnTopMargin 
                        rightMargin = 0 // 靠最右邊
                    }
                }
                root.addView(btnText)
            }
            btnText.visibility = View.VISIBLE
            btnText.setOnClickListener {
                val content = messageEntry.message.content
                Utils.setClipboard(content, content)
                Utils.showToast("已複製文字！")
            }

            // ==========================================
            // 2. 紅色按鈕 (連結) - 跟隨藍色按鈕
            // ==========================================
            var targetUrl: String? = null
            val embeds = messageEntry.message.embeds
            if (embeds.isNotEmpty()) {
                val embed = embeds[0]
                try {
                    val urlField = embed::class.java.getDeclaredField("url")
                    urlField.isAccessible = true
                    targetUrl = urlField.get(embed) as String?
                } catch (e: Exception) {}
            }

            var btnLink = root.findViewById<ImageView>(ID_BTN_COPY_LINK)
            
            if (targetUrl != null && targetUrl!!.isNotEmpty()) {
                if (btnLink == null) {
                    btnLink = ImageView(root.context).apply {
                        id = ID_BTN_COPY_LINK
                        if (iconLink != null) setImageDrawable(iconLink)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setPadding(btnPadding, btnPadding, btnPadding, btnPadding)

                        layoutParams = ConstraintLayout.LayoutParams(btnSize, btnSize).apply {
                            // 【關鍵調整 2】
                            // 它的頂部對齊藍色按鈕的頂部 (這樣就會一起上去)
                            topToTop = ID_BTN_COPY_TEXT 
                            
                            // 它的右邊接在藍色按鈕的左邊
                            endToStart = ID_BTN_COPY_TEXT 
                            
                            // 這裡只需要設定右邊距(兩按鈕的間隔)，不需要設定 topMargin
                            rightMargin = btnGap 
                        }
                    }
                    root.addView(btnLink)
                }
                btnLink.visibility = View.VISIBLE
                val finalUrl = targetUrl
                btnLink.setOnClickListener {
                    Utils.setClipboard(finalUrl, finalUrl)
                    Utils.showToast("已複製連結！")
                }
            } else {
                btnLink?.visibility = View.GONE
            }

            // 取得聊天室 Adapter 的類別，用來辨識現在是不是在「聊天室」
            // 如果不加這個檢查，你連伺服器列表、成員列表都滑不動了
            val chatAdapterClass = com.discord.widgets.chat.list.adapter.WidgetChatListAdapter::class.java

            // 1. 攔截瞬間跳轉 (scrollToPosition)
            patcher.before<RecyclerView>("scrollToPosition", Int::class.javaPrimitiveType) {
                val adapter = (it.thisObject as RecyclerView).adapter
                // 檢查：只有當這個 RecyclerView 是「聊天室」的時候才攔截
                if (adapter != null && adapter::class.java == chatAdapterClass) {
                    it.result = null // 設為 null 代表「什麼都不做」，直接取消原本的執行
                }
            }

            // 2. 攔截平滑捲動 (smoothScrollToPosition) <-- 這就是你之前註解無效的主因
            patcher.before<RecyclerView>("smoothScrollToPosition", Int::class.javaPrimitiveType) {
                val adapter = (it.thisObject as RecyclerView).adapter
                if (adapter != null && adapter::class.java == chatAdapterClass) {
                    it.result = null
                }
            }

            // 3. 攔截帶有偏移量的跳轉 (scrollToPositionWithOffset)
            // 這是 LinearLayoutManager 的方法，很多精確定位都用這個
            patcher.before<androidx.recyclerview.widget.LinearLayoutManager>(
                "scrollToPositionWithOffset", 
                Int::class.javaPrimitiveType, 
                Int::class.javaPrimitiveType
            ) {
                // 這裡比較麻煩，LayoutManager 不一定能直接拿到 Adapter
                // 但我們可以檢查這個 LayoutManager 綁定的 RecyclerView
                val layoutManager = it.thisObject as androidx.recyclerview.widget.LinearLayoutManager
                // 這是個投機的檢查法：聊天室通常會設 stackFromEnd = true (訊息從底部開始堆疊)
                // 或者你可以嘗試透過反射去抓 RecyclerView (比較慢)
            
                // 簡單判斷：如果這個行為發生時，我們正在看聊天室，就攔截
                // 這裡為了保險，我們先只攔截 RecyclerView 的那兩個。
                // 如果上面兩個攔截後還有漏網之魚，再考慮把這個打開。
            
                // it.result = null 
            }
        }
    }

    override fun stop(ctx: Context) = patcher.unpatchAll()
}

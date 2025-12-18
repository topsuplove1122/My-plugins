package com.github.razertexz

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.*
import com.aliucord.utils.DimenUtils

// Discord Imports
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapter
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.entries.MessageEntry
import com.discord.utilities.view.text.SimpleDraweeSpanTextView

// Resources
import com.lytefast.flexinput.R
import rx.functions.Action0

@AliucordPlugin(requiresRestart = true)
class Main : Plugin() {
    private val ID_BTN_COPY_TEXT = 1001 
    private val ID_BTN_COPY_LINK = 1002

    // 狀態變數
    private var wasAtBottom = false
    private var oldListSize = 0

    override fun start(ctx: Context) {
        
        // ========================================================================
        // Feature 1: 完美防跳轉 (Perfect Anti-Auto-Scroll)
        // ========================================================================
        
        // [A] 攔截系統主動的滾動指令
        patcher.before<WidgetChatListAdapter>(
            "scrollToMessageId", 
            java.lang.Long.TYPE, 
            Action0::class.java            
        ) {
            val adapter = it.thisObject as WidgetChatListAdapter
            val layoutManager = adapter.layoutManager

            if (layoutManager is LinearLayoutManager) {
                // 如果我們不在最底部 (Index > 0)，或是我們剛做過 "微調" 操作
                // 拒絕系統的跳轉指令
                val firstVisible = layoutManager.findFirstVisibleItemPosition()
                if (firstVisible > 0) {
                    it.result = null
                }
            }
        }

        val dataClass = try {
            com.discord.widgets.chat.list.adapter.WidgetChatListAdapter.Data::class.java
        } catch (e: Throwable) {
            Class.forName("com.discord.widgets.chat.list.adapter.WidgetChatListAdapter\$Data")
        }

        // [B] 數據更新前：執行 "微調 (Micro-Scroll)"
        patcher.before<WidgetChatListAdapter>(
            "setData",
            dataClass
        ) {
            val adapter = it.thisObject as WidgetChatListAdapter
            val layoutManager = adapter.layoutManager as? LinearLayoutManager ?: return@before
            
            oldListSize = adapter.itemCount

            // 檢查是否 "完全" 在底部 (Index 0 且完全可見)
            val firstVisible = layoutManager.findFirstVisibleItemPosition()
            val completelyVisible = layoutManager.findFirstCompletelyVisibleItemPosition()
            
            // 如果使用者目前完全貼在底部 (這是導致強制跳轉的原因)
            if (firstVisible == 0 || completelyVisible == 0) {
                wasAtBottom = true
                
                // 【關鍵修改】：主動幫使用者 "往上拉一點點"
                // scrollBy(0, 1) 在 Reverse Layout 中通常代表讓內容稍微位移
                // 這會破壞系統的 "Anchor to Bottom" 判定
                adapter.recycler.scrollBy(0, 10)
            } else {
                wasAtBottom = false
            }
        }

        // [C] 數據更新後：執行位置修正
        patcher.after<WidgetChatListAdapter>(
            "setData",
            dataClass
        ) {
            val adapter = it.thisObject as WidgetChatListAdapter
            val layoutManager = adapter.layoutManager as? LinearLayoutManager ?: return@after
            
            val newListSize = adapter.itemCount
            val isNewItemAdded = newListSize > oldListSize

            // 如果原本在底部 (wasAtBottom) 且有新訊息進來 (isNewItemAdded)
            // 因為我們在 [B] 做了微調，系統現在認為我們是 "正在看歷史訊息"，所以可能不會自動跳轉。
            // 但為了保險起見，我們還是強制將視圖鎖定在 Index 1 (原本的最新訊息)。
            if (wasAtBottom && isNewItemAdded) {
                adapter.recycler.post {
                    try {
                        // 強制鎖定到 Index 1 (舊訊息的位置)
                        // 使用 offset 0 讓它貼齊底部
                        layoutManager.scrollToPositionWithOffset(1, 0)
                    } catch (e: Exception) { e.printStackTrace() }
                }
            }
        }

        // ========================================================================
        // Feature 2: 複製按鈕 (保持原樣)
        // ========================================================================
        val btnSize = DimenUtils.dpToPx(40)
        val btnPadding = DimenUtils.dpToPx(8)
        val btnTopMargin = DimenUtils.dpToPx(-5) 
        val btnGap = DimenUtils.dpToPx(4)

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

            // 1. Blue Button (Text)
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
                        topMargin = btnTopMargin 
                        rightMargin = 0
                    }
                }
                root.addView(btnText)
            }
            btnText.visibility = View.VISIBLE
            btnText.setOnClickListener {
                val content = messageEntry.message.content
                Utils.setClipboard(content, content)
                Utils.showToast("Copied Text!")
            }

            // 2. Red Button (Link)
            var targetUrl: String? = null
            val embeds = messageEntry.message.embeds
            val urlRegex = Regex("https?://[^\\s\\)\\]]+")

            if (embeds.isNotEmpty()) {
                val embed = embeds[0]
                try {
                    val urlField = embed::class.java.getDeclaredField("url")
                    urlField.isAccessible = true
                    targetUrl = urlField.get(embed) as String?
                } catch (e: Exception) {}

                if (targetUrl == null || targetUrl!!.isEmpty()) {
                    try {
                        val descField = embed::class.java.getDeclaredField("description")
                        descField.isAccessible = true
                        val description = descField.get(embed) as String?
                        if (description != null) {
                            val match = urlRegex.find(description, 0)
                            if (match != null) targetUrl = match.value
                        }
                    } catch (e: Exception) {}
                }
            }

            if (targetUrl == null && messageEntry.message.content != null) {
                 val contentStr = messageEntry.message.content
                 if (contentStr != null) {
                     val match = urlRegex.find(contentStr, 0)
                     if (match != null) targetUrl = match.value
                 }
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
                            topToTop = ID_BTN_COPY_TEXT 
                            endToStart = ID_BTN_COPY_TEXT 
                            rightMargin = btnGap 
                        }
                    }
                    root.addView(btnLink)
                }
                btnLink.visibility = View.VISIBLE
                val finalUrl = targetUrl
                btnLink.setOnClickListener {
                    Utils.setClipboard(finalUrl, finalUrl)
                    Utils.showToast("Copied Link!")
                }
            } else {
                btnLink?.visibility = View.GONE
            }
        }
    }

    override fun stop(ctx: Context) = patcher.unpatchAll()
}

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
        // Feature 1: 絕對防跳轉 (Absolute Anti-Auto-Scroll)
        // ========================================================================
        
        // [A] 攔截系統跳轉
        // 為了徹底防止它跳到底部，我們這裡做一個比較激進的攔截。
        // 如果系統試圖跳到 MessageId = 0 (最新訊息)，我們直接擋掉。
        // 副作用：你按聊天室右下角的「跳到底部」按鈕可能會失效一次，需要按兩次，
        // 但這能保證新訊息絕對不會拉動你的畫面。
        patcher.before<WidgetChatListAdapter>(
            "scrollToMessageId", 
            java.lang.Long.TYPE, 
            Action0::class.java            
        ) {
            val messageId = it.args[0] as Long
            
            // 如果目標是 0 (底部)，且我們不在看歷史訊息 (這通常是系統自動觸發的)
            // 我們選擇攔截它。
            if (messageId == 0L) {
                it.result = null
            }
        }

        val dataClass = try {
            com.discord.widgets.chat.list.adapter.WidgetChatListAdapter.Data::class.java
        } catch (e: Throwable) {
            Class.forName("com.discord.widgets.chat.list.adapter.WidgetChatListAdapter\$Data")
        }

        // [B] 數據更新前：記錄狀態
        patcher.before<WidgetChatListAdapter>(
            "setData",
            dataClass
        ) {
            val adapter = it.thisObject as WidgetChatListAdapter
            val layoutManager = adapter.layoutManager as? LinearLayoutManager ?: return@before
            
            oldListSize = adapter.itemCount

            // 嚴格判定：只有 Index 0 才是底部
            val firstVisible = layoutManager.findFirstVisibleItemPosition()
            wasAtBottom = (firstVisible == 0)
        }

        // [C] 數據更新後：使用 OnLayoutChangeListener 精準鎖定
        patcher.after<WidgetChatListAdapter>(
            "setData",
            dataClass
        ) {
            val adapter = it.thisObject as WidgetChatListAdapter
            val layoutManager = adapter.layoutManager as? LinearLayoutManager ?: return@after
            
            val newListSize = adapter.itemCount
            val isNewItemAdded = newListSize > oldListSize

            // 如果原本在底部，且有新訊息進來
            if (wasAtBottom && isNewItemAdded) {
                val recyclerView = adapter.recycler
                
                // 定義一個監聽器，在 Layout 完成的瞬間執行
                val listener = object : View.OnLayoutChangeListener {
                    override fun onLayoutChange(
                        v: View?, left: Int, top: Int, right: Int, bottom: Int,
                        oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
                    ) {
                        // 立刻移除監聽器，避免重複觸發
                        recyclerView.removeOnLayoutChangeListener(this)
                        
                        // 【核心邏輯】
                        // 此時新訊息已經插入到 Index 0，畫面可能已經被推上去了。
                        // 我們強制將 Index 1 (也就是原本的舊訊息) 鎖定在底部 (Offset 0)。
                        layoutManager.scrollToPositionWithOffset(1, 0)
                    }
                }
                
                // 掛載監聽器
                recyclerView.addOnLayoutChangeListener(listener)
            }
        }

        // ========================================================================
        // Feature 2: 複製按鈕 (保持不變)
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

package com.github.razertexz

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.AdapterDataObserver

import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.*
import com.aliucord.utils.DimenUtils

// Discord Imports
import com.discord.widgets.chat.list.WidgetChatList
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

    override fun start(ctx: Context) {
        
        // ========================================================================
        // Feature 1: 終極防跳轉 (Observer Anti-Auto-Scroll)
        // ========================================================================
        
        // [A] 攔截系統主動的滾動指令 (保持原樣，防止 App 強制介入)
        patcher.before<WidgetChatListAdapter>(
            "scrollToMessageId", 
            java.lang.Long.TYPE, 
            Action0::class.java            
        ) {
            val messageId = it.args[0] as Long
            if (messageId == 0L) {
                // 只有當目標是 0 (最新訊息) 時才攔截
                it.result = null
            }
        }

        // [B] 掛載數據監聽器
        // 我們Hook WidgetChatList 的 onViewBound，這是介面初始化的時候
        patcher.after<WidgetChatList>(
            "onViewBound",
            View::class.java
        ) {
            val chatListFragment = it.thisObject as WidgetChatList
            
            // 透過反射獲取 adapter (因為它是 private 的)
            val adapterField = chatListFragment::class.java.getDeclaredField("adapter")
            adapterField.isAccessible = true
            val adapter = adapterField.get(chatListFragment) as? WidgetChatListAdapter

            if (adapter != null) {
                // 註冊我們自己的觀察者
                // 這會在 "新訊息真正插入列表" 的瞬間被觸發
                adapter.registerAdapterDataObserver(object : AdapterDataObserver() {
                    
                    override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                        super.onItemRangeInserted(positionStart, itemCount)
                        
                        // 1. 檢查是否是插入在 Index 0 (最新訊息位置)
                        if (positionStart == 0) {
                            val layoutManager = adapter.layoutManager as? LinearLayoutManager ?: return
                            
                            // 2. 檢查使用者目前的位置
                            val firstVisible = layoutManager.findFirstVisibleItemPosition()
                            
                            // 3. 判斷邏輯：
                            // 如果 firstVisible 為 0 (貼底) 或 1 (剛被擠上來)
                            // 這表示使用者原本在看最新的訊息。
                            // 當新訊息插入(Index 0)時，原本的訊息變成了 Index 1。
                            // 系統預設會顯示新的 Index 0，我們要強制它顯示 Index 1。
                            if (firstVisible <= 1) {
                                // 強制鎖定到 Index 1 (Offset 0 表示貼齊底部)
                                layoutManager.scrollToPositionWithOffset(1, 0)
                            }
                        }
                    }
                })
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

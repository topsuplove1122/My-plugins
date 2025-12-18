package com.github.razertexz

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.OnScrollListener

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
        // Feature 1: 底部封印術 (The Bottom Seal)
        // ========================================================================
        
        // [A] 攔截系統強制跳轉 (防止 App 在載入時強制拉到底)
        patcher.before<WidgetChatListAdapter>(
            "scrollToMessageId", 
            java.lang.Long.TYPE, 
            Action0::class.java            
        ) {
            val messageId = it.args[0] as Long
            // 只要目標是 0 (底部)，一律攔截
            if (messageId == 0L) {
                it.result = null
            }
        }

        // [B] 掛載 "底部回彈" 監聽器
        patcher.after<WidgetChatList>(
            "onViewBound",
            View::class.java
        ) {
            val chatListFragment = it.thisObject as WidgetChatList
            try {
                // 取得 Adapter (為了確保我們操作的是正確的 View)
                val adapterField = chatListFragment::class.java.getDeclaredField("adapter")
                adapterField.isAccessible = true
                val adapter = adapterField.get(chatListFragment) as? WidgetChatListAdapter

                if (adapter != null) {
                    val recyclerView = adapter.recycler
                    
                    // 移除舊的監聽器 (避免重複)並新增我們的邏輯
                    recyclerView.clearOnScrollListeners()
                    
                    recyclerView.addOnScrollListener(object : OnScrollListener() {
                        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                            super.onScrollStateChanged(recyclerView, newState)
                            
                            // 當手指放開，且列表停止滾動時 (IDLE)
                            if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                                // 檢查是否 "無法再繼續往下滑" (代表到底了)
                                // canScrollVertically(1) : 正數代表檢查是否能向下滑
                                if (!recyclerView.canScrollVertically(1)) {
                                    
                                    // 【核心邏輯】：底部回彈
                                    // 既然已經到底了，我們強制往回 (上) 滾動 1 像素
                                    // 這會讓 Offset 從 0 變成 1 (或極小值)
                                    // 系統就會判定我們 "不在底部"
                                    recyclerView.scrollBy(0, -150)
                                    
                                    // 這樣一來，你的畫面看起來是在最底部 (肉眼看不出 1px 差異)
                                    // 但在程式邏輯上，你永遠懸浮在底部的上方一點點。
                                }
                            }
                        }
                    })
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        // ========================================================================
        // Feature 2: 複製按鈕 (包含修復後的連結抓取)
        // ========================================================================
        val btnSize = DimenUtils.dpToPx(40)
        val btnPadding = DimenUtils.dpToPx(8)
        val btnTopMargin = DimenUtils.dpToPx(-5) 
        val btnGap = DimenUtils.dpToPx(4)

        val iconText = ctx.getDrawable(R.e.ic_copy_24dp)?.mutate()
        iconText?.setTint(Color.CYAN)

        val iconLink = ctx.getDrawable(R.e.ic_copy_24dp)?.mutate()
        iconLink?.setTint(Color.RED)

        // Regex: 抓取 Markdown 連結 [文字](網址) 中的網址
        val markdownLinkRegex = Regex("""\]\((https?://[^\)]+)\)""")

        patcher.after<WidgetChatListAdapterItemMessage>(
            "processMessageText", 
            SimpleDraweeSpanTextView::class.java, 
            MessageEntry::class.java
        ) {
            val messageEntry = it.args[1] as MessageEntry
            val holder = it.thisObject as RecyclerView.ViewHolder
            val root = holder.itemView as ConstraintLayout

            // --- 1. Blue Button (Text) ---
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

            // --- 2. Red Button (Link) ---
            var targetUrl: String? = null
            val embeds = messageEntry.message.embeds
            
            if (embeds.isNotEmpty()) {
                val embed = embeds[0]
                
                // [A] Title URL
                try {
                    val urlField = embed::class.java.getDeclaredField("url")
                    urlField.isAccessible = true
                    val rawUrl = urlField.get(embed) as String?
                    if (rawUrl != null && rawUrl.isNotEmpty()) targetUrl = rawUrl
                } catch (e: Exception) {}

                // [B] Description (Markdown Link)
                if (targetUrl == null) {
                    try {
                        val descField = embed::class.java.getDeclaredField("description")
                        descField.isAccessible = true
                        val description = descField.get(embed) as String?
                        if (description != null) {
                            val match = markdownLinkRegex.find(description, 0)
                            if (match != null && match.groupValues.size > 1) {
                                targetUrl = match.groupValues[1]
                            }
                        }
                    } catch (e: Exception) {}
                }

                // [C] Fields (Markdown Link)
                if (targetUrl == null) {
                    try {
                        val fieldsField = embed::class.java.getDeclaredField("fields")
                        fieldsField.isAccessible = true
                        val fieldsList = fieldsField.get(embed) as List<*>?

                        if (fieldsList != null) {
                            for (fieldObj in fieldsList) {
                                if (fieldObj == null) continue
                                val valueField = fieldObj::class.java.getDeclaredField("value")
                                valueField.isAccessible = true
                                val valueText = valueField.get(fieldObj) as String?
                                
                                if (valueText != null) {
                                    val match = markdownLinkRegex.find(valueText, 0)
                                    if (match != null && match.groupValues.size > 1) {
                                        targetUrl = match.groupValues[1]
                                        break
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {}
                }
            }

            // [D] Content (Markdown Link or Raw URL)
            if (targetUrl == null && messageEntry.message.content != null) {
                 val contentStr = messageEntry.message.content
                 if (contentStr != null) {
                     val match = markdownLinkRegex.find(contentStr, 0)
                     if (match != null && match.groupValues.size > 1) {
                         targetUrl = match.groupValues[1]
                     } else {
                         val rawRegex = Regex("https?://[^\\s\\)\\]]+")
                         val rawMatch = rawRegex.find(contentStr, 0)
                         if (rawMatch != null) targetUrl = rawMatch.value
                     }
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

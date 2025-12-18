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

// Discord 相關引用
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapter
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.entries.MessageEntry
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
// 引用 Data 類別以便檢查列表長度
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapter$Data

// 資源引用
import com.lytefast.flexinput.R
import rx.functions.Action0

@AliucordPlugin(requiresRestart = true)
class Main : Plugin() {
    private val ID_BTN_COPY_TEXT = 1001 
    private val ID_BTN_COPY_LINK = 1002

    // 用來記錄數據更新前的狀態
    private var wasAtBottom = false
    private var oldListSize = 0

    override fun start(ctx: Context) {
        
        // ========================================================================
        // 功能 1 (加強版): 防止自動跳轉 (Anti-Auto-Scroll)
        // 包含：
        // A. 閱讀歷史訊息時，新訊息不強制拉到底部 (之前的邏輯)
        // B. 就在底部時，新訊息也不自動顯示 (新增的邏輯)
        // ========================================================================
        
        // 1. 攔截 scrollToMessageId (防止 App 主動發出的跳轉指令)
        patcher.before<WidgetChatListAdapter>(
            "scrollToMessageId", 
            Long::class.javaPrimitiveType, 
            Action0::class.java
        ) {
            val adapter = it.thisObject as WidgetChatListAdapter
            val layoutManager = adapter.layoutManager

            if (layoutManager is LinearLayoutManager) {
                val firstVisible = layoutManager.findFirstVisibleItemPosition()
                // 如果位置 > 5 (正在看歷史)，攔截跳轉
                if (firstVisible > 5) {
                    it.result = null
                }
            }
        }

        // 2. 監聽數據更新 (setData)，處理「在底部時新訊息進來」的情況
        patcher.before<WidgetChatListAdapter>(
            "setData",
            WidgetChatListAdapter$Data::class.java
        ) {
            val adapter = it.thisObject as WidgetChatListAdapter
            val layoutManager = adapter.layoutManager as? LinearLayoutManager ?: return@before
            
            // 記錄更新前的狀態
            // 0 是底部 (因為是 ReverseLayout)
            wasAtBottom = layoutManager.findFirstVisibleItemPosition() == 0
            
            // 記錄舊的列表長度
            oldListSize = adapter.itemCount
        }

        patcher.after<WidgetChatListAdapter>(
            "setData",
            WidgetChatListAdapter$Data::class.java
        ) {
            val adapter = it.thisObject as WidgetChatListAdapter
            val layoutManager = adapter.layoutManager as? LinearLayoutManager ?: return@after
            val newData = it.args[0] as WidgetChatListAdapter$Data
            
            // 檢查是否真的有新項目增加 (避免編輯訊息或狀態更新時也觸發)
            val isNewItemAdded = newData.list.size > oldListSize

            // 核心邏輯：
            // 如果原本在底部 (wasAtBottom) 且有新訊息進來 (isNewItemAdded)
            // 我們強制滾動到 Index 1。
            // 原理：新訊息是 Index 0，舊的最新訊息變成了 Index 1。
            // 滾動到 1 等於「停留在原本看到的最後一條訊息」，把新訊息藏在下面。
            if (wasAtBottom && isNewItemAdded) {
                adapter.recycler.post {
                    // 使用 scrollToPositionWithOffset(1, 0)
                    // 這會把 Index 1 (舊訊息) 鎖定在畫面底部
                    layoutManager.scrollToPositionWithOffset(1, 0)
                }
            }
        }

        // ========================================================================
        // 功能 2: 訊息複製按鈕 (Copy Buttons) - 保持不變
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

            // --- 1. 藍色按鈕 (複製文字) ---
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
                Utils.showToast("已複製文字！")
            }

            // --- 2. 紅色按鈕 (複製連結) ---
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
                    Utils.showToast("已複製連結！")
                }
            } else {
                btnLink?.visibility = View.GONE
            }
        }
    }

    override fun stop(ctx: Context) = patcher.unpatchAll()
}

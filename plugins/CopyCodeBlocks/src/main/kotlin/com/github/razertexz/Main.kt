package com.github.razertexz

import androidx.constraintlayout.widget.ConstraintLayout
import android.content.Context
import android.widget.ImageView
import android.view.View
import android.view.MotionEvent // 用來監聽觸控
import android.graphics.Color
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import android.view.ViewGroup

import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.*
import com.aliucord.Utils
import com.aliucord.utils.DimenUtils

import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.entries.MessageEntry
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapter
import com.discord.widgets.chat.list.WidgetChatList // 聊天室主介面

import com.lytefast.flexinput.R

@AliucordPlugin(requiresRestart = true)
class Main : Plugin() {
    private val ID_BTN_COPY_TEXT = 1001 
    private val ID_BTN_COPY_LINK = 1002
    
    // 【通行證變數】記錄最後一次按下「跳到底部」的時間
    private var lastJumpRequestTime = 0L

    override fun start(ctx: Context) {
        // ==========================================
        //  功能 0: 監聽「跳到底部」按鈕 (發放通行證)
        // ==========================================
        
        // 這是 Discord 聊天室介面建立時的方法
        patcher.after<WidgetChatList>("onViewCreated", View::class.java, android.os.Bundle::class.java) {
            val fragment = it.thisObject as WidgetChatList
            val view = it.args[0] as View
            
            // 嘗試找到 Discord原本的「跳到底部」按鈕
            // 這個 ID 通常是 chat_list_scroll_to_bottom
            val scrollBtnId = Utils.getResId("chat_list_scroll_to_bottom", "id")
            
            if (scrollBtnId != 0) {
                val scrollBtn = view.findViewById<View>(scrollBtnId)
                if (scrollBtn != null) {
                    // 加上觸控監聽：當手指按下按鈕時，更新通行證時間
                    scrollBtn.setOnTouchListener { _, event ->
                        if (event.action == MotionEvent.ACTION_DOWN) {
                            // 發放通行證！(更新時間戳記)
                            lastJumpRequestTime = System.currentTimeMillis()
                        }
                        // 回傳 false 代表「我不攔截這個事件」，讓它繼續傳給原本的 onClick 邏輯
                        false 
                    }
                }
            }
        }

        // ==========================================
        //  功能 1: 智慧型防捲動 (Anti-Scroll)
        // ==========================================
        
        val chatAdapterClass = WidgetChatListAdapter::class.java

        val blockScroll = { it: PreHookParam ->
            try {
                // 檢查是否擁有通行證 (距離上次按按鈕是否在 1.5 秒內)
                val isAllowed = (System.currentTimeMillis() - lastJumpRequestTime) < 1500

                if (!isAllowed) {
                    val adapter = (it.thisObject as RecyclerView).adapter
                    // 只有在「沒有通行證」且「是聊天室」的時候才攔截
                    if (adapter != null && adapter::class.java == chatAdapterClass) {
                        it.result = null
                    }
                }
            } catch (e: Exception) {}
        }

        // 1. 攔截瞬間跳轉
        patcher.before<RecyclerView>(
            "scrollToPosition", 
            Int::class.javaPrimitiveType!! 
        ) { blockScroll(it) }

        // 2. 攔截平滑捲動 (Discord 跳到底部按鈕、新訊息主要用這個)
        patcher.before<RecyclerView>(
            "smoothScrollToPosition", 
            Int::class.javaPrimitiveType!! 
        ) { blockScroll(it) }

        // 3. 攔截 LayoutManager 的捲動
        // 這裡我們也加上同樣的邏輯：如果剛按了按鈕，就允許 LayoutManager 捲動
        patcher.before<LinearLayoutManager>(
            "scrollToPositionWithOffset",
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!
        ) { 
             try {
                val isAllowed = (System.currentTimeMillis() - lastJumpRequestTime) < 1500
                if (!isAllowed) {
                     // 這裡比較難判斷 adapter，所以我們採取保守策略：
                     // 如果沒有按按鈕，我們就不讓 LayoutManager 做這個操作
                     // (注意：如果這導致副作用，可以把這裡註解掉)
                     // it.result = null 
                }
             } catch (e: Exception) {}
        }


        // ==========================================
        //  功能 2: 複製按鈕 (Copy Buttons) - 保持原樣
        // ==========================================
        
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

            // --- 藍色按鈕 ---
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

            // --- 紅色按鈕 ---
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

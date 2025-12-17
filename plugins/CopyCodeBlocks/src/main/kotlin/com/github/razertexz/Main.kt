package com.github.razertexz

import androidx.constraintlayout.widget.ConstraintLayout
import android.content.Context
import android.widget.ImageView
import android.view.View
import android.view.MotionEvent
import android.graphics.Color
import android.view.ViewGroup

// 引入關鍵的 RecyclerView 類別
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager

import androidx.fragment.app.Fragment 

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
    
    // 通行證時間戳記
    private var lastJumpRequestTime = 0L

    override fun start(ctx: Context) {
        // ==========================================
        //  功能 0: 監聽「跳到底部」按鈕
        // ==========================================
        patcher.after<Fragment>("onViewCreated", View::class.java, android.os.Bundle::class.java) {
            // 嘗試綁定按鈕，不管是不是聊天室 Fragment，只要找得到 ID 就綁定
            try {
                val view = it.args[0] as View
                val scrollBtnId = Utils.getResId("chat_list_scroll_to_bottom", "id")
                
                if (scrollBtnId != 0) {
                    val scrollBtn = view.findViewById<View>(scrollBtnId)
                    if (scrollBtn != null) {
                        scrollBtn.setOnTouchListener { _, event ->
                            if (event.action == MotionEvent.ACTION_DOWN) {
                                // 發放 3 秒通行證 (給久一點)
                                lastJumpRequestTime = System.currentTimeMillis()
                            }
                            false 
                        }
                    }
                }
            } catch (e: Exception) {}
        }

        // ==========================================
        //  功能 1: 無差別防捲動 (Nuclear Anti-Scroll)
        // ==========================================
        
        // 這是我們的攔截守門員
        // 如果你沒有按按鈕，我就不讓你捲動，不管你是誰
        fun shouldBlock(): Boolean {
            // 檢查是否在 2 秒通行證時間內
            if ((System.currentTimeMillis() - lastJumpRequestTime) < 2000) {
                return false // 有通行證，放行
            }
            return true // 攔截
        }

        // 1. RecyclerView: scrollToPosition
        patcher.before<RecyclerView>(
            "scrollToPosition", 
            Int::class.javaPrimitiveType!! 
        ) {
            if (shouldBlock()) it.result = null
        }

        // 2. RecyclerView: smoothScrollToPosition
        patcher.before<RecyclerView>(
            "smoothScrollToPosition", 
            Int::class.javaPrimitiveType!! 
        ) {
            if (shouldBlock()) it.result = null
        }

        // 3. LinearLayoutManager: scrollToPosition
        // Discord 很可能直接對 LayoutManager 呼叫這個
        patcher.before<LinearLayoutManager>(
            "scrollToPosition", 
            Int::class.javaPrimitiveType!!
        ) {
            if (shouldBlock()) it.result = null
        }

        // 4. LinearLayoutManager: smoothScrollToPosition
        patcher.before<LinearLayoutManager>(
            "smoothScrollToPosition", 
            RecyclerView::class.java, 
            RecyclerView.State::class.java, 
            Int::class.javaPrimitiveType!!
        ) {
            if (shouldBlock()) it.result = null
        }

        // 5. LinearLayoutManager: scrollToPositionWithOffset
        // 這是最常見的「瞬間跳到底部」方法
        patcher.before<LinearLayoutManager>(
            "scrollToPositionWithOffset",
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!
        ) { 
            if (shouldBlock()) it.result = null
        }

        // 6. 【新增】LinearLayoutManager: startSmoothScroll
        // 這是所有平滑捲動的底層入口，攔截這個最強力
        try {
            patcher.before<LinearLayoutManager>(
                "startSmoothScroll",
                androidx.recyclerview.widget.RecyclerView.SmoothScroller::class.java
            ) {
                if (shouldBlock()) it.result = null
            }
        } catch (e: Exception) {
            // 某些舊版 Android 可能沒有這個方法，忽略錯誤
        }

        // ==========================================
        //  功能 2: 複製按鈕 (保持不變)
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

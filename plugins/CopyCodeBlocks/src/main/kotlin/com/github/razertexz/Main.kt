package com.github.razertexz

import androidx.constraintlayout.widget.ConstraintLayout
import android.content.Context
import android.widget.ImageView
import android.view.View
import android.view.MotionEvent
import android.graphics.Color
import android.view.ViewGroup

// 引入關鍵類別
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
    
    // 儲存我們找到的 LayoutManager，方便隨時控制它
    private var chatLayoutManager: LinearLayoutManager? = null

    override fun start(ctx: Context) {
        // ==========================================
        //  功能 0: 監聽「跳到底部」按鈕 & 抓取 LayoutManager
        // ==========================================
        patcher.after<Fragment>("onViewCreated", View::class.java, android.os.Bundle::class.java) {
            // 確保是聊天室
            if (it.thisObject::class.java.name.contains("WidgetChatList")) {
                val view = it.args[0] as View
                
                // 1. 抓取 RecyclerView 並獲取 LayoutManager
                val recyclerId = Utils.getResId("chat_list_recycler_view", "id")
                if (recyclerId != 0) {
                    val recycler = view.findViewById<RecyclerView>(recyclerId)
                    if (recycler != null) {
                        chatLayoutManager = recycler.layoutManager as? LinearLayoutManager
                        
                        // 【關鍵】：一開始就先強制解除鎖定底部，避免一進入就亂跳
                        // 但這可能會導致一開始不在最底部，所以我們用 ScrollListener 來動態調整
                        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                                super.onScrollStateChanged(recyclerView, newState)
                                // 當使用者手動拖曳時，強制把 stackFromEnd 關掉
                                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                                    chatLayoutManager?.stackFromEnd = false
                                }
                            }
                        })
                    }
                }

                // 2. 綁定「跳到底部」按鈕
                val scrollBtnId = Utils.getResId("chat_list_scroll_to_bottom", "id")
                if (scrollBtnId != 0) {
                    val scrollBtn = view.findViewById<View>(scrollBtnId)
                    if (scrollBtn != null) {
                        scrollBtn.setOnTouchListener { _, event ->
                            if (event.action == MotionEvent.ACTION_DOWN) {
                                // 當按下按鈕時：
                                // 1. 把 stackFromEnd 開回來 (這樣才能吸附到底部)
                                chatLayoutManager?.stackFromEnd = true
                                // 2. 順便強制捲動一下，確保觸發
                                chatLayoutManager?.scrollToPosition(
                                    chatLayoutManager?.itemCount?.minus(1) ?: 0
                                )
                            }
                            false 
                        }
                    }
                }
            }
        }

        // ==========================================
        //  功能 1: 暴力鎖定 (Force Lock)
        // ==========================================
        
        // 攔截 Discord 試圖把 stackFromEnd 設為 true 的行為
        // Discord 會在很多地方(例如打開鍵盤、切換頻道)偷偷把這個改回 true
        // 我們要阻止它，除非是我們自己(透過按鈕)想改的
        patcher.before<LinearLayoutManager>(
            "setStackFromEnd", 
            Boolean::class.javaPrimitiveType!!
        ) {
            val manager = it.thisObject as LinearLayoutManager
            
            // 只有當這是聊天室的 Manager 時才攔截
            if (manager === chatLayoutManager) {
                // 如果傳入的是 true (Discord 想鎖定底部)，我們直接把它改成 false
                // 除非...這裡很難判斷是不是按鈕觸發的。
                // 比較好的做法是：永遠強制設為 false，只有按鈕點擊事件裡直接修改 field
                
                // 改寫參數：強制設為 false
                it.args[0] = false
            }
        }

        // ==========================================
        //  功能 2: 複製按鈕 (Copy Buttons) - 保持不變
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

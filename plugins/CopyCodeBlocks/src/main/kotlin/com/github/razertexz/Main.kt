package com.github.razertexz

import android.content.Context
import android.widget.ImageView
import android.view.View
import android.view.MotionEvent
import android.graphics.Color
import android.view.ViewGroup
import android.os.Bundle

// 引入 RecyclerView 相關類別
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.constraintlayout.widget.ConstraintLayout
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
    
    // 用來標記是否剛剛按下了跳轉按鈕
    private var isJumping = false

    override fun start(ctx: Context) {
        
        // ==========================================
        //  功能 1: 物理防捲動 (Direct Layout Control)
        // ==========================================
        
        // 我們不 Hook 函式了，直接在介面建立時介入
        patcher.after<Fragment>("onViewCreated", View::class.java, Bundle::class.java) {
            // 確保是聊天室 Fragment
            if (it.thisObject::class.java.name.contains("WidgetChatList")) {
                val view = it.args[0] as View
                
                // 1. 取得 RecyclerView
                val recyclerId = Utils.getResId("chat_list_recycler_view", "id")
                if (recyclerId != 0) {
                    val recycler = view.findViewById<RecyclerView>(recyclerId)
                    if (recycler != null) {
                        
                        // 【關鍵步驟 A】初始化時，強制關閉「置底堆疊」
                        // 這會讓新訊息進來時，列表不會自動往上頂
                        (recycler.layoutManager as? LinearLayoutManager)?.stackFromEnd = false

                        // 【關鍵步驟 B】監聽滑動事件
                        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                                super.onScrollStateChanged(recyclerView, newState)
                                
                                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return

                                // 當使用者手指碰到螢幕並開始拖曳時 (DRAGGING)
                                // 或者滑動停止時 (IDLE)
                                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                                    // 使用者正在手動閱讀，將 isJumping 設為 false
                                    isJumping = false
                                    // 強制鎖定，不讓新訊息干擾
                                    layoutManager.stackFromEnd = false
                                }
                            }

                            // 這裡可以處理更細緻的邏輯，例如當 adapter 變動時
                            // 但通常 stackFromEnd = false 就足夠強大
                        })
                        
                        // 【關鍵步驟 C】透過 AdapterDataObserver 監聽新訊息
                        // 這是為了防止 Discord 在接收訊息時強制把 stackFromEnd 改回來
                        recycler.adapter?.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                                super.onItemRangeInserted(positionStart, itemCount)
                                // 如果沒有在執行跳轉，再次強制鎖死
                                if (!isJumping) {
                                    (recycler.layoutManager as? LinearLayoutManager)?.stackFromEnd = false
                                }
                            }
                        })
                    }
                }

                // 2. 處理「跳到底部」按鈕
                val scrollBtnId = Utils.getResId("chat_list_scroll_to_bottom", "id")
                if (scrollBtnId != 0) {
                    val scrollBtn = view.findViewById<View>(scrollBtnId)
                    if (scrollBtn != null) {
                        scrollBtn.setOnTouchListener { v, event ->
                            if (event.action == MotionEvent.ACTION_DOWN) {
                                isJumping = true
                                // 當按下按鈕時，暫時允許自動捲動，這樣才能順利吸到底部
                                val recycler = view.findViewById<RecyclerView>(recyclerId)
                                val layoutManager = recycler?.layoutManager as? LinearLayoutManager
                                if (layoutManager != null) {
                                    layoutManager.stackFromEnd = true
                                    // 強制滾動到底部
                                    layoutManager.scrollToPosition(layoutManager.itemCount - 1)
                                }
                            }
                            false 
                        }
                    }
                }
            }
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

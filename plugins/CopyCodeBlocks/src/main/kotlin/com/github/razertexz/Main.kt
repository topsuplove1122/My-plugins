package com.github.razertexz

import android.content.Context
import android.widget.ImageView
import android.view.View
import android.view.MotionEvent
import android.graphics.Color
import android.os.Bundle

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
    
    // 標記：是否正在執行手動跳轉
    private var isManualJumping = false
    
    // 用來儲存聊天室的 LayoutManager 實例，避免誤殺其他列表
    private var targetLayoutManager: LinearLayoutManager? = null

    override fun start(ctx: Context) {
        
        // ==========================================
        //  功能 1: 鎖定目標 & 按鈕監聽
        // ==========================================
        
        patcher.after<Fragment>("onViewCreated", View::class.java, Bundle::class.java) {
            // 鎖定聊天室 Fragment
            if (it.thisObject::class.java.name.contains("WidgetChatList")) {
                val view = it.args[0] as View
                val recyclerId = Utils.getResId("chat_list_recycler_view", "id")
                
                if (recyclerId != 0) {
                    val recycler = view.findViewById<RecyclerView>(recyclerId)
                    if (recycler != null) {
                        // 1. 抓取目標 Manager
                        targetLayoutManager = recycler.layoutManager as? LinearLayoutManager
                        
                        // 2. 初始化：立刻關閉吸附
                        targetLayoutManager?.stackFromEnd = false
                    }
                }

                // 3. 綁定按鈕 (手動跳轉)
                val scrollBtnId = Utils.getResId("chat_list_scroll_to_bottom", "id")
                if (scrollBtnId != 0) {
                    val scrollBtn = view.findViewById<View>(scrollBtnId)
                    scrollBtn?.setOnTouchListener { _, event ->
                        if (event.action == MotionEvent.ACTION_DOWN) {
                            // 當按下按鈕：允許吸附，並執行跳轉
                            isManualJumping = true
                            
                            targetLayoutManager?.let { lm ->
                                lm.stackFromEnd = true // 開啟吸附，這樣才能跳到底
                                
                                // 強制捲動
                                val count = lm.itemCount
                                if (count > 0) {
                                    lm.scrollToPosition(count - 1)
                                }
                                
                                // 1秒後自動恢復鎖定狀態
                                Utils.mainThread.postDelayed({
                                    isManualJumping = false
                                    lm.stackFromEnd = false // 恢復鎖定
                                }, 1000)
                            }
                        }
                        false 
                    }
                }
            }
        }

        // ==========================================
        //  功能 2: 底層佈局劫持 (Layout Hijack)
        // ==========================================
        
        // 【核心大招】：Hook onLayoutChildren
        // 這是 RecyclerView 決定要把東西放在哪裡的函式。
        // 每次有新訊息、或者畫面重繪，這個函式都會跑一次。
        // 我們在這裡強制把 stackFromEnd 改成 false。
        
        patcher.before<LinearLayoutManager>(
            "onLayoutChildren",
            RecyclerView.Recycler::class.java,
            RecyclerView.State::class.java
        ) {
            val manager = it.thisObject as LinearLayoutManager
            
            // 檢查：這是不是聊天室的 Manager？
            if (manager === targetLayoutManager) {
                // 如果不是手動跳轉狀態，強制關閉 StackFromEnd
                if (!isManualJumping) {
                    // 即使 Discord 剛剛把它設為 true，我們在佈局發生的前一刻把它改回 false
                    // 這樣佈局出來的結果就是「不自動吸附」
                    manager.stackFromEnd = false
                }
            }
        }

        // 雙重保險：攔截 setStackFromEnd
        // 防止 Discord 在其他地方偷偷設定
        patcher.before<LinearLayoutManager>(
            "setStackFromEnd",
            Boolean::class.javaPrimitiveType!!
        ) {
            if (it.thisObject === targetLayoutManager) {
                if (!isManualJumping) {
                    // 強制竄改參數為 false
                    it.args[0] = false
                }
            }
        }


        // ==========================================
        //  功能 3: 複製按鈕 (保持不變)
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

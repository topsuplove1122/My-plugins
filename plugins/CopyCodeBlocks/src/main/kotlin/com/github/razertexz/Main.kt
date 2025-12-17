package com.github.razertexz

import android.content.Context
import android.widget.ImageView
import android.view.View
import android.view.MotionEvent
import android.graphics.Color
import android.view.ViewGroup
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
    
    // 標記：是否正在執行「跳到底部」的操作
    private var isJumping = false

    override fun start(ctx: Context) {
        
        // ==========================================
        //  功能 1: 狀態強制修正 (State Enforcer)
        // ==========================================
        
        patcher.after<Fragment>("onViewCreated", View::class.java, Bundle::class.java) {
            if (it.thisObject::class.java.name.contains("WidgetChatList")) {
                val view = it.args[0] as View
                
                val recyclerId = Utils.getResId("chat_list_recycler_view", "id")
                if (recyclerId != 0) {
                    val recycler = view.findViewById<RecyclerView>(recyclerId)
                    if (recycler != null) {
                        
                        // 1. 初始化時先關閉
                        val manager = recycler.layoutManager as? LinearLayoutManager
                        manager?.stackFromEnd = false

                        // 2. 【核心大招】監聽佈局變更
                        // 每當有新訊息進來，RecyclerView 會重新佈局 (Layout)
                        // 我們就在這一瞬間，檢查 stackFromEnd，如果是 true 且沒在跳轉，就強制關掉！
                        recycler.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                            if (!isJumping) {
                                val lm = recycler.layoutManager as? LinearLayoutManager
                                // 只要發現它被偷偷改成 true，立刻改回 false
                                if (lm != null && lm.stackFromEnd) {
                                    lm.stackFromEnd = false
                                }
                            }
                        }

                        // 3. 監聽使用者滑動
                        // 只要手指一碰螢幕，就視為停止跳轉，立刻鎖定
                        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                                    isJumping = false
                                    (recyclerView.layoutManager as? LinearLayoutManager)?.stackFromEnd = false
                                }
                            }
                        })
                    }
                }

                // 4. 綁定按鈕
                val scrollBtnId = Utils.getResId("chat_list_scroll_to_bottom", "id")
                if (scrollBtnId != 0) {
                    val scrollBtn = view.findViewById<View>(scrollBtnId)
                    scrollBtn?.setOnTouchListener { _, event ->
                        if (event.action == MotionEvent.ACTION_DOWN) {
                            // 按下按鈕時，允許置底
                            isJumping = true
                            
                            val recycler = view.findViewById<RecyclerView>(recyclerId)
                            val manager = recycler?.layoutManager as? LinearLayoutManager
                            if (manager != null) {
                                // 開啟吸附功能
                                manager.stackFromEnd = true
                                // 強制捲動
                                manager.scrollToPosition(manager.itemCount - 1)
                            }
                        }
                        false 
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

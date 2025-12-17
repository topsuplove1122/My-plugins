package com.github.razertexz

import android.content.Context
import android.widget.ImageView
import android.view.View
import android.view.MotionEvent
import android.graphics.Color
import android.os.Bundle

// 【關鍵修正】補回遺失的 ConstraintLayout Import
import androidx.constraintlayout.widget.ConstraintLayout
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
    
    // 錨點變數
    private var anchorIndex = -1
    private var anchorOffset = 0
    private var shouldRestore = false
    
    // 手動跳轉標記
    private var isManualJumping = false

    override fun start(ctx: Context) {
        
        // ==========================================
        //  功能 1: 視角錨定 (Scroll Anchoring)
        // ==========================================
        
        // 1. 佈局前 (Before)：記住我現在在哪裡
        patcher.before<LinearLayoutManager>(
            "onLayoutChildren",
            RecyclerView.Recycler::class.java,
            RecyclerView.State::class.java
        ) {
            val manager = it.thisObject as LinearLayoutManager
            
            // 如果正在手動跳轉，就不要紀錄，讓它自然捲動
            if (isManualJumping) {
                shouldRestore = false
                return@before
            }

            try {
                val firstPos = manager.findFirstVisibleItemPosition()
                val lastPos = manager.findLastVisibleItemPosition()
                val itemCount = manager.itemCount
                
                // 邏輯：如果還沒到底部 (最後一個看到的 item 不是總數的最後一個)
                // 代表使用者正在閱讀舊訊息，開啟錨定保護
                if (lastPos < itemCount - 1 && firstPos != -1) {
                    shouldRestore = true
                    
                    // 記住現在第一則訊息是誰 (Index)
                    anchorIndex = firstPos
                    
                    // 記住這則訊息距離頂部多少像素 (Offset)
                    val view = manager.findViewByPosition(firstPos)
                    anchorOffset = view?.top ?: 0
                } else {
                    // 在最底部，隨便它動
                    shouldRestore = false
                }
            } catch (e: Exception) {
                shouldRestore = false
            }
        }

        // 2. 佈局後 (After)：把我丟回去
        patcher.after<LinearLayoutManager>(
            "onLayoutChildren",
            RecyclerView.Recycler::class.java,
            RecyclerView.State::class.java
        ) {
            val manager = it.thisObject as LinearLayoutManager
            
            if (shouldRestore && anchorIndex != -1) {
                // 強制跳轉回剛剛記住的位置
                manager.scrollToPositionWithOffset(anchorIndex, anchorOffset)
                
                // 重置狀態
                shouldRestore = false 
            }
        }

        // ==========================================
        //  功能 2: 按鈕監聽 (手動跳轉)
        // ==========================================
        patcher.after<Fragment>("onViewCreated", View::class.java, Bundle::class.java) {
            if (it.thisObject::class.java.name.contains("WidgetChatList")) {
                val view = it.args[0] as View
                val scrollBtnId = Utils.getResId("chat_list_scroll_to_bottom", "id")
                val recyclerId = Utils.getResId("chat_list_recycler_view", "id")
                
                if (scrollBtnId != 0) {
                    val scrollBtn = view.findViewById<View>(scrollBtnId)
                    scrollBtn?.setOnTouchListener { _, event ->
                        if (event.action == MotionEvent.ACTION_DOWN) {
                            isManualJumping = true
                            shouldRestore = false 
                            
                            val recycler = view.findViewById<RecyclerView>(recyclerId)
                            val manager = recycler?.layoutManager as? LinearLayoutManager
                            if (manager != null && manager.itemCount > 0) {
                                manager.scrollToPosition(manager.itemCount - 1)
                            }
                            
                            Utils.mainThread.postDelayed({
                                isManualJumping = false
                            }, 1000)
                        }
                        false 
                    }
                }
            }
        }

        // ==========================================
        //  功能 3: 複製按鈕 (Copy Buttons)
        // ==========================================
        setupCopyButtons(ctx)
    }

    private fun setupCopyButtons(ctx: Context) {
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
